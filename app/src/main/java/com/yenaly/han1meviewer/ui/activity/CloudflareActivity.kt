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

        /**
         * 為 true 時先清掉 WebView 裏的 cf_clearance 再打開頁面。
         *
         * 用於「WebView 裏有 cookie 但 okhttp 拿它過不了盾」的情況：不清掉的話
         * WebView 會直接打開首頁（看起來根本沒有盾），驗證頁等於白開。
         */
        const val EXTRA_FORCE = "force_challenge"

        /** 被強制重驗前那份 cf_clearance 的短指紋；只有拿到新值才算驗證通過。 */
        const val EXTRA_STALE_CLEARANCE_FP = "stale_clearance_fp"
        var onFinished: (() -> Unit)? = null

        /**
         * 返回 '1' 表示还在 Cloudflare 挑战页，'0' 表示已经是正常页面，'2' 表示探测失败。
         * 只按标题 + 挑战页专有 DOM 判断；新版 Turnstile 挑战页没有旧的 #challenge-form。
         */
        private const val CHALLENGE_PROBE = """(function(){
  try {
    var t = (document.title || '').toLowerCase();
    if (/just a moment|稍候|attention required|verifying you are human|checking your browser|un momento|しばらくお待ち|잠시만/.test(t)) return '1';
    if (document.querySelector('#challenge-form,#challenge-stage,#challenge-body,#challenge-running,#challenge-success-text,#challenge-error-text,#turnstile-wrapper')) return '1';
    return '0';
  } catch (e) { return '2'; }
})()"""
    }

    private val progressState = mutableIntStateOf(0)
    private val tipTextState = mutableStateOf("")

    /** 驗證結果只回調一次：成功結束和用戶手動關閉都走 [notifyFinished]。 */
    private var finishedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        val staleFp = intent.getStringExtra(EXTRA_STALE_CLEARANCE_FP)

        tipTextState.value = getString(R.string.complete_cloudflare_verification_with_warning)

        val composeView = ComposeView(this)
        setContentView(composeView)
        composeView.setContent {
            HanimeTheme {
                CloudflareScreen(
                    progress = progressState.intValue,
                    tipText = tipTextState.value,
                    onClose = { finish() },
                    webViewFactory = { createWebView(url, force, staleFp) },
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String, force: Boolean, staleFp: String?): WebView {
        return WebView(this).apply {
            val wv = this
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
                userAgentString = USER_AGENT
                // 不許用緩存：否則是拿「上次成功時的整頁緩存」冒充驗證通過，
                // 既看不到盾，拿到的 cf_clearance 也早就失效了。
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            val cookieMgr = CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }

            if (force) {
                // 這份 cf_clearance 對 okhttp 已經失效；先讓它過期，
                // Cloudflare 才會重新發盾，而不是把首頁直接放給 WebView。
                runCatching {
                    cookieMgr.setCookie(url, "cf_clearance=; Path=/; Max-Age=0; Secure")
                    cookieMgr.flush()
                }
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
                            checkChallengeResult(view, cookieMgr, url, staleFp)
                        }, 1000)
                    }
                }
            }
            loadUrl(url)
        }
    }

    /**
     * 检查 challenge 是否已通过：页面不再是 Cloudflare 的挑战页、且 cookie 里有
     * cf_clearance 时保存 cookie 并结束；否则延时继续轮询。
     *
     * 不能只看「head 里没有 #challenge-form」：新版 Turnstile 挑战页的 DOM 完全变了，
     * 旧判断会直接放行；而带缓存打开时，页面可能根本就是缓存的首页。
     */
    private fun checkChallengeResult(
        view: WebView,
        cookieMgr: CookieManager,
        url: String,
        staleFp: String?,
    ) {
        if (isDestroyed || isFinishing) return
        view.evaluateJavascript(CHALLENGE_PROBE) { raw ->
            val result = raw?.trim()?.trim('"')
            if (result == "0") {
                val cookies = cookieMgr.getCookie(url) ?: ""
                val fp = clearanceFingerprint(cookies)
                // 強制重驗時，舊的 cf_clearance 可能還沒被清掉（WebView 直接打開首頁）。
                // 值沒變就說明 Cloudflare 根本沒重新發盾，不能算驗證通過。
                if (fp != null && (staleFp == null || fp != staleFp)) {
                    saveCookiesAndFinish(cookieMgr, url, cookies)
                    return@evaluateJavascript
                }
            }
            view.postDelayed({ checkChallengeResult(view, cookieMgr, url, staleFp) }, 1500)
        }
    }

    private fun clearanceFingerprint(cookies: String): String? = cookies.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("cf_clearance=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }
        ?.let { Integer.toHexString(it.hashCode()) }

    private fun saveCookiesAndFinish(cookieMgr: CookieManager, url: String, cookies: String) {
        if (finishedOnce) return
        cloudFlareCookie = CookieString(cookies)
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
        // 必須同時灌進 okhttp 的 jar：loadForRequest 是 jar 優先，
        // 只寫 Preferences 的話，jar 裡的舊 cf_clearance 會蓋掉這裡剛拿到的新值。
        if (host != null) {
            com.yenaly.han1meviewer.logic.network.HCookieJar
                .putRawCookieString(host, cookies)
        }
        cookieMgr.flush()
        val clearance = clearanceFingerprint(cookies)?.let { " cf#$it" }.orEmpty()
        android.util.Log.d(
            "CloudflareActivity",
            "verified $url: ${cookies.split(';').map { it.substringBefore('=').trim() }}$clearance"
        )
        notifyFinished()
        finish()
    }

    /** 回調等待中的請求，且只回調一次。 */
    private fun notifyFinished() {
        if (finishedOnce) return
        finishedOnce = true
        onFinished?.invoke()
        onFinished = null
    }

    override fun onDestroy() {
        // 用戶手動關掉驗證頁時也要放行：否則呼叫端會乾等滿 90 秒，
        // 表現為「關掉驗證頁之後 app 卡住，過很久才報驗證失敗」。
        if (!finishedOnce) notifyFinished()
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
