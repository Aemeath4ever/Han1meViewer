package com.yenaly.han1meviewer.logic.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yenaly.han1meviewer.SiteType
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * 本地收藏。与站点的「喜欢」相互独立，不需要登入，也不区分站点：
 * 三站的影片都存放在同一张表里，用 [site] + [videoCode] 去重。
 *
 * [videoUrl] 是加入收藏当下的影片页地址，保留它而不是每次按当前站点拼，
 * 是为了汇出的离线 HTML 与自订镜像站也能指向正确页面。
 */
@Serializable
@Entity(
    tableName = "LocalFavoriteEntity",
    indices = [Index(value = ["site", "videoCode"], unique = true)],
)
data class LocalFavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val site: String,
    val videoCode: String,
    val title: String,
    val coverUrl: String,
    val videoUrl: String,
    /** 0 表示未评分，1~5 对应星数。 */
    val rating: Int = 0,
    val comment: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val siteType: SiteType?
        get() = SiteType.entries.firstOrNull { it.name == site }

    /**
     * 打开收藏时使用的站点：优先用 [site]，缺失或无法识别时按 [videoUrl] 的域名推断，
     * 这样旧数据/导入的数据也能自动解析到对应站点。
     */
    val resolvedSiteType: SiteType?
        get() = siteType ?: inferSiteType(videoUrl)
}

private fun inferSiteType(url: String): SiteType? {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return null
    return when {
        "missav" in host -> SiteType.MISSAV
        "jable" in host -> SiteType.JABLE
        "hanime" in host -> SiteType.HANIME
        else -> null
    }
}

/** 把解析器可能给出的相对封面地址补成绝对地址，方便列表与离线汇出使用。 */
fun String.toAbsoluteMediaUrl(baseUrl: String): String {
    val url = trim()
    return when {
        url.isEmpty() -> ""
        url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> baseUrl.trimEnd('/') + url
        else -> baseUrl.trimEnd('/') + "/" + url
    }
}
