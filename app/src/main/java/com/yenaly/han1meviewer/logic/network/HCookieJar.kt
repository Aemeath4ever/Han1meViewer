package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.util.toLoginCookieList
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 用於管理 Cookie。
 *
 * #issue-71: 我竟然栽倒在 Cookie 管理上好幾年了！你去看我以前的管理方式，
 * 是完全錯誤的，竟然還能維持應用正常運行，太離譜了！怪不得切換簡體繁體一直不起作用！
 *
 * 2026-10-25 修正：`saveFromResponse` 以前會「用本次響應的 cookie 整表替換」，
 * 這會丟掉本次響應沒提到的 cookie（`cf_clearance`、會話、剛從 WebView 灌進來的登入 cookie），
 * 還會讓 jar 裡的死值蓋掉 WebView 裡的活值。OkHttp 的契約是「增量保存」，故改為按名稱合併：
 * 舊值打底 → 本次響應覆蓋 → 持久化 cookie 只補空缺 → 丟掉已過期的（服務端刪除仍生效）。
 *
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/13 013 15:20
 */
class HCookieJar : CookieJar {

    companion object {
        @JvmStatic
        val cookieMap: MutableMap<String, MutableList<Cookie>> = ConcurrentHashMap()

        /** `cookieMap` 的讀改寫需要互斥；OkHttp 會在多個 IO 執行緒上同時調用。 */
        private val lock = Any()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        // 同名只送一份：jar 優先，其次持久化的登入 / Cloudflare cookie。
        // （以前會把 loginCookie 再追加一遍，服務端收到重複的同名 cookie。）
        val merged = LinkedHashMap<String, Cookie>()
        synchronized(lock) { cookieMap[host]?.forEach { merged[it.name] = it } }
        Preferences.loginCookieStateFlow.value.toLoginCookieList(host)
            .forEach { merged.putIfAbsent(it.name, it) }
        Preferences.cloudFlareCookieStateFlow.value.toLoginCookieList(host)
            .forEach { merged.putIfAbsent(it.name, it) }

        val cookies = merged.values.toList()
        Log.d("HCookieJar", "loadForRequest for $host: ${cookies.map { it.name }}")
        return cookies
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val merged = LinkedHashMap<String, Cookie>()
            cookieMap[host]?.forEach { merged[it.name] = it }
            // 本次響應的同名 cookie 覆蓋舊值；過期時間在過去的等於服務端要求刪除。
            cookies.forEach { merged[it.name] = it }
            Preferences.loginCookieStateFlow.value.toLoginCookieList(host)
                .forEach { merged.putIfAbsent(it.name, it) }
            cookieMap[host] = CopyOnWriteArrayList(
                merged.values.filter { it.expiresAt > now }
            )
        }
    }
}
