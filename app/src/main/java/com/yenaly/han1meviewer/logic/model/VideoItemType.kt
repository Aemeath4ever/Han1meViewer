package com.yenaly.han1meviewer.logic.model

interface VideoItemType {
    val title: String
    val coverUrl: String
    val videoCode: String
    val duration: String?
    val views: String?
    val reviews: String?
    val currentArtist: String?
    val uploadTime: String?

    /** 内容是否为无码（uncensored），卡片封面会显示「无码」角标。 */
    val isUncensored: Boolean get() = false
}
