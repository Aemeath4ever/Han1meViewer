package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.HA1_GITHUB_API_URL
import com.yenaly.han1meviewer.HJson
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.interceptor.CloudflareInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.GetchuInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.SpeedLimitInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import com.yenaly.yenaly_libs.utils.applicationContext
import com.yenaly.yenaly_libs.utils.unsafeLazy
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:35
 */
object ServiceCreator {

    private val cache = Cache(
        directory = File(applicationContext.cacheDir, "http_cache"),
        maxSize = 10 * 1024 * 1024
    )

    private val downloadSpeedLimitInterceptor by unsafeLazy {
        SpeedLimitInterceptor(maxSpeed = Preferences.downloadSpeedLimit)
    }

    private val dns = HDns()

    /**
     * 注意：**必须**挂 kotlinx-serialization 转换器。
     * 以前这里没有转换器，凡是 `@Body` 自订型别的介面（missav 的
     * `missavLogin` / `missavPlaylistAdd` / `missavPlaylistRemove`）在呼叫时就会
     * 同步抛 `IllegalArgumentException: Unable to create @Body converter`，
     * 请求根本发不出去 —— 表现为「点登入毫无反应」。
     */
    inline fun <reified T> create(baseUrl: String): T = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(hClient)
        .addConverterFactory(HJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)

    inline fun <reified T> createGitHubApi(): T = Retrofit.Builder()
        .baseUrl(HA1_GITHUB_API_URL)
        .client(githubClient)
        .addConverterFactory(HJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)

    inline fun <reified T> createGetchu(baseUrl: String): T = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(getchuClient)
        .build()
        .create(T::class.java)

    /**
     * OkHttpClient
     */
    var hClient: OkHttpClient = buildHClient()
        private set

    var githubClient: OkHttpClient = buildGithubClient()
        private set

    var downloadClient: OkHttpClient = buildDownloadClient()
        private set

    var getchuClient: OkHttpClient = buildGetchuClient()
        private set

    /**
     * Recombee 推荐接口（missav 首页前几栏）用的 client。
     *
     * **不能共用 [hClient]**：`HCookieJar.loadForRequest` 会把 `Preferences.loginCookie`
     * 按请求的 host 重新拼成 Cookie 发出去，也就是会把 missav 的会话 cookie 送到
     * `*.recombee.com`。这里刻意不挂 CookieJar。
     */
    var recombeeClient: OkHttpClient = buildRecombeeClient()
        private set

    /**
     * 下载站点静态资源（目前只有 missav 的 `app.<hash>.js`，用来取 Recombee 的公开
     * token）用的 client：要带 cookie / UA / 代理，但**不挂** [CloudflareInterceptor]。
     *
     * 走 [hClient] 的话，资源请求被盾拦时会给用户弹出人机验证页（页面里是一个 js 档），
     * 还会占住 CloudflareActivity 的等待锁，把真正的首页请求一起拖死。静态资源拿不到
     * 就该直接放弃推荐，而不是惊动用户。
     */
    var assetClient: OkHttpClient = buildAssetClient()
        private set

    /**
     * Rebuild OkHttpClient
     */
    fun rebuildOkHttpClient() {
        hClient = buildHClient()
        getchuClient = buildGetchuClient()
        recombeeClient = buildRecombeeClient()
        assetClient = buildAssetClient()
    }

    private fun buildGetchuClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor(GetchuInterceptor())
            .cookieJar(CookieJar.NO_COOKIES)
            .proxySelector(HProxySelector())
            .dns(dns)
            .build()
    }

    private fun buildDownloadClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(downloadSpeedLimitInterceptor)
            .dns(dns)
            .build()
    }

    /**
     * Build OkHttpClient
     */
    private fun buildHClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 部分 CDN 节点会接受连接后不回响应（黑洞），读超时必须有，
            // 否则首页等请求会永远挂在 recvfrom 上表现为无限 Loading
            .readTimeout(20, TimeUnit.SECONDS)
            // callTimeout 只是兜底，必须大于 CloudflareInterceptor 等待人机验证的时间
            // （WAIT_SECONDS = 90s）：否则用户还在验证页上，整个 call 就先被取消，
            // 之后验证成功也来不及重放，首页只能显示失败。
            .callTimeout(150, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor(CloudflareInterceptor(applicationContext))
            .cache(cache)
            .cookieJar(HCookieJar())
            .proxySelector(HProxySelector())
            .dns(dns)
            .build()
    }

    private fun buildRecombeeClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // 推荐只是首页的锦上添花：接口不通时要尽快失败，别把首页拖住
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .proxySelector(HProxySelector())
            .dns(dns)
            .build()
    }

    private fun buildAssetClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .cookieJar(HCookieJar())
            .proxySelector(HProxySelector())
            .dns(dns)
            .build()
    }

    private fun buildGithubClient(): OkHttpClient {
        // 令牌可能为空（CI/本地构建时没有配置 HA_GITHUB_TOKEN）。以前这里无条件带上
        // "Bearer "，会得到一个必然 401 的非法凭证，反而掩盖了真正的原因；
        // 空令牌时干脆走匿名请求（限额更低，但至少不会自己把自己弄挂）。
        val token = BuildConfig.HA_GITHUB_TOKEN.trim()
        if (token.isEmpty()) {
            Log.w("ServiceCreator", "HA_GITHUB_TOKEN 为空，GitHub 请求将以匿名方式进行（限额更低）")
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .dns(GitHubDns)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                if (token.isNotEmpty()) {
                    builder.addHeader("Authorization", "Bearer $token")
                }
                return@addInterceptor chain.proceed(builder.build())
            }
            .build()
    }
}
