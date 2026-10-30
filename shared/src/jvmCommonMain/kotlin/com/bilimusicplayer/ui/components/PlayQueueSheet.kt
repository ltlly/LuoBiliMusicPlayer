package com.bilimusicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilimusicplayer.core.LocalAppContainer

/** Bottom sheet showing the current play queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayQueueSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints {
        val sheetMaxHeight = maxHeight * 0.8f
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            PlayQueueContent(
                modifier = Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight),
                onClose = onDismiss,
                queueTag = "play_queue_sheet",
            )
        }
    }
}

/** Shared by the phone sheet and the persistent queue on wide player layouts. */
@Composable
fun PlayQueueContent(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    queueTag: String = "play_queue_inline",
) {
    val player = LocalAppContainer.current.player
    val playbackState by player.state.collectAsStateWithLifecycle()
    val queueItems by player.queue.collectAsStateWithLifecycle()

    val currentIndex = playbackState.queueIndex
    val queueSize = queueItems.size

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    LaunchedEffect(currentIndex) {
        if (currentIndex in queueItems.indices && !listState.isScrollInProgress) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = "播放队列", style = MaterialTheme.typography.titleLarge)
                if (queueSize > 0) {
                    Text(
                        text = "${currentIndex + 1}/$queueSize 首歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                IconButton(onClick = { player.setShuffle(!playbackState.shuffle) }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "随机播放",
                        tint = if (playbackState.shuffle) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (queueItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "播放队列为空",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.testTag(queueTag).fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(
                    items = queueItems,
                    key = { index, item -> "${index}_${item.id}" },
                ) { index, track ->
                    val isCurrentItem = index == currentIndex

                    QueueItem(
                        index = index + 1,
                        title = track.title,
                        artist = track.artist,
                        artworkUri = track.coverUrl,
                        isPlaying = isCurrentItem && playbackState.isPlaying,
                        isCurrent = isCurrentItem,
                        onClick = { player.skipTo(index) },
                        onDelete = { player.removeAt(index) },
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun QueueItem(
    index: Int,
    title: String,
    artist: String,
    artworkUri: String?,
    isPlaying: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(40.dp)
                    .background(
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                        },
                    ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "正在播放",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            BiliCoverThumb(
                url = artworkUri,
                modifier = Modifier.size(44.dp),
                cornerRadius = 8.dp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!isCurrent) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
        }
    }
}