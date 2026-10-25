package com.yenaly.han1meviewer

import android.webkit.CookieManager
import androidx.core.text.parseAsHtml
import com.yenaly.han1meviewer.Preferences.isAlreadyLogin
import com.yenaly.han1meviewer.Preferences.loginCookie
import com.yenaly.han1meviewer.Preferences.savedUserId
import com.yenaly.han1meviewer.logic.network.HCookieJar
import com.yenaly.han1meviewer.util.CookieString
import kotlinx.serialization.json.Json

@JvmField
val HJson = Json {
    ignoreUnknownKeys = true
}

/**
 * 给用户显示的错误信息
 *
 * ぴえん化
 */
val Throwable.pienization: CharSequence get() = "🥺\n$localizedMessage"

// base

private const val HANIME_TITLE_HTML =
    """<span style="color: #FF0000;"><b>H</b></span><b>an1me</b>Viewer"""

val hanimeSpannedTitle = HANIME_TITLE_HTML.parseAsHtml()

/**
 * 獲取影片地址（同時用於「跳轉網頁」與分享文本）
 *
 * missav 的影片頁形如 `https://missav.ai/cn/{slug}`，與 Hanime的 `watch?v=` 完全不同。
 */
fun getHanimeVideoLink(videoCode: String) = when {
    Preferences.isMissavSite -> Preferences.baseUrl + "cn/" + videoCode
    Preferences.isJableSite -> Preferences.baseUrl + "videos/" + videoCode + "/"
    else -> HANIME_BASE_URL + "watch?v=" + videoCode
}


/**
 * 獲取搜索地址
 */
fun getHanimeSearchLink(artist: String) = when {
    Preferences.isMissavSite -> Preferences.baseUrl + "cn/search/" + artist
    Preferences.isJableSite -> Preferences.baseUrl + "search/" + artist + "/"
    else -> HANIME_BASE_URL + "search?query=" + artist
}
/**
 * 獲取 Hanime 影片分享文本
 */
fun getHanimeShareText(title: String, videoCode: String): String = buildString {
    appendLine(title)
    appendLine(getHanimeVideoLink(videoCode))
    append("- From Han1meViewer -")
}
/**
 * 獲取 Hanime 影片分享文本
 */
fun getHanimeSearchShareText(artist: String): String = buildString {
    appendLine(artist)
    appendLine(getHanimeSearchLink(artist))
    append("- From Han1meViewer -")
}

/**
 * 獲取 Hanime 影片**官方**下載地址
 */
fun getHanimeVideoDownloadLink(videoCode: String) =
    if (Preferences.isJableSite) getHanimeVideoLink(videoCode)
    else HANIME_BASE_URL + "download?v=" + videoCode

    val videoUrlRegex = Regex(
        """(?:(?:https?:)?//[^\s"'<>/]+|(?:hanime(?:1|one))\.(?:com|me))?(?:/[^/?#\s"'<>]+)*/watch\?(?:[^#\s"'<>]*&)?v=(\d+)"""
    )

    // missav 的影片連結形如 https://missav.ai/dm72/en/{slug}，dm 前綴與語言目錄先於 slug
    val missavVideoUrlRegex =
        Regex("""(?:https?://missav\.(?:ai|ws))?/(?:dm\d+/)?(?:[a-z]{2}/)?([A-Za-z0-9-]+)""")

    // jable 的影片連結形如 https://jable.tv/videos/{slug}/，部分鏡像帶 /s0/ 前綴。
    // 必須在 missav 正則之前匹配：missav 的正則允許省略主機，會把 /videos/... 裏的
    // 「videos」當成影片碼。
    val jableVideoUrlRegex = Regex(
        """(?:https?://(?:[\w-]+\.)?jable\.tv)?/(?:[A-Za-z0-9_-]+/)?videos/([A-Za-z0-9_-]+)/?""",
        RegexOption.IGNORE_CASE,
    )

    fun String.toVideoCode() = videoUrlRegex.find(this)?.groupValues?.get(1)
        ?: jableVideoUrlRegex.find(this)?.groupValues?.get(1)
        ?: missavVideoUrlRegex.find(this)?.groupValues?.get(1)

// log in and log out

fun logout() {
    isAlreadyLogin = false
    loginCookie = CookieString(EMPTY_STRING)
    savedUserId = EMPTY_STRING
    HCookieJar.cookieMap.clear()
    CookieManager.getInstance().removeAllCookies(null)
}

fun login(cookies: String) {
    isAlreadyLogin = true
    loginCookie = CookieString(cookies)
}

fun login(cookies: List<String>) {
    login(cookies.joinToString(";") {
        it.substringBefore(';')
    })
}
