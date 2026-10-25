package com.yenaly.han1meviewer.logic.network.service

import androidx.annotation.IntRange
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

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
