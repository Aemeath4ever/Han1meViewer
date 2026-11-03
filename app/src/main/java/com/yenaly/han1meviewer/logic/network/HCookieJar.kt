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
 * 用于管理 Cookie。
 *
 * #issue-71: 我竟然栽倒在 Cookie 管理上好几年了！你去看我以前的管理方式，
 * 是完全错误的，竟然还能维持应用正常运行，太离谱了！怪不得切换简体繁体一直不起作用！
 *
 * 2026-10-25 修正：`saveFromResponse` 以前会「用本次响应的 cookie 整表替换」，
 * 这会丢掉本次响应没提到的 cookie（`cf_clearance`、会话、刚从 WebView 灌进来的登入 cookie），
 * 还会让 jar 里的死值盖掉 WebView 里的活值。OkHttp 的契约是「增量保存」，故改为按名称合并：
 * 旧值打底 → 本次响应覆盖 → 持久化 cookie 只补空缺 → 丢掉已过期的（服务端删除仍生效）。
 *
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/13 013 15:20
 */
class HCookieJar : CookieJar {

    companion object {
        @JvmStatic
        val cookieMap: MutableMap<String, MutableList<Cookie>> = ConcurrentHashMap()

        /** `cookieMap` 的读改写需要互斥；OkHttp 会在多个 IO 执行绪上同时调用。 */
        private val lock = Any()

        /**
         * 同一个站点的 host 变体：`a.com` 与 `www.a.com` 视为同一份 cookie 池。
         * 只加减 `www.` 前缀，不放行 cdn/static 等其他子域。
         */
        private fun hostAliases(host: String): List<String> {
            val bare = host.removePrefix("www.")
            return listOf(host, bare, "www.$bare").distinct()
        }

        /**
         * 是否为当前站点自己的主机。
         *
         * 登录 / Cloudflare cookie 只属于站点本身：以前不管请求去哪个 host
         * 都把这两串 cookie 以该 host 为 domain 塞进去，等于把站点 session
         * （甚至 cf_clearance）发给封面 CDN、Recombee 等第三方。第三方收到
         * 不属于自己的 session 容易触发风控/Cloudflare challenge，也是
         * 「切站或重开 app 时莫名弹盾」的来源之一。
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
         * 把一整串 `a=1; b=2`（WebView 的 `CookieManager.getCookie` 结果）灌进 jar，同名覆盖。
         *
         * WebView 过完 Cloudflare、或在站内拿到新会话之后必须走这里：`loadForRequest`
         * 是「jar 优先」，jar 里的旧 `cf_clearance` 会把 WebView 刚拿到的新值盖掉，
         * 于是重放请求又被拦，使用者看到的就是「当前网路环境无法通过验证」。
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
        // 同名只送一份：jar 优先，其次持久化的登入 / Cloudflare cookie。
        // （以前会把 loginCookie 再追加一遍，服务端收到重复的同名 cookie。）
        val merged = LinkedHashMap<String, Cookie>()
        // cookieMap 是按 host 精确取值的，但站点可能从 a.com 301 到 www.a.com，
        // 验证页保存 cookie 时的 host 与实际请求的 host 不一致，旧写法就取不到。
        synchronized(lock) {
            hostAliases(host).forEach { alias ->
                cookieMap[alias]?.forEach { merged.putIfAbsent(it.name, it) }
            }
        }
        // 只向站点自己的 host 附加持久化的登录 / CF cookie；第三方 host
        // 只使用它们自己在响应里种下的 cookie。
        if (url.isCurrentSiteHost()) {
            Preferences.loginCookieStateFlow.value.toLoginCookieList(host)
                .forEach { merged.putIfAbsent(it.name, it) }
            Preferences.cloudFlareCookieStateFlow.value.toLoginCookieList(host)
                .forEach { merged.putIfAbsent(it.name, it) }
        }

        val cookies = merged.values.toList()
        // cf_clearance 只记一个不可逆的短指纹，方便对照「WebView 拿到的」和「jar 发出去的」
        // 是不是同一份（排查过盾失败时用），不把 cookie 值写进日志。
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
            // 本次响应的同名 cookie 覆盖旧值；过期时间在过去的等于服务端要求删除。
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
