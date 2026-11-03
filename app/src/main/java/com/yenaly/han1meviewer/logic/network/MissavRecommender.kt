package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.isMissavUncensoredCode
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.logic.model.HomePage
import com.yenaly.han1meviewer.util.toLoginCookieList
import com.yenaly.yenaly_libs.utils.applicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * missav 首页「推荐给你」等分节的资料来源。
 *
 * 站点首页前四栏不是服务端渲染的：页面里的 Alpine 组件在浏览器端直接调
 * [Recombee](https://www.recombee.com/) 的 client API，所以静态 HTML 里只有模板骨架。
 * 这里复刻同一条调用链，让 app 也能拿到按帐号行为生成的推荐：
 *
 * 1. `POST /recomms/users/{user_uuid}/item-segments/` → 3 个「分节」（标签 id，如 `中出し`）
 * 2. 再一次 batch：`mobile-home-recommended`（推荐给你）+ 每个分节各一次
 *    `mobile-home-segment-items`（filter: `"标签" in 'genres'`）
 *
 * 两个细节：
 * - 请求要带 `frontend_timestamp` / `frontend_sign`，签名见 [signedPath]；
 * - token 是公开的 frontend token（就写在站点的 app.js 里），不是私钥。为了不把它
 *   硬编码进来，第一次使用时从当前版本的 app.js 里取并缓存。
 */
object MissavRecommender {

    private const val TAG = "missav_rec"
    private const val BASE_URI = "https://client-rapi-missav.recombee.com"
    private const val DATABASE = "missav-default"

    private const val SCENARIO_RECOMMENDED = "mobile-home-recommended"
    private const val SCENARIO_SEGMENTS = "mobile-home-segments"
    private const val SCENARIO_SEGMENT_ITEMS = "mobile-home-segment-items"

    /** 站点 `window.recommendedQuantity`（每栏 4 个）。 */
    private const val ITEMS_PER_SECTION = 4

    /** 站点 `window.recommendedRows - 1`（推荐给你之外的 3 个分节）。 */
    private const val SEGMENT_COUNT = 3

    private const val ITEM_CDN = "https://fourhoi.com"

    /**
     * 站点「载入更多」按钮的取数方式：`RecommendNextItems(recommId, count)`。
     *
     * Recombee 的推荐 id 只在基础请求（或上一次续接）之后 30 分钟内可用，
     * 所以首页重新整理后必须用新的 recommId，这也是它跟著分节一起回传的原因。
     */
    private const val NEXT_ITEMS_PATH = "/recomms/next/items/"

    /** 站点一次「载入更多」补的数量（`recommendedQuantity * 2`）。 */
    private const val NEXT_ITEMS_COUNT = ITEMS_PER_SECTION * 2

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 「载入更多」的结果。
     *
     * @param recommId 续接后的推荐 id，下一次载入更多要用它（不是原来那个）。
     * @param videos 新一批影片。
     */
    data class NextItems(
        val recommId: String,
        val videos: List<HanimeInfo>,
    )

    private val includedProperties = JSONArray(
        listOf(
            "title_cn", "duration", "has_chinese_subtitle",
            "has_english_subtitle", "is_uncensored_leak", "dm",
        )
    )

    /**
     * 拉取推荐分节。任何一步失败都返回空列表 —— 首页的静态分节（新作上市/最近更新/无码影片）
     * 不能因为推荐接口挂了而不显示。
     *
     * @param homeHtml 刚抓到的首页 HTML，用于取 genreMap 与 app.js 地址。
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
                blocks.mapIndexedNotNull { index, block ->
                    if (block.videos.isEmpty()) return@mapIndexedNotNull null
                    HomePage.MissavSection(
                        title = titles.getOrElse(index) { titles.last() },
                        videos = block.videos,
                        recommId = block.recommId,
                    )
                }.also {
                    Log.d(TAG, "sections=${it.map { s -> "${s.title}:${s.videos.size}" }}")
                }
            }.getOrElse {
                Log.w(TAG, "recommend failed: ${it.javaClass.simpleName}: ${it.message}")
                emptyList()
            }
        }

    /**
     * 首页推荐区块的「更多」：续接同一条推荐。
     *
     * 站点的「载入更多」就是 `RecommendNextItems(recommId, count)`，其余参数（scenario、
     * filter、返回栏位）由 Recombee 从基础请求继承，所以这里只需要 recommId 和数量。
     *
     * @param recommId 分节自带的推荐 id
     * @return 新一批影片与续接后的推荐 id；拿不到（token 丢了／超过 30 分钟）时返回 null，
     * 呼叫方保持原样即可
     */
    suspend fun fetchNext(
        recommId: String,
        count: Int = NEXT_ITEMS_COUNT,
    ): NextItems? = withContext(Dispatchers.IO) {
        if (recommId.isBlank()) return@withContext null
        val token = Preferences.missavRecombeeToken.takeIf { it.isNotBlank() } ?: return@withContext null
        val body = JSONObject().put("count", count).toString()
        val json = post("$NEXT_ITEMS_PATH$recommId", token, body) ?: return@withContext null
        val response = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null
        val recomms = response.optJSONArray("recomms") ?: return@withContext null
        val videos = parseItems(recomms)
        if (videos.isEmpty()) {
            Log.d(TAG, "next items for $recommId is empty")
            return@withContext null
        }
        NextItems(
            recommId = response.optString("recommId").takeIf { it.isNotBlank() } ?: recommId,
            videos = videos,
        )
    }

    //<editor-fold desc="Recombee 调用">

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
    ): List<Block> {
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
                // 下标必须与请求一一对应（标题按位置取），失败的段补空列表而不是跳过，
                // 否则后面某段失败会让后续区块全部错位到别的标题下。
                val block = blocks.optJSONObject(i)
                val data = block?.takeIf { it.optInt("code") == 200 }?.optJSONObject("json")
                val recomms = data?.optJSONArray("recomms")
                add(
                    Block(
                        // recommId 是「载入更多」的凭证，跟著分节一起带给 UI
                        recommId = data?.optString("recommId")?.takeIf { it.isNotBlank() },
                        videos = if (recomms == null) emptyList() else parseItems(recomms),
                    )
                )
            }
        }
    }

    /** 批次里的一个推荐区块。 */
    private data class Block(val recommId: String?, val videos: List<HanimeInfo>)

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
            // 站点 generateFullItemTitle()：番号大写去掉后缀 + 中文标题
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
                    itemType = HanimeInfo.NORMAL,
                    isUncensored = values.optBoolean("is_uncensored_leak") ||
                            isMissavUncensoredCode(id),
                )
            )
        }
    }

    private fun post(path: String, token: String, body: String): String? {
        // Recombee 的路径是 `/{databaseId}{path}`；签名里带了 database 前缀，
        // 实际请求 URL 也必须带，否则第一个 path 段会被当成 databaseId → 401。
        val request = Request.Builder()
            .url("$BASE_URI/$DATABASE" + signedPath(path, token))
            .post(body.toRequestBody(mediaType))
            .build()
        return runCatching {
            ServiceCreator.recombeeClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $path")
                    // token 轮换或站点改版：下次重新从 app.js 取
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
     * 复刻 Recombee JS SDK 的 `_signUrl`：
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
     * 站点把推荐画像记在 cookie `user_uuid` 下面。
     *
     * 站点自己的 id 在登录 cookie 里（登入时从 WebView 整串抄过来），必须优先采用 ——
     * 之前只看 jar，而 jar 里是旧版写进去的随机 UUID，等于每次都在用一个没有历史行为的
     * 新画像，推荐自然不准。找不到站点 id 才退回 jar / 本地生成的 id。
     */
    private fun userUuid(): String {
        val host = runCatching { URI(Preferences.baseUrl).host }.getOrNull()
        if (host != null) {
            Preferences.loginCookieStateFlow.value.toLoginCookieList(host)
                .firstOrNull { it.name == "user_uuid" }
                ?.value?.takeIf { it.isNotBlank() }
                ?.let { uuid ->
                    // 让 jar 也回到站点的 id（旧版本可能留了一个随机 UUID 在里面）
                    HCookieJar.putRawCookieString(host, "user_uuid=$uuid")
                    return uuid
                }
            runCatching {
                Preferences.baseUrl.toHttpUrlOrNull()?.let { HCookieJar().loadForRequest(it) }
            }.getOrNull()
                ?.firstOrNull { it.name == "user_uuid" }
                ?.value?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        Preferences.missavUserUuid.takeIf { it.isNotBlank() }?.let { return it }
        val uuid = UUID.randomUUID().toString()
        Preferences.missavUserUuid = uuid
        return uuid
    }

    private fun publicToken(homeHtml: String): String? {
        Preferences.missavRecombeeToken.takeIf { it.isNotBlank() }?.let { return it }
        val jsUrl = extractAppJsUrl(homeHtml) ?: return null
        // 首次要下载 ~100KB 的 bundle。不能走 hClient：静态资源被盾拦时
        // CloudflareInterceptor 会弹出验证页（用户看到的是「验证页打开了一个 js 档」），
        // 而且会把首页请求一起卡住。取不到就默默跳过推荐。
        val js = runCatching {
            ServiceCreator.assetClient
                .newCall(Request.Builder().url(jsUrl).build())
                .execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
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

    /** 站点 `window.genreMap`（日文标签 id → 当前语言的名称）。 */
    private fun parseGenreMap(homeHtml: String): Map<String, String> {
        // `}` 在 Java 正则里要转义，否则 PatternSyntaxException（同 Parser 里踩过的坑）
        val raw = Regex(
            """window\.genreMap\s*=\s*(\{.*?\})\s*;""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(homeHtml)?.groupValues?.get(1) ?: return emptyMap()
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
