package com.yenaly.han1meviewer.logic.network.interceptor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.HCookieJar
import com.yenaly.han1meviewer.ui.activity.CloudflareActivity
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val context: Context
) : Interceptor {

    companion object {
        private const val TAG = "CloudflareInterceptor"
        private const val WAIT_SECONDS = 90L

        /** 同一时间只允许一个验证页；其他被拦的请求等这一个结果，避免叠一堆 Activity。 */
        private val lock = Any()
        private var activeLatch: CountDownLatch? = null

        /**
         * 已经为某份 cf_clearance 开过验证页的时间（`host|fp` -> elapsedRealtime）。
         *
         * 如果验证页拿回的新 cookie 对 okhttp 依然无效（TLS 指纹、IP 绑定等），
         * 每个请求都会重新弹验证页，变成连环盾。同一份值在冷却时间内只尝试一次，
         * 之后直接把 challenge 响应交给上层显示错误。
         */
        private val attemptedClearance = ConcurrentHashMap<String, Long>()
        private const val CLEARANCE_RETRY_COOLDOWN_MS = 10 * 60 * 1000L

        private fun clearanceKey(host: String, fp: String) = "$host|$fp"

        private fun markAttempted(host: String, fp: String) {
            attemptedClearance[clearanceKey(host, fp)] = SystemClock.elapsedRealtime()
        }

        private fun recentlyAttempted(host: String, fp: String): Boolean {
            val key = clearanceKey(host, fp)
            val last = attemptedClearance[key] ?: return false
            if (SystemClock.elapsedRealtime() - last > CLEARANCE_RETRY_COOLDOWN_MS) {
                attemptedClearance.remove(key)
                return false
            }
            return true
        }

        /** 读 jar 里当前会送出的 cf_clearance 指纹（与验证页的算法一致）。 */
        private fun jarClearanceFp(url: HttpUrl): String? = runCatching {
            HCookieJar().loadForRequest(url)
                .firstOrNull { it.name == "cf_clearance" }
                ?.value
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { Integer.toHexString(it.hashCode()) }

        private fun baseHost(): String? = runCatching {
            Uri.parse(Preferences.baseUrl).host
        }.getOrNull()

        /**
         * 是否为「当前站点自己」的主机。
         *
         * 只有站点本身的请求被 Cloudflare 拦截时才需要人机验证。图片 CDN、
         * 推荐接口等第三方主机被拦截时若也弹验证页，使用者切站或重开 app
         * 就会无缘无故看到盾 —— 那些资源失败顶多是封面空白，不影响浏览。
         */
        private fun String.isSiteHost(): Boolean {
            val site = baseHost() ?: return false
            if (equals(site, ignoreCase = true)) return true
            // 允许 www 前缀（部分镜像站会 301 到 www），但不放行 cdn/static 等子域。
            return (startsWith("www.", ignoreCase = true) &&
                    substring(4).equals(site, ignoreCase = true)) ||
                    (site.startsWith("www.", ignoreCase = true) &&
                            equals(site.substring(4), ignoreCase = true))
        }

        /**
         * 判断是否为 Cloudflare 的人机验证页。
         *
         * `cf-mitigated: challenge` 是最准的讯号，但实际站点不一定每次都带：
         * jable 首次被拦时只回 403 + `Just a moment...` 的 HTML，没有该标头，
         * 于是第一次载入直接变成「网路环境无法通过验证」，手动重试才弹验证页。
         * 这里补充按状态码 + 挑战页特征的判断；`peekBody` 不消耗原响应。
         */
        private fun Response.isCloudflareChallenge(): Boolean {
            if (header("cf-mitigated") == "challenge") return true
            if (code != 403 && code != 429 && code != 503) return false
            val contentType = body?.contentType()?.toString().orEmpty()
            if (!contentType.contains("text/html", ignoreCase = true)) return false
            val snippet = runCatching { peekBody(8192).string() }.getOrNull() ?: return false
            // 不再只看 `challenge-platform`：Cloudflare 的 JavaScript Detections
            // 会把这段脚本注入普通页面（含某些 403 错误页），拿它当盾的证据会
            // 把「不是盾」的 403 也当成盾，使用者就被无故弹验证页。
            return snippet.contains("Just a moment", ignoreCase = true) ||
                    snippet.contains("Verifying you are human", ignoreCase = true) ||
                    snippet.contains("Checking your browser", ignoreCase = true) ||
                    snippet.contains("cf-chl-", ignoreCase = true)
        }

        /**
         * 读 WebView 自己的 cookie 存储，把 Cloudflare 的 cookie 灌进 okhttp 的 jar。
         *
         * WebView 往往早就过了盾（它的 cookie 存储是持久的），只是 jar 里还是旧值。
         * 以前一被拦就直接弹验证页，可验证页在 WebView 里也是拿着这份 cookie 直接打开
         * 了首页 —— 用户看到的是「验证页里是 missav 首页」，而且 okhttp 重试依然被拦。
         * 先同步再重试：能过就不用打扰用户。
         *
         * 只同步 `cf_clearance` / `__cf_bm`，不动登录会话，避免用 WebView 的旧 session
         * 盖掉 app 里较新的登录态。
         *
         * @return 非 null 表示 WebView 里确实有一份 cf_clearance，已写进 jar；返回值是
         *   这份值的短指纹，验证页用它判断 Cloudflare 是否真的重新发了一份。
         */
        private fun syncWebViewCookies(url: String): String? {
            var raw: String? = null
            val readLatch = CountDownLatch(1)
            val read = {
                raw = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                readLatch.countDown()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                read()
            } else {
                Handler(Looper.getMainLooper()).post { read() }
                readLatch.await(3, TimeUnit.SECONDS)
            }

            val cookies = raw?.split(';')
                ?.map { it.trim() }
                ?.filter { it.startsWith("cf_clearance=") || it.startsWith("__cf_bm=") }
                ?.filter { it.substringAfter('=').isNotBlank() }
                ?.takeIf { parts -> parts.any { it.startsWith("cf_clearance=") } }
                ?: return null
            val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return null
            HCookieJar.putRawCookieString(host, cookies.joinToString("; "))
            val fp = cookies.first { it.startsWith("cf_clearance=") }
                .substringAfter('=').let { Integer.toHexString(it.hashCode()) }
            Log.d(TAG, "synced WebView cf cookies into jar for $host cf#$fp")
            return fp
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)

        // `cf-mitigated: challenge` 是最准的讯号，状态码不一定是 403（也可能是 429/503 等）。
        // 部分站点（jable）首次被拦只给 403 + 挑战页 HTML，不带该标头，故一并按页面特征判断。
        if (!response.isCloudflareChallenge()) {
            return response
        }
        // 非站点主机（封面 CDN、Recombee 等）的 challenge 直接放行给呼叫端，
        // 绝对不要替使用者打开验证页：切站/重进 app 时背景请求很多，
        // 任何一个第三方 challenge 都弹盾会让人以为「每次都要验证」。
        if (!request.url.host.isSiteHost()) {
            Log.d(TAG, "ignore challenge from non-site host: ${request.url.host}")
            return response
        }
        response.close()
        val url = request.url.toString()

        // 第一步：先把 WebView 里可能已经有效的 cf_clearance 抄进 jar 重试。
        // 只同步 cf_clearance / __cf_bm，不动登录会话，避免用 WebView 的旧 session
        // 盖掉 app 里较新的登录态。
        val staleClearanceFp = syncWebViewCookies(url)
        if (staleClearanceFp != null) {
            response = chain.proceed(request)
            if (!response.isCloudflareChallenge()) {
                return response
            }
            response.close()
            // 同一份 clearance 已经进过验证页、重放仍被拦：再弹一次只会变成连环盾。
            // 直接把 challenge 交给上层（首页显示错误，用户可稍后手动重试）。
            if (recentlyAttempted(request.url.host, staleClearanceFp)) {
                Log.w(TAG, "同一份 cf_clearance 已驗證失敗過，不再重開驗證頁: $url")
                return chain.proceed(request)
            }
        }

        // 第二步：确实需要人机验证。同一时间只开一个验证页，后续被拦的请求
        // 共用同一个 latch（以前每次都覆盖 CloudflareActivity.onFinished，
        // 先到的请求会永远等不到自己的回调，只能干等 90 秒）。
        val (latch, owner) = synchronized(lock) {
            val current = activeLatch
            if (current != null) {
                current to false
            } else {
                CountDownLatch(1).also { activeLatch = it } to true
            }
        }

        if (owner) {
            // WebView 里本来就有 cf_clearance 却没能让 okhttp 过盾，说明这份值对
            // okhttp 已经失效；不清掉的话 WebView 会拿着它直接打开首页，用户根本
            // 看不到验证页，重试依旧被拦。强制它重新走一次人机验证。
            val force = staleClearanceFp != null
            if (staleClearanceFp != null) {
                markAttempted(request.url.host, staleClearanceFp)
            }
            CloudflareActivity.onFinished = { latch.countDown() }

            val intent = Intent(context, CloudflareActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.putExtra(CloudflareActivity.EXTRA_URL, url)
            intent.putExtra(CloudflareActivity.EXTRA_FORCE, force)
            intent.putExtra(CloudflareActivity.EXTRA_STALE_CLEARANCE_FP, staleClearanceFp)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    GlobalToasts.show(
                        "cf启动失败错误: ${e.javaClass.simpleName}",
                        level = GlobalToasts.ToastLevel.ERROR
                    )
                }
                latch.countDown()
            }

            // 等待 WebView 验证完成。必须有上限：以前是无限等待，
            // 用户中途退出验证页就会让呼叫端永远挂住（登入按钮点下去毫无反应）。
            val finished = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                Log.w(TAG, "等待人機驗證逾時（${WAIT_SECONDS}s），放行原請求: $url")
            }
            synchronized(lock) {
                if (activeLatch === latch) activeLatch = null
            }
            CloudflareActivity.onFinished = null
        } else {
            val finished = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                Log.w(TAG, "等待其他人機驗證逾時（${WAIT_SECONDS}s），放行原請求: $url")
            }
        }

        val retried = chain.proceed(request)
        // 验证页已经把新 cookie 灌进 jar（CloudflareActivity.saveCookiesAndFinish），
        // 这里仍被挑战就说明这份 clearance 对 okhttp 无效，留个明确日志便于排查，
        // 并记下这份值，避免它让每个后续请求都再弹一次验证页。
        if (retried.isCloudflareChallenge()) {
            jarClearanceFp(request.url)?.let { markAttempted(request.url.host, it) }
            Log.w(TAG, "人機驗證後重放仍被挑戰: $url")
        }
        return retried
    }
}
