package com.bilimusicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.bilimusicplayer.BiliMusicApplication

/**
 * Bottom sheet showing the current play queue
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayQueueSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    val playerController = BiliMusicApplication.musicPlayerController
    val playbackState by playerController.playbackState.collectAsState()

    // Force refresh queue items periodically to show newly loaded songs
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000) // Refresh every 3 seconds (battery friendly)
            refreshTrigger++
        }
    }

    // Get all media items in queue - update when playback state changes or refresh trigger fires
    val queueItems = remember(playbackState, refreshTrigger) {
        playerController.getAllMediaItems()
    }
    // Reactive currentIndex — updates with playbackState and refreshTrigger
    val currentIndex = remember(playbackState, refreshTrigger) {
        playerController.getCurrentMediaItemIndex()
    }
    val queueSize = queueItems.size

    var showDeleteDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<Int?>(null) }

    // Delete confirmation dialog
    if (showDeleteDialog && itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除歌曲") },
            text = { Text("确定要从播放队列中删除这首歌曲吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        itemToDelete?.let { playerController.removeMediaItem(it) }
                        showDeleteDialog = false
                        itemToDelete = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    itemToDelete = null
                }) {
                    Text("取消")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "播放队列",
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (queueSize > 0) {
                        Text(
                            text = "${currentIndex + 1}/$queueSize 首歌曲",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = {
                            playerController.setShuffleMode(!playbackState.shuffleMode)
                        }
                    ) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "随机播放",
                            tint = if (playbackState.shuffleMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Queue list
            if (queueItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "播放队列为空",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    itemsIndexed(
                        items = queueItems,
                        key = { index, item -> "${index}_${item.mediaId}" }
                    ) { index, mediaItem ->
                        val isCurrentItem = index == currentIndex

                        QueueItem(
                            index = index + 1,
                            title = mediaItem.mediaMetadata.title?.toString() ?: "未知歌曲",
                            artist = mediaItem.mediaMetadata.artist?.toString() ?: "未知艺术家",
                            artworkUri = mediaItem.mediaMetadata.artworkUri?.toString(),
                            isPlaying = isCurrentItem && playbackState.isPlaying,
                            isCurrent = isCurrentItem,
                            onClick = {
                                playerController.skipToMediaItem(index)
                            },
                            onDelete = {
                                itemToDelete = index
                                showDeleteDialog = true
                            }
                        )
                    }

                    // Bottom spacer
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
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
    onDelete: () -> Unit
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
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored accent bar marking the currently playing track
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                        }
                    )
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Index number or playing indicator
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "正在播放",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Album art: 统一缩略图组件（artworkUri 已是完整 URL，走 CDN 小图 + 共享缓存）
            BiliCoverThumb(
                url = artworkUri?.toString(),
                modifier = Modifier.size(44.dp),
                cornerRadius = 8.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Song info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Delete button (hidden for the currently playing track)
            if (!isCurrent) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Drag handle affordance for manual reordering
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "拖拽排序",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(20.dp)
            )
        }
    }
}
