package com.yenaly.han1meviewer.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.yenaly.han1meviewer.HANIME_LOGIN_URL
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.logic.NetworkRepo
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.login
import com.yenaly.han1meviewer.ui.screen.login.LoginDialog
import com.yenaly.han1meviewer.ui.screen.login.LoginScreen
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import com.yenaly.yenaly_libs.base.frame.FrameActivity
import kotlinx.coroutines.launch
import java.util.Locale

class LoginActivity : FrameActivity() {
    private lateinit var scannerLauncher: ActivityResultLauncher<Intent>
    private var isRefreshing by mutableStateOf(true)
    private var showLoginDialog by mutableStateOf(false)
    private var isLoggingIn by mutableStateOf(false)
    private var loginError by mutableStateOf<String?>(null)

    override fun setUiStyle() {
        enableEdgeToEdge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                scannerLauncher.launch(Intent(this, ManualInputCookiesActivity::class.java))
            } else {
                GlobalToasts.show(getString(R.string.request_camera), level = GlobalToasts.ToastLevel.WARNING)
            }
        }

        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val cookie = result.data?.getStringExtra("cookie")
                Log.i("LoginActivity", "扫描结果: $cookie")
                login(cookie.toString())
                setResult(RESULT_OK)
                finish()
            }
        }

        val composeView = ComposeView(this)
        setContentView(composeView)
        // missav 站点的登录是站内 SPA 弹窗（依赖 Alpine/axios 全链路），WebView 内交互不稳定；
        // 直接弹出原生登录框走 /cn/api/login，网页作为兜底。
        // jable 的登录也是 fancybox AJAX 表單，成功後不一定會導航，故同樣原生優先；
        // 頁面仍保留在背景，原生登入被 Cloudflare 攔截時可以改用手動登入。
        if (Preferences.isMissavSite || Preferences.isJableSite) {
            showLoginDialog = true
        }
        composeView.setContent {
            HanimeTheme {
                if (showLoginDialog) {
                    LoginDialog(
                        isLoggingIn = isLoggingIn,
                        onDismiss = { showLoginDialog = false },
                        onLogin = { username, password -> handleLogin(username, password) },
                        errorMessage = loginError,
                    )
                }
                LoginScreen(
                    isRefreshing = isRefreshing,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { webView?.loadUrl(HANIME_LOGIN_URL) },
                    onShowLoginDialog = { showLoginDialog = true },
                    onOpenQrScanner = { openQrScanner() },
                    webViewFactory = { createWebView() },
                    missavMode = Preferences.isMissavSite,
                )
            }
        }
    }

    private var webView: WebView? = null
    private var missavLoginWatching = false
    private var jableLoginWatching = false

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(this).apply {
            webView = this
            // missav / jable 都依赖 cf_clearance cookie，清空会导致重新触发 Cloudflare 人机验证。
            // 只有 Hanime 站沿用「清空 cookie 再登入」的舊流程。
            if (Preferences.siteType == com.yenaly.han1meviewer.SiteType.HANIME) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    isRefreshing = false
                    if (Preferences.isMissavSite) watchMissavLogin(view)
                    if (Preferences.isJableSite) watchJableLogin(view)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val baseHost = runCatching { java.net.URI(Preferences.baseUrl).host }.getOrNull()
                    val isSameUrl = request.url.host == baseHost ||
                            HANIME_URL.contains(request.url.toString())
                    if (request.isRedirect && isSameUrl) {
                        val url = request.url
                        val cookieManager = CookieManager.getInstance().getCookie(url.host)
                        Log.d("login_cookie", cookieManager.toString())
                        login(cookieManager)
                        setResult(RESULT_OK)
                        finish()
                        return true
                    }
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true && !isDestroyed && !isFinishing) {
                        isRefreshing = false
                        showLoginDialog = true
                    }
                }
            }
            loadUrl(HANIME_LOGIN_URL)
        }
    }

    /**
     * missav 登录态轮询：站点是 Alpine SPA，登录成功后根组件 `user` 从 null 变为对象。
     * 检测到已登录后把 WebView 全部 cookie 交给 [login] 保存，供 okhttp 使用。
     */
    private fun watchMissavLogin(view: WebView) {
        if (missavLoginWatching) return
        missavLoginWatching = true
        val probe = "(function(){try{var els=document.querySelectorAll('[x-data]');" +
                "for(var i=0;i<els.length;i++){var d=els[i]._x_dataStack;" +
                "if(d&&d[0]&&d[0].user)return true;}return false;}catch(e){return false;}})()"
        val checker = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                view.evaluateJavascript(probe) { result ->
                    if (result == "true") {
                        val cookies = CookieManager.getInstance().getCookie(view.url)
                        if (!cookies.isNullOrBlank()) {
                            Log.d("login_cookie", cookies)
                            login(cookies)
                            setResult(RESULT_OK)
                            finish()
                        }
                    } else {
                        view.postDelayed(this, 1500)
                    }
                }
            }
        }
        view.postDelayed(checker, 2500)
    }

    /**
     * jable 的 AJAX 登入成功後會 `window.location.reload()` 或跳到 `redirect`，
     * 不會觸發 [WebViewClient.shouldOverrideUrlLoading] 的 redirect 分支。
     * 因此改用 DOM 判斷：非 /login 頁且頁首的「登入」入口消失，即代表已登入。
     *
     * 只認真的 jable 頁面（有 `.header-right .settings`），Cloudflare 驗證頁不會誤判。
     */
    private fun watchJableLogin(view: WebView) {
        if (jableLoginWatching) return
        jableLoginWatching = true
        val probe = "(function(){try{" +
                "if(location.pathname.indexOf('/login')===0)return false;" +
                "var s=document.querySelector('.header-right .settings');" +
                "if(!s)return false;" +
                "return !s.querySelector('a[href*=\"/login/\"]');" +
                "}catch(e){return false;}})()"
        val checker = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                view.evaluateJavascript(probe) { result ->
                    if (result == "true") {
                        val cookies = CookieManager.getInstance().getCookie(view.url)
                        if (!cookies.isNullOrBlank()) {
                            Log.d("login_cookie", "jable login detected")
                            login(cookies)
                            setResult(RESULT_OK)
                            finish()
                        } else {
                            view.postDelayed(this, 1500)
                        }
                    } else {
                        view.postDelayed(this, 1500)
                    }
                }
            }
        }
        view.postDelayed(checker, 2500)
    }

    private fun openQrScanner() {
        scannerLauncher.launch(Intent(this, ManualInputCookiesActivity::class.java))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView?.canGoBack() == true) {
            webView?.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.removeAllViews()
        webView?.destroy()
    }

    private fun handleLogin(username: String, password: String) {
        isLoggingIn = true
        loginError = null
        // 登入頁的 WebView 是唯一「真瀏覽器」會話：cf_clearance / XSRF-TOKEN / missav_session
        // 都只存在 CookieManager 裏。okhttp 自己抓 /dm12/cn 只會拿到 Cloudflare 快取的 301，
        // 沒有 Set-Cookie，於是 POST /cn/api/login 必然 419。把這些 cookie 轉交給 okhttp。
        // missav / jable 的登入頁 WebView 是唯一「真瀏覽器」會話：cf_clearance
        // 只存在 CookieManager 裏，先交給 okhttp 才不會又被 Cloudflare 攔。
        val seedCookies = if (Preferences.isMissavSite || Preferences.isJableSite) {
            runCatching { CookieManager.getInstance().getCookie(HANIME_LOGIN_URL) }
                .getOrNull()
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        } else {
            emptyList()
        }
        lifecycleScope.launch {
            NetworkRepo.login(username, password, seedCookies).collect { state ->
                when (state) {
                    WebsiteState.Loading -> Unit

                    is WebsiteState.Error -> {
                        isLoggingIn = false
                        state.throwable.printStackTrace()
                        // 直接顯示具體原因（服務端訊息 / HTTP 狀態碼）並留在彈窗裡，
                        // 不能用一個轉瞬即逝的通用 toast 把原因吞掉。
                        val reason = state.throwable.message
                            ?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.login_failed)
                        loginError = reason
                        GlobalToasts.show(reason, level = GlobalToasts.ToastLevel.ERROR)
                    }

                    is WebsiteState.Success -> {
                        loginError = null
                        if (Preferences.isMissavSite) Preferences.missavLoginEmail = username
                        if (Preferences.isJableSite) Preferences.jableLoginEmail = username
                        login(state.info)
                        setResult(RESULT_OK)
                        showLoginDialog = false
                        GlobalToasts.show(getString(R.string.login_success), level = GlobalToasts.ToastLevel.SUCCESS)
                        finish()
                    }
                }
            }
        }
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
