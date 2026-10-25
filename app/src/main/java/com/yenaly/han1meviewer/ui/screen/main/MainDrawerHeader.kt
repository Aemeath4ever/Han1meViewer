package com.yenaly.han1meviewer.ui.screen.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.preview.ComponentPreview

/** 抽屜「切換站點」下拉選項。 */
data class SiteSwitchOption(
    val label: String,
    val url: String,
    val isCurrent: Boolean,
)

@Composable
fun MainDrawerHeader(
    avatarUrl: String?,
    username: String?,
    isLoggedIn: Boolean,
    isLoading: Boolean,
    currentSite: String,
    siteOptions: List<SiteSwitchOption>,
    onAvatarClick: () -> Unit,
    onAvatarLongClick: () -> Unit,
    onSiteSelected: (String) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onAvatarClick,
                        onLongClick = onAvatarLongClick
                    ),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(id = R.drawable.bg_default_header),
                fallback = painterResource(id = R.drawable.bg_default_header),
                error = painterResource(id = R.drawable.bg_default_header),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp)
                    .align(Alignment.CenterVertically)
            ) {
                Text(
                    text = when {
                        isLoading -> stringResource(R.string.loading)
                        isLoggedIn -> username
                            ?: stringResource(R.string.refresh_page_or_login_expired)

                        else -> stringResource(R.string.not_logged_in)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = currentSite,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            var siteMenuExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .width(IntrinsicSize.Min)
                    .align(Alignment.Top)
            ) {
                Column(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable(
                            onClick = { siteMenuExpanded = true },
                            indication = ripple(bounded = false),
                            interactionSource = remember { MutableInteractionSource() }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_baseline_switch_24),
                            contentDescription = stringResource(R.string.switch_site)
                        )
                    }

                    Text(
                        text = stringResource(R.string.switch_site),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.alpha(0.7f),
                        textAlign = TextAlign.Center
                    )
                }
                DropdownMenu(
                    expanded = siteMenuExpanded,
                    onDismissRequest = { siteMenuExpanded = false },
                ) {
                    siteOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                siteMenuExpanded = false
                                if (!option.isCurrent) onSiteSelected(option.url)
                            },
                            trailingIcon = if (option.isCurrent) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainDrawerHeaderPreview() {
    ComponentPreview {
        MainDrawerHeader(
            avatarUrl = "https://www.baidu.com",
            username = "用户名",
            isLoggedIn = true,
            isLoading = false,
            currentSite = "https://www.baidu.com",
            siteOptions = listOf(
                SiteSwitchOption("Hanime", "https://hanime1.me/", false),
                SiteSwitchOption("MissAV", "https://missav.ai/", false),
                SiteSwitchOption("Jable", "https://jable.tv/", true),
            ),
            onAvatarClick = {},
            onAvatarLongClick = {},
            onSiteSelected = {},
        )
    }
}