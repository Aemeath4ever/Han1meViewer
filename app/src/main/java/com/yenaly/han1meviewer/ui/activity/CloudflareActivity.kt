package com.yenaly.han1meviewer.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.logic.network.HCookieJar
import com.yenaly.han1meviewer.ui.screen.web.CloudflareScreen
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.han1meviewer.util.CookieString
import java.util.Locale

class CloudflareActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CloudflareActivity"
        const val EXTRA_URL = "request_url"

        /**
         * 为 true 时先清掉 WebView 里的 cf_clearance 再打开页面。
         *
         * 用于「WebView 里有 cookie 但 okhttp 拿它过不了盾」的情况：不清掉的话
         * WebView 会直接打开首页（看起来根本没有盾），验证页等于白开。
         */
        const val EXTRA_FORCE = "force_challenge"

        /** 被强制重验前那份 cf_clearance 的短指纹；只用于日志对照。 */
        const val EXTRA_STALE_CLEARANCE_FP = "stale_clearance_fp"
        var onFinished: (() -> Unit)? = null

        /** 挑战页判定轮询间隔。不能只依赖 onProgressChanged：重定向后不一定再回调。 */
        private const val POLL_INTERVAL_MS = 1_000L

        /**
         * 页面已经不是挑战页、但 cookie 还没落盘时的宽限时间。
         *
         * 有些 Cloudflare 配置根本不发 cf_clearance（或只发 __cf_bm），
         * 硬等 cf_clearance 会让验证页永远关不掉 —— 用户看到的就是
         * 「页面已经跳到首页了，app 却一直卡在验证页」。超过宽限时间就放行，
         * 由上层重放请求来判定是否真的通过。
         */
        private const val COOKIE_GRACE_MS = 15_000L

        /**
         * 返回 '1' 表示还在 Cloudflare 挑战页，'0' 表示已经是正常页面，'2' 表示探测失败。
         * 只按标题 + 挑战页专有 DOM 判断；新版 Turnstile 挑战页没有旧的 #challenge-form。
         */
        private const val CHALLENGE_PROBE = """(function(){
  try {
    var t = (document.title || '').toLowerCase();
    if (/just a moment|one moment|稍候|請稍候|attention required|verifying you are human|checking your browser|un momento|しばらくお待ち|잠시만|access denied/.test(t)) return '1';
    if (document.querySelector('#challenge-form,#challenge-stage,#challenge-body,#challenge-running,#challenge-success-text,#challenge-error-text,#turnstile-wrapper,#cf-please-wait,#cf-challenge-running,#challenge-error-title')) return '1';
    if (location.pathname.indexOf('/cdn-cgi/challenge-platform/') >= 0) return '1';
    return '0';
  } catch (e) { return '2'; }
})()"""
    }

    private val progressState = mutableIntStateOf(0)
    private val tipTextState = mutableStateOf("")

    /** 验证结果只回调一次：成功结束和用户手动关闭都走 [notifyFinished]。 */
    private var finishedOnce = false

    /** 是否已经在轮询（onPageFinished / onProgressChanged 可能重复触发）。 */
    private var polling = false

    private var nonChallengeSince = 0L

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
                // 不许用缓存：否则是拿「上次成功时的整页缓存」冒充验证通过，
                // 既看不到盾，拿到的 cf_clearance 也早就失效了。
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            val cookieMgr = CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }

            if (force) {
                // 这份 cf_clearance 对 okhttp 已经失效；先让它过期，
                // Cloudflare 才会重新发盾，而不是把首页直接放给 WebView。
                // 删除只是尽力而为：cookie 可能带 Domain 属性而删不掉，
                // 所以下面不能把「值没变」当成验证失败（那会让验证页永远关不掉）。
                runCatching {
                    listOfNotNull(url, Preferences.baseUrl)
                        .distinct()
                        .forEach { target ->
                            cookieMgr.setCookie(
                                target,
                                "cf_clearance=; Path=/; Max-Age=0; Secure"
                            )
                        }
                    cookieMgr.flush()
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    Log.d(TAG, "onPageFinished: $finishedUrl")
                    // 通过验证后 Cloudflare 会重定向到站点页面；onPageFinished 是
                    // 最可靠的「页面已经换掉」信号，不能只靠 onProgressChanged。
                    startChallengePolling(view, cookieMgr, url, staleFp)
                }
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
                        view?.let { startChallengePolling(it, cookieMgr, url, staleFp) }
                    }
                }
            }
            loadUrl(url)
        }
    }

    /** 启动挑战页判定轮询；重复调用无害，同一时间只保留一条轮询链。 */
    private fun startChallengePolling(
        view: WebView,
        cookieMgr: CookieManager,
        url: String,
        staleFp: String?,
    ) {
        if (polling) return
        polling = true
        nonChallengeSince = 0L
        view.postDelayed(
            { checkChallengeResult(view, cookieMgr, url, staleFp) },
            POLL_INTERVAL_MS,
        )
    }

    /**
     * 检查 challenge 是否已通过：页面不再是 Cloudflare 的挑战页时保存 cookie 并结束。
     *
     * 以前的实现要求「cf_clearance 的指纹必须和验证前不同」才算通过，这在 cookie
     * 删不掉（带 Domain 属性）时会死锁：WebView 拿着仍然有效的 cookie 直接打开
     * 首页，页面明明是站点却永远不算「验证通过」，用户只看到验证页卡住、首页一直
     * 载入失败。现在只要页面离开挑战页就放行：拿得到 cf_clearance 就保存，
     * 拿不到也关闭验证页，由上层重放请求来做最终判定。
     */
    private fun checkChallengeResult(
        view: WebView,
        cookieMgr: CookieManager,
        url: String,
        staleFp: String?,
    ) {
        if (isDestroyed || isFinishing) {
            polling = false
            return
        }
        view.evaluateJavascript(CHALLENGE_PROBE) { raw ->
            if (isDestroyed || isFinishing) {
                polling = false
                return@evaluateJavascript
            }
            val result = raw?.trim()?.trim('"')
            val currentUrl = view.url.orEmpty()
            if (result == "0") {
                val cookies = readCookiesForVerification(cookieMgr, url, view.url)
                val fp = clearanceFingerprint(cookies)
                Log.d(
                    TAG,
                    "probe=0 url=$url current=$currentUrl " +
                            "cookies=${cookieNames(cookies)} cf#$fp stale#$staleFp"
                )
                if (fp != null) {
                    saveCookiesAndFinish(cookieMgr, url, currentUrl, cookies)
                    return@evaluateJavascript
                }
                // 页面已经离开挑战页，但 cookie 可能还在写入 → 给一点宽限；
                // 超过宽限仍然没有 cf_clearance 就放行，避免验证页永远关不掉。
                val now = SystemClock.elapsedRealtime()
                if (nonChallengeSince == 0L) nonChallengeSince = now
                if (now - nonChallengeSince >= COOKIE_GRACE_MS) {
                    Log.w(TAG, "頁面已離開挑戰頁但沒有 cf_clearance，按已通過處理: $url")
                    saveCookiesAndFinish(cookieMgr, url, currentUrl, cookies)
                    return@evaluateJavascript
                }
            } else {
                nonChallengeSince = 0L
            }
            view.postDelayed(
                { checkChallengeResult(view, cookieMgr, url, staleFp) },
                POLL_INTERVAL_MS,
            )
        }
    }

    /**
     * 从多个候选 URL 读 cookie。
     *
     * Cloudflare 验证后可能把页面重定向到别的 host/path（例如 `www.` 前缀或首页），
     * 只读原始请求 URL 会漏掉刚发下来的 cf_clearance。
     */
    private fun readCookiesForVerification(
        cookieMgr: CookieManager,
        url: String,
        currentUrl: String?,
    ): String {
        val candidates = buildList {
            add(url)
            currentUrl?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { add(it) }
            add(Preferences.baseUrl)
            add(Preferences.homeRequestUrl)
        }.distinct()

        // 同名 cookie 只保留一份：先读到的优先，空值不覆盖有效值。
        val merged = LinkedHashMap<String, String>()
        for (candidate in candidates) {
            val raw = runCatching { cookieMgr.getCookie(candidate) }.getOrNull().orEmpty()
            for (part in raw.split(';')) {
                val pair = part.trim()
                if (pair.isEmpty()) continue
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) continue
                if (merged[name].isNullOrEmpty()) merged[name] = pair
            }
        }
        return merged.values.joinToString("; ")
    }

    private fun cookieNames(cookies: String): String = cookies.split(';')
        .map { it.substringBefore('=').trim() }
        .filter { it.isNotEmpty() }
        .joinToString(",")

    private fun clearanceFingerprint(cookies: String): String? = cookies.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("cf_clearance=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }
        ?.let { Integer.toHexString(it.hashCode()) }

    private fun hostOf(url: String?): String? = runCatching {
        url?.takeIf { it.isNotBlank() }?.let { Uri.parse(it).host }
    }.getOrNull()

    private fun saveCookiesAndFinish(
        cookieMgr: CookieManager,
        url: String,
        currentUrl: String?,
        cookies: String,
    ) {
        if (finishedOnce) return
        if (cookies.isNotBlank()) {
            Preferences.cloudFlareCookie = CookieString(cookies)
        }
        // 必须同时灌进 okhttp 的 jar：loadForRequest 是 jar 优先，
        // 只写 Preferences 的话，jar 里的旧 cf_clearance 会盖掉这里刚拿到的新值。
        // 原始 URL、WebView 最终 URL、站点 baseUrl 的 host 都写一份：
        // 三者可能因重定向而不同（如 www. 前缀），而 jar 是按 host 精确取值的。
        val hosts = buildSet {
            hostOf(url)?.let { add(it) }
            hostOf(currentUrl)?.let { add(it) }
            hostOf(Preferences.baseUrl)?.let { add(it) }
        }
        if (cookies.isNotBlank()) {
            hosts.forEach { HCookieJar.putRawCookieString(it, cookies) }
        }
        cookieMgr.flush()
        val clearance = clearanceFingerprint(cookies)?.let { " cf#$it" }.orEmpty()
        Log.d(TAG, "verified $url -> ${currentUrl.orEmpty()} hosts=$hosts$clearance")
        if (cookies.isNotBlank()) {
            // 验证完成后通知首页：如果原请求已经超时失败，收到事件就自动重载。
            Preferences.notifyCloudFlareVerified()
        }
        notifyFinished()
        finish()
    }

    /** 回调等待中的请求，且只回调一次。 */
    private fun notifyFinished() {
        if (finishedOnce) return
        finishedOnce = true
        onFinished?.invoke()
        onFinished = null
    }

    override fun onDestroy() {
        polling = false
        // 用户手动关掉验证页时也要放行：否则呼叫端会干等满 90 秒，
        // 表现为「关掉验证页之后 app 卡住，过很久才报验证失败」。
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
