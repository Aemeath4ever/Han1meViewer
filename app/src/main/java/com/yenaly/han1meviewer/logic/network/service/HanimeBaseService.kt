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
    val remember: Boolean = true,
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
