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
     * 注意：**必須**掛 kotlinx-serialization 轉換器。
     * 以前這裏沒有轉換器，凡是 `@Body` 自訂型別的介面（missav 的
     * `missavLogin` / `missavPlaylistAdd` / `missavPlaylistRemove`）在呼叫時就會
     * 同步拋 `IllegalArgumentException: Unable to create @Body converter`，
     * 請求根本發不出去 —— 表現為「點登入毫無反應」。
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
     * Recombee 推薦接口（missav 首頁前幾欄）用的 client。
     *
     * **不能共用 [hClient]**：`HCookieJar.loadForRequest` 會把 `Preferences.loginCookie`
     * 按請求的 host 重新拼成 Cookie 發出去，也就是會把 missav 的會話 cookie 送到
     * `*.recombee.com`。這裏刻意不掛 CookieJar。
     */
    var recombeeClient: OkHttpClient = buildRecombeeClient()
        private set

    /**
     * Rebuild OkHttpClient
     */
    fun rebuildOkHttpClient() {
        hClient = buildHClient()
        getchuClient = buildGetchuClient()
        recombeeClient = buildRecombeeClient()
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
            .callTimeout(45, TimeUnit.SECONDS)
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
            // 推薦只是首頁的錦上添花：接口不通時要儘快失敗，別把首頁拖住
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
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
