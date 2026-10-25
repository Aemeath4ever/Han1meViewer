package com.yenaly.han1meviewer.logic.network.interceptor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.HCookieJar
import com.yenaly.han1meviewer.ui.activity.CloudflareActivity
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val context: Context
) : Interceptor {

    companion object {
        private const val TAG = "CloudflareInterceptor"
        private const val WAIT_SECONDS = 90L

        /** 同一時間只允許一個驗證頁；其他被攔的請求等這一個結果，避免疊一堆 Activity。 */
        private val lock = Any()
        private var activeLatch: CountDownLatch? = null

        private fun baseHost(): String? = runCatching {
            Uri.parse(Preferences.baseUrl).host
        }.getOrNull()

        /**
         * 是否為「當前站點自己」的主機。
         *
         * 只有站點本身的請求被 Cloudflare 攔截時才需要人機驗證。圖片 CDN、
         * 推薦接口等第三方主機被攔截時若也彈驗證頁，使用者切站或重開 app
         * 就會無緣無故看到盾 —— 那些資源失敗頂多是封面空白，不影響瀏覽。
         */
        private fun String.isSiteHost(): Boolean {
            val site = baseHost() ?: return false
            if (equals(site, ignoreCase = true)) return true
            // 允許 www 前綴（部分鏡像站會 301 到 www），但不放行 cdn/static 等子域。
            return (startsWith("www.", ignoreCase = true) &&
                    substring(4).equals(site, ignoreCase = true)) ||
                    (site.startsWith("www.", ignoreCase = true) &&
                            equals(site.substring(4), ignoreCase = true))
        }

        /**
         * 判斷是否為 Cloudflare 的人機驗證頁。
         *
         * `cf-mitigated: challenge` 是最準的訊號，但實際站點不一定每次都帶：
         * jable 首次被攔時只回 403 + `Just a moment...` 的 HTML，沒有該標頭，
         * 於是第一次載入直接變成「網路環境無法通過驗證」，手動重試才彈驗證頁。
         * 這裡補充按狀態碼 + 挑戰頁特徵的判斷；`peekBody` 不消耗原響應。
         */
        private fun Response.isCloudflareChallenge(): Boolean {
            if (header("cf-mitigated") == "challenge") return true
            if (code != 403 && code != 429 && code != 503) return false
            val contentType = body?.contentType()?.toString().orEmpty()
            if (!contentType.contains("text/html", ignoreCase = true)) return false
            val snippet = runCatching { peekBody(8192).string() }.getOrNull() ?: return false
            // 不再只看 `challenge-platform`：Cloudflare 的 JavaScript Detections
            // 會把這段腳本注入普通頁面（含某些 403 錯誤頁），拿它當盾的證據會
            // 把「不是盾」的 403 也當成盾，使用者就被無故彈驗證頁。
            return snippet.contains("Just a moment", ignoreCase = true) ||
                    snippet.contains("Verifying you are human", ignoreCase = true) ||
                    snippet.contains("Checking your browser", ignoreCase = true) ||
                    snippet.contains("cf-chl-", ignoreCase = true)
        }

        /**
         * 讀 WebView 自己的 cookie 存儲，把 Cloudflare 的 cookie 灌進 okhttp 的 jar。
         *
         * WebView 往往早就過了盾（它的 cookie 存儲是持久的），只是 jar 裏還是舊值。
         * 以前一被攔就直接彈驗證頁，可驗證頁在 WebView 裏也是拿着這份 cookie 直接打開
         * 了首頁 —— 用戶看到的是「驗證頁裏是 missav 首頁」，而且 okhttp 重試依然被攔。
         * 先同步再重試：能過就不用打擾用戶。
         *
         * 只同步 `cf_clearance` / `__cf_bm`，不動登錄會話，避免用 WebView 的舊 session
         * 蓋掉 app 裏較新的登錄態。
         *
         * @return 非 null 表示 WebView 裏確實有一份 cf_clearance，已寫進 jar；返回值是
         *   這份值的短指紋，驗證頁用它判斷 Cloudflare 是否真的重新發了一份。
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

        // `cf-mitigated: challenge` 是最準的訊號，狀態碼不一定是 403（也可能是 429/503 等）。
        // 部分站點（jable）首次被攔只給 403 + 挑戰頁 HTML，不帶該標頭，故一併按頁面特徵判斷。
        if (!response.isCloudflareChallenge()) {
            return response
        }
        // 非站點主機（封面 CDN、Recombee 等）的 challenge 直接放行給呼叫端，
        // 絕對不要替使用者打開驗證頁：切站/重進 app 時背景請求很多，
        // 任何一個第三方 challenge 都彈盾會讓人以為「每次都要驗證」。
        if (!request.url.host.isSiteHost()) {
            Log.d(TAG, "ignore challenge from non-site host: ${request.url.host}")
            return response
        }
        response.close()
        val url = request.url.toString()

        // 第一步：先把 WebView 裏可能已經有效的 cf_clearance 抄進 jar 重試。
        // 只同步 cf_clearance / __cf_bm，不動登錄會話，避免用 WebView 的舊 session
        // 蓋掉 app 裏較新的登錄態。
        val staleClearanceFp = syncWebViewCookies(url)
        if (staleClearanceFp != null) {
            response = chain.proceed(request)
            if (!response.isCloudflareChallenge()) {
                return response
            }
            response.close()
        }

        // 第二步：確實需要人機驗證。同一時間只開一個驗證頁，後續被攔的請求
        // 共用同一個 latch（以前每次都覆蓋 CloudflareActivity.onFinished，
        // 先到的請求會永遠等不到自己的回調，只能乾等 90 秒）。
        val (latch, owner) = synchronized(lock) {
            val current = activeLatch
            if (current != null) {
                current to false
            } else {
                CountDownLatch(1).also { activeLatch = it } to true
            }
        }

        if (owner) {
            // WebView 裏本來就有 cf_clearance 卻沒能讓 okhttp 過盾，說明這份值對
            // okhttp 已經失效；不清掉的話 WebView 會拿着它直接打開首頁，用戶根本
            // 看不到驗證頁，重試依舊被攔。強制它重新走一次人機驗證。
            val force = staleClearanceFp != null
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

            // 等待 WebView 驗證完成。必須有上限：以前是無限等待，
            // 用戶中途退出驗證頁就會讓呼叫端永遠掛住（登入按鈕點下去毫無反應）。
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

        return chain.proceed(request)
    }
}
