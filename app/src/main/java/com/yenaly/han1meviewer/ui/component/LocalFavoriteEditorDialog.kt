package com.yenaly.han1meviewer.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R

/**
 * 新增／編輯本地收藏的評分與評價。
 *
 * 評分為 0~5 星，0 表示未評分；再點一次目前星數可清回 0。
 */
@Composable
fun LocalFavoriteEditorDialog(
    visible: Boolean,
    videoTitle: String,
    initialRating: Int,
    initialComment: String,
    isExisting: Boolean,
    onDismiss: () -> Unit,
    onSave: (rating: Int, comment: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    if (!visible) return

    var rating by remember(initialRating) {
        mutableIntStateOf(initialRating.coerceIn(0, 5))
    }
    var comment by remember(initialComment) { mutableStateOf(initialComment) }
    val unratedText = stringResource(R.string.local_favorite_unrated)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isExisting) R.string.edit_local_favorite
                    else R.string.add_to_local_favorites
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = videoTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.local_favorite_rating),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(5) { index ->
                        val star = index + 1
                        IconButton(
                            modifier = Modifier.size(36.dp),
                            onClick = { rating = if (rating == star) 0 else star },
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (star <= rating) R.drawable.ic_baseline_star_24
                                    else R.drawable.ic_baseline_star_border_24
                                ),
                                contentDescription = star.toString(),
                                tint = if (star <= rating) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                    Text(
                        text = if (rating == 0) unratedText else "$rating / 5",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.local_favorite_comment)) },
                    placeholder = {
                        Text(stringResource(R.string.local_favorite_comment_hint))
                    },
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(rating, comment.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
