package com.yenaly.han1meviewer.logic.network

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.logic.Parser
import com.yenaly.han1meviewer.logic.model.HomePage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * missav 首頁抓取器。
 *
 * missav 新版首頁（/dm12/cn 等）的內容區塊由 Alpine.js + Recombee 在瀏覽器端動態渲染，
 * okhttp 拿到的 HTML 只有「載入中」骨架，無法離線解析。因此首頁改用 WebView 渲染。
 *
 * 【關鍵】WebView 必須 attach 到窗口才會真正執行 JS：實測純離屏（不 attach）的 WebView
 * 連 evaluateJavascript 回調和注入的 JS 都不會跑（渲染進程被系統掛起）。故通過
 * [MissavHomeRenderActivity]（透明、1dp）承載 WebView，渲染完取 DOM 後立即銷毀。
 */
object MissavHomeWebViewFetcher {

    private const val TAG = "MissavHomeFetcher"

    /** 渲染總超時：包含可能的 CF challenge 等待。 */
    private const val RENDER_TIMEOUT_MILLIS = 45_000L

    /** 頁面加載完成後額外等待 JS 拉取推薦數據的時間。 */
    internal const val JS_SETTLE_MILLIS = 6_000L

    /** Activity 與協程之間的結果傳遞。 */
    internal var pendingResult: ((String?) -> Unit)? = null

    suspend fun fetch(context: Context): WebsiteState<HomePage> {
        val url = Preferences.baseUrl + "dm12/cn"
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

    private suspend fun renderHomeHtml(context: Context, url: String): String? =
        suspendCancellableCoroutine { continuation ->
            pendingResult = { html ->
                if (continuation.isActive) continuation.resume(html)
            }
            val intent = android.content.Intent(context, MissavHomeRenderActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MissavHomeRenderActivity.EXTRA_URL, url)
            context.startActivity(intent)
            continuation.invokeOnCancellation {
                pendingResult = null
                MissavHomeRenderActivity.finishQuietly()
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

/**
 * 透明殼 Activity：承載 1dp 的 WebView 讓其真正渲染。
 * 渲染完成（JS 橋回傳 DOM）後立即 finish，用戶幾乎無感。
 */
class MissavHomeRenderActivity : Activity() {

    companion object {
        const val EXTRA_URL = "request_url"
        private const val TAG = "MissavHomeFetcher"
        private const val BRIDGE_NAME = "ANDROID_BRIDGE"
        private var instance: MissavHomeRenderActivity? = null

        fun finishQuietly() {
            instance?.finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = USER_AGENT
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onHtml(html: String?) {
                        Log.i(TAG, "bridge onHtml: size=${html?.length ?: -1}")
                        MissavHomeWebViewFetcher.pendingResult?.invoke(html)
                        MissavHomeWebViewFetcher.pendingResult = null
                        runOnUiThread { finish() }
                    }
                },
                BRIDGE_NAME
            )
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    Log.i(TAG, "onPageFinished: $finishedUrl")
                    view.postDelayed({
                        view.evaluateJavascript(
                            "$BRIDGE_NAME.onHtml(document.documentElement.outerHTML)",
                            null
                        )
                    }, MissavHomeWebViewFetcher.JS_SETTLE_MILLIS)
                }
            }
            layoutParams = FrameLayout.LayoutParams(1, 1)
            loadUrl(url)
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)
        )
        setContentView(root)
        Log.i(TAG, "render activity attached: $url")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
