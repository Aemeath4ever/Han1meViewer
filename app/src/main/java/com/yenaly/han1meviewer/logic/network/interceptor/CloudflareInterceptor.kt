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

        // `cf-mitigated: challenge` 才是權威訊號，狀態碼不一定是 403（也可能是 429/503 等）。
        // 以前要求 code == 403，會漏掉一部分挑戰頁，於是請求直接帶著挑戰頁 HTML 返回，
        // 上層只能看到「解析失敗 / 登入失敗」，表現為「點登入沒反應」。
        if (response.header("cf-mitigated") != "challenge") {
            return response
        }
        response.close()
        val url = request.url.toString()

        // 第一步：先把 WebView 裏可能已經有效的 cf_clearance 抄進 jar 重試。
        // 只在 missav 站點做：其他站點沒有這種「WebView 已過盾」的前提，
        // 把 WebView 的舊會話灌進 jar 反而可能弄壞登錄態。
        var staleClearanceFp: String? = null
        if (request.url.host == baseHost()) {
            staleClearanceFp = syncWebViewCookies(url)
            if (staleClearanceFp != null) {
                response = chain.proceed(request)
                if (response.header("cf-mitigated") != "challenge") {
                    return response
                }
                response.close()
            }
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
