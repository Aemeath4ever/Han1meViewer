package com.yenaly.han1meviewer.logic.model

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2023/08/26 026 17:47
 */
data class Playlists(
    val playlists: List<Playlist>,
    val csrfToken: String? = null,
    val maxPage: Int = 1,
) {
    data class Playlist(
        val listCode: String,
        var title: String,
        var total: Int,
        val coverUrl: String? = null
    )
}

/**
 * 用于 修改播放清单 Flow 的返回值
 */
data class ModifiedPlaylistArgs(
    var title: String,
    var desc: String,
    var isDeleted: Boolean,
)
