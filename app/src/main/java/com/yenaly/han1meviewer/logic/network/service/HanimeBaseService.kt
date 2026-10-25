package com.yenaly.han1meviewer.logic.network.service

import androidx.annotation.IntRange
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** jable 搜尋結果的 KVS block id（取自搜尋頁 `#list_videos_videos_list_search_result`）。 */
private const val JABLE_SEARCH_BLOCK_ID = "list_videos_videos_list_search_result"

/** jable 影片頁留言的 KVS block id（取自 `section.comments[data-block-id]`）。 */
private const val JABLE_COMMENTS_BLOCK_ID = "video_comments_video_comments"

/** missav SPA 登入請求體（對應頁面 `axios.post('/cn/api/login', {email, password, remember})`） */
@Serializable
data class MissavLoginRequest(
    val email: String,
    val password: String,
    // 不給預設值：kotlinx.serialization 預設不會編碼「等於預設值」的欄位，
    // 給預設值會讓 remember 從請求體裡消失。
    val remember: Boolean,
)

/**
 * missav 片單增刪請求體（影片頁 `{dvdId, key}`，key 即片單的 key）。
 * key 在頁面裏可能是數字或字串，故保留原始 JSON 型別回傳。
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

    /** missav 首頁：dm12/cn 301 到 dm247/cn，retrofit 跟隨重定向後拿到服務端渲染的靜態分節 */
    @GET("dm12/cn")
    suspend fun getMissavHomePage(): Response<ResponseBody>

    /** 詳情頁固定走 /cn/，避免服務端按 IP 自動選擇語言（與首頁 /dm12/cn 保持一致） */
    @GET("cn/{videoCode}")
    suspend fun getMissavVideo(@Path("videoCode") videoCode: String): Response<ResponseBody>

    /**
     * missav 登入：SPA 的 JSON API（頁面 JS 中 `axios.post('/cn/api/login', ...)`）。
     * 需要先 GET 任意頁面拿 XSRF-TOKEN cookie，再以 X-XSRF-TOKEN 頭提交。
     */
    @Headers("Accept: application/json")
    @POST("cn/api/login")
    suspend fun missavLogin(
        @Body body: MissavLoginRequest,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    //<editor-fold desc="missav 使用者頁面（SSR，需登入會話 cookie）">

    /** 我的收藏（喜歡的影片） */
    @GET("cn/saved")
    suspend fun getMissavSaved(@Query("page") page: Int = 1): Response<ResponseBody>

    /** 觀看記錄 */
    @GET("cn/history")
    suspend fun getMissavHistory(@Query("page") page: Int = 1): Response<ResponseBody>

    /** 我的片單（資料夾列表） */
    @GET("cn/playlists")
    suspend fun getMissavPlaylistFolders(@Query("page") page: Int = 1): Response<ResponseBody>

    /** 單個片單的內容 */
    @GET("cn/playlists/{key}")
    suspend fun getMissavPlaylistItems(
        @Path("key") key: String,
        @Query("page") page: Int = 1,
    ): Response<ResponseBody>

    //</editor-fold>

    //<editor-fold desc="missav 喜歡 / 片單（取自影片頁內聯 Alpine 組件的 axios 調用）">

    /**
     * 影片頁載入時站內調用：返回 `{user, saved}`，同時寫入觀看記錄。
     * `itemId` 是站內物品 id（非 dvd id），可從影片頁 HTML 的 `api/items/{id}/save` 取得。
     */
    @Headers("Accept: application/json")
    @GET("api/items/{itemId}/view")
    suspend fun missavItemView(
        @Path("itemId") itemId: String,
    ): Response<ResponseBody>

    /** 加入「喜歡」（影片頁 `axios.post('/api/items/{id}/save')`） */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @POST("api/items/{itemId}/save")
    suspend fun missavSaveItem(
        @Path("itemId") itemId: String,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    /** 取消「喜歡」（影片頁 `axios.delete('/api/items/{id}/save')`） */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @HTTP(method = "DELETE", path = "api/items/{itemId}/save", hasBody = false)
    suspend fun missavUnsaveItem(
        @Path("itemId") itemId: String,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    /** 該影片所屬的片單列表：`{data: [{key, name, is_added}]}` */
    @Headers("Accept: application/json")
    @GET("api/playlists/{dvdId}")
    suspend fun missavPlaylists(
        @Path("dvdId") dvdId: String,
    ): Response<ResponseBody>

    /** 把影片加入片單（影片頁 `axios.post('/api/playlists/add', {dvdId, key})`） */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @POST("api/playlists/add")
    suspend fun missavPlaylistAdd(
        @Body body: MissavPlaylistRequest,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    /** 把影片移出片單（影片頁 `axios.post('/api/playlists/remove', {dvdId, key})`） */
    @Headers("Accept: application/json", "X-Requested-With: XMLHttpRequest")
    @POST("api/playlists/remove")
    suspend fun missavPlaylistRemove(
        @Body body: MissavPlaylistRequest,
        @Header("X-XSRF-TOKEN") xsrfToken: String?,
    ): Response<ResponseBody>

    //</editor-fold>

    //<editor-fold desc="jable（KVS，服務端渲染）">

    /** jable 搜尋首頁：`/search/{query}/`，query 為 URL 編碼後的關鍵詞。 */
    @GET("search/{query}/")
    suspend fun getJableSearch(@Path("query") query: String): Response<ResponseBody>

    /**
     * jable 搜尋第 2 頁起。KVS 的分頁是 AJAX block：在當前頁 URL 上追加
     * `mode=async&function=get_block&block_id=...&from=N`，回應是分頁容器片段。
     */
    @GET("search/{query}/")
    suspend fun getJableSearchPage(
        @Path("query") query: String,
        @Query("q") q: String,
        @Query("mode") mode: String = "async",
        @Query("function") function: String = "get_block",
        @Query("block_id") blockId: String = JABLE_SEARCH_BLOCK_ID,
        @Query("sort_by") sortBy: String = "post_date",
        // KVS 搜尋 block 的分頁參數有兩種寫法：純影片列表用 from，
        // 影片+合集混合列表用 from_videos+from_albums。三個都帶上以兼容兩種情況。
        @Query("from") from: Int,
        @Query("from_videos") fromVideos: Int,
        @Query("from_albums") fromAlbums: Int,
    ): Response<ResponseBody>

    /** 空關鍵詞的 `/search/`（站點顯示全部影片）。 */
    @GET("search/")
    suspend fun getJableSearchAll(): Response<ResponseBody>

    /**
     * 空關鍵詞分頁：站點的「全部影片」分頁連結就是 `/search/{page}/`（服務端直出），
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
     * jable 影片頁留言第 2 頁起。
     *
     * 站點的「載入更多」是 KVS block AJAX：在當前影片頁 URL 上追加
     * `mode=async&function=get_block&block_id=video_comments_video_comments`
     * `&sort_by=&from=2&ipp=5`（`data-parameters="sort_by:;from:2;ipp:5"`），
     * 回應是整個留言 block 的 HTML 片段。
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
     * jable 登入。站點是一個 `data-form="ajax"` 的普通表單，JS 提交時追加
     * `format=json&mode=async`，回應 `{"status":"success"|"failure", "errors":[...]}`。
     * 表單欄位用 [FieldMap] 原樣轉發，避免站點日後加 hidden 欄位（token）時失效。
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
     * jable 收藏切換：站點 JS 在當前影片頁 URL 上追加
     * `?mode=async&format=json&action=add_to_favourites|delete_from_favourites&video_id=...`
     * （`fav_type=0` 收藏，`1` 稍後觀看）。需要登入會話 cookie。
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
     * jable 會員中心「喜歡」影片列表。
     *
     * KVS 的會員頁本身是服務端渲染，但分頁統一走 block：
     * `?mode=async&function=get_block&block_id=list_videos_my_favourite_videos`
     * `&fav_type=0&playlist_id=0&from_my_fav_videos={page}`。
     * 第 1 頁也可直接帶同樣參數拿到片段，避免不同主題的 `/my/` 首頁 redirect 差異。
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

    /** jable 會員中心完整頁面（AJAX block 拿不到時的兜底）。 */
    @GET("my/")
    suspend fun getJableMyPage(): Response<ResponseBody>

    /** jable 會員中心的片單資料夾列表。 */
    @GET("my/playlists/")
    suspend fun getJableMyPlaylists(): Response<ResponseBody>

    /**
     * jable 片單資料夾列表第 2 頁起。部分 KVS 主題的片單列表也支持
     * `?type=playlists` 形式的會員頁，NetworkRepo 會在 404 時改試它。
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
