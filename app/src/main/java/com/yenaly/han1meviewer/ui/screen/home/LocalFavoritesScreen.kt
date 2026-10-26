package com.yenaly.han1meviewer.ui.screen.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.SiteType
import com.yenaly.han1meviewer.labelRes
import com.yenaly.han1meviewer.logic.LocalFavoriteHtmlExporter
import com.yenaly.han1meviewer.logic.entity.LocalFavoriteEntity
import com.yenaly.han1meviewer.ui.component.ConfirmDialog
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import com.yenaly.han1meviewer.ui.component.LocalFavoriteEditorDialog
import com.yenaly.han1meviewer.ui.component.appbar.HanimeScaffold
import com.yenaly.han1meviewer.ui.component.content.EmptyContent
import com.yenaly.han1meviewer.ui.screen.RetryableImage
import com.yenaly.han1meviewer.ui.viewmodel.LocalFavoritesViewModel
import com.yenaly.yenaly_libs.utils.browse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地收藏列表：三站共用、無需登入，可評分／寫評價，並導出單檔離線 HTML。
 */
@Composable
fun LocalFavoritesScreen(
    onBack: () -> Unit,
    onOpenVideo: (LocalFavoriteEntity) -> Unit,
    onSwitchSiteAndOpen: (LocalFavoriteEntity) -> Unit,
    viewModel: LocalFavoritesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val currentSite = Preferences.siteType

    var filterSite by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LocalFavoriteEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<LocalFavoriteEntity?>(null) }
    var pendingSwitch by remember { mutableStateOf<LocalFavoriteEntity?>(null) }
    var exporting by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val filtered = if (filterSite == null) {
        favorites
    } else {
        favorites.filter { it.site == filterSite }
    }

    val updatedText = stringResource(R.string.local_favorite_updated)
    val deletedText = stringResource(R.string.local_favorite_deleted)
    val exportSuccessText = stringResource(R.string.export_local_favorites_success)
    val exportFailedText = stringResource(R.string.export_local_favorites_failed)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        // 回呼用的是最新一次重組的 lambda，因此直接取當前列表：
        // 即使在選檔期間轉屏（remember 狀態被清掉）也能正常匯出。
        val snapshot = favorites
        if (uri == null || snapshot.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = 0 to snapshot.size
            runCatching {
                val html = LocalFavoriteHtmlExporter.build(
                    context = context,
                    items = snapshot,
                    onProgress = { done, total -> exporting = done to total },
                )
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(html.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open export file")
                }
            }.onSuccess {
                GlobalToasts.show(
                    exportSuccessText,
                    level = GlobalToasts.ToastLevel.SUCCESS,
                )
            }.onFailure {
                GlobalToasts.show(
                    exportFailedText,
                    level = GlobalToasts.ToastLevel.ERROR,
                )
            }
            exporting = null
        }
    }

    HanimeScaffold(
        title = stringResource(R.string.local_favorites),
        subtitle = {
            Text(
                text = stringResource(R.string.local_favorite_count, favorites.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onBack = onBack,
        actions = {
            FilledIconButton(
                onClick = {
                    if (favorites.isNotEmpty() && exporting == null) {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                            .format(Date())
                        exportLauncher.launch("Han1meViewer-favorites-$stamp.html")
                    }
                },
                enabled = favorites.isNotEmpty() && exporting == null,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_download_24),
                    contentDescription = stringResource(R.string.export_local_favorites),
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = filterSite == null,
                        onClick = { filterSite = null },
                        label = { Text(stringResource(R.string.filter_all)) },
                    )
                }
                items(SiteType.entries) { site ->
                    FilterChip(
                        selected = filterSite == site.name,
                        onClick = { filterSite = site.name },
                        label = { Text(stringResource(site.labelRes)) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    favorites.isEmpty() -> EmptyContent(
                        hint = stringResource(R.string.local_favorites_empty),
                    )

                    filtered.isEmpty() -> EmptyContent(
                        hint = stringResource(R.string.empty_content),
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            LocalFavoriteCard(
                                item = item,
                                onClick = {
                                    val target = item.siteType
                                    when {
                                        target == null -> GlobalToasts.show(
                                            context.getString(R.string.fault_prompt),
                                            level = GlobalToasts.ToastLevel.WARNING,
                                        )

                                        target == currentSite -> onOpenVideo(item)
                                        else -> pendingSwitch = item
                                    }
                                },
                                onEdit = { editing = item },
                                onDelete = { pendingDelete = item },
                                onOpenInBrowser = {
                                    runCatching { context.browse(item.videoUrl) }
                                        .onFailure {
                                            GlobalToasts.show(
                                                context.getString(R.string.action_not_support),
                                                level = GlobalToasts.ToastLevel.WARNING,
                                            )
                                        }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { item ->
        LocalFavoriteEditorDialog(
            visible = true,
            videoTitle = item.title,
            initialRating = item.rating,
            initialComment = item.comment,
            isExisting = true,
            onDismiss = { editing = null },
            onSave = { rating, comment ->
                editing = null
                viewModel.update(item, rating, comment)
                GlobalToasts.show(updatedText, level = GlobalToasts.ToastLevel.SUCCESS)
            },
            onDelete = {
                editing = null
                viewModel.delete(item)
                GlobalToasts.show(deletedText, level = GlobalToasts.ToastLevel.SUCCESS)
            },
        )
    }

    ConfirmDialog(
        visible = pendingDelete != null,
        title = stringResource(R.string.local_favorite_delete_title),
        message = stringResource(
            R.string.local_favorite_delete_message,
            pendingDelete?.title.orEmpty(),
        ),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDelete?.let {
                viewModel.delete(it)
                GlobalToasts.show(deletedText, level = GlobalToasts.ToastLevel.SUCCESS)
            }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    )

    val switching = pendingSwitch
    ConfirmDialog(
        visible = switching != null,
        title = stringResource(R.string.switch_site),
        message = stringResource(
            R.string.local_favorite_switching_site,
            switching?.siteType?.let { stringResource(it.labelRes) }
                ?: switching?.site.orEmpty(),
        ),
        confirmText = stringResource(R.string.local_favorite_switch_and_open),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            switching?.let(onSwitchSiteAndOpen)
            pendingSwitch = null
        },
        onDismiss = { pendingSwitch = null },
    )

    val progress = exporting
    if (progress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.export_local_favorites_exporting)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        stringResource(
                            R.string.export_local_favorites_progress,
                            progress.first,
                            progress.second,
                        )
                    )
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun LocalFavoriteCard(
    item: LocalFavoriteEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                RetryableImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = painterResource(R.drawable.h_chan_loading),
                    error = painterResource(R.drawable.h_chan_load_failed),
                    contentScale = ContentScale.Crop,
                )
                SiteBadge(
                    site = item.siteType,
                    fallback = item.site,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarRow(item.rating)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (item.rating == 0) {
                            stringResource(R.string.local_favorite_unrated)
                        } else {
                            "${item.rating} / 5"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.comment.isNotBlank()) {
                    Text(
                        text = item.comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.videoCode,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.edit),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    IconButton(
                        onClick = onOpenInBrowser,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_language_24),
                            contentDescription = stringResource(R.string.local_favorite_open_in_browser),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteBadge(
    site: SiteType?,
    fallback: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.65f),
        contentColor = Color.White,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = site?.let { stringResource(it.labelRes) } ?: fallback,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StarRow(rating: Int) {
    Row {
        repeat(5) { index ->
            Icon(
                painter = painterResource(
                    if (index < rating) R.drawable.ic_baseline_star_24
                    else R.drawable.ic_baseline_star_border_24
                ),
                contentDescription = null,
                tint = if (index < rating) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
