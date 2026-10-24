package com.yenaly.han1meviewer.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.preference.PreferenceManager
import com.yenaly.han1meviewer.Preferences.cloudFlareCookie
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.ui.screen.web.CloudflareScreen
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.han1meviewer.util.CookieString
import java.util.Locale

class CloudflareActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "request_url"
        var onFinished: (() -> Unit)? = null
    }

    private val progressState = mutableIntStateOf(0)
    private val tipTextState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }

        tipTextState.value = getString(R.string.complete_cloudflare_verification_with_warning)

        val composeView = ComposeView(this)
        setContentView(composeView)
        composeView.setContent {
            HanimeTheme {
                CloudflareScreen(
                    progress = progressState.intValue,
                    tipText = tipTextState.value,
                    onClose = { finish() },
                    webViewFactory = { createWebView(url) },
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String): WebView {
        return WebView(this).apply {
            val wv = this
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
                userAgentString = USER_AGENT
            }

            val cookieMgr = CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false
            }

            evaluateJavascript("navigator.userAgent") { output ->
                val userAgent = output
                    .removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val chromePattern = "Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)".toRegex()
                val versionCode =
                    chromePattern.find(userAgent)?.groupValues?.getOrNull(1) ?: userAgent
                runOnUiThread {
                    var t = getString(R.string.complete_cloudflare_verification_with_warning)
                    t += getString(R.string.current_webview_version, versionCode)
                    try {
                        val parts = versionCode.split(".").map { it.toIntOrNull() ?: 0 }
                        if (parts.size >= 4) {
                            if (parts[0] < 120) {
                                t += getString(R.string.webview_version_too_low)
                            }
                        } else {
                            t += getString(R.string.webview_version_unknown)
                        }
                    } catch (_: Exception) {
                        t += getString(R.string.version_check_failed)
                    }
                    tipTextState.value = t
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressState.intValue = newProgress
                    if (newProgress >= 90) {
                        // challenge 判定轮询：Turnstile 验证可能在进度到顶后仍需数秒，
                        // 只检查一次会漏掉验证成功的时机，需持续轮询直到拿到 cookie
                        view?.postDelayed({
                            checkChallengeResult(view, cookieMgr, url)
                        }, 1000)
                    }
                }
            }
            loadUrl(url)
        }
    }

    /**
     * 检查 challenge 是否已通过：页面 head 不再含 challenge 元素且 cookie 里有
     * cf_clearance 时保存 cookie 并结束；否则延时继续轮询。
     */
    private fun checkChallengeResult(view: WebView, cookieMgr: CookieManager, url: String) {
        if (isDestroyed || isFinishing) return
        view.evaluateJavascript("document.head.innerHTML") { html ->
            if (html.contains("#challenge-form") ||
                html.contains("#challenge-success-text") ||
                html.contains("#challenge-error-text")
            ) {
                view.postDelayed({ checkChallengeResult(view, cookieMgr, url) }, 1500)
                return@evaluateJavascript
            }
            val cookies = cookieMgr.getCookie(url) ?: ""
            if (cookies.contains("cf_clearance")) {
                cloudFlareCookie = CookieString(cookies)
                cookieMgr.flush()
                onFinished?.invoke()
                onFinished = null
                finish()
            } else {
                view.postDelayed({ checkChallengeResult(view, cookieMgr, url) }, 1500)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun applyAppLocale(context: Context): Context {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lang = prefs.getString("app_language", "system") ?: "system"
        val newLocale = when (lang) {
            "zh-rCN" -> Locale.SIMPLIFIED_CHINESE
            "zh" -> Locale.TRADITIONAL_CHINESE
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPANESE
            else -> Resources.getSystem().configuration.locales.get(0)
        }
        Locale.setDefault(newLocale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(newLocale)
        return context.createConfigurationContext(config)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applyAppLocale(newBase))
    }
}
