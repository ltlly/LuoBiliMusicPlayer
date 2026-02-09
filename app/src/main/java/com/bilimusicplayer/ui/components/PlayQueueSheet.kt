package com.bilimusicplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "播放队列",
                    style = MaterialTheme.typography.titleLarge
                )
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

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Current playing
            if (playbackState.currentMediaItem != null) {
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                QueueItem(
                    title = playbackState.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未知歌曲",
                    artist = playbackState.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "未知艺术家",
                    artworkUri = playbackState.currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
                    isPlaying = playbackState.isPlaying,
                    onClick = { }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Queue info message
            Text(
                text = "播放队列功能将在后续版本中完善",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun QueueItem(
    title: String,
    artist: String,
    artworkUri: String?,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = if (isPlaying) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album art
            Card(
                modifier = Modifier.size(48.dp)
            ) {
                if (artworkUri != null) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Song info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Playing indicator
            if (isPlaying) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "正在播放",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
