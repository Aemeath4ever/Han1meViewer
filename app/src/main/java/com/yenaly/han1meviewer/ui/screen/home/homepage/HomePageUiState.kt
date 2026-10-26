package com.yenaly.han1meviewer.ui.screen.home.homepage

import com.yenaly.han1meviewer.logic.model.Announcement
import com.yenaly.han1meviewer.logic.model.HanimeInfo

/**
 * 主页 UI 事件集合，用于在主界面（Home）中处理用户交互行为，功能如函数名所写。
 *
 */

sealed interface HomeUiEvent {
    data object OpenDrawer : HomeUiEvent
    data object NavigateToPreview : HomeUiEvent
    data class OpenSearchPage(val query: String = "") : HomeUiEvent
    data class NavigateToSearchAdvanced(val params: Map<String, String>) : HomeUiEvent
    data class OpenVideo(val videoCode: String) : HomeUiEvent
    data class LongPressVideoCopy(val videoCode: String, val videoTitle: String) : HomeUiEvent
    data object ShowExitDialog : HomeUiEvent
    data class ShowAnnouncementDialog(val announcement: Announcement) : HomeUiEvent

    /** 打开分类对应的站点列表页（如 missav 無碼影片 → `/cn/fc2`），列表自带分页。 */
    data class NavigateToListing(val title: String, val url: String) : HomeUiEvent

    /** 就地续接推荐分类的下一批（站点「载入更多」的等价操作）。 */
    data class LoadMoreSection(val categoryKey: String, val recommId: String) : HomeUiEvent
}

/**
 * 首页各分类行的「加载更多」状态。
 *
 * 推荐分类的续接结果单独存放，不去改 [HomeData]：主页内容用 `AnimatedContent` 整体切换，
 * 改数据会让整页淡入淡出一次，而追加几行卡片不该有这种闪烁。
 *
 * @param extraItems 已续接到的额外卡片，按分类 key 分组，展示时拼在行尾。
 * @param loadingKeys 正在请求下一批的分类 key，对应行的「更多」显示为加载中。
 */
data class SectionMoreState(
    val extraItems: Map<String, List<HanimeInfo>> = emptyMap(),
    val loadingKeys: Set<String> = emptySet(),
)
