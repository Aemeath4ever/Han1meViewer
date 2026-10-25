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
import com.yenaly.han1meviewer.logic.model.HanimeSearchResult
import com.yenaly.han1meviewer.logic.model.HomePage
import com.yenaly.han1meviewer.logic.model.VideoCommentArgs
import com.yenaly.han1meviewer.logic.model.VideoComments
import com.yenaly.han1meviewer.logic.network.HUpdater
import com.yenaly.han1meviewer.logic.network.HanimeNetwork
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

    //<editor-fold desc="Hanime">

    fun getHomePage(): Flow<WebsiteState<HomePage>> = if (Preferences.isMissavSite) {
        // missav 新版首页的服务端静态分节（新作上市/最近更新/无码影片/随机）okhttp 可直接获取；
        // 「推荐给你」等 Recombee 分节为客户端渲染，静态 HTML 中无数据，不展示。
        websiteIOFlow(
            request = { HanimeNetwork.hanimeService.getMissavHomePage() },
            action = Parser::missavHomePage,
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
            // missav 僅支持關鍵詞 + 頁碼，高級篩選不生效
            pageIOFlow(
                request = {
                    HanimeNetwork.hanimeService.getMissavSearchResult(query.orEmpty(), page)
                },
                action = { Parser.missavSearch(it, page) },
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

    fun getHanimeVideo(videoCode: String) =
        if (Preferences.isMissavSite) {
            videoIOFlow(
                request = { HanimeNetwork.hanimeService.getMissavVideo(videoCode) },
                action = Parser::missavVideo
            ).transform { state ->
                // 喜歡狀態與片單屬於用戶數據，SSR HTML 裏只有匿名默認值，需再補兩個接口
                emit(
                    if (state is VideoLoadingState.Success) {
                        VideoLoadingState.Success(enrichMissavVideo(videoCode, state.info))
                    } else state
                )
            }.flowOn(Dispatchers.IO)
        } else {
            videoIOFlow(
                request = { HanimeNetwork.hanimeService.getHanimeVideo(videoCode) },
                action = Parser::hanimeVideoVer2
            )
        }

    /**
     * missav 的 Laravel CSRF：`XSRF-TOKEN` cookie 需 URL 解碼後放進 `X-XSRF-TOKEN` 頭。
     *
     * 不能只讀 [HCookieJar.cookieMap]：它要等本次行程收過該 host 的響應後才有值，
     * 而 `HCookieJar.loadForRequest` 同時還會帶上持久化的登入 cookie。
     */
    private fun missavXsrfToken(): String? = missavCookies().firstOrNull { it.name == "XSRF-TOKEN" }
        ?.value
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /** 讀一次響應體（成功/失敗都可能有），之後只能靠返回值，不能再讀第二次。 */
    private fun readResponseBody(resp: retrofit2.Response<*>): String? = runCatching {
        val body = (resp as? retrofit2.Response<okhttp3.ResponseBody>)?.errorBody()
            ?: resp.body() as? okhttp3.ResponseBody
        body?.string()
    }.getOrNull()

    /** 登入失敗時把「狀態碼 + CF 標記 + 響應體前 300 字」寫進 log，這是唯一能定位 419/403 的線索。 */
    private fun describeResponse(resp: retrofit2.Response<*>, body: String?) = buildString {
        append("code=").append(resp.code())
        resp.headers()["cf-mitigated"]?.let { append(", cf-mitigated=").append(it) }
        append(", set-cookie=").append(resp.headers().values("Set-Cookie").map { it.substringBefore('=') })
        body?.take(300)?.let { append(", body=").append(it) }
    }

    /**
     * missav 登入失敗的原因。
     *
     * 422 的響應形如 `{"message":"登入失敗","errors":{"email":["登入失敗"]}}`，
     * 直接把服務端原文交给使用者，比自己猜「帳號或密碼錯誤」準確。
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

    /** 當前 missav 主機名（可能被鏡像站覆蓋，不能寫死 `missav.ai`）。 */
    private fun missavHost(): String = runCatching {
        java.net.URI(Preferences.baseUrl).host
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "missav.ai"

    private fun missavCookies(host: String = missavHost()): List<okhttp3.Cookie> =
        com.yenaly.han1meviewer.logic.network.HCookieJar.cookieMap[host].orEmpty() +
                Preferences.loginCookie.toLoginCookieList(host)

    /**
     * 把 WebView 裏的會話 cookie 灌進 okhttp 的 [HCookieJar]。
     *
     * WebView 是「真瀏覽器」：它已經通過 Cloudflare（`cf_clearance`）並且由 Laravel 下發過
     * `XSRF-TOKEN` / `missav_session`。okhttp 自己空手去撞 `/cn/api/login` 時，
     * 首頁 `/dm12/cn` 只是個 Cloudflare 快取的 301（`cf-cache-status: HIT`，不帶 Set-Cookie），
     * 拿不到 CSRF token，於是必然 419。
     */
    private fun seedMissavCookies(host: String, rawCookies: List<String>) {
        if (rawCookies.isEmpty()) return
        val jar = com.yenaly.han1meviewer.logic.network.HCookieJar.cookieMap
            .getOrPut(host) { mutableListOf() }
        rawCookies.forEach { raw ->
            val pair = raw.substringBefore(';').trim()
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) return@forEach
            val cookie = runCatching {
                okhttp3.Cookie.Builder()
                    .domain(host)
                    .path("/")
                    .name(name)
                    .value(value)
                    .expiresAt(Long.MAX_VALUE)
                    .build()
            }.getOrNull() ?: return@forEach
            jar.removeAll { it.name == name }
            jar.add(cookie)
        }
        Log.d("missav_login", "seeded cookies=${jar.map { it.name }}")
    }

    /**
     * 補齊 missav 影片的用戶數據（未登入時直接跳過）：
     * - `GET /api/items/{itemId}/view` → `saved`（站內打開影片頁時同樣會調，附帶記錄觀看歷史）
     * - `GET /api/playlists/{dvdId}` → 用戶片單，含 is_added
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
     * missav「喜歡」切換。注意與漢米不同：按站內 itemId 操作，取消用 DELETE。
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
     * missav 片單增刪：影片頁 `{dvdId, key}`，key 可能是數字或字串，按原樣回傳。
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
        if (Preferences.isMissavSite) {
            // missav 的「喜歡」= /cn/saved（站內用 api/items/{id}/save 切換）。
            // 「稍後觀看」在這個站沒有對應頁面，UI 已隱藏，這裡返回空。
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
        if (Preferences.isMissavSite) pageIOFlow(
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
    ) = if (Preferences.isMissavSite) pageIOFlow(
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
        if (Preferences.isMissavSite) websiteIOFlow(
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

    fun getComments(type: String, code: String) = websiteIOFlow(
        request = { HanimeNetwork.commentService.getComments(type, code) },
        action = Parser::comments
    )

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
        isPositive: Boolean, // 你選擇的是讚還是踩，1是讚，0是踩
        likeUserId: String?,
        commentLikesCount: Int,
        commentLikesSum: Int,
        likeCommentStatus: Boolean, // 你之前有沒有點過讚，1是0否
        unlikeCommentStatus: Boolean, // 你之前有沒有點過踩，1是0否
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
     * @param seedCookies 由登入頁 WebView 取到的「真瀏覽器」cookie（`missav_session` / `XSRF-TOKEN`
     *   / `cf_clearance`）。okhttp 自己拿不到 CSRF token 時就靠它。
     */
    fun login(email: String, password: String, seedCookies: List<String>? = null) = flow {
        emit(WebsiteState.Loading)
        if (Preferences.isMissavSite) {
            val host = missavHost()
            seedMissavCookies(host, seedCookies.orEmpty())

            // 預熱請求：仍缺 XSRF-TOKEN 時才抓頁面（Laravel 會 Set-Cookie）。
            // 注意 `/dm12/cn` 是 Cloudflare 快取的 301（cf-cache-status: HIT），常常不帶 Set-Cookie，
            // 所以要再補一個動態頁（片單頁就是登入頁入口，未登入會彈登入框，必然是 Laravel 直出）。
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
            // 419 = CSRF token 過期，重新抓頁面再試一次
            if (req.code() == 419) {
                // `/dm12/cn` 是 CF 快取的 301，重抓它換不到新 token；要抓動態頁。
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
                // 會話 cookie 可能來自預熱請求（POST 響應不一定重複下發），
                // 因此把「預熱 jar + 響應 Set-Cookie + 登入後 jar」按名稱合併，後者覆蓋前者。
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
                // 把真正的原因帶給使用者：422 取服務端原文（Laravel 的 message / errors），
                // 403 是 Cloudflare 攔截，其餘保留 HTTP 狀態碼。
                emit(WebsiteState.Error(missavLoginError(req.code(), respBody)))
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
                    // Cookie 會返回 XSRF-TOKEN 和 hanime1_session，我們只需要後者
                    // 错误的，还需要 remember_web 字段！但我没找到！
                    Log.d("login_headers", req.headers().toMultimap().toString())
                    emit(WebsiteState.Success(req.headers().values("Set-Cookie")))
                } else {
                    emit(WebsiteState.Error(IllegalStateException(getString(R.string.account_or_password_wrong))))
                }
            } else {
                // 雙重保險
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
                        throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出現在影片界面，當你v數不大時會報403
                }
            } else throw IllegalStateException("$code ${message()}")

            500 -> throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出現在影片界面，當你v數很大時會報500

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
