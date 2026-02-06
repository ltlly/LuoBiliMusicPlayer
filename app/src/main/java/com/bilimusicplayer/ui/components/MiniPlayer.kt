package com.bilimusicplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.bilimusicplayer.BiliMusicApplication
import com.bilimusicplayer.service.PlaybackState

/**
 * Mini player component shown at the bottom of screens
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerController = BiliMusicApplication.musicPlayerController
    val playbackState by playerController.playbackState.collectAsState()

    // Show mini player only when there's a current media item
    AnimatedVisibility(
        visible = playbackState.currentMediaItem != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand),
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album art
                Card(
                    modifier = Modifier.size(48.dp)
                ) {
                    if (playbackState.currentMediaItem?.mediaMetadata?.artworkUri != null) {
                        AsyncImage(
                            model = playbackState.currentMediaItem?.mediaMetadata?.artworkUri,
                            contentDescription = "封面",
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
                        text = playbackState.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未知歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = playbackState.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "未知艺术家",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Play/Pause button
                IconButton(
                    onClick = {
                        if (playbackState.isPlaying) {
                            playerController.pause()
                        } else {
                            playerController.play()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Skip to next button
                IconButton(
                    onClick = { playerController.skipToNext() }
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
