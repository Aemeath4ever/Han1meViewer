package com.yenaly.han1meviewer.logic.model

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 */
interface HanimeInfoType : MultiItemEntity

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:56
 */
data class HanimeInfo(
    override val title: String,
    override val coverUrl: String,
    override val videoCode: String,
    override val duration: String? = null,
    override val views: String? = null,
    override val uploadTime: String? = null,
    val genre: String? = null,

    val isPlaying: Boolean = false, // for video playlist only.

    val playlistItemId: String? = null,

    /** jable 站内影片 id（卡片收藏钮的 `data-fav-video-id`），用于从「喜欢」列表移除。 */
    val jableVideoId: String? = null,

    override var itemType: Int,
    override val reviews: String? = "",
    override val currentArtist: String? = "",
    val watched: Boolean ?= false,
    override val isUncensored: Boolean = false,
): VideoItemType , HanimeInfoType {
    companion object {
        const val NORMAL = 0
        const val SIMPLIFIED = 1
    }
}
