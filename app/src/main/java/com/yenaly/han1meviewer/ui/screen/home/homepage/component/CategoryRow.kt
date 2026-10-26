package com.yenaly.han1meviewer.ui.screen.home.homepage.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.ui.component.VideoCardItem
import com.yenaly.han1meviewer.ui.component.lazy.LazyRow
import com.yenaly.han1meviewer.ui.preview.ComponentPreview
import com.yenaly.han1meviewer.ui.preview.fakeHomePageVideos
import com.yenaly.han1meviewer.ui.screen.rememberCardResponsiveWidth
import com.yenaly.han1meviewer.ui.theme.SpacingLarge
import com.yenaly.han1meviewer.ui.theme.SpacingNormal

/**
 * 显示横向滚动的视频分类行。
 *
 * @param title 分类标题。
 * @param videos 当前分类下的视频列表。续接出来的卡片会追加到末尾，追加后自动滚到新卡片处，
 * 否则新内容会停在屏幕外，看起来像什么都没发生。
 * @param onMoreClick 点击更多按钮时调用；为 null 时不显示「更多」（该类行没有可去的地方）。
 * @param onVideoClick 点击视频卡片时调用，参数为视频编号。
 * @param onVideoLongClick 长按视频卡片时调用，参数为视频编号和标题。
 * @param isLoadingMore 是否正在加载下一批，此时「更多」显示为转圈。
 * @param modifier 应用于分类行根布局的修饰符。
 */
@Composable
fun CategoryRow(
    title: String,
    videos: List<HanimeInfo>,
    onMoreClick: (() -> Unit)?,
    onVideoClick: (String) -> Unit,
    onVideoLongClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    isLoadingMore: Boolean = false,
) {
    val uniqueVideos = videos.distinctBy { it.videoCode }
    val listState = rememberLazyListState()
    var shownCount by remember { mutableStateOf(0) }
    val moreClick = onMoreClick

    // 续接出来的卡片在行尾：滚到第一张新卡片，用户才看得到「加载更多」的结果
    LaunchedEffect(uniqueVideos.size) {
        if (shownCount in 1 until uniqueVideos.size) {
            listState.animateScrollToItem(shownCount)
        }
        shownCount = uniqueVideos.size
    }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (isLoadingMore) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .size(16.dp)
                )
            } else if (moreClick != null) {
                Text(
                    text = stringResource(R.string.more),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { moreClick() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        val (cardWidth, _) = rememberCardResponsiveWidth()
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingNormal),
            contentPadding = PaddingValues(horizontal = SpacingLarge)
        ) {
            items(uniqueVideos, key = { it.videoCode }) { video ->
                VideoCardItem(
                    modifier = Modifier.width(cardWidth),
                    videoItem = video,
                    isHorizontalCard = true,
                    onClickVideosItem = onVideoClick,
                    onLongClickVideosItem = onVideoLongClick
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "视频分类行", showSystemUi = false)
@Composable
private fun CategoryRowPreview() {
    ComponentPreview {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            CategoryRow(
                title = "最新裏番",
                videos = fakeHomePageVideos,
                onMoreClick = {},
                onVideoClick = {},
                onVideoLongClick = { _, _ -> }
            )
        }
    }
}
