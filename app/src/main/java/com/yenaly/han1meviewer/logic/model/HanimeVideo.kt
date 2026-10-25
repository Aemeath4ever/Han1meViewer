package com.yenaly.han1meviewer.logic.model

import com.yenaly.han1meviewer.ResolutionLinkMap
import com.yenaly.yenaly_libs.utils.mapToArray
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/11 011 20:30
 */
@Serializable
data class HanimeVideo(
    val title: String,
    val coverUrl: String,
    val chineseTitle: String?,
    val introduction: String?,
    val uploadTime: LocalDate?,
    @Transient val views: String? = null,

    // resolution to video url
    val videoUrls: ResolutionLinkMap,

    val tags: List<String>,
    /**
     * 注意，這裏的myList是指用戶的播放清單playlist
     */
    @Transient val myList: MyList? = null,
    /**
     * 注意，這裏的playlist是指該影片的系列影片，並非用戶的播放清單
     */
    @Transient val playlist: Playlist? = null,
    @Transient val relatedHanimes: List<HanimeInfo> = emptyList(),
    val artist: Artist? = null,

    @Transient val favTimes: Int? = null,
    @Transient val isFav: Boolean = false,
    @Transient val unlikesCount: Int? = null,
    @Transient val isUnlike: Boolean = false,
    @Transient val csrfToken: String? = null,
    @Transient val currentUserId: String? = null,
    @Transient val originalComic: String? = null,
    /** missav 站內物品 id（`/api/items/{id}/save`），與 dvd id 不同，用於喜歡操作 */
    @Transient val missavItemId: String? = null,
    /** jable 站內影片 id（主收藏按鈕的 data-fav-video-id，匿名頁退回 pageContext.videoId） */
    @Transient val jableVideoId: String? = null,
    /** jable「稍後觀看」狀態（書籤按鈕 active，fav_type=1） */
    @Transient val isWatchLater: Boolean = false,
) {

    val ratingCount: Int?
        get() = if (favTimes != null || unlikesCount != null) {
            (favTimes ?: 0) + (unlikesCount ?: 0)
        } else {
            null
        }

    val likeRatio: Int?
        get() = ratingCount?.takeIf { it > 0 }?.let { total ->
            (((favTimes ?: 0) * 100f) / total).toInt()
        }

    fun rateVideo(isPositive: Boolean): HanimeVideo {
        val liked = isFav
        val unliked = isUnlike
        val likes = favTimes ?: 0
        val unlikes = unlikesCount ?: 0
        return when {
            isPositive && liked -> copy(favTimes = (likes - 1).coerceAtLeast(0), isFav = false)
            isPositive -> copy(
                favTimes = likes + 1,
                unlikesCount = if (unliked) (unlikes - 1).coerceAtLeast(0) else unlikesCount,
                isFav = true,
                isUnlike = false,
            )

            !isPositive && unliked -> copy(
                unlikesCount = (unlikes - 1).coerceAtLeast(0),
                isUnlike = false,
            )

            else -> copy(
                favTimes = if (liked) (likes - 1).coerceAtLeast(0) else favTimes,
                unlikesCount = unlikes + 1,
                isFav = false,
                isUnlike = true,
            )
        }
    }

    // 為保證兼容性，不能直接用天數
    val uploadTimeMillis: Long
        get() = uploadTime?.let {
            it.toEpochDays() * 24 * 60 * 60 * 1000
        } ?: 0L

    data class MyList(
        var isWatchLater: Boolean,
        val myListInfo: List<MyListInfo>,
    ) {
        data class MyListInfo(
            val code: String,
            val title: String,
            var isSelected: Boolean,
        )

        val titleArray get() = myListInfo.mapToArray(MyListInfo::title)
        val isSelectedArray get() = myListInfo.map(MyListInfo::isSelected).toBooleanArray()
    }

    data class Playlist(
        val playlistName: String?,
        val video: List<HanimeInfo>,
    )

    @Serializable
    data class Artist(
        val name: String,
        val avatarUrl: String,
        val genre: String,
        @Transient val post: POST? = null,
        /**
         * 站內女優／演员主页。missav 是 `/actresses/{name}`，
         * jable 是 `/models/{hash}/`；Hanime 沒有对应页面，保持 null。
         */
        @Transient val profileUrl: String? = null,
    ) {
        val isSubscribed: Boolean get() = post != null && post.isSubscribed

        data class POST(
            val userId: String,
            val artistId: String,
            val isSubscribed: Boolean,
        )
    }
}
