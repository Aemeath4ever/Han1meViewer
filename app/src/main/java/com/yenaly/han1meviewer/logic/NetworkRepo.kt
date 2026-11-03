package com.yenaly.han1meviewer.logic

import android.util.Log
import com.yenaly.han1meviewer.EMPTY_STRING
import com.yenaly.han1meviewer.HANIME_LOGIN_URL
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.Preferences.isAlreadyLogin
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.exception.CloudFlareBlockedException
import com.yenaly.han1meviewer.logic.exception.HanimeNotFoundException
import com.yenaly.han1meviewer.logic.exception.IPBlockedException
import com.yenaly.han1meviewer.logic.exception.ParseException
import com.yenaly.han1meviewer.logic.model.CommentPlace
import com.yenaly.han1meviewer.logic.model.CreatorSort
import com.yenaly.han1meviewer.logic.model.ModifiedPlaylistArgs
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.logic.model.MyListItems
import com.yenaly.han1meviewer.logic.model.MyListType
import com.yenaly.han1meviewer.logic.model.OnlineWatchHistorySort
import com.yenaly.han1meviewer.logic.model.Playlists
import com.yenaly.han1meviewer.logic.model.HanimeSearchResult
import com.yenaly.han1meviewer.logic.model.HomePage
import com.yenaly.han1meviewer.logic.model.VideoCommentArgs
import com.yenaly.han1meviewer.logic.model.VideoComments
import com.yenaly.han1meviewer.logic.network.HUpdater
import com.yenaly.han1meviewer.logic.network.HanimeNetwork
import com.yenaly.han1meviewer.logic.network.MissavRecommender
import com.yenaly.han1meviewer.logic.state.PageLoadingState
import com.yenaly.han1meviewer.logic.state.VideoLoadingState
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.replaceBackupMediaCdnHost
import com.yenaly.han1meviewer.util.toLoginCookieList
import com.yenaly.yenaly_libs.utils.applicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import java.io.File
import javax.net.ssl.SSLHandshakeException

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:38
 */
object NetworkRepo {

    /** jable 留言每页条数（站点留言 block 的 `data-parameters` 固定 ipp=5）。 */
    private const val JABLE_COMMENTS_PAGE_SIZE = 5

    //<editor-fold desc="Hanime">

    fun getHomePage(): Flow<WebsiteState<HomePage>> = if (Preferences.isMissavSite) {
        // missav 新版首页分两部分：
        //  - 服务端静态分节（新作上市/最近更新/无码影片/随机），okhttp 直接解析；
        //  - 「推荐给你」+ 3 个行为分节，由浏览器端调 Recombee 生成，静态 HTML 里只有骨架。
        // 登入后补上后者，并排在最前面；推荐失败就只显示静态分节，不影响原有内容。
        flow {
            val response = HanimeNetwork.hanimeService.getMissavHomePage()
            val body = response.body()?.string()?.replaceBackupMediaCdnHost()
            if (!response.isSuccessful) response.throwRequestException()
            val state = Parser.missavHomePage(body ?: EMPTY_STRING)
            if (state is WebsiteState.Success && isAlreadyLogin && body != null) {
                val recommended = MissavRecommender.fetch(body)
                state.info.missavRecommended.clear()
                state.info.missavRecommended.addAll(recommended)
            }
            emit(state)
        }.catch { e ->
            emit(WebsiteState.Error(handleException(e)))
        }.flowOn(Dispatchers.IO)
    } else if (Preferences.isJableSite) {
        // jable 是 KVS 站，首页所有分节都由服务端直出，直接解析根路径即可。
        websiteIOFlow(
            request = { HanimeNetwork.hanimeService.getHomePage(Preferences.homeRequestUrl) },
            action = Parser::jableHomePage,
        )
    } else {
        websiteIOFlow(
            request = { HanimeNetwork.hanimeService.getHomePage(Preferences.homeRequestUrl) },
            action = Parser::homePageVer2,
        )
    }

    fun getHanimeSearchResult(
        page: Int, query: String?, genre: String?,
        sort: String?, broad: Boolean, date: String?,
        duration: String?, tags: Set<String>, brands: Set<String>,
    ): Flow<PageLoadingState<HanimeSearchResult>> {
        return if (Preferences.isMissavSite) {
            // missav 仅支持关键词 + 页码，高级筛选不生效
            pageIOFlow(
                request = {
                    HanimeNetwork.hanimeService.getMissavSearchResult(query.orEmpty(), page)
                },
                action = { Parser.missavSearch(it, page) },
            )
        } else if (Preferences.isJableSite) {
            // jable 同样只支持关键词；genre/sort/date 等高级筛选不生效。
            val keyword = query.orEmpty().trim()
            pageIOFlow(
                request = {
                    if (keyword.isEmpty()) {
                        if (page <= 1) HanimeNetwork.hanimeService.getJableSearchAll()
                        else HanimeNetwork.hanimeService.getJableSearchAllPage(page)
                    } else {
                        if (page <= 1) HanimeNetwork.hanimeService.getJableSearch(keyword)
                        else HanimeNetwork.hanimeService.getJableSearchPage(
                            query = keyword,
                            q = keyword,
                            from = page,
                            fromVideos = page,
                            fromAlbums = page,
                        )
                    }
                },
                action = { Parser.jableSearch(it, page) },
            )
        } else {
            pageIOFlow(
                request = {
                    HanimeNetwork.hanimeService.getHanimeSearchResult(
                        page, query, genre, sort,
                        if (broad) "on" else null,
                        date, duration, tags, brands
                    )
                },
                action = Parser::hanimeSearch
            )
        }
    }

    /**
     * 女优／模特主页的作品列表。
     *
     * - missav：`/actresses/{name}?page=N`（Laravel 分页）。
     * - jable：`/models/{hash}/{N}/`；若该路径式分页没有卡片，再退回 `?page=N`。
     *
     * 女优名字在不同语言下有多种写法，按名字搜索会漏作品；直接用站内主页连结最准。
     */
    fun getArtistVideos(
        profileUrl: String,
        page: Int,
    ): Flow<PageLoadingState<HanimeSearchResult>> {
        val url = profileUrl.toAbsoluteSiteUrl()
        return when {
            Preferences.isMissavSite -> pageIOFlow(
                request = { HanimeNetwork.hanimeService.getHomePage(url.withPageQuery(page)) },
                action = { Parser.missavArtistVideos(it, page) },
            )

            Preferences.isJableSite -> jableArtistVideosFlow(url, page)

            else -> flow {
                emit(PageLoadingState.NoMoreData)
            }
        }
    }

    /**
     * jable 模特页分页。KVS 主题可能用路径式（`/models/{hash}/2/`）或
     * query 式（`/models/{hash}/?page=2`）分页，先试路径式，拿不到卡片再试 query。
     */
    private fun jableArtistVideosFlow(
        profileUrl: String,
        page: Int,
    ): Flow<PageLoadingState<HanimeSearchResult>> = flow {
        val base = profileUrl.trimEnd('/')
        val candidates = if (page <= 1) {
            listOf("$base/")
        } else {
            listOf("$base/$page/", "$base/?page=$page")
        }
        var lastState: PageLoadingState<HanimeSearchResult>? = null
        for (candidate in candidates) {
            val state = try {
                val response = HanimeNetwork.hanimeService.getHomePage(candidate)
                val body = response.body()?.string()?.replaceBackupMediaCdnHost()
                if (!response.isSuccessful || body == null) response.throwRequestException()
                Parser.jableArtistVideos(body, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PageLoadingState.Error(handleException(e))
            }
            lastState = state
            val hasCards = (state as? PageLoadingState.Success)
                ?.info?.list?.isNotEmpty() == true
            if (hasCards || candidate == candidates.last()) {
                emit(state)
                return@flow
            }
        }
        emit(lastState ?: PageLoadingState.NoMoreData)
    }.catch { e ->
        emit(PageLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * jable 会员中心影片列表（喜欢 / 稍后观看）。
     *
     * KVS 主题常把 `/my/` 做成 profile 首页，真正的影片 block 必须带 async 参数；
     * 若该主题不吃 block 参数，再退回完整 `/my/` 页面解析。第 1 页两者都拿不到卡片时
     * 回空列表（真的没收藏），第 2 页起同理，UI 会据此停止翻页。
     */
    private fun jableFavouritesFlow(
        page: Int,
        favType: Int,
    ): Flow<PageLoadingState<MyListItems<HanimeInfo>>> = flow {
        val candidates: List<suspend () -> Response<ResponseBody>> = buildList {
            add {
                HanimeNetwork.hanimeService.getJableMyFavourites(
                    favType = favType,
                    from = page,
                )
            }
            if (page <= 1) {
                add { HanimeNetwork.hanimeService.getJableMyPage() }
            }
        }
        var lastError: Throwable? = null
        for ((index, request) in candidates.withIndex()) {
            try {
                val response = request()
                val body = response.body()?.string()?.replaceBackupMediaCdnHost()
                if (!response.isSuccessful || body == null) {
                    response.throwRequestException()
                }
                val state = Parser.jableUserVideoList(body, page)
                val cards = (state as? PageLoadingState.Success)
                    ?.info?.hanimeInfo.orEmpty()
                if (cards.isNotEmpty() || index == candidates.lastIndex) {
                    emit(state)
                    return@flow
                }
                Log.d(
                    "jable_user",
                    "fav_type=$favType page=$page candidate #$index empty, trying next"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(
                    "jable_user",
                    "fav_type=$favType page=$page candidate #$index failed: ${e.message}"
                )
            }
        }
        emit(
            PageLoadingState.Error(
                handleException(
                    lastError ?: IllegalStateException("jable favourites returned no data")
                )
            )
        )
    }.catch { e ->
        emit(PageLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * jable 会员中心的片单资料夹列表。
     *
     * 先走 `/my/playlists/`；部分主题把片单挂在 `/my/?type=playlists`，
     * 404 或空列表时自动改试后者。`listCode` 保存完整连结，供列表内容使用。
     */
    private fun jablePlaylistsFlow(page: Int): Flow<WebsiteState<Playlists>> = flow {
        val base = Preferences.baseUrl.trimEnd('/')
        val typePlaylistsUrl = "$base/my/?type=playlists"
        val candidates: List<suspend () -> Response<ResponseBody>> = buildList {
            if (page <= 1) {
                add { HanimeNetwork.hanimeService.getJableMyPlaylists() }
                add { HanimeNetwork.hanimeService.getHomePage(typePlaylistsUrl) }
            } else {
                add { HanimeNetwork.hanimeService.getJableMyPlaylistsPage(from = page) }
                add {
                    HanimeNetwork.hanimeService.getHomePage(
                        typePlaylistsUrl.withJableBlock(
                            blockId = "list_playlists_my_created_playlists",
                            fromParam = "from_my_playlists",
                            from = page,
                        )
                    )
                }
            }
        }
        var lastState: WebsiteState<Playlists>? = null
        for ((index, request) in candidates.withIndex()) {
            try {
                val response = request()
                val body = response.body()?.string()?.replaceBackupMediaCdnHost()
                if (!response.isSuccessful || body == null) {
                    response.throwRequestException()
                }
                val state = Parser.jablePlaylists(body)
                lastState = state
                val lists = (state as? WebsiteState.Success)?.info?.playlists.orEmpty()
                if (lists.isNotEmpty() || index == candidates.lastIndex) {
                    emit(state)
                    return@flow
                }
                Log.d("jable_playlists", "page=$page candidate #$index empty, trying next")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastState = WebsiteState.Error(handleException(e))
                Log.w(
                    "jable_playlists",
                    "page=$page candidate #$index failed: ${e.message}"
                )
            }
        }
        emit(lastState ?: WebsiteState.Error(IllegalStateException("jable playlists failed")))
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * jable 单个片单的内容。
     *
     * `listCode` 是片单资料夹页解析出来的完整连结（`/my/playlists/{id}/` 或
     * `/playlists/{id}/{slug}/`）。第 2 页起优先走 KVS block；不同主题的 block id
     * 可能是 `playlist_view_playlist_view` 或 `list_videos_my_favourite_videos`，
     * 故依序尝试，最后才退回路径式分页。
     */
    private fun jablePlaylistItemsFlow(
        listCode: String,
        page: Int,
    ): Flow<PageLoadingState<MyListItems<HanimeInfo>>> = flow {
        val url = listCode.toAbsoluteSiteUrl()
        val playlistId = Regex("""/(?:my/)?playlists/([^/?#]+)""")
            .find(url)
            ?.groupValues
            ?.get(1)
        val candidates = mutableListOf<String>()
        if (page <= 1) candidates += url
        candidates += url.withJableBlock(
            blockId = "playlist_view_playlist_view",
            fromParam = "from",
            from = page,
        )
        if (playlistId != null) {
            candidates += url.withJableBlock(
                blockId = "list_videos_my_favourite_videos",
                fromParam = "from_my_fav_videos",
                from = page,
                extra = mapOf("fav_type" to "0", "playlist_id" to playlistId),
            )
        }
        if (page > 1 && !url.contains('?')) {
            candidates += url.trimEnd('/') + "/$page/"
        }

        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            try {
                val response = HanimeNetwork.hanimeService.getHomePage(candidate)
                val body = response.body()?.string()?.replaceBackupMediaCdnHost()
                if (!response.isSuccessful || body == null) {
                    response.throwRequestException()
                }
                val state = Parser.jableUserVideoList(body, page)
                val cards = (state as? PageLoadingState.Success)
                    ?.info?.hanimeInfo.orEmpty()
                if (cards.isNotEmpty() || index == candidates.lastIndex) {
                    emit(state)
                    return@flow
                }
                Log.d(
                    "jable_playlist",
                    "page=$page candidate #$index empty: $candidate"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(
                    "jable_playlist",
                    "page=$page candidate #$index failed: ${e.message}"
                )
            }
        }
        emit(
            PageLoadingState.Error(
                handleException(
                    lastError ?: IllegalStateException("jable playlist returned no data")
                )
            )
        )
    }.catch { e ->
        emit(PageLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    private fun String.withJableBlock(
        blockId: String,
        fromParam: String,
        from: Int,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val url = toAbsoluteSiteUrl().toHttpUrlOrNull() ?: return this
        return url.newBuilder()
            .addQueryParameter("mode", "async")
            .addQueryParameter("function", "get_block")
            .addQueryParameter("block_id", blockId)
            .addQueryParameter(fromParam, from.toString())
            .apply { extra.forEach { (key, value) -> addQueryParameter(key, value) } }
            .addQueryParameter("_", System.currentTimeMillis().toString())
            .build()
            .toString()
    }

    private fun String.toAbsoluteSiteUrl(): String {
        if (startsWith("http://") || startsWith("https://")) return this
        return Preferences.baseUrl.trimEnd('/') + "/" + trimStart('/')
    }

    private fun String.withPageQuery(page: Int): String {
        if (page <= 1) return this
        val url = toHttpUrlOrNull() ?: return this
        return url.newBuilder().setQueryParameter("page", page.toString()).build().toString()
    }

    fun getHanimeVideo(videoCode: String) =
        if (Preferences.isMissavSite) {
            videoIOFlow(
                request = { HanimeNetwork.hanimeService.getMissavVideo(videoCode) },
                action = Parser::missavVideo
            ).transform { state ->
                // 喜欢状态与片单属于用户数据，SSR HTML 里只有匿名默认值，需再补两个接口
                emit(
                    if (state is VideoLoadingState.Success) {
                        VideoLoadingState.Success(enrichMissavVideo(videoCode, state.info))
                    } else state
                )
            }.flowOn(Dispatchers.IO)
        } else if (Preferences.isJableSite) {
            videoIOFlow(
                request = { HanimeNetwork.hanimeService.getJableVideo(videoCode) },
                action = Parser::jableVideo,
            )
        } else {
            videoIOFlow(
                request = { HanimeNetwork.hanimeService.getHanimeVideo(videoCode) },
                action = Parser::hanimeVideoVer2
            )
        }

    /**
     * missav 的 Laravel CSRF：`XSRF-TOKEN` cookie 需 URL 解码后放进 `X-XSRF-TOKEN` 头。
     *
     * 不能只读 [HCookieJar.cookieMap]：它要等本次行程收过该 host 的响应后才有值，
     * 而 `HCookieJar.loadForRequest` 同时还会带上持久化的登入 cookie。
     */
    private fun missavXsrfToken(): String? = missavCookies().firstOrNull { it.name == "XSRF-TOKEN" }
        ?.value
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /** 读一次响应体（成功/失败都可能有），之后只能靠返回值，不能再读第二次。 */
    private fun readResponseBody(resp: retrofit2.Response<*>): String? = runCatching {
        val body = (resp as? retrofit2.Response<okhttp3.ResponseBody>)?.errorBody()
            ?: resp.body() as? okhttp3.ResponseBody
        body?.string()
    }.getOrNull()

    /** 登入失败时把「状态码 + CF 标记 + 响应体前 300 字」写进 log，这是唯一能定位 419/403 的线索。 */
    private fun describeResponse(resp: retrofit2.Response<*>, body: String?) = buildString {
        append("code=").append(resp.code())
        resp.headers()["cf-mitigated"]?.let { append(", cf-mitigated=").append(it) }
        append(", set-cookie=").append(resp.headers().values("Set-Cookie").map { it.substringBefore('=') })
        body?.take(300)?.let { append(", body=").append(it) }
    }

    /**
     * missav 登入失败的原因。
     *
     * 422 的响应形如 `{"message":"登入失败","errors":{"email":["登入失败"]}}`，
     * 直接把服务端原文交给使用者，比自己猜「帐号或密码错误」准确。
     */
    private fun missavLoginError(code: Int, body: String?): Throwable {
        if (code == 422) {
            val detail = runCatching {
                val json = JSONObject(body.orEmpty())
                val errors = json.optJSONObject("errors")
                val firstError = errors?.keys()?.asSequence()
                    ?.mapNotNull { key -> errors.optJSONArray(key)?.optString(0) }
                    ?.firstOrNull { it.isNotBlank() }
                firstError ?: json.optString("message").takeIf { it.isNotBlank() }
            }.getOrNull()
            return IllegalStateException(detail ?: getString(R.string.account_or_password_wrong))
        }
        val hint = when (code) {
            403 -> "（Cloudflare 人機驗證攔截）"
            419 -> "（CSRF token 不匹配）"
            else -> ""
        }
        return java.io.IOException("missav 登入失敗：HTTP $code$hint")
    }

    /**
     * jable 登入失败原因：KVS 回 `{status:"failure", errors:[{field, code, message}]}`，
     * 直接把第一条 message 交给使用者。
     */
    private fun jableLoginError(code: Int, body: String?): Throwable {
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        val message = json?.optJSONArray("errors")?.let { errors ->
            (0 until errors.length())
                .mapNotNull { index ->
                    errors.optJSONObject(index)?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                }
                .firstOrNull()
        }
        if (!message.isNullOrBlank()) return IllegalStateException(message)
        val hint = if (code == 403) "（Cloudflare 人機驗證攔截）" else ""
        return java.io.IOException("jable 登入失敗：HTTP $code$hint")
    }

    /** 当前 missav 主机名（可能被镜像站覆盖，不能写死 `missav.ai`）。 */
    private fun missavHost(): String = runCatching {
        java.net.URI(Preferences.baseUrl).host
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "missav.ai"

    private fun missavCookies(host: String = missavHost()): List<okhttp3.Cookie> =
        com.yenaly.han1meviewer.logic.network.HCookieJar.cookieMap[host].orEmpty() +
                Preferences.loginCookie.toLoginCookieList(host)

    /**
     * 把 WebView 里的会话 cookie 灌进 okhttp 的 [HCookieJar]。
     *
     * WebView 是「真浏览器」：它已经通过 Cloudflare（`cf_clearance`）并且由 Laravel 下发过
     * `XSRF-TOKEN` / `missav_session`。okhttp 自己空手去撞 `/cn/api/login` 时，
     * 首页 `/dm12/cn` 只是个 Cloudflare 快取的 301（`cf-cache-status: HIT`，不带 Set-Cookie），
     * 拿不到 CSRF token，于是必然 419。
     */
    private fun seedMissavCookies(host: String, rawCookies: List<String>) {
        if (rawCookies.isEmpty()) return
        com.yenaly.han1meviewer.logic.network.HCookieJar
            .putRawCookieString(host, rawCookies.joinToString("; "))
        Log.d("missav_login", "seeded cookies=${missavCookies(host).map { it.name }}")
    }

    /**
     * 补齐 missav 影片的用户数据（未登入时直接跳过）：
     * - `GET /api/items/{itemId}/view` → `saved`（站内打开影片页时同样会调，附带记录观看历史）
     * - `GET /api/playlists/{dvdId}` → 用户片单，含 is_added
     */
    private suspend fun enrichMissavVideo(
        videoCode: String,
        video: com.yenaly.han1meviewer.logic.model.HanimeVideo,
    ): com.yenaly.han1meviewer.logic.model.HanimeVideo {
        if (!Preferences.isAlreadyLogin) return video
        var saved = video.isFav
        video.missavItemId?.let { itemId ->
            runCatching {
                HanimeNetwork.hanimeService.missavItemView(itemId)
                    .takeIf { it.isSuccessful }?.body()?.string()
                    ?.let { JSONObject(it).optBoolean("saved", saved) }
                    ?.let { saved = it }
            }.onFailure { Log.w("missav_view", "view($itemId) failed: ${it.message}") }
        }
        val myList = runCatching {
            HanimeNetwork.hanimeService.missavPlaylists(videoCode)
                .takeIf { it.isSuccessful }?.body()?.string()
                ?.let(Parser::missavPlaylists)
        }.onFailure { Log.w("missav_playlists", "playlists($videoCode) failed: ${it.message}") }
            .getOrNull()
        return video.copy(isFav = saved, myList = myList ?: video.myList)
    }

    /**
     * missav「喜欢」切换。注意与 Hanime不同：按站内 itemId 操作，取消用 DELETE。
     */
    fun missavToggleSave(itemId: String, save: Boolean) = websiteIOFlow(
        request = {
            val xsrf = missavXsrfToken()
            if (save) HanimeNetwork.hanimeService.missavSaveItem(itemId, xsrf)
            else HanimeNetwork.hanimeService.missavUnsaveItem(itemId, xsrf)
        }
    ) { body ->
        Log.d("missav_save", "save=$save, body=$body")
        WebsiteState.Success(save)
    }

    /**
     * jable「收藏」切换。
     *
     * 站点 JS 是对当前影片页发 GET（`mode=async&format=json&action=...`），
     * 成功回 `{"status":"success"}`；未登入回 `not_logged_in`。
     */
    fun jableToggleFavorite(
        videoCode: String,
        videoId: String,
        add: Boolean,
        favType: Int = 0,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.hanimeService.jableToggleFavourite(
                videoCode = videoCode,
                action = if (add) "add_to_favourites" else "delete_from_favourites",
                videoId = videoId,
                videoIds = videoId,
                favType = favType,
            )
        }
    ) { body ->
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json?.optString("status") == "success") {
            WebsiteState.Success(add)
        } else {
            val message = json?.optJSONArray("errors")
                ?.optJSONObject(0)
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
            WebsiteState.Error(
                IllegalStateException(message ?: body.take(200).ifBlank { "jable favourite failed" })
            )
        }
    }

    /**
     * missav 片单增删：影片页 `{dvdId, key}`，key 可能是数字或字串，按原样回传。
     */
    fun missavTogglePlaylist(dvdId: String, key: String, add: Boolean) = websiteIOFlow(
        request = {
            val keyElement = key.toLongOrNull()
                ?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                ?: kotlinx.serialization.json.JsonPrimitive(key)
            val body = com.yenaly.han1meviewer.logic.network.service.MissavPlaylistRequest(
                dvdId = dvdId, key = keyElement,
            )
            val xsrf = missavXsrfToken()
            if (add) HanimeNetwork.hanimeService.missavPlaylistAdd(body, xsrf)
            else HanimeNetwork.hanimeService.missavPlaylistRemove(body, xsrf)
        }
    ) { body ->
        Log.d("missav_playlist", "add=$add, key=$key, body=$body")
        WebsiteState.Success(Unit)
    }

    fun getHanimePreview(date: String) = websiteIOFlow(
        request = { HanimeNetwork.hanimeService.getHanimePreview(date) },
        action = Parser::hanimePreview
    )

    //获取订阅或者可以说是关注列表及它们的更新
    fun getMySubscriptions(page: Int) = websiteIOFlow(
        request = { HanimeNetwork.hanimeService.getMySubscriptions(page) },
        action = Parser::getMySubscriptions
    )
    //</editor-fold>

    //<editor-fold desc="My List">

    fun getMyListItems(userId: String, listType: Any, page: Int): Flow<PageLoadingState<MyListItems<HanimeInfo>>> {
        // jable 是 KVS 会员中心：
        //  - 喜欢 / 稍后观看共用同一个 block，只差 fav_type（0/1）；
        //  - 片单内容由 getMyPlayListItems 按片单连结抓取。
        if (Preferences.isJableSite) {
            return when (listType) {
                is MyListType -> when (listType) {
                    MyListType.FAV_VIDEO -> jableFavouritesFlow(page, favType = 0)
                    MyListType.WATCH_LATER -> jableFavouritesFlow(page, favType = 1)
                    else -> kotlinx.coroutines.flow.flowOf(PageLoadingState.NoMoreData)
                }

                is String -> when (listType) {
                    MyListType.FAV_VIDEO.value -> jableFavouritesFlow(page, favType = 0)
                    MyListType.WATCH_LATER.value -> jableFavouritesFlow(page, favType = 1)
                    else -> if (listType.contains("/playlists/") ||
                        listType.startsWith("http")
                    ) {
                        jablePlaylistItemsFlow(listType, page)
                    } else {
                        kotlinx.coroutines.flow.flowOf(PageLoadingState.NoMoreData)
                    }
                }

                else -> kotlinx.coroutines.flow.flowOf(PageLoadingState.NoMoreData)
            }
        }
        if (Preferences.isMissavSite) {
            // missav 的「喜欢」= /cn/saved（站内用 api/items/{id}/save 切换）。
            // 「稍后观看」在这个站没有对应页面，UI 已隐藏，这里返回空。
            val type = when (listType) {
                is MyListType -> listType
                is String -> MyListType.values().firstOrNull { it.value == listType }
                else -> null
            }
            if (type != MyListType.FAV_VIDEO) {
                return kotlinx.coroutines.flow.flowOf(PageLoadingState.NoMoreData)
            }
            return pageIOFlow(
                request = { HanimeNetwork.hanimeService.getMissavSaved(page) },
                action = { Parser.missavUserVideoList(it, page) },
            )
        }
        return pageIOFlow(
            request = {
                when (listType) {
                    is String ->
                        HanimeNetwork.myListService.getMyListItems(userId, listType, page)

                    is MyListType ->
                        HanimeNetwork.myListService.getMyListItems(userId, listType.value, page)

                    else ->
                        throw IllegalArgumentException("typeOrId must be String or MyListType")
                }
            },
            action = Parser::myListItems
        )
    }

    fun getMyPlayListItems(page: Int = 1, listCode: String = "0") =
        if (Preferences.isJableSite) jablePlaylistItemsFlow(listCode, page)
        else if (Preferences.isMissavSite) pageIOFlow(
            request = { HanimeNetwork.hanimeService.getMissavPlaylistItems(listCode, page) },
            action = { Parser.missavUserVideoList(it, page) },
        ) else pageIOFlow(
            request = {
                HanimeNetwork.myListService.getMyPlayListItems(listCode, page)
            },
            action = Parser::myPlayListItems
        )

    fun getOnlineWatchHistories(
        userId: String,
        sort: OnlineWatchHistorySort,
        page: Int,
    ) = if (Preferences.isJableSite) kotlinx.coroutines.flow.flowOf(PageLoadingState.NoMoreData)
    else if (Preferences.isMissavSite) pageIOFlow(
        request = { HanimeNetwork.hanimeService.getMissavHistory(page) },
        action = { Parser.missavUserVideoList(it, page) },
    ) else pageIOFlow(
        request = {
            HanimeNetwork.myListService.getOnlineWatchHistories(userId, sort.value, page)
        },
        action = Parser::onlineWatchHistoryItems,
    )

    fun getUserAccountPage(userId: String) = websiteIOFlow(
        request = { HanimeNetwork.myListService.getUserAccountPage(userId) },
        action = Parser::userAccountPage,
    )

    fun getUploadedVideos(
        userId: String,
        sort: CreatorSort,
        page: Int,
    ) = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getUploadedVideos(userId, sort.value, page)
        },
        action = Parser::creatorUploadedItems,
    )

    fun getUploadingVideos(
        userId: String,
        sort: CreatorSort,
        page: Int,
    ) = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getUploadingVideos(userId, sort.value, page)
        },
        action = Parser::creatorUploadingItems,
    )

    fun updateUserAccountProfile(
        userId: String,
        csrfToken: String?,
        name: String,
        email: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.updateUserAccountProfile(
                userId = userId,
                csrfToken = csrfToken,
                name = name,
                email = email,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun updateUserAccountPassword(
        userId: String,
        csrfToken: String?,
        oldPassword: String,
        newPassword: String,
        newPasswordConfirm: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.updateUserAccountPassword(
                userId = userId,
                csrfToken = csrfToken,
                oldPassword = oldPassword,
                newPassword = newPassword,
                newPasswordConfirm = newPasswordConfirm,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun updateUserAccountAvatar(
        userId: String,
        csrfToken: String?,
        avatarFile: File,
    ) = websiteIOFlow(
        request = {
            val imageRequestBody = avatarFile.asRequestBody("image/jpeg".toMediaType())
            val imagePart = MultipartBody.Part.createFormData(
                "photo",
                avatarFile.name,
                imageRequestBody,
            )
            HanimeNetwork.myListService.updateUserAccountAvatar(
                userId = userId,
                csrfToken = (csrfToken ?: EMPTY_STRING).toRequestBody("text/plain".toMediaType()),
                method = "patch".toRequestBody("text/plain".toMediaType()),
                type = "photo".toRequestBody("text/plain".toMediaType()),
                photo = imagePart,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun deleteOnlineWatchHistory(
        videoCode: String,
        position: Int,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.deleteOnlineWatchHistory(
                videoCode = videoCode,
                csrfToken = csrfToken,
            )
        },
    ) {
        val jsonObject = JSONObject(it)
        val success = jsonObject.optBoolean("success", false)
        if (success) {
            WebsiteState.Success(position)
        } else {
            WebsiteState.Error(IllegalStateException("cannot delete it ?!"))
        }
    }

    fun deleteMyListItems(
        itemId: String,
        position: Int,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.deleteMyListItems(
                itemId = itemId,
                csrfToken = token
            )
        },
    ) { deleteBody ->
        val jsonObject = JSONObject(deleteBody)
        val success = jsonObject.optBoolean("success", false)
        if (success) {
            WebsiteState.Success(position)
        } else {
            WebsiteState.Error(IllegalStateException("cannot delete it ?!"))
        }
    }

    fun getPlaylists(page: Int, userId: String ) =
        if (Preferences.isJableSite) jablePlaylistsFlow(page)
        else if (Preferences.isMissavSite) websiteIOFlow(
            request = { HanimeNetwork.hanimeService.getMissavPlaylistFolders(page) },
            action = Parser::missavPlaylistFolders,
        ) else websiteIOFlow(
            request = { HanimeNetwork.myListService.getPlaylists(userId, page) },
            action = Parser::playlists
        )

    fun addToMyFavVideo(
        videoCode: String,
        likeStatus: Boolean, // false => "": add fav; true => "1": cancel fav;
        currentUserId: String?,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.addToMyFavVideo(
                videoCode, if (likeStatus) "1" else EMPTY_STRING,
                token, currentUserId
            )
        }
    ) {
        Log.d("add_to_fav_body", it)
        return@websiteIOFlow WebsiteState.Success(likeStatus)
    }

    fun rateVideo(
        videoCode: String,
        isPositive: Boolean,
        likeStatus: Boolean,
        unlikeStatus: Boolean,
        likesCount: Int,
        unlikesCount: Int,
        currentUserId: String?,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.rateVideo(
                videoCode = videoCode,
                isPositive = if (isPositive) 1 else 0,
                likeStatus = if (likeStatus) "1" else EMPTY_STRING,
                unlikeStatus = if (unlikeStatus) "1" else EMPTY_STRING,
                likesCount = likesCount,
                unlikesCount = unlikesCount,
                csrfToken = token,
                userId = currentUserId,
            )
        }
    ) {
        Log.d("rate_video_body", it)
        return@websiteIOFlow WebsiteState.Success(isPositive)
    }

    fun createPlaylist(
        videoCode: String,
        title: String,
        description: String,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.createPlaylist(
                csrfToken, videoCode, title, description
            )
        },
        permittedSuccessCode = intArrayOf(500)
    ) {
        Log.d("create_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun addToMyList(
        listCode: String,
        videoCode: String,
        isChecked: Boolean,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.addToMyList(
                csrfToken, listCode, videoCode, isChecked
            )
        }
    ) {
        Log.d("add_to_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun modifyPlaylist(
        listCode: String,
        title: String,
        description: String,
        delete: Boolean,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.modifyPlaylist(
                listCode, title, description,
                if (delete) "on" else null,
                csrfToken
            )
        },
        permittedSuccessCode = intArrayOf(302)
    ) {
        Log.d("modify_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(
            ModifiedPlaylistArgs(
                title = title, desc = description, isDeleted = delete,
            )
        )
    }

    //</editor-fold>

    //<editor-fold desc="Comment">

    fun getComments(type: String, code: String): Flow<WebsiteState<VideoComments>> {
        // jable 的留言就是原站影片页的 KVS comments block，与 Hanime 的
        // `loadComment` 接口无关；影片码在这里是 jable 的 slug。
        if (Preferences.isJableSite) return jableCommentsFlow(code)
        return websiteIOFlow(
            request = { HanimeNetwork.commentService.getComments(type, code) },
            action = Parser::comments
        )
    }

    /**
     * jable 留言：先解析影片页自带的第一页，再按站点「载入更多」的规则
     * （`data-max-queries=3`、每页 5 条）补 2~4 页。
     * 第一页先 emit，后续页面拿到再更新，避免用户要等 4 个请求才看到留言。
     */
    private fun jableCommentsFlow(videoCode: String) = flow {
        val firstResponse = HanimeNetwork.hanimeService.getJableVideo(videoCode)
        val firstBody = firstResponse.body()?.string()?.replaceBackupMediaCdnHost()
        if (!firstResponse.isSuccessful || firstBody == null) {
            firstResponse.throwRequestException()
        }
        val first = Parser.jableComments(firstBody ?: EMPTY_STRING)
        emit(first)

        val all = (first as? WebsiteState.Success)?.info?.videoComment ?: return@flow
        if (all.isEmpty()) return@flow
        for (page in 2..4) {
            val response = HanimeNetwork.hanimeService.getJableCommentsPage(videoCode, from = page)
            val body = response.body()?.string()
            if (!response.isSuccessful || body == null) break
            val more = (Parser.jableComments(body) as? WebsiteState.Success)
                ?.info?.videoComment.orEmpty()
            if (more.isEmpty()) break
            all.addAll(more)
            emit(WebsiteState.Success(VideoComments(all.toMutableList())))
            if (more.size < JABLE_COMMENTS_PAGE_SIZE) break
        }
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    fun getCommentReply(commentId: String) = websiteIOFlow(
        request = { HanimeNetwork.commentService.getCommentReply(commentId) },
        action = Parser::commentReply
    )

    fun postComment(
        csrfToken: String?,
        currentUserId: String,
        targetUserId: String,
        type: String,
        text: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.postComment(
                csrfToken, currentUserId,
                type, targetUserId, text
            )
        }
    ) {
        Log.d("post_comment_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun postCommentReply(
        csrfToken: String?,
        replyCommentId: String,
        text: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.postCommentReply(
                csrfToken, replyCommentId, text
            )
        }
    ) {
        Log.d("post_comment_reply_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun likeComment(
        csrfToken: String?,
        commentPlace: CommentPlace,
        foreignId: String?,
        isPositive: Boolean, // 你选择的是赞还是踩，1是赞，0是踩
        likeUserId: String?,
        commentLikesCount: Int,
        commentLikesSum: Int,
        likeCommentStatus: Boolean, // 你之前有没有点过赞，1是0否
        unlikeCommentStatus: Boolean, // 你之前有没有点过踩，1是0否
        commentPosition: Int, comment: VideoComments.VideoComment,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.likeComment(
                csrfToken, commentPlace.value, foreignId,
                if (isPositive) 1 else 0,
                likeUserId, commentLikesCount, commentLikesSum,
                if (likeCommentStatus) 1 else 0,
                if (unlikeCommentStatus) 1 else 0
            )
        }
    ) {
        Log.d("like_comment_body", it)
        return@websiteIOFlow WebsiteState.Success(
            VideoCommentArgs(
                commentPosition, isPositive, comment
            )
        )
    }

    fun reportComment(
        csrfToken: String?,
        reason: String,
        currentUserId: String?,
        redirectUrl: String,
        reportableType: String?,
        reportableId: String?
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.submitReport(
                userId = currentUserId,
                csrfToken = csrfToken,
                redirectUrl = redirectUrl,
                reportableId = reportableId,
                reportableType = reportableType,
                reason = reason
            )
        },
        action = Parser::reportCommentResponse
    )

    //</editor-fold>

    //<editor-fold desc="Subscription">

    fun subscribeArtist(
        csrfToken: String?,
        userId: String,
        artistId: String,
        // 这里表示目标状态
        status: Boolean,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.subscriptionService.subscribeArtist(
                csrfToken, userId, artistId,
                if (status) "" else "1"
            )
        }
    ) {
        Log.d("subscribe_artist_body", it)
        return@websiteIOFlow WebsiteState.Success(status)
    }

    //</editor-fold>

    //<editor-fold desc="Base">

    fun getLatestVersion(forceCheck: Boolean = true) = flow {
        emit(WebsiteState.Loading)
        val versionInfo = HUpdater.checkForUpdate(forceCheck)
        emit(WebsiteState.Success(versionInfo))
    }.catch { e ->
        when (e) {
            is CancellationException -> throw e
            else -> {
                e.printStackTrace()
                emit(WebsiteState.Error(e))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * @param seedCookies 由登入页 WebView 取到的「真浏览器」cookie（`missav_session` / `XSRF-TOKEN`
     *   / `cf_clearance`）。okhttp 自己拿不到 CSRF token 时就靠它。
     */
    fun login(email: String, password: String, seedCookies: List<String>? = null) = flow {
        emit(WebsiteState.Loading)
        if (Preferences.isMissavSite) {
            val host = missavHost()
            seedMissavCookies(host, seedCookies.orEmpty())

            // 预热请求：仍缺 XSRF-TOKEN 时才抓页面（Laravel 会 Set-Cookie）。
            // 注意 `/dm12/cn` 是 Cloudflare 快取的 301（cf-cache-status: HIT），常常不带 Set-Cookie，
            // 所以要再补一个动态页（片单页就是登入页入口，未登入会弹登入框，必然是 Laravel 直出）。
            if (missavCookies(host).none { it.name == "XSRF-TOKEN" }) {
                runCatching { HanimeNetwork.hanimeService.getMissavHomePage() }
            }
            if (missavCookies(host).none { it.name == "XSRF-TOKEN" }) {
                runCatching { HanimeNetwork.hanimeService.getHomePage(HANIME_LOGIN_URL) }
            }
            fun jarCookies(): List<String> = missavCookies().map { "${it.name}=${it.value}" }
            val preLoginCookies = jarCookies()
            Log.d(
                "missav_login",
                "warmup done, jar=${missavCookies().map { it.name }}, xsrf=${missavXsrfToken() != null}"
            )

            var req = HanimeNetwork.hanimeService.missavLogin(
                com.yenaly.han1meviewer.logic.network.service.MissavLoginRequest(
                    email = email, password = password, remember = true
                ),
                missavXsrfToken(),
            )
            var respBody = readResponseBody(req)
            Log.d("missav_login", "login resp ${describeResponse(req, respBody)}")
            // 419 = CSRF token 过期，重新抓页面再试一次
            if (req.code() == 419) {
                // `/dm12/cn` 是 CF 快取的 301，重抓它换不到新 token；要抓动态页。
                runCatching { HanimeNetwork.hanimeService.getHomePage(HANIME_LOGIN_URL) }
                if (missavCookies(host).none { it.name == "XSRF-TOKEN" }) {
                    runCatching { HanimeNetwork.hanimeService.getMissavHomePage() }
                }
                req = HanimeNetwork.hanimeService.missavLogin(
                    com.yenaly.han1meviewer.logic.network.service.MissavLoginRequest(
                        email = email, password = password, remember = true
                    ),
                    missavXsrfToken(),
                )
                respBody = readResponseBody(req)
                Log.d("missav_login", "login retry(419) ${describeResponse(req, respBody)}")
            }
            if (req.isSuccessful) {
                // 会话 cookie 可能来自预热请求（POST 响应不一定重复下发），
                // 因此把「预热 jar + 响应 Set-Cookie + 登入后 jar」按名称合并，后者覆盖前者。
                val merged = LinkedHashMap<String, String>()
                fun putAll(list: List<String>) {
                    list.forEach { c ->
                        val name = c.substringBefore('=').trim()
                        if (name.isNotEmpty()) merged[name] = c
                    }
                }
                putAll(preLoginCookies)
                putAll(req.headers().values("Set-Cookie").map { it.substringBefore(';') })
                putAll(jarCookies())
                val cookies = merged.values.toList()
                Log.d("missav_login", "login ok, cookies=${cookies.map { it.substringBefore('=') }}")
                if (cookies.isEmpty()) {
                    emit(WebsiteState.Error(IllegalStateException(getString(R.string.login_failed))))
                } else {
                    emit(WebsiteState.Success(cookies))
                }
            } else {
                // 把真正的原因带给使用者：422 取服务端原文（Laravel 的 message / errors），
                // 403 是 Cloudflare 拦截，其余保留 HTTP 状态码。
                emit(WebsiteState.Error(missavLoginError(req.code(), respBody)))
            }
        } else if (Preferences.isJableSite) {
            val host = missavHost()
            // 预热 WebView 拿到的 cf_clearance / 会话 cookie；Cloudflare 的盾要靠它。
            seedMissavCookies(host, seedCookies.orEmpty())
            val loginPage = HanimeNetwork.hanimeService.getJableLoginPage()
            val loginBody = loginPage.body()?.string()
            if (!loginPage.isSuccessful || loginBody.isNullOrBlank()) {
                emit(WebsiteState.Error(jableLoginError(loginPage.code(), null)))
            } else {
                val fields = Parser.jableLoginFormFields(loginBody, email, password)
                val req = HanimeNetwork.hanimeService.jableLogin(fields)
                val respBody = readResponseBody(req)
                val json = runCatching { JSONObject(respBody.orEmpty()) }.getOrNull()
                Log.d(
                    "jable_login",
                    "login resp code=${req.code()}, status=${json?.optString("status")}",
                )
                if (req.isSuccessful && json?.optString("status") == "success") {
                    val merged = LinkedHashMap<String, String>()
                    fun putAll(list: List<String>) {
                        list.forEach { c ->
                            val name = c.substringBefore('=').trim()
                            if (name.isNotEmpty()) merged[name] = c
                        }
                    }
                    putAll(req.headers().values("Set-Cookie").map { it.substringBefore(';') })
                    putAll(
                        com.yenaly.han1meviewer.logic.network.HCookieJar.cookieMap[host]
                            .orEmpty()
                            .map { "${it.name}=${it.value}" }
                    )
                    val cookies = merged.values.toList()
                    Log.d("jable_login", "login ok, cookies=${cookies.map { it.substringBefore('=') }}")
                    if (cookies.isEmpty()) {
                        emit(WebsiteState.Error(IllegalStateException(getString(R.string.login_failed))))
                    } else {
                        emit(WebsiteState.Success(cookies))
                    }
                } else {
                    emit(WebsiteState.Error(jableLoginError(req.code(), respBody)))
                }
            }
        } else {
            // 首先获取token
            val loginPage = HanimeNetwork.hanimeService.getLoginPage()
            val token = loginPage.body()?.string()?.let(Parser::extractTokenFromLoginPage)
            val req = HanimeNetwork.hanimeService.login(token, email, password)
            if (req.isSuccessful) {
                // 再次获取登录页面，如果失败则返回 cookie
                // 因为登录成功再次访问 login 会 404，这是判断是否登录成功的方法
                val loginPageAgain = HanimeNetwork.hanimeService.getLoginPage()
                if (loginPageAgain.code() == 404) {
                    // Cookie 会返回 XSRF-TOKEN 和 hanime1_session，我们只需要后者
                    // 错误的，还需要 remember_web 字段！但我没找到！
                    Log.d("login_headers", req.headers().toMultimap().toString())
                    emit(WebsiteState.Success(req.headers().values("Set-Cookie")))
                } else {
                    emit(WebsiteState.Error(IllegalStateException(getString(R.string.account_or_password_wrong))))
                }
            } else {
                // 双重保险
                emit(WebsiteState.Error(IllegalStateException(getString(R.string.account_or_password_wrong))))
            }
        }
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于单网页的情况
     *
     * @param permittedSuccessCode 用于处理特殊情况，比如[NetworkRepo.modifyPlaylist]需要302成功
     */
    private fun <T> websiteIOFlow(
        request: suspend () -> Response<ResponseBody>,
        permittedSuccessCode: IntArray? = null,
        action: (String) -> WebsiteState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()?.replaceBackupMediaCdnHost()
        val permitted = permittedSuccessCode?.contains(requestResult.code()) == true
        if ((permitted || requestResult.isSuccessful)) {
            emit(action.invoke(resultBody ?: EMPTY_STRING))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于有page分页的情况
     */
    private fun <T> pageIOFlow(
        request: suspend () -> Response<ResponseBody>,
        action: (String) -> PageLoadingState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()?.replaceBackupMediaCdnHost()
        if (requestResult.isSuccessful && resultBody != null) {
            emit(action.invoke(resultBody))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(PageLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于影片界面
     */
    private fun <T> videoIOFlow(
        request: suspend () -> Response<ResponseBody>,
        action: (String) -> VideoLoadingState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()?.replaceBackupMediaCdnHost()
        if (requestResult.isSuccessful && resultBody != null) {
            emit(action.invoke(resultBody))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(VideoLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    internal fun Response<ResponseBody>.throwRequestException(): Nothing {
        val body = errorBody()?.string()
        when (val code = code()) {
            403 -> if (!body.isNullOrBlank()) {
                when {
                    "you have been blocked" in body ->
                        throw IPBlockedException(getString(R.string.cloudflare_ip_block_warning))

                    "Just a moment" in body ->
                        throw CloudFlareBlockedException(getString(R.string.cloudflare_network_mismatch))

                    else ->
                        throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出现在影片界面，当你v数不大时会报403
                }
            } else throw IllegalStateException("$code ${message()}")

            500 -> throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出现在影片界面，当你v数很大时会报500

            404 -> if (!isAlreadyLogin) {
                throw IllegalStateException(getString(R.string.not_logged_in_currently))
            } else {
                throw IllegalStateException("$code ${message()}")
            }

            else -> throw IllegalStateException("$code ${message()}")
        }
    }

    internal fun handleException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException -> throw e
            is ParseException -> {
                e.printStackTrace()
                ParseException(getString(R.string.parse_error_msg))
            }

            is SSLHandshakeException -> {
                e.printStackTrace()
                SSLHandshakeException(getString(R.string.ssl_handshake_error))
            }

            else -> {
                e.printStackTrace()
                e
            }
        }
    }

    //</editor-fold>

    private fun getString(resId: Int) = applicationContext.getString(resId)
}
