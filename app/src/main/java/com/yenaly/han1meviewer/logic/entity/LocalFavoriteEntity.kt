package com.yenaly.han1meviewer.logic.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yenaly.han1meviewer.SiteType
import kotlinx.serialization.Serializable

/**
 * 本地收藏。與站點的「喜歡」相互獨立，不需要登入，也不區分站點：
 * 三站的影片都存放在同一張表裡，用 [site] + [videoCode] 去重。
 *
 * [videoUrl] 是加入收藏當下的影片頁地址，保留它而不是每次按當前站點拼，
 * 是為了匯出的離線 HTML 與自訂鏡像站也能指向正確頁面。
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
    /** 0 表示未評分，1~5 對應星數。 */
    val rating: Int = 0,
    val comment: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val siteType: SiteType?
        get() = SiteType.entries.firstOrNull { it.name == site }
}

/** 把解析器可能給出的相對封面地址補成絕對地址，方便列表與離線匯出使用。 */
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
