package com.yenaly.han1meviewer.logic.network.interceptor

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yenaly.han1meviewer.ui.activity.CloudflareActivity
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // `cf-mitigated: challenge` 才是權威訊號，狀態碼不一定是 403（也可能是 429/503 等）。
        // 以前要求 code == 403，會漏掉一部分挑戰頁，於是請求直接帶著挑戰頁 HTML 返回，
        // 上層只能看到「解析失敗 / 登入失敗」，表現為「點登入沒反應」。
        if (response.header("cf-mitigated") == "challenge") {
            response.close()
            val url = request.url.toString()

            // 用 CountDownLatch 代替同步锁
            val latch = CountDownLatch(1)

            // 设置回调，用户验证完成后调用
            CloudflareActivity.onFinished = {
                latch.countDown()
            }

            val intent = Intent(context, CloudflareActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.putExtra(CloudflareActivity.EXTRA_URL, url)
            try {
                context.startActivity(intent)
            } catch (e: Exception){
                Handler(Looper.getMainLooper()).post {
                    GlobalToasts.show("cf启动失败错误: ${e.javaClass.simpleName}", level = GlobalToasts.ToastLevel.ERROR)
                }
                CloudflareActivity.onFinished?.invoke()
            }

            // 等待 WebView 验证完成。必须有上限：以前是無限等待，
            // 用戶中途退出驗證頁就會讓呼叫端永遠掛住（登入按鈕點下去毫無反應）。
            val finished = latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                Log.w("CloudflareInterceptor", "等待人機驗證逾時（90s），放行原請求: $url")
            }
            return chain.proceed(request)
        }
        return response
    }
}


