package com.yenaly.han1meviewer.logic.model

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2022/07/05 005 15:30
 */
data class MyListItems<I>(
    val hanimeInfo: List<I>,
    /**
     * 清单的介绍，一般用于播放清单
     */
    var desc: String? = null,
    val csrfToken: String? = null,
    val maxPage: Int = 1,
)
