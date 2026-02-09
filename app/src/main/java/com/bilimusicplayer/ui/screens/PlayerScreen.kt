package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.bilimusicplayer.BiliMusicApplication
import androidx.media3.common.Player
import com.bilimusicplayer.ui.components.PlayQueueSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(navController: NavController) {
    val playerController = BiliMusicApplication.musicPlayerController
    val playbackState by playerController.playbackState.collectAsState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showPlayQueue by remember { mutableStateOf(false) }

    // Track current position for smooth progress updates
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    // Update progress periodically
    LaunchedEffect(playbackState.isPlaying, playbackState.currentMediaItem) {
        while (true) {
            currentPosition = playerController.getCurrentPosition()
            duration = playerController.getDuration()
            kotlinx.coroutines.delay(100) // Update every 100ms for smooth progress
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("正在播放") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            showPlayQueue = true
                        }
                    ) {
                        Icon(Icons.Default.QueueMusic, contentDescription = "播放队列")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Album art
            Card(
                modifier = Modifier
                    .size(300.dp)
                    .padding(16.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (playbackState.currentMediaItem?.mediaMetadata?.artworkUri != null) {
                        AsyncImage(
                            model = playbackState.currentMediaItem?.mediaMetadata?.artworkUri,
                            contentDescription = "封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(100.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Song info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = playbackState.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未播放",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = playbackState.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Progress bar
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                val progress = if (duration > 0) {
                    currentPosition.toFloat() / duration.toFloat()
                } else 0f

                Slider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = { newValue ->
                        val newPosition = (newValue * duration).toLong()
                        playerController.seekTo(newPosition)
                        currentPosition = newPosition
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(currentPosition),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        formatTime(duration),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Playback controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        playerController.setShuffleMode(!playbackState.shuffleMode)
                    }
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "随机播放",
                        modifier = Modifier.size(32.dp),
                        tint = if (playbackState.shuffleMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                IconButton(
                    onClick = { playerController.skipToPrevious() }
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(40.dp)
                    )
                }

                FilledIconButton(
                    onClick = {
                        if (playbackState.isPlaying) {
                            playerController.pause()
                        } else {
                            playerController.play()
                        }
                    },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(40.dp)
                    )
                }

                IconButton(
                    onClick = { playerController.skipToNext() }
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(40.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val newMode = when (playbackState.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        playerController.setRepeatMode(newMode)
                    }
                ) {
                    Icon(
                        when (playbackState.repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        contentDescription = "循环播放",
                        modifier = Modifier.size(32.dp),
                        tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }

    // Play queue bottom sheet
    if (showPlayQueue) {
        PlayQueueSheet(
            sheetState = sheetState,
            onDismiss = {
                scope.launch {
                    sheetState.hide()
                    showPlayQueue = false
                }
            }
        )
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val seconds = (millis / 1000).toInt()
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%d:%02d", minutes, remainingSeconds)
}
