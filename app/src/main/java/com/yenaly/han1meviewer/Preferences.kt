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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

object Preferences {
    private const val USAGE_NOTICE_ACCEPTED = "usage_notice_accepted"

    /**
     * 登入状态按站点隔离的迁移标记。
     *
     * 旧版本的 `cookie` / `already_login` / `saved_user_id` / `cf_cookie` 是全域的，
     * 从 missav 切到 jable 时 missav 的会话会被 [HCookieJar][com.yenaly.han1meviewer.logic.network.HCookieJar]
     * 带到 jable 的请求上，于是 jable 被误判成已登入 missav 帐号。
     */
    private const val LOGIN_STATE_SCOPED_V1 = "login_state_scoped_v1"

    /**
     * 从本地收藏打开其他站点的影片时，切站会重启进程；
     * 这里暂存重启后要导航到的影片码，由 MainNavHost 消费一次。
     */
    private const val PENDING_LOCAL_FAVORITE_VIDEO = "pending_local_favorite_video"

    /**
     * [Preference][androidx.preference.PreferenceFragmentCompat]自带的SP
     */
    val preferenceSp: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(
            applicationContext
        )

    /** 把站点无关的旧键名变成站点专属键名，例如 `cookie` → `cookie_jable`。 */
    private fun scopedLoginKey(base: String, site: SiteType = siteType): String =
        "${base}_${site.name.lowercase()}"

    /**
     * 一次性把旧版全域登入状态归属到推断出的站点。
     *
     * 推断依据（依序）：cookie 名称 → 各站登入信箱 → 目前站点。
     * 迁移后旧键不再被读取，避免任何站点的会话再被其他站点带走。
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

    /** 旧版全域 cookie / 信箱归属推断；只在首次迁移时使用。 */
    private fun inferLegacyLoginSite(cookie: String, userId: String?): SiteType {
        // 只看 cookie 名称，避免随机值恰好包含站点关键字
        val cookieNames = cookie.split(';')
            .map { it.substringBefore('=').trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        fun hasName(keyword: String) = cookieNames.any { keyword in it }
        return when {
            hasName("missav") -> SiteType.MISSAV
            hasName("hanime") || hasName("remember_web") -> SiteType.HANIME
            hasName("phpsessid") || hasName("kt_tcookie") || hasName("jable") -> SiteType.JABLE
            // 登入信箱是站点自己记录的，比可能残留的 savedUserId 可靠
            missavLoginEmail.isNotBlank() -> SiteType.MISSAV
            jableLoginEmail.isNotBlank() -> SiteType.JABLE
            // 只有 missav 会写这个推荐用 id
            missavUserUuid.isNotBlank() -> SiteType.MISSAV
            // savedUserId 只在Hanime 有意义（missav / jable 首页解析固定回传空字串），
            // 但它可能是使用者很早以前在Hanime 留下的残值，故放在信箱之后。
            !userId.isNullOrBlank() -> SiteType.HANIME
            else -> siteType
        }
    }

    // app 相关

    /**
     * 当前站点是否已登入，一般跟[loginCookie]一起赋值。
     * 每个站点各自保存，切换站点后不会沿用其他站点的登入状态。
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
     * 保存的string格式的登入cookie（当前站点）。
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

    /** missav 登入帐号（email），仅用于介面显示。 */
    var missavLoginEmail: String
        get() = getSpValue(MISSAV_LOGIN_EMAIL, EMPTY_STRING)
        set(value) = putSpValue(MISSAV_LOGIN_EMAIL, value)

    /** jable 登入帐号，仅用于介面显示。 */
    var jableLoginEmail: String
        get() = getSpValue(JABLE_LOGIN_EMAIL, EMPTY_STRING)
        set(value) = putSpValue(JABLE_LOGIN_EMAIL, value)

    /**
     * missav 首页 Recombee 推荐用的使用者 id（对应站点 cookie `user_uuid`）。
     * 站点会把浏览行为记在这个 id 下，所以优先沿用它，否则每次安装都会是一份空白画像。
     */
    var missavUserUuid: String
        get() = getSpValue(MISSAV_USER_UUID, EMPTY_STRING)
        set(value) = putSpValue(MISSAV_USER_UUID, value)

    /**
     * Recombee frontend public token（从 missav 的 app.js 里取），
     * 用于给 client-rapi 请求签名。缓存起来避免每次启动都下载 100KB 的 bundle。
     */
    var missavRecombeeToken: String
        get() = getSpValue(MISSAV_RECOMBEE_TOKEN, EMPTY_STRING)
        set(value) = putSpValue(MISSAV_RECOMBEE_TOKEN, value)

    /**
     * 当前站点的 Cloudflare 验证 cookie。
     *
     * 不同站点的 `cf_clearance` 不能互用；共用一份会让切换站点后的第一个请求
     * 带著上一站的验证值，被 Cloudflare 当成无效会话，表现为「网路环境有问题」。
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

    /**
     * 人机验证完成的一次性事件。
     *
     * 请求等待验证是有上限的（见 CloudflareInterceptor.WAIT_SECONDS）：用户验证得
     * 太慢时，原请求早已超时失败，首页会停在错误页。收到这个事件后由首页自行重载，
     * 不必让用户手动下拉刷新。
     */
    private val cloudFlareVerifiedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cloudFlareVerifiedEvent = cloudFlareVerifiedEvents.asSharedFlow()

    fun notifyCloudFlareVerified() {
        cloudFlareVerifiedEvents.tryEmit(Unit)
    }

    // 更新 相关

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

    // 设定 相关

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
     * 当前站点类型。以 [baseUrl] 为唯一真相源，自订镜像站视为 Hanime。
     */
    val siteType: SiteType
        get() = SiteType.fromBaseUrl(baseUrl)

    /**
     * 当前站点是否为 missav（AV 站）
     */
    val isMissavSite: Boolean
        get() = siteType == SiteType.MISSAV

    /**
     * 当前站点是否为 jable（KVS 站）
     */
    val isJableSite: Boolean
        get() = siteType == SiteType.JABLE

    /**
     * 首页请求地址。missav 的内容位于 /dm12/cn 目录下，
     * 直接请求根路径或 /en/ 会被 Cloudflare 中转页（JS 跳转）拦截，故显式带上目录。
     * jable 的首页就是根路径，分节（最近更新/全新上市/热门影片…）都在根页。
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

    /** 切站重启后待打开的本地收藏影片码；没有时为 null。 */
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

    // 关键H帧 相关

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

    // 代理 相关

    val proxyType: Int
        get() = preferenceSp.getInt(
            SettingsPreferenceKeys.PROXY_TYPE,
            HProxySelector.TYPE_SYSTEM
        )

    val proxyIp: String
        get() = preferenceSp.getString(SettingsPreferenceKeys.PROXY_IP, EMPTY_STRING).orEmpty()

    val proxyPort: Int
        get() = preferenceSp.getInt(SettingsPreferenceKeys.PROXY_PORT, -1)

    // 隐私 相关

    val isAnalyticsEnabled: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.USE_ANALYTICS, true)

    // 下载 相关

    val downloadCountLimit: Int
        get() = preferenceSp.getInt(
            SettingsPreferenceKeys.DOWNLOAD_COUNT_LIMIT,
            // HanimeDownloadManager.MAX_CONCURRENT_DOWNLOAD_DEF
            HanimeDownloadManagerV2.MAX_CONCURRENT_DOWNLOAD_DEF
        )

    val collapseDownloadedGroup: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.COLLAPSE_DOWNLOADED_GROUP,false)

    /**
     * 下载时是否预设按系列自动建立分组，同时作为下载确认对话框的预设勾选状态。
     */
    var downloadAutoGroup: Boolean
        get() = preferenceSp.getBoolean(SettingsPreferenceKeys.DOWNLOAD_AUTO_GROUP, true)
        set(value) = preferenceSp.edit {
            putBoolean(SettingsPreferenceKeys.DOWNLOAD_AUTO_GROUP, value)
        }

    /**
     * 自动分组时分组名是否取自系列名（关闭时取自影片标题）。
     * 于下载确认对话框选择，并记住作为下次的预设选项。
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
     * 自动分组时是否将分组名快速转换为繁体（关闭时转为简体）。
     * 于下载确认对话框选择，未曾选择过时跟随[视频标题语言][videoLanguage]。
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
