package com.yenaly.han1meviewer.logic.network

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.logic.Parser
import com.yenaly.han1meviewer.logic.model.HomePage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * missav 首頁抓取器。
 *
 * missav 新版首頁（/dm247/cn 等）的內容區塊由 Alpine.js + Recombee 在瀏覽器端動態渲染，
 * okhttp 拿到的 HTML 只有「載入中」骨架，無法離線解析。因此首頁改用離屏 WebView 渲染，
 * 待 JS 執行完成後取回 DOM 字串交給 [Parser.missavHomePage]。
 *
 * WebView 同時具備通過 Cloudflare challenge 的能力，通過後 cookie 留在
 * [CookieManager] 中，並同步給 okhttp 側的 [HCookieJar] 供後續請求使用。
 */
object MissavHomeWebViewFetcher {

    private const val TAG = "MissavHomeFetcher"

    /** 渲染總超時：包含可能的 CF challenge 等待。 */
    private const val RENDER_TIMEOUT_MILLIS = 30_000L

    /** 頁面加載完成後額外等待 JS 拉取推薦數據的時間。 */
    private const val JS_SETTLE_MILLIS = 4_000L

    suspend fun fetch(context: Context): WebsiteState<HomePage> {
        val url = Preferences.baseUrl + "dm247/cn"
        Log.i(TAG, "fetch start: $url")
        val body = withTimeoutOrNull(RENDER_TIMEOUT_MILLIS) {
            renderHomeHtml(context, url)
        }
        Log.i(TAG, "render finished, htmlSize=${body?.length ?: -1}")
        syncCookiesToOkhttp()
        return if (body != null) {
            runCatching { Parser.missavHomePage(body) }
                .onSuccess { state ->
                    (state as? WebsiteState.Success)?.info?.let { home ->
                        Log.i(
                            TAG,
                            "parsed: latestHanime=${home.latestHanime.size} " +
                                "latestRelease=${home.latestRelease.size} " +
                                "sections=${home.missavSections.joinToString { "${it.title}:${it.videos.size}" }}"
                        )
                    }
                }
                .onFailure { Log.e(TAG, "parse failed", it) }
                .getOrElse { WebsiteState.Error(it) }
        } else {
            WebsiteState.Error(IllegalStateException("missav 首頁渲染超時"))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderHomeHtml(context: Context, url: String): String? =
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val mainHandler = android.os.Handler(context.mainLooper)
            var webView: WebView? = null
            fun complete(html: String?) {
                if (finished.compareAndSet(false, true)) {
                    if (continuation.isActive) continuation.resume(html)
                }
            }
            mainHandler.post {
                try {
                    webView = WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = USER_AGENT
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = false

                            override fun onPageFinished(view: WebView, finishedUrl: String) {
                                Log.i(TAG, "onPageFinished: $finishedUrl")
                                // 等 Alpine.js + Recombee 拉完推薦數據再取 DOM
                                view.postDelayed({
                                    view.evaluateJavascript(
                                        "document.documentElement.outerHTML"
                                    ) { html ->
                                        val decoded = html
                                            ?.takeIf { it.length > 2 }
                                            ?.removeSurrounding("\"")
                                            ?.replace("\\\"", "\"")
                                            ?.replace("\\\\", "\\")
                                            ?.replace("\\n", "\n")
                                            ?.replace("\\u003C", "<")
                                        Log.i(TAG, "evaluated htmlSize=${decoded?.length ?: -1}")
                                        complete(decoded)
                                        view.post { view.destroy() }
                                    }
                                }, JS_SETTLE_MILLIS)
                            }
                        }
                        Log.i(TAG, "loadUrl: $url")
                        loadUrl(url)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "webview create failed", e)
                    complete(null)
                    webView?.destroy()
                }
            }
            continuation.invokeOnCancellation {
                mainHandler.post { webView?.destroy() }
            }
        }

    /**
     * 把 WebView 側的 missav cookie 同步到 okhttp 的 [HCookieJar]，
     * 供搜索/視頻等後續 okhttp 請求復用 cf_clearance。
     */
    fun syncCookiesToOkhttp() {
        val host = runCatching {
            java.net.URI(Preferences.baseUrl).host
        }.getOrNull() ?: return
        val cookie = CookieManager.getInstance().getCookie(Preferences.baseUrl) ?: return
        if (cookie.contains("cf_clearance")) {
            Preferences.cloudFlareCookie =
                com.yenaly.han1meviewer.util.CookieString(cookie)
        }
    }
}
