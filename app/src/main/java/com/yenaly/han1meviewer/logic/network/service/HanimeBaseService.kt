package com.yenaly.han1meviewer.logic.network.service

import androidx.annotation.IntRange
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** jable 搜寻结果的 KVS block id（取自搜寻页 `#list_videos_videos_list_search_result`）。 */
private const val JABLE_SEARCH_BLOCK_ID = "list_videos_videos_list_search_result"

/** jable 影片页留言的 KVS block id（取自 `section.comments[data-block-id]`）。 */
private const val JABLE_COMMENTS_BLOCK_ID = "video_comments_video_comments"

/** missav SPA 登入请求体（对应页面 `axios.post('/cn/api/login', {email, password, remember})`） */
@Serializable
data class MissavLoginRequest(
    val email: String,
    val password: String,
    // 不给预设值：kotlinx.serialization 预设不会编码「等于预设值」的栏位，
    // 给预设值会让 remember 从请求体里消失。
    val remember: Boolean,
)

/**
 * missav 片单增删请求体（影片页 `{dvdId, key}`，key 即片单的 key）。
 * key 在页面里可能是数字或字串，故保留原始 JSON 型别回传。
 */
@Serializable
data class MissavPlaylistRequest(
    val dvdId: String,
    val key: kotlinx.serialization.json.JsonElement,
)

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:10
 */
interface HanimeBaseService {

    @GET
    suspend fun getHomePage(@Url url: String): Response<ResponseBody>

    @GET("search")
    suspend fun getHanimeSearchResult(
        @Query("page") @IntRange(from = 1) page: Int = 1,
        @Query("query") query: String? = null,
        @Query("genre") genre: String? = null,
        @Query("sort") sort: String? = null,
        @Query("broad") broad: String? = null,
//        @Query("year") year: Int? = null,
//        @Query("month") month: Int? = null,
        @Query("date") date: String? = null,
        @Query("duration") duration: String? = null,
        @Query("tags[]") tags: Set<String> = emptySet(),
        @Query("brands[]") brands: Set<String> = emptySet(),
    ): Response<ResponseBody>

    @GET("en/search/{query}")
    suspend fun getMissavSearchResult(
        @Path("query") query: String,
        @Query("page") @IntRange(from = 1) page: Int = 1,
    ): Response<ResponseBody>

    /** missav 首页：dm12/cn 301 到 dm247/cn，retrofit 跟随重定向后拿到服务端渲染的静态分节 */
    @GET("dm12/cn")
    suspend fun getMissavHomePage(): Response<ResponseBody>

    /** 详情页固定走 /cn/，避免服务端按 IP 自动选择语言（与首页 /dm12/cn 保持一致） */
    @GET("cn/{videoCode}")
    suspend fun getMissavVideo(@Path("videoCode") videoCode: String): Response<ResponseBody>

    /**
     * missav 登入：SPA 的 JSON API（页面 JS 中 `axios.post('/cn/api/login', ...)`）。
     * 需要先 GET 任意页面拿 XSRF-TOKEN cookie，再以 X-XSRF-TOKEN 头提交。
     */
    @Headers("Accept: application/json")
    @POST("cn/api/login")
    suspend fun missavLogin(
        @Body body: MissavLoginRequest,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    //<editor-fold desc="missav 使用者页面（SSR，需登入会话 cookie）">

    /** 我的收藏（喜欢的影片） */
    @GET("cn/saved")
    suspend fun getMissavSaved(@Query("page") page: Int = 1): Response<ResponseBody>

    /** 观看记录 */
    @GET("cn/history")
    suspend fun getMissavHistory(@Query("page") page: Int = 1): Response<ResponseBody>

    /** 我的片单（资料夹列表） */
    @GET("cn/playlists")
    suspend fun getMissavPlaylistFolders(@Query("page") page: Int = 1): Response<ResponseBody>

    /** 单个片单的内容 */
    @GET("cn/playlists/{key}")
    suspend fun getMissavPlaylistItems(
        @Path("key") key: String,
        @Query("page") page: Int = 1,
    ): Response<ResponseBody>

    //</editor-fold>

    //<editor-fold desc="missav 喜欢 / 片单（取自影片页内联 Alpine 组件的 axios 调用）">

    /**
     * 影片页载入时站内调用：返回 `{user, saved}`，同时写入观看记录。
     * `itemId` 是站内物品 id（非 dvd id），可从影片页 HTML 的 `api/items/{id}/save` 取得。
     */
    @Headers("Accept: application/json")
    @GET("api/items/{itemId}/view")
    suspend fun missavItemView(
        @Path("itemId") itemId: String,
    ): Response<ResponseBody>

    /** 加入「喜欢」（影片页 `axios.post('/api/items/{id}/save')`） */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @POST("api/items/{itemId}/save")
    suspend fun missavSaveItem(
        @Path("itemId") itemId: String,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    /** 取消「喜欢」（影片页 `axios.delete('/api/items/{id}/save')`） */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @HTTP(method = "DELETE", path = "api/items/{itemId}/save", hasBody = false)
    suspend fun missavUnsaveItem(
        @Path("itemId") itemId: String,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    /** 该影片所属的片单列表：`{data: [{key, name, is_added}]}` */
    @Headers("Accept: application/json")
    @GET("api/playlists/{dvdId}")
    suspend fun missavPlaylists(
        @Path("dvdId") dvdId: String,
    ): Response<ResponseBody>

    /** 把影片加入片单（影片页 `axios.post('/api/playlists/add', {dvdId, key})`） */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @POST("api/playlists/add")
    suspend fun missavPlaylistAdd(
        @Body body: MissavPlaylistRequest,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    /** 把影片移出片单（影片页 `axios.post('/api/playlists/remove', {dvdId, key})`） */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @POST("api/playlists/remove")
    suspend fun missavPlaylistRemove(
        @Body body: MissavPlaylistRequest,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    //</editor-fold>

    //<editor-fold desc="jable（KVS，服务端渲染）">

    /** jable 搜寻首页：`/search/{query}/`，query 为 URL 编码后的关键词。 */
    @GET("search/{query}/")
    suspend fun getJableSearch(@Path("query") query: String): Response<ResponseBody>

    /**
     * jable 搜寻第 2 页起。KVS 的分页是 AJAX block：在当前页 URL 上追加
     * `mode=async&function=get_block&block_id=...&from=N`，回应是分页容器片段。
     */
    @GET("search/{query}/")
    suspend fun getJableSearchPage(
        @Path("query") query: String,
        @Query("q") q: String,
        @Query("mode") mode: String = "async",
        @Query("function") function: String = "get_block",
        @Query("block_id") blockId: String = JABLE_SEARCH_BLOCK_ID,
        @Query("sort_by") sortBy: String = "post_date",
        // KVS 搜寻 block 的分页参数有两种写法：纯影片列表用 from，
        // 影片+合集混合列表用 from_videos+from_albums。三个都带上以兼容两种情况。
        @Query("from") from: Int,
        @Query("from_videos") fromVideos: Int,
        @Query("from_albums") fromAlbums: Int,
    ): Response<ResponseBody>

    /** 空关键词的 `/search/`（站点显示全部影片）。 */
    @GET("search/")
    suspend fun getJableSearchAll(): Response<ResponseBody>

    /**
     * 空关键词分页：站点的「全部影片」分页连结就是 `/search/{page}/`（服务端直出），
     * 不需要走 KVS block AJAX。
     */
    @GET("search/{page}/")
    suspend fun getJableSearchAllPage(
        @Path("page") page: Int,
    ): Response<ResponseBody>

    @GET("videos/{videoCode}/")
    suspend fun getJableVideo(
        @Path("videoCode") videoCode: String,
    ): Response<ResponseBody>

    /**
     * jable 影片页留言第 2 页起。
     *
     * 站点的「载入更多」是 KVS block AJAX：在当前影片页 URL 上追加
     * `mode=async&function=get_block&block_id=video_comments_video_comments`
     * `&sort_by=&from=2&ipp=5`（`data-parameters="sort_by:;from:2;ipp:5"`），
     * 回应是整个留言 block 的 HTML 片段。
     */
    @Headers("X-Requested-With: XMLHttpRequest")
    @GET("videos/{videoCode}/")
    suspend fun getJableCommentsPage(
        @Path("videoCode") videoCode: String,
        @Query("mode") mode: String = "async",
        @Query("function") function: String = "get_block",
        @Query("block_id") blockId: String = JABLE_COMMENTS_BLOCK_ID,
        @Query("sort_by") sortBy: String = "",
        @Query("from") from: Int,
        @Query("ipp") ipp: Int = 5,
    ): Response<ResponseBody>

    /**
     * jable 登入。站点是一个 `data-form="ajax"` 的普通表单，JS 提交时追加
     * `format=json&mode=async`，回应 `{"status":"success"|"failure", "errors":[...]}`。
     * 表单栏位用 [FieldMap] 原样转发，避免站点日后加 hidden 栏位（token）时失效。
     */
    @GET("login/")
    suspend fun getJableLoginPage(): Response<ResponseBody>

    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @FormUrlEncoded
    @POST("login/")
    suspend fun jableLogin(
        @FieldMap fields: Map<String, String>,
    ): Response<ResponseBody>

    /**
     * jable 收藏切换：站点 JS 在当前影片页 URL 上追加
     * `?mode=async&format=json&action=add_to_favourites|delete_from_favourites&video_id=...`
     * （`fav_type=0` 收藏，`1` 稍后观看）。需要登入会话 cookie。
     */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @GET("videos/{videoCode}/")
    suspend fun jableToggleFavourite(
        @Path("videoCode") videoCode: String,
        @Query("mode") mode: String = "async",
        @Query("format") format: String = "json",
        @Query("action") action: String,
        @Query("video_id") videoId: String,
        @Query("video_ids[]") videoIds: String,
        @Query("fav_type") favType: Int = 0,
        @Query("playlist_id") playlistId: Int = 0,
    ): Response<ResponseBody>

    /**
     * jable 会员中心「喜欢」影片列表。
     *
     * KVS 的会员页本身是服务端渲染，但分页统一走 block：
     * `?mode=async&function=get_block&block_id=list_videos_my_favourite_videos`
     * `&fav_type=0&playlist_id=0&from_my_fav_videos={page}`。
     * 第 1 页也可直接带同样参数拿到片段，避免不同主题的 `/my/` 首页 redirect 差异。
     */
    @GET("my/")
    suspend fun getJableMyFavourites(
        @Query("mode") mode: String = "async",
        @Query("function") function: String = "get_block",
        @Query("block_id") blockId: String = "list_videos_my_favourite_videos",
        @Query("fav_type") favType: Int = 0,
        @Query("playlist_id") playlistId: Int = 0,
        @Query("sort_by") sortBy: String = "",
        @Query("from_my_fav_videos") from: Int,
        @Query("_") timestamp: Long = System.currentTimeMillis(),
    ): Response<ResponseBody>

    /** jable 会员中心完整页面（AJAX block 拿不到时的兜底）。 */
    @GET("my/")
    suspend fun getJableMyPage(): Response<ResponseBody>

    /** jable 会员中心的片单资料夹列表。 */
    @GET("my/playlists/")
    suspend fun getJableMyPlaylists(): Response<ResponseBody>

    /**
     * jable 片单资料夹列表第 2 页起。部分 KVS 主题的片单列表也支持
     * `?type=playlists` 形式的会员页，NetworkRepo 会在 404 时改试它。
     */
    @GET("my/playlists/")
    suspend fun getJableMyPlaylistsPage(
        @Query("mode") mode: String = "async",
        @Query("function") function: String = "get_block",
        @Query("block_id") blockId: String = "list_playlists_my_created_playlists",
        @Query("sort_by") sortBy: String = "last_content_date",
        @Query("from_my_playlists") from: Int,
        @Query("_") timestamp: Long = System.currentTimeMillis(),
    ): Response<ResponseBody>

    //</editor-fold>

    @GET("watch")
    suspend fun getHanimeVideo(
        @Query("v") videoCode: String,
    ): Response<ResponseBody>

    @GET("previews/{date}")
    suspend fun getHanimePreview(
        @Path("date") date: String, // 类似 202206. 202012
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("login")
    suspend fun login(
        @Field("_token") csrfToken: String?,
        @Field("email") email: String,
        @Field("password") password: String,
        @Header("X-CSRF-TOKEN") csrfToken_1: String? = csrfToken,
    ): Response<ResponseBody>

    @GET("login")
    suspend fun getLoginPage(): Response<ResponseBody>

    @GET("subscriptions")
    suspend fun getMySubscriptions(
        @Query("page") page: Int
    ): Response<ResponseBody>

}
