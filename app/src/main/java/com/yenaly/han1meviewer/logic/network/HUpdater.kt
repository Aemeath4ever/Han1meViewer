package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.exception.HUpdaterException
import com.yenaly.han1meviewer.logic.model.github.CommitComparison
import com.yenaly.han1meviewer.logic.model.github.Latest
import com.yenaly.han1meviewer.util.checkNeedUpdate
import com.yenaly.han1meviewer.util.copyTo
import com.yenaly.han1meviewer.util.runSuspendCatching
import com.yenaly.yenaly_libs.utils.applicationContext
import okio.use
import java.io.File
import java.util.zip.ZipInputStream

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/21 021 08:28
 */
object HUpdater {

    const val TAG = "HUpdater"

    /**
     * CI 更新通道对应的滚动预发布 tag。
     *
     * 工作流（`.github/workflows/build.yml`）在 `build_type=debug/both` 时把 debug 包发布到
     * 这个 tag，并用 `--clobber` 覆盖旧 asset，所以它始终是最近一次 CI 构建。
     */
    private const val CI_RELEASE_TAG = "debug-latest"

    /** 从 Release 标题（`Debug build debug+1 (77d0790)`）或正文里取 commit。 */
    private val ciCommitRegex = Regex("\\(([0-9a-fA-F]{7,40})\\)")

    /**
     * 展示文案里附带细节（具体网络错误、HTTP 状态码）时的长度上限，
     * 避免把整段诊断信息塞进通知栏。
     */
    private const val MESSAGE_DETAIL_LIMIT = 120

    /**
     * Regex to match multiple line feeds to a single line feed
     */
    private val linefeedRegex = Regex("\\n{2,}")

    /**
     * 检查更新的数据来源。
     */
    enum class Channel(val logName: String) {
        /** CI 滚动预发布（`debug-latest`，每次 CI 构建的 debug 包） */
        CI("CI debug-latest"),

        /** GitHub Release */
        RELEASE("Release"),
    }

    /**
     * Check for update
     *
     * 失败时抛出带分类的[HUpdaterException]，调用方通过[errorMessage(e)]拿到可直接展示的
     * 文案。
     *
     * 注意这里**不会**在失败时切换更新渠道：CI 频道的构建始终领先于 Release，一旦因为
     * CI 出错就回落到 Release，用户会被降级到更旧的版本，反而更容易混乱。渠道由用户设置
     * 唯一决定，出错就如实报错，由用户自己在设置页手动重试。
     *
     * @param forceCheck force check
     * @throws HUpdaterException.VersionCheck 无法从 GitHub 获取版本信息时
     */
    suspend fun checkForUpdate(forceCheck: Boolean = false): Latest? {
        if (!forceCheck && !Preferences.isUpdateDialogVisible) return null
        val channel = currentChannel()
        Log.d(TAG, "开始检查更新，渠道：${channel.logName}")
        return when (channel) {
            Channel.CI -> checkCiChannel()
            Channel.RELEASE -> checkReleaseChannel()
        }
    }

    /**
     * 用户当前选择的更新渠道。
     *
     * 渠道只由用户设置决定（上游那个由作者用 Remote Config 远程关停 CI 频道的开关，在本仓库
     * 里没有意义，已移除）。CI 频道读取 [CI_RELEASE_TAG] 这个滚动预发布。
     */
    private fun currentChannel(): Channel =
        if (Preferences.useCIUpdateChannel) Channel.CI else Channel.RELEASE

    /**
     * 走 CI 预发布（[CI_RELEASE_TAG]）里的 debug 包。
     *
     * 新版 CI 不再上传 Actions Artifacts，而是把 APK 直接发到滚动预发布，所以这里只需要
     * 读一次 `releases/tags/<tag>`。版本判断用 Release 标题里带的 commit：
     * 与本机 `BuildConfig.COMMIT_SHA` 相同即视为已是最新。
     */
    private suspend fun checkCiChannel(): Latest? {
        val release = requestCiRelease()
        val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        if (asset == null) {
            Log.w(TAG, "CI 预发布 ${release.tagName} 没有可下载的安装包")
            throw HUpdaterException.VersionCheck(
                message = "CI 预发布（${release.tagName}）没有可下载的安装包",
                cause = null,
                reason = HUpdaterException.VersionCheck.Reason.NO_RELEASE,
            )
        }

        val latestSha = ciCommitRegex.find(release.name)?.groupValues?.get(1)?.lowercase()
            ?: ciCommitRegex.find(release.body)?.groupValues?.get(1)?.lowercase()
        if (latestSha == null) {
            // 没有 commit 就没法判断新旧；当作「没有新版本」，避免每次检查都弹同一个包
            Log.w(TAG, "CI 预发布标题里没有 commit，跳过：${release.name}")
            return null
        }
        val curSha = BuildConfig.COMMIT_SHA.lowercase()
        if (latestSha.startsWith(curSha) || curSha.startsWith(latestSha)) {
            Log.d(TAG, "已是最新的 CI 构建：$latestSha")
            return null
        }

        // 变更日志属于「锦上添花」，拿不到就退回 Release 正文
        val changelog = runSuspendCatching {
            requestCommitComparison(curSha, latestSha).commits.toChangelogPrettyString()
        }.getOrNull()
            ?: release.body.takeIf { it.isNotBlank() }
            ?: "CI build $latestSha"
        return Latest("$latestSha (CI)", changelog, asset.browserDownloadURL, asset.nodeID)
    }

    /**
     * 走 GitHub Release。
     */
    private suspend fun checkReleaseChannel(): Latest? {
        val ver = requestLatestRelease()
        if (!checkNeedUpdate(ver.tagName)) return null
        // both 模式下同一个 Release 会同时挂 release 与 debug 两个 APK，
        // 这里要挑正式包，否则会把 debug 包当成更新（applicationId 不同，装不上/装成另一个应用）。
        val asset = ver.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) &&
                    !it.name.contains("debug", ignoreCase = true)
        }
        if (asset == null) {
            // Release 存在但没有上传安装包
            Log.w(TAG, "Release ${ver.tagName} 没有任何可下载的 asset")
            throw HUpdaterException.VersionCheck(
                message = "Release ${ver.tagName} 没有任何可下载的 asset",
                cause = null,
                reason = HUpdaterException.VersionCheck.Reason.NO_RELEASE,
            )
        }
        return Latest(ver.tagName, ver.body, asset.browserDownloadURL, asset.nodeID)
    }

    private suspend fun requestLatestRelease() = try {
        HanimeNetwork.githubService.getLatestVersion()
            .orThrowVersionCheck("GET releases/latest")
    } catch (e: HUpdaterException) {
        throw e
    } catch (e: Exception) {
        // 网络层异常（DNS、超时等）统一转成带分类的更新异常
        throw e.toVersionCheckException("GET releases/latest")
    }

    private suspend fun requestCiRelease() = try {
        HanimeNetwork.githubService.getReleaseByTag(CI_RELEASE_TAG)
            .orThrowVersionCheck("GET releases/tags/$CI_RELEASE_TAG")
    } catch (e: HUpdaterException) {
        throw e
    } catch (e: Exception) {
        throw e.toVersionCheckException("GET releases/tags/$CI_RELEASE_TAG")
    }

    private suspend fun requestCommitComparison(curSha: String, latestSha: String) =
        HanimeNetwork.githubService.getCommitComparison(curSha, latestSha)
            .orThrowVersionCheck("GET compare/$curSha...$latestSha")

    /**
     * 把网络层异常（DNS、超时、连接重置等）包装成带分类的更新异常。
     */
    private fun Exception.toVersionCheckException(api: String): HUpdaterException.VersionCheck {
        return HUpdaterException.VersionCheck(
            message = "请求 $api 失败：${message ?: this::class.java.simpleName}",
            cause = this,
            reason = HUpdaterException.VersionCheck.Reason.NETWORK,
        )
    }

    /**
     * Inject update to file
     *
     * @param url update url
     * @throws HUpdaterException.Download 下载或写入失败时
     */
    suspend fun File.injectUpdate(url: String, progress: (suspend (Int, Long, Long) -> Unit)? = null) {
        val isZip = url.endsWith("zip")
        Log.d(TAG, "开始下载更新包（${if (isZip) "zip" else "apk"}）：$url")
        downloadTo(url, isZip, progress)
    }

    private suspend fun File.downloadTo(
        url: String,
        isZip: Boolean,
        progress: (suspend (Int, Long, Long) -> Unit)?,
    ) {
        val res = try {
            HanimeNetwork.githubService.request(url)
        } catch (e: Exception) {
            throw HUpdaterException.Download(
                message = "请求下载地址失败：${e.message ?: e::class.java.simpleName}",
                cause = e,
                reason = HUpdaterException.Download.Reason.NETWORK,
            )
        }
        // 先确认拿到的是安装包，再决定是否落盘
        val body = res.orThrowDownload(url)
        if (isZip) {
            Log.d(TAG, "Injecting update from zip ($url)")
            body.use {
                it.byteStream().use { stream ->
                    ZipInputStream(stream).use { zip ->
                        val entry = zip.nextEntry
                        if (entry == null) {
                            throw HUpdaterException.Download(
                                message = "下载到的 CI 压缩包内容为空（$url）",
                                cause = null,
                                reason = HUpdaterException.Download.Reason.INVALID_PAYLOAD,
                            )
                        }
                        outputStream().use { out ->
                            Log.i(TAG, "content length: ${it.contentLength()}")
                            // 估摸着压缩率为0.56左右，稍微估算解压后大小，防止进度卡在100%时间过长
                            zip.copyTo(out, (it.contentLength() * 1.79).toLong(), progress = progress)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Injecting update from apk ($url)")
            outputStream().use { out ->
                body.use {
                    Log.i(TAG, "content length: ${it.contentLength()}")
                    it.byteStream().copyTo(out, it.contentLength(), progress = progress)
                }
            }
        }
    }

    /**
     * 更新失败时可直接展示给用户的文案。
     *
     * 与[checkForUpdate] / [injectUpdate]抛出的异常配套使用：把「限额耗尽 / 密钥失效 /
     * 网络异常」这类具体原因呈现给用户，而不是笼统的「更新失败」。
     *
     * @return 可直接放进 UI / 通知的文案；异常不是[HUpdaterException]时返回 null，
     * 由调用方决定自己的兜底文案
     */
    fun errorMessage(e: Throwable?): CharSequence? {
        val reason = e as? HUpdaterException ?: return null
        val template = applicationContext.getString(reason.errorMessageRes)
        if (!template.contains("%s")) return template

        // 模板带 %s 时补上简短细节：有状态码就用 `HTTP xxx`，
        // 否则退回底层网络错误（如 “Connection refused”）
        val detail = reason.statusCode?.let { "HTTP $it" }
            ?: e.cause?.message?.takeIf { it.isNotBlank() }
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: return template

        return runCatching {
            applicationContext.getString(reason.errorMessageRes, detail.take(MESSAGE_DETAIL_LIMIT))
        }.getOrDefault(template)
    }

    /**
     * 更新失败的详细诊断信息，写日志 / Crashlytics 用（含状态码与响应体片段）。
     */
    fun errorDetail(e: Throwable?): String? {
        return e?.message?.takeIf { it.isNotBlank() }
    }

    /**
     * This function is used to filter out commits that are not authored by the user.
     */
    private val CommitComparison.Commit.CommitDetail.CommitAuthor.isAuthorShouldIgnore: Boolean
        get() = name.contains("dependabot")

    private fun List<CommitComparison.Commit>.toChangelogPrettyString(): String {
        return filterNot { commit ->
            commit.commit.author.isAuthorShouldIgnore
        }.distinct().reversed().joinToString("\n\n") { commit ->
            val message = commit.commit.message.replace(linefeedRegex, "\n")
            "↓ (@${commit.commit.author.name})\n$message"
        }
    }
}
