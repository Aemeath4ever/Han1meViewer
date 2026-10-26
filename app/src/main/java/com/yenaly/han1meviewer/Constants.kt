package com.yenaly.han1meviewer

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.char

/**
 * 我觉得空字符串写出来太逆天了，所以搞了个常量
 */
const val EMPTY_STRING = ""

const val APP_NAME = "Han1meViewer"

// 标准时间格式

/* yyyy-MM-dd */
@JvmField
val LOCAL_DATE_FORMAT = LocalDate.Formats.ISO

/* yyyy-MM-dd HH:mm */
@JvmField
val LOCAL_DATE_TIME_FORMAT = LocalDateTime.Format {
    date(LocalDate.Formats.ISO); char(' ')
    hour(); char(':'); minute()
}

// 网络基本设置

const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

// 設置發佈日期年份，在搜索的tag裏

/**
 * 發佈日期年份開始於
 */
const val SEARCH_YEAR_RANGE_START = 1990

/**
 * 發佈日期年份結束於
 */
const val SEARCH_YEAR_RANGE_END = BuildConfig.SEARCH_YEAR_RANGE_END

const val VIDEO_COMMENT_PREFIX = "video"

const val PREVIEW_COMMENT_PREFIX = "preview"

// base url

@JvmField
val HANIME_BASE_URL = Preferences.baseUrl

/**
 * 如果添加备选网址别忘了确认[String.toVideoCode]的videoUrlRegex
 *
 * [HANIME_HOSTNAME] 僅用於內置 DNS 解析，missav.ai 不在其中：
 * 其 Cloudflare IP 與 hanime 不同，須走常規 DNS / DoH。
 */
object HanimeConstants {
    val HANIME_HOSTNAME = arrayOf("hanime1.me","hanime1.com","hanimeone.me")

    /** jable.tv 是 KVS 站，與Hanime / missav 的介面與接口都不同。 */
    const val JABLE_URL = "https://jable.tv/"

    val HANIME_URL = arrayOf(
        "https://hanime1.me/",
        "https://hanime1.com/",
        "https://hanimeone.me/",
        "https://missav.ai/",
        JABLE_URL,
    )
    val ANIME_URL = arrayOf("https://hanime1.me/","https://hanime1.com/","https://hanimeone.me/")
}

/**
 * 目前選擇的站點類型。
 *
 * 站點以 [Preferences.baseUrl] 為唯一真相源（自訂鏡像站一律按 Hanime處理），
 * 不另外持久化，避免切換域名後兩份設定不一致。
 */
enum class SiteType {
    HANIME, MISSAV, JABLE;

    companion object {
        fun fromBaseUrl(url: String): SiteType = when (url) {
            HanimeConstants.HANIME_URL[3] -> MISSAV
            HanimeConstants.JABLE_URL -> JABLE
            else -> HANIME
        }
    }
}

/** 站點名稱的資源 id（抽屜、本地收藏等跨站點列表共用）。 */
val SiteType.labelRes: Int
    get() = when (this) {
        SiteType.HANIME -> R.string.site_hanime
        SiteType.MISSAV -> R.string.site_missav
        SiteType.JABLE -> R.string.site_jable
    }

/** 切換到該站點時使用的標準首頁地址；Hanime 優先沿用上次選的鏡像站。 */
val SiteType.defaultBaseUrl: String
    get() = when (this) {
        SiteType.HANIME -> Preferences.selectedBaseUrl
            .takeIf { it in HanimeConstants.ANIME_URL }
            ?: HanimeConstants.ANIME_URL[0]
        SiteType.MISSAV -> HanimeConstants.HANIME_URL[3]
        SiteType.JABLE -> HanimeConstants.JABLE_URL
    }

val HANIME_LOGIN_URL: String
    get() = when (Preferences.siteType) {
        SiteType.MISSAV -> {
            // missav 新版把登录做成了 SPA 弹窗（旧的 dm12/login 已 404）。
            // 片单页未登录时会自动弹出登录框（x-init="showLoginModal()"）。
            Preferences.baseUrl + "cn/playlists"
        }
        SiteType.JABLE -> {
            // jable 的登录是 fancybox 弹出的 AJAX 表單；直接打開也能看到並提交表單，
            // 成功後服務端 302 回首頁，由 LoginActivity 攔截並保存 cookie。
            Preferences.baseUrl + "login/"
        }
        SiteType.HANIME -> HANIME_BASE_URL + "login"
    }

// 媒體 CDN

/**
 * 主站影片與圖片使用的 CDN 主機，部分地區可能無法訪問。
 */
const val HANIME_MEDIA_CDN_HOST = "vdownload.hembed.com"

/**
 * 備用媒體 CDN 主機，用於替換 [HANIME_MEDIA_CDN_HOST]。
 */
const val HANIME_BACKUP_MEDIA_CDN_HOST = "1497203185.rsc.cdn77.org"

/**
 * 啟用備用媒體 CDN 後，將回應內容中的媒體（圖片 / 影片）CDN 主機替換為備用主機。
 */
fun String.replaceBackupMediaCdnHost(): String {
    if (!Preferences.useBackupMediaCdn) return this
    return replace(HANIME_MEDIA_CDN_HOST, HANIME_BACKUP_MEDIA_CDN_HOST, ignoreCase = true)
}

// github url

const val HA1_GITHUB_URL = "https://github.com/misaka10032w/Han1meViewer"

const val HA1_GITHUB_ISSUE_URL = "$HA1_GITHUB_URL/issues"

const val HA1_GITHUB_FORUM_URL = "$HA1_GITHUB_URL/discussions"

const val HA1_GITHUB_RELEASES_URL = "$HA1_GITHUB_URL/releases"

const val HA1_GITHUB_API_URL = "https://api.github.com/repos/misaka10032w/Han1meViewer/"
const val FIREBASE_REALTIME_DATABASE = "https://han1meviewer-86e5f-default-rtdb.asia-southeast1.firebasedatabase.app/"
// for Shared Preference

const val LOGIN_COOKIE = "cookie"
const val SAVED_USER_ID = "saved_user_id"

const val CLOUDFLARE_COOKIE = "cf_cookie"

/** missav 登入時使用的帳號。站點未必有顯示名（user.name 可能是 null），抽屜就拿這個兜底。 */
const val MISSAV_LOGIN_EMAIL = "missav_login_email"

/** jable 登入時使用的帳號，僅用於介面顯示。 */
const val JABLE_LOGIN_EMAIL = "jable_login_email"

/** missav 推薦使用的 user_uuid（站點把它放在同名 cookie 裡）。 */
const val MISSAV_USER_UUID = "missav_user_uuid"

/** Recombee frontend public token（取自 missav app.js，用於請求簽名）。 */
const val MISSAV_RECOMBEE_TOKEN = "missav_recombee_token"

const val ALREADY_LOGIN = "already_login"

// Notification

const val DOWNLOAD_NOTIFICATION_CHANNEL = "download_channel"

const val UPDATE_NOTIFICATION_CHANNEL = "update_channel"

// File

const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileProvider"
const val GETCHU_BASE_URL = "https://www.getchu.com/"
