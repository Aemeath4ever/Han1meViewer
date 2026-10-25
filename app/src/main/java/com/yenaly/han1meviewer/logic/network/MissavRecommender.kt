package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.isMissavUncensoredCode
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.logic.model.HomePage
import com.yenaly.yenaly_libs.utils.applicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * missav 首頁「推薦給你」等分節的資料來源。
 *
 * 站點首頁前四欄不是服務端渲染的：頁面裏的 Alpine 組件在瀏覽器端直接調
 * [Recombee](https://www.recombee.com/) 的 client API，所以靜態 HTML 裏只有模板骨架。
 * 這裏復刻同一條調用鏈，讓 app 也能拿到按帳號行為生成的推薦：
 *
 * 1. `POST /recomms/users/{user_uuid}/item-segments/` → 3 個「分節」（標籤 id，如 `中出し`）
 * 2. 再一次 batch：`mobile-home-recommended`（推薦給你）+ 每個分節各一次
 *    `mobile-home-segment-items`（filter: `"標籤" in 'genres'`）
 *
 * 兩個細節：
 * - 請求要帶 `frontend_timestamp` / `frontend_sign`，簽名見 [signedPath]；
 * - token 是公開的 frontend token（就寫在站點的 app.js 裏），不是私鑰。為了不把它
 *   硬編碼進來，第一次使用時從當前版本的 app.js 裏取並緩存。
 */
object MissavRecommender {

    private const val TAG = "missav_rec"
    private const val BASE_URI = "https://client-rapi-missav.recombee.com"
    private const val DATABASE = "missav-default"

    private const val SCENARIO_RECOMMENDED = "mobile-home-recommended"
    private const val SCENARIO_SEGMENTS = "mobile-home-segments"
    private const val SCENARIO_SEGMENT_ITEMS = "mobile-home-segment-items"

    /** 站點 `window.recommendedQuantity`（每欄 4 個）。 */
    private const val ITEMS_PER_SECTION = 4

    /** 站點 `window.recommendedRows - 1`（推薦給你之外的 3 個分節）。 */
    private const val SEGMENT_COUNT = 3

    private const val ITEM_CDN = "https://fourhoi.com"

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val includedProperties = JSONArray(
        listOf(
            "title_cn", "duration", "has_chinese_subtitle",
            "has_english_subtitle", "is_uncensored_leak", "dm",
        )
    )

    /**
     * 拉取推薦分節。任何一步失敗都返回空列表 —— 首頁的靜態分節（新作上市/最近更新/無碼影片）
     * 不能因為推薦接口掛了而不顯示。
     *
     * @param homeHtml 剛抓到的首頁 HTML，用於取 genreMap 與 app.js 地址。
     */
    suspend fun fetch(homeHtml: String): List<HomePage.MissavSection> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = publicToken(homeHtml) ?: run {
                    Log.w(TAG, "no recombee token, skip recommendations")
                    return@withContext emptyList()
                }
                val uuid = userUuid()
                val segments = fetchSegments(uuid, token)
                val blocks = fetchBlocks(uuid, token, segments)
                val genreMap = parseGenreMap(homeHtml)
                val titles = buildList {
                    add(applicationContext.getString(R.string.missav_recommended))
                    segments.forEach { add(genreMap[it] ?: it) }
                }
                blocks.mapIndexedNotNull { index, videos ->
                    if (videos.isEmpty()) return@mapIndexedNotNull null
                    HomePage.MissavSection(
                        title = titles.getOrElse(index) { titles.last() },
                        videos = videos,
                    )
                }.also {
                    Log.d(TAG, "sections=${it.map { s -> "${s.title}:${s.videos.size}" }}")
                }
            }.getOrElse {
                Log.w(TAG, "recommend failed: ${it.javaClass.simpleName}: ${it.message}")
                emptyList()
            }
        }

    //<editor-fold desc="Recombee 調用">

    private fun fetchSegments(uuid: String, token: String): List<String> {
        val body = JSONObject()
            .put("count", SEGMENT_COUNT)
            .put("scenario", SCENARIO_SEGMENTS)
            .put("cascadeCreate", true)
        val json = post("/recomms/users/$uuid/item-segments/", token, body.toString())
            ?: return emptyList()
        val recomms = runCatching { JSONObject(json).optJSONArray("recomms") }
            .getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until recomms.length()) {
                recomms.optJSONObject(i)?.optString("id")
                    ?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun fetchBlocks(
        uuid: String,
        token: String,
        segments: List<String>,
    ): List<List<HanimeInfo>> {
        val requests = JSONArray()
        requests.put(recommendRequest(uuid, SCENARIO_RECOMMENDED, filter = null, withLogic = true))
        segments.forEach { segment ->
            requests.put(
                recommendRequest(
                    uuid, SCENARIO_SEGMENT_ITEMS,
                    filter = "\"$segment\" in 'genres'", withLogic = false,
                )
            )
        }
        val payload = JSONObject()
            .put("requests", requests)
            .put("distinctRecomms", true)
        val json = post("/batch/", token, payload.toString()) ?: return emptyList()
        val blocks = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until blocks.length()) {
                // 下標必須與請求一一對應（標題按位置取），失敗的段補空列表而不是跳過，
                // 否則後面某段失敗會讓後續區塊全部錯位到別的標題下。
                val block = blocks.optJSONObject(i)
                val recomms = block?.takeIf { it.optInt("code") == 200 }
                    ?.optJSONObject("json")?.optJSONArray("recomms")
                add(if (recomms == null) emptyList() else parseItems(recomms))
            }
        }
    }

    private fun recommendRequest(
        uuid: String,
        scenario: String,
        filter: String?,
        withLogic: Boolean,
    ): JSONObject {
        val params = JSONObject()
            .put("count", ITEMS_PER_SECTION)
            .put("scenario", scenario)
            .put("returnProperties", true)
            .put("includedProperties", includedProperties)
            .put("filter", filter ?: JSONObject.NULL)
            .put("cascadeCreate", true)
        if (withLogic) {
            params.put(
                "logic", JSONObject()
                    .put("name", "missav:personal")
                    .put("settings", JSONObject().put("spotlightVideoIds", JSONArray()))
            )
        }
        return JSONObject()
            .put("method", "POST")
            .put("path", "/recomms/users/$uuid/items/")
            .put("params", params)
    }

    private fun parseItems(recomms: JSONArray): List<HanimeInfo> = buildList {
        for (i in 0 until recomms.length()) {
            val item = recomms.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val values = item.optJSONObject("values") ?: JSONObject()
            val titleCn = values.optString("title_cn").trim()
            // 站點 generateFullItemTitle()：番號大寫去掉後綴 + 中文標題
            val code = id.uppercase()
                .replace("-UNCENSORED-LEAK", "")
                .replace("-CHINESE-SUBTITLE", "")
                .replace("-ENGLISH-SUBTITLE", "")
            add(
                HanimeInfo(
                    title = if (titleCn.isEmpty()) code else "$code $titleCn",
                    coverUrl = "$ITEM_CDN/$id/cover-t.jpg",
                    videoCode = id,
                    duration = formatDuration(values.optInt("duration")),
                    itemType = HanimeInfo.SIMPLIFIED,
                    isUncensored = values.optBoolean("is_uncensored_leak") ||
                            isMissavUncensoredCode(id),
                )
            )
        }
    }

    private fun post(path: String, token: String, body: String): String? {
        val request = Request.Builder()
            .url(BASE_URI + signedPath(path, token))
            .post(body.toRequestBody(mediaType))
            .build()
        return runCatching {
            ServiceCreator.recombeeClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $path")
                    // token 輪換或站點改版：下次重新從 app.js 取
                    if (response.code == 401 || response.code == 403) {
                        Preferences.missavRecombeeToken = ""
                    }
                    return null
                }
                text
            }
        }.getOrElse {
            Log.w(TAG, "request failed: ${it.message}")
            null
        }
    }

    /**
     * 復刻 Recombee JS SDK 的 `_signUrl`：
     * `sign = HMAC-SHA1(publicToken, "/{databaseId}{path}?frontend_timestamp={秒}")`。
     */
    private fun signedPath(path: String, token: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val separator = if (path.contains("?")) "&" else "?"
        val message = "/$DATABASE$path${separator}frontend_timestamp=$timestamp"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sign = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$path${separator}frontend_timestamp=$timestamp&frontend_sign=$sign"
    }

    //</editor-fold>

    //<editor-fold desc="使用者 id / token">

    /**
     * 站點把推薦畫像記在 cookie `user_uuid` 下面。優先沿用站點的 id（WebView 登入時
     * 會把整串 cookie 灌進 jar），沒有才生成一個並寫回去，保證 WebView 與 okhttp 用同一個。
     */
    private fun userUuid(): String {
        val host = runCatching { URI(Preferences.baseUrl).host }.getOrNull()
        if (host != null) {
            HCookieJar.cookieMap[host]
                ?.firstOrNull { it.name == "user_uuid" }
                ?.value?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        Preferences.missavUserUuid.takeIf { it.isNotBlank() }?.let { uuid ->
            if (host != null) HCookieJar.putRawCookieString(host, "user_uuid=$uuid")
            return uuid
        }
        val uuid = UUID.randomUUID().toString()
        Preferences.missavUserUuid = uuid
        if (host != null) HCookieJar.putRawCookieString(host, "user_uuid=$uuid")
        return uuid
    }

    private suspend fun publicToken(homeHtml: String): String? {
        Preferences.missavRecombeeToken.takeIf { it.isNotBlank() }?.let { return it }
        val jsUrl = extractAppJsUrl(homeHtml) ?: return null
        // 首次要下載 ~100KB 的 bundle；給個上限，別讓首頁卡在「推薦」上
        val js = runCatching {
            withTimeoutOrNull(15_000) {
                HanimeNetwork.hanimeService.getHomePage(jsUrl).body()?.string()
            }
        }.getOrNull() ?: return null
        val token = Regex(
            """ApiClient\(\s*["']$DATABASE["']\s*,\s*["']([^"']+)["']"""
        ).find(js)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
        Preferences.missavRecombeeToken = token
        Log.d(TAG, "recombee token refreshed from app.js")
        return token
    }

    private fun extractAppJsUrl(homeHtml: String): String? {
        val src = Regex("""<script[^>]+src=["']([^"']*app\.[0-9a-f]+\.js)["']""")
            .find(homeHtml)?.groupValues?.get(1) ?: return null
        return if (src.startsWith("http")) src
        else Preferences.baseUrl.trimEnd('/') + "/" + src.trimStart('/')
    }

    /** 站點 `window.genreMap`（日文標籤 id → 當前語言的名稱）。 */
    private fun parseGenreMap(homeHtml: String): Map<String, String> {
        val raw = Regex("""window\.genreMap\s*=\s*(\{.*?})\s*;""")
            .find(homeHtml)?.groupValues?.get(1) ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            json.keys().forEach { key ->
                json.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }

    private fun formatDuration(seconds: Int): String? {
        if (seconds <= 0) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    //</editor-fold>
}
