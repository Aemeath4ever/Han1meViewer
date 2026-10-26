package com.yenaly.han1meviewer

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.logic.network.interceptor.SpeedLimitInterceptor
import com.yenaly.han1meviewer.ui.navigation.settings.SettingsPreferenceKeys
import com.yenaly.han1meviewer.ui.view.video.HJzvdStd
import com.yenaly.han1meviewer.ui.view.video.HMediaKernel
import com.yenaly.han1meviewer.util.CookieString
import com.yenaly.han1meviewer.util.SafFileManager
import com.yenaly.han1meviewer.worker.HanimeDownloadManagerV2
import com.yenaly.yenaly_libs.utils.applicationContext
import com.yenaly.yenaly_libs.utils.getSpValue
import com.yenaly.yenaly_libs.utils.putSpValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

object Preferences {
    private const val USAGE_NOTICE_ACCEPTED = "usage_notice_accepted"

    /**
     * 登入狀態按站點隔離的遷移標記。
     *
     * 舊版本的 `cookie` / `already_login` / `saved_user_id` / `cf_cookie` 是全域的，
     * 從 missav 切到 jable 時 missav 的會話會被 [HCookieJar][com.yenaly.han1meviewer.logic.network.HCookieJar]
     * 帶到 jable 的請求上，於是 jable 被誤判成已登入 missav 帳號。
     */
    private const val LOGIN_STATE_SCOPED_V1 = "login_state_scoped_v1"

    /**
     * 從本地收藏打開其他站點的影片時，切站會重啟進程；
     * 這裡暫存重啟後要導航到的影片碼，由 MainNavHost 消費一次。
     */
    private const val PENDING_LOCAL_FAVORITE_VIDEO = "pending_local_favorite_video"

    /**
     * [Preference][androidx.preference.PreferenceFragmentCompat]自帶的SP
     */
    val preferenceSp: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(
            applicationContext
        )

    /** 把站點無關的舊鍵名變成站點專屬鍵名，例如 `cookie` → `cookie_jable`。 */
    private fun scopedLoginKey(base: String, site: SiteType = siteType): String =
        "${base}_${site.name.lowercase()}"

    /**
     * 一次性把舊版全域登入狀態歸屬到推斷出的站點。
     *
     * 推斷依據（依序）：cookie 名稱 → 各站登入信箱 → 目前站點。
     * 遷移後舊鍵不再被讀取，避免任何站點的會話再被其他站點帶走。
     */
    private fun ensureLoginStateScoped() {
        if (preferenceSp.getBoolean(LOGIN_STATE_SCOPED_V1, false)) return
        synchronized(this) {
            if (preferenceSp.getBoolean(LOGIN_STATE_SCOPED_V1, false)) return
            val legacyCookie = getSpValue(LOGIN_COOKIE, EMPTY_STRING)
            val legacyLogin = getSpValue(ALREADY_LOGIN, false)
            val legacyCfCookie = getSpValue(CLOUDFLARE_COOKIE, EMPTY_STRING)
            val legacyUserId = preferenceSp.getString(SAVED_USER_ID, null)
                ?: getSpValue(SAVED_USER_ID, EMPTY_STRING).takeIf { it.isNotBlank() }
            val hasLegacyState = legacyCookie.isNotBlank() || legacyLogin ||
                    legacyCfCookie.isNotBlank() || !legacyUserId.isNullOrBlank()
            val owner = if (hasLegacyState) {
                inferLegacyLoginSite(legacyCookie, legacyUserId)
            } else {
                siteType
            }
            val suffix = owner.name.lowercase()
            if (legacyLogin) putSpValue("${ALREADY_LOGIN}_$suffix", true)
            if (legacyCookie.isNotBlank()) putSpValue("${LOGIN_COOKIE}_$suffix", legacyCookie)
            if (legacyCfCookie.isNotBlank()) putSpValue("${CLOUDFLARE_COOKIE}_$suffix", legacyCfCookie)
            preferenceSp.edit {
                if (!legacyUserId.isNullOrBlank()) {
                    putString("${SAVED_USER_ID}_$suffix", legacyUserId)
                }
                putBoolean(LOGIN_STATE_SCOPED_V1, true)
            }
        }
    }

    /** 舊版全域 cookie / 信箱歸屬推斷；只在首次遷移時使用。 */
    private fun inferLegacyLoginSite(cookie: String, userId: String?): SiteType {
        // 只看 cookie 名稱，避免隨機值恰好包含站點關鍵字
        val cookieNames = cookie.split(';')
            .map { it.substringBefore('=').trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        fun hasName(keyword: String) = cookieNames.any { keyword in it }
        return when {
            hasName("missav") -> SiteType.MISSAV
            hasName("hanime") || hasName("remember_web") -> SiteType.HANIME
            hasName("phpsessid") || hasName("kt_tcookie") || hasName("jable") -> SiteType.JABLE
            // 登入信箱是站點自己記錄的，比可能殘留的 savedUserId 可靠
            missavLoginEmail.isNotBlank() -> SiteType.MISSAV
            jableLoginEmail.isNotBlank() -> SiteType.JABLE
            // 只有 missav 會寫這個推薦用 id
            missavUserUuid.isNotBlank() -> SiteType.MISSAV
            // savedUserId 只在Hanime 有意義（missav / jable 首頁解析固定回傳空字串），
            // 但它可能是使用者很早以前在Hanime 留下的殘值，故放在信箱之後。
            !userId.isNullOrBlank() -> SiteType.HANIME
            else -> siteType
        }
    }

    // app 相關

    /**
     * 當前站點是否已登入，一般跟[loginCookie]一起賦值。
     * 每個站點各自保存，切換站點後不會沿用其他站點的登入狀態。
     */
    var isAlreadyLogin: Boolean
        get() {
            ensureLoginStateScoped()
            return getSpValue(scopedLoginKey(ALREADY_LOGIN), false)
        }
        set(value) {
            loginStateFlow.value = value
            putSpValue(scopedLoginKey(ALREADY_LOGIN), value)
        }

    val loginStateFlow = MutableStateFlow(isAlreadyLogin)

    var usageNoticeAccepted: Boolean
        get() = preferenceSp.getBoolean(USAGE_NOTICE_ACCEPTED, false)
        set(value) = preferenceSp.edit { putBoolean(USAGE_NOTICE_ACCEPTED, value) }

    var savedUserId: String
        get() {
            ensureLoginStateScoped()
            return preferenceSp.getString(scopedLoginKey(SAVED_USER_ID), "") ?: ""
        }
        set(value) {
            ensureLoginStateScoped()
            preferenceSp.edit {
                if (value.isBlank()) {
                    remove(scopedLoginKey(SAVED_USER_ID))
                } else {
                    putString(scopedLoginKey(SAVED_USER_ID), value)
                }
            }
        }

    /**
     * 保存的string格式的登入cookie（當前站點）。
     */
    var loginCookie: CookieString
        get() {
            ensureLoginStateScoped()
            return CookieString(getSpValue(scopedLoginKey(LOGIN_COOKIE), EMPTY_STRING))
        }
        set(value) {
            loginCookieStateFlow.value = value
            putSpValue(scopedLoginKey(LOGIN_COOKIE), value.cookie)
        }

    val loginCookieStateFlow = MutableStateFlow(loginCookie)

    /** missav 登入帳號（email），僅用於介面顯示。 */
    var missavLoginEmail: String
        get() = getSpValue(MISSAV_LOGIN_EMAIL, EMPTY_STRING)
        set(value) = putSpValue(MISSAV_LOGIN_EMAIL, value)

    /** jable 登入帳號，僅用於介面顯示。 */
    var jableLoginEmail: String
        get() = getSpValue(JABLE_LOGIN_EMAIL, EMPTY_STRING)
        set(value) = putSpValue(JABLE_LOGIN_EMAIL, value)

    /**
     * missav 首頁 Recombee 推薦用的使用者 id（對應站點 cookie `user_uuid`）。
     * 站點會把瀏覽行為記在這個 id 下，所以優先沿用它，否則每次安裝都會是一份空白畫像。
     */
    var missavUserUuid: String
        get() = getSpValue(MISSAV_USER_UUID, EMPTY_STRING)
        set(value) = putSpValue(MISSAV_USER_UUID, value)

    /**
     * Recombee frontend public token（從 missav 的 app.js 裡取），
     * 用於給 client-rapi 請求簽名。緩存起來避免每次啟動都下載 100KB 的 bundle。
     */
    var missavRecombeeToken: String
        get() = getSpValue(MISSAV_RECOMBEE_TOKEN, EMPTY_STRING)
        set(value) = putSpValue(MISSAV_RECOMBEE_TOKEN, value)

    /**
     * 當前站點的 Cloudflare 驗證 cookie。
     *
     * 不同站點的 `cf_clearance` 不能互用；共用一份會讓切換站點後的第一個請求
     * 帶著上一站的驗證值，被 Cloudflare 當成無效會話，表現為「網路環境有問題」。
     */
    var cloudFlareCookie: CookieString
        get() {
            ensureLoginStateScoped()
            return CookieString(getSpValue(scopedLoginKey(CLOUDFLARE_COOKIE), EMPTY_STRING))
        }
        set(value) {
            cloudFlareCookieStateFlow.value = value
            putSpValue(scopedLoginKey(CLOUDFLARE_COOKIE), value.cookie)
        }
    val cloudFlareCookieStateFlow = MutableStateFlow(cloudFlareCookie)

    // 更新 相關

    private const val UPDATE_NODE_ID = "update_node_id"

    var updateNodeId: String
        get() = getSpValue(UPDATE_NODE_ID, EMPTY_STRING)
        set(value) = putSpValue(UPDATE_NODE_ID, value)

    var lastUpdatePopupTime
        get() = getSpValue(SettingsPreferenceKeys.LAST_UPDATE_POPUP_TIME, 0L)
        set(value) = putSpValue(SettingsPreferenceKeys.LAST_UPDATE_POPUP_TIME, value)

    val updatePopupIntervalDays
        get() = preferenceSp.getInt(SettingsPreferenceKeys.UPDATE_POPUP_INTERVAL_DAYS, 0)

    val useCIUpdateChannel
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_CI_UPDATE_CHANNEL, false)

    // Check if show update dialog.
    @OptIn(ExperimentalTime::class)
    val isUpdateDialogVisible: Boolean
        get() {
            val now = kotlin.time.Clock.System.now()
            val lastCheckTime = kotlin.time.Instant.fromEpochSeconds(lastUpdatePopupTime)
            val interval = updatePopupIntervalDays
            return now > lastCheckTime + interval.days
        }

    // 設定 相關

    val switchPlayerKernel: String
        get() = preferenceSp.getString(
            SettingsPreferenceKeys.SWITCH_PLAYER_KERNEL,
            HMediaKernel.Type.ExoPlayer.name
        ) ?: HMediaKernel.Type.ExoPlayer.name

    val showBottomProgress: Boolean
        get() = preferenceSp.getBoolean(
            SettingsPreferenceKeys.SHOW_BOTTOM_PROGRESS,
            true
        )

    val playerSpeed: Float
        get() = preferenceSp.getString(
            SettingsPreferenceKeys.PLAYER_SPEED,
            HJzvdStd.DEF_SPEED.toString()
        )?.toFloat() ?: HJzvdStd.DEF_SPEED

    val slideSensitivity: Int
        get() = preferenceSp.getInt(
            SettingsPreferenceKeys.SLIDE_SENSITIVITY,
            HJzvdStd.DEF_PROGRESS_SLIDE_SENSITIVITY
        )

    val longPressSpeedTime: Float
        get() = preferenceSp.getString(
            SettingsPreferenceKeys.LONG_PRESS_SPEED_TIMES,
            HJzvdStd.DEF_LONG_PRESS_SPEED_TIMES.toString()
        )?.toFloat() ?: HJzvdStd.DEF_LONG_PRESS_SPEED_TIMES

    val videoLanguage: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.VIDEO_LANGUAGE, "zhs") ?: "zht"

    val videoQuality: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.DEFAULT_VIDEO_QUALITY, "1080P") ?: "1080P"

    val showPlayedIndicator: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.SHOW_PLAYED_INDICATOR,true)

    val watchedProgressThreshold: Int
        get() = preferenceSp.getInt(SettingsPreferenceKeys.WATCHED_PROGRESS_THRESHOLD, 90)

    val autoPlayNext: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.AUTO_PLAY_NEXT, false)

    val searchPagination: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.SEARCH_PAGINATION, false)

    val searchGridColumnsConfig: SearchGridColumnsConfig
        get() = SearchGridColumnsConfig(
            compactColumns = preferenceSp.getInt(
                SettingsPreferenceKeys.SEARCH_GRID_COLUMNS_COMPACT,
                SearchGridColumnsConfig.DEFAULT_COMPACT_COLUMNS,
            ),
            mediumColumns = preferenceSp.getInt(
                SettingsPreferenceKeys.SEARCH_GRID_COLUMNS_MEDIUM,
                SearchGridColumnsConfig.DEFAULT_MEDIUM_COLUMNS,
            ),
            expandedColumns = preferenceSp.getInt(
                SettingsPreferenceKeys.SEARCH_GRID_COLUMNS_EXPANDED,
                SearchGridColumnsConfig.DEFAULT_EXPANDED_COLUMNS,
            ),
            largeColumns = preferenceSp.getInt(
                SettingsPreferenceKeys.SEARCH_GRID_COLUMNS_LARGE,
                SearchGridColumnsConfig.DEFAULT_LARGE_COLUMNS,
            ),
        )

    val horizontalCardCountConfig: HorizontalCardCountConfig
        get() = HorizontalCardCountConfig(
            narrowCount = preferenceSp.getString(
                SettingsPreferenceKeys.HORIZONTAL_CARD_COUNT_NARROW,
                HorizontalCardCountConfig.DEFAULT_NARROW_COUNT.toString(),
            )?.toFloatOrNull() ?: HorizontalCardCountConfig.DEFAULT_NARROW_COUNT,
            compactCount = preferenceSp.getString(
                SettingsPreferenceKeys.HORIZONTAL_CARD_COUNT_COMPACT,
                HorizontalCardCountConfig.DEFAULT_COMPACT_COUNT.toString(),
            )?.toFloatOrNull() ?: HorizontalCardCountConfig.DEFAULT_COMPACT_COUNT,
            mediumCount = preferenceSp.getString(
                SettingsPreferenceKeys.HORIZONTAL_CARD_COUNT_MEDIUM,
                HorizontalCardCountConfig.DEFAULT_MEDIUM_COUNT.toString(),
            )?.toFloatOrNull() ?: HorizontalCardCountConfig.DEFAULT_MEDIUM_COUNT,
            expandedCount = preferenceSp.getString(
                SettingsPreferenceKeys.HORIZONTAL_CARD_COUNT_EXPANDED,
                HorizontalCardCountConfig.DEFAULT_EXPANDED_COUNT.toString(),
            )?.toFloatOrNull() ?: HorizontalCardCountConfig.DEFAULT_EXPANDED_COUNT,
        )

    val fakeLauncherIcon: String
        get() = preferenceSp.getString(
            SettingsPreferenceKeys.FAKE_LAUNCHER_ICON,
            "${BuildConfig.NAMESPACE}.LauncherAliasDefault")
            ?: "${BuildConfig.NAMESPACE}.LauncherAliasDefault"

    val baseUrl: String
        get() {
            if (useCustomMirrorSite && customMirrorSite.isNotBlank()) {
                val url = if (appendCustomMirrorPath) customMirrorSite else customMirrorSite.toRootUrl()
                return url.toRetrofitBaseUrl()
            }
            return preferenceSp.getString(SettingsPreferenceKeys.DOMAIN_NAME, HANIME_URL[0])
                ?: HANIME_URL[0]
        }

    val homeUrl: String
        get() {
            if (useCustomMirrorSite && customMirrorSite.isNotBlank()) return customMirrorSite
            return baseUrl
        }

    /**
     * 當前站點類型。以 [baseUrl] 為唯一真相源，自訂鏡像站視為 Hanime。
     */
    val siteType: SiteType
        get() = SiteType.fromBaseUrl(baseUrl)

    /**
     * 當前站點是否為 missav（AV 站）
     */
    val isMissavSite: Boolean
        get() = siteType == SiteType.MISSAV

    /**
     * 當前站點是否為 jable（KVS 站）
     */
    val isJableSite: Boolean
        get() = siteType == SiteType.JABLE

    /**
     * 首頁請求地址。missav 的內容位於 /dm12/cn 目錄下，
     * 直接請求根路徑或 /en/ 會被 Cloudflare 中轉頁（JS 跳轉）攔截，故顯式帶上目錄。
     * jable 的首頁就是根路徑，分節（最近更新/全新上市/熱門影片…）都在根頁。
     */
    val homeRequestUrl: String
        get() = when (siteType) {
            SiteType.MISSAV -> baseUrl + "dm12/cn"
            SiteType.JABLE -> baseUrl
            SiteType.HANIME -> homeUrl
        }

    val useCustomMirrorSite: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_CUSTOM_MIRROR_SITE, false)

    val customMirrorSite: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.CUSTOM_MIRROR_SITE, EMPTY_STRING).orEmpty()

    val appendCustomMirrorPath: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.APPEND_CUSTOM_MIRROR_PATH, true)

    private fun String.toRetrofitBaseUrl(): String = if (endsWith('/')) this else "$this/"

    private fun String.toRootUrl(): String {
        val uri = toUri()
        return "${uri.scheme}://${uri.encodedAuthority}"
    }

    val selectedBaseUrl: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.SELECTED_BASE_URL, HANIME_URL[0])
            ?: HANIME_URL[0]

    /** 切站重啟後待打開的本地收藏影片碼；沒有時為 null。 */
    var pendingLocalFavoriteVideoCode: String?
        get() = preferenceSp.getString(PENDING_LOCAL_FAVORITE_VIDEO, null)
        set(value) = preferenceSp.edit {
            if (value.isNullOrBlank()) remove(PENDING_LOCAL_FAVORITE_VIDEO)
            else putString(PENDING_LOCAL_FAVORITE_VIDEO, value)
        }

    val useBuiltInHosts: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_BUILT_IN_HOSTS, false)

    val useBackupMediaCdn: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_BACKUP_MEDIA_CDN, false)

    val customHostsData: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.CUSTOM_HOSTS_DATA, EMPTY_STRING).orEmpty()

    val useDoH: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_DOH, false)

    val dohPreset: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.DOH_PRESET, "alidns") ?: "alidns"

    val dohCustomUrl: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.DOH_CUSTOM_URL, EMPTY_STRING).orEmpty()

    val dohBootstrapIps: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.DOH_BOOTSTRAP_IPS, EMPTY_STRING).orEmpty()

    val dohTimeoutSeconds: Int
        get() = preferenceSp.getInt(SettingsPreferenceKeys.DOH_TIMEOUT_SECONDS, 10)

    // 關鍵H幀 相關

    val whenCountdownRemind: Int
        get() = preferenceSp.getInt(
            SettingsPreferenceKeys.WHEN_COUNTDOWN_REMIND,
            HJzvdStd.DEF_COUNTDOWN_SEC
        ) * 1_000 // 越不了界，最大就30_000ms而已

    val showCommentWhenCountdown: Boolean
        get() = preferenceSp.getBoolean(
            SettingsPreferenceKeys.SHOW_COMMENT_WHEN_COUNTDOWN,
            false
        )

    val hKeyframesEnable: Boolean
        get() = preferenceSp.getBoolean(
            SettingsPreferenceKeys.H_KEYFRAMES_ENABLE,
            true
        )

    val sharedHKeyframesEnable: Boolean
        get() = preferenceSp.getBoolean(
            SettingsPreferenceKeys.SHARED_H_KEYFRAMES_ENABLE,
            true
        )

    val sharedHKeyframesUseFirst: Boolean
        get() = preferenceSp.getBoolean(
            SettingsPreferenceKeys.SHARED_H_KEYFRAMES_USE_FIRST,
            false
        )

    // 代理 相關

    val proxyType: Int
        get() = preferenceSp.getInt(
            SettingsPreferenceKeys.PROXY_TYPE,
            HProxySelector.TYPE_SYSTEM
        )

    val proxyIp: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.PROXY_IP, EMPTY_STRING).orEmpty()

    val proxyPort: Int
        get() = preferenceSp.getInt(SettingsPreferenceKeys.PROXY_PORT, -1)

    // 隐私 相關

    val isAnalyticsEnabled: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_ANALYTICS, true)

    // 下载 相關

    val downloadCountLimit: Int
        get() = preferenceSp.getInt(
            SettingsPreferenceKeys.DOWNLOAD_COUNT_LIMIT,
            // HanimeDownloadManager.MAX_CONCURRENT_DOWNLOAD_DEF
            HanimeDownloadManagerV2.MAX_CONCURRENT_DOWNLOAD_DEF
        )

    val collapseDownloadedGroup: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.COLLAPSE_DOWNLOADED_GROUP,false)

    /**
     * 下載時是否預設按系列自動建立分組，同時作為下載確認對話框的預設勾選狀態。
     */
    var downloadAutoGroup: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.DOWNLOAD_AUTO_GROUP, true)
        set(value) = preferenceSp.edit {
            putBoolean(SettingsPreferenceKeys.DOWNLOAD_AUTO_GROUP, value)
        }

    /**
     * 自動分組時分組名是否取自系列名（關閉時取自影片標題）。
     * 於下載確認對話框選擇，並記住作為下次的預設選項。
     */
    var downloadGroupNameFromSeriesName: Boolean
        get() = preferenceSp.getBoolean(
            SettingsPreferenceKeys.DOWNLOAD_GROUP_NAME_FROM_SERIES_NAME,
            false,
        )
        set(value) = preferenceSp.edit {
            putBoolean(SettingsPreferenceKeys.DOWNLOAD_GROUP_NAME_FROM_SERIES_NAME, value)
        }

    /**
     * 自動分組時是否將分組名快速轉換為繁體（關閉時轉為簡體）。
     * 於下載確認對話框選擇，未曾選擇過時跟隨[視頻標題語言][videoLanguage]。
     */
    var downloadGroupNameTraditional: Boolean
        get() = preferenceSp.getBoolean(
            SettingsPreferenceKeys.DOWNLOAD_GROUP_TRADITIONAL,
            videoLanguage == "zht",
        )
        set(value) = preferenceSp.edit {
            putBoolean(SettingsPreferenceKeys.DOWNLOAD_GROUP_TRADITIONAL, value)
        }
    val isUsePrivateStorage: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_PRIVATE_STORAGE,true)
    val safDownloadPath: String?
        get() = preferenceSp.getString(SafFileManager.KEY_TREE_URI,null)

    val useDarkMode: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.USE_DARK_MODE,"always_off") ?: "always_off"
    val useDynamicColor: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_DYNAMIC_COLOR,false)
    val themeColor: String?
        get() = preferenceSp.getString(SettingsPreferenceKeys.THEME_COLOR, null)
    val pureBlackDarkMode: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.PURE_BLACK_DARK_MODE, false)
    val allowResumePlayback: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.ALLOW_RESUME_PLAYBACK,true)

    val searchArtistIgnoreVideoType: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.SEARCH_ARTIST_IGNORE_VIDEO_TYPE,false)

    val disableMobileDataWarning: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.DISABLE_MOBILE_DATA_WARNING,false)

    val disablePredictiveBack: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.DISABLE_PREDICTIVE_BACK, false)

    val tabletMode: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.TABLET_MODE, false)

    val showExitConfirm: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.SHOW_EXIT_CONFIRM, true)

    /**
     * MPV播放器设置
     */
    val mpvProfile: String // 预设模式
        get() = preferenceSp.getString(SettingsPreferenceKeys.MPV_PROFILE, "fast") ?: "fast"

    val enableGPUNextRenderer: Boolean // gpu-next 渲染器
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.ENABLE_GPU_NEXT_RENDERER, false)

    val mpvInterpolation: Boolean  // 插帧相关
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.MPV_INTERPOLATION, false)

    val mpvDeband: Boolean  // 去色带
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.MPV_DEBAND, true)

    val mpvFramedrop: Boolean  // GPU 繁忙时允许丢帧
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.MPV_FRAMEDROP, true)

    val mpvHwdec: String  // 硬件解码
        get() = preferenceSp.getString(SettingsPreferenceKeys.MPV_HWDEC, "Auto")?: "Auto"

    val mpvCacheSecs: Int  // 预缓存秒数
        get() = preferenceSp.getInt(SettingsPreferenceKeys.MPV_CACHE_SECS, 60)

    val mpvTlsVerify: Boolean  // 忽略证书验证
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.MPV_TLS_VERIFY, true)

    val mpvNetworkTimeout: Int  // 请求超时
        get() = preferenceSp.getInt(SettingsPreferenceKeys.MPV_NETWORK_TIMEOUT, 10)

    val customMpvParams: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.CUSTOM_PARAMS,"")?: ""

    /**
     * 对应关系详见 [SpeedLimitInterceptor.SPEED_BYTES]
     */
    val downloadSpeedLimit: Long
        get() {
            val index = preferenceSp.getInt(
                SettingsPreferenceKeys.DOWNLOAD_SPEED_LIMIT,
                SpeedLimitInterceptor.NO_LIMIT_INDEX
            )
            return SpeedLimitInterceptor.SPEED_BYTES[index]
        }
}
