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

    /** 內容是否為無碼（uncensored），卡片封面會顯示「無碼」角標。 */
    val isUncensored: Boolean get() = false
}
