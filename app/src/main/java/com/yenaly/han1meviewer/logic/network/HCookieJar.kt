package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.util.toLoginCookieList
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

        /**
         * 是否為當前站點自己的主機。
         *
         * 登錄 / Cloudflare cookie 只屬於站點本身：以前不管請求去哪個 host
         * 都把這兩串 cookie 以該 host 為 domain 塞進去，等於把站點 session
         * （甚至 cf_clearance）發給封面 CDN、Recombee 等第三方。第三方收到
         * 不屬於自己的 session 容易觸發風控/Cloudflare challenge，也是
         * 「切站或重開 app 時莫名彈盾」的來源之一。
         */
        private fun HttpUrl.isCurrentSiteHost(): Boolean {
            val site = runCatching { Preferences.baseUrl.toHttpUrlOrNull()?.host }.getOrNull()
                ?: return false
            if (host.equals(site, ignoreCase = true)) return true
            return (host.startsWith("www.", ignoreCase = true) &&
                    host.substring(4).equals(site, ignoreCase = true)) ||
                    (site.startsWith("www.", ignoreCase = true) &&
                            host.equals(site.substring(4), ignoreCase = true))
        }

        /**
         * 把一整串 `a=1; b=2`（WebView 的 `CookieManager.getCookie` 結果）灌進 jar，同名覆蓋。
         *
         * WebView 過完 Cloudflare、或在站內拿到新會話之後必須走這裡：`loadForRequest`
         * 是「jar 優先」，jar 裡的舊 `cf_clearance` 會把 WebView 剛拿到的新值蓋掉，
         * 於是重放請求又被攔，使用者看到的就是「當前網路環境無法通過驗證」。
         */
        @JvmStatic
        fun putRawCookieString(host: String, raw: String?) {
            if (raw.isNullOrBlank()) return
            synchronized(lock) {
                val jar = cookieMap.getOrPut(host) { CopyOnWriteArrayList() }
                raw.split(';').forEach { part ->
                    val pair = part.trim()
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isEmpty() || value.isEmpty()) return@forEach
                    val cookie = runCatching {
                        Cookie.Builder()
                            .domain(host)
                            .path("/")
                            .name(name)
                            .value(value)
                            .expiresAt(Long.MAX_VALUE)
                            .build()
                    }.getOrNull() ?: return@forEach
                    jar.removeAll { it.name == name }
                    jar.add(cookie)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        // 同名只送一份：jar 優先，其次持久化的登入 / Cloudflare cookie。
        // （以前會把 loginCookie 再追加一遍，服務端收到重複的同名 cookie。）
        val merged = LinkedHashMap<String, Cookie>()
        synchronized(lock) { cookieMap[host]?.forEach { merged[it.name] = it } }
        // 只向站點自己的 host 附加持久化的登錄 / CF cookie；第三方 host
        // 只使用它們自己在響應裡種下的 cookie。
        if (url.isCurrentSiteHost()) {
            Preferences.loginCookieStateFlow.value.toLoginCookieList(host)
                .forEach { merged.putIfAbsent(it.name, it) }
            Preferences.cloudFlareCookieStateFlow.value.toLoginCookieList(host)
                .forEach { merged.putIfAbsent(it.name, it) }
        }

        val cookies = merged.values.toList()
        // cf_clearance 只記一個不可逆的短指紋，方便對照「WebView 拿到的」和「jar 發出去的」
        // 是不是同一份（排查過盾失敗時用），不把 cookie 值寫進日誌。
        val clearanceFp = merged["cf_clearance"]?.value
            ?.let { " cf#${Integer.toHexString(it.hashCode())}" }.orEmpty()
        Log.d("HCookieJar", "loadForRequest for $host: ${cookies.map { it.name }}$clearanceFp")
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
            if (url.isCurrentSiteHost()) {
                Preferences.loginCookieStateFlow.value.toLoginCookieList(host)
                    .forEach { merged.putIfAbsent(it.name, it) }
            }
            cookieMap[host] = CopyOnWriteArrayList(
                merged.values.filter { it.expiresAt > now }
            )
        }
    }
}
