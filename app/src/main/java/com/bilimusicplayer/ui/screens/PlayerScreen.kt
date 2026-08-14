package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.bilimusicplayer.BiliMusicApplication
import androidx.media3.common.Player
import com.bilimusicplayer.ui.components.PlayQueueSheet
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Song
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(navController: NavController) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val playerController = BiliMusicApplication.musicPlayerController
    val playbackState by playerController.playbackState.collectAsState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showPlayQueue by remember { mutableStateOf(false) }

    // Track current position for smooth progress updates
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    // Track download/cache status
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isDownloaded by remember { mutableStateOf(false) }
    var isCached by remember { mutableStateOf(false) }

    // Queue position (e.g. "3/15") — recomputed whenever playback state changes
    val queueIndex = remember(playbackState) { playerController.getCurrentMediaItemIndex() }
    val queueSize = remember(playbackState) { playerController.getMediaItemCount() }

    // Check if song is downloaded or cached
    LaunchedEffect(playbackState.currentMediaItem) {
        val mediaItem = playbackState.currentMediaItem ?: run {
            isDownloaded = false
            isCached = false
            return@LaunchedEffect
        }
        val mediaUri = mediaItem.requestMetadata.mediaUri?.toString() ?: ""
        val mediaId = mediaItem.mediaId  // bvid

        // Quick check by URI scheme
        when {
            mediaUri.startsWith("file://") || mediaUri.startsWith("/") -> {
                isDownloaded = true
                isCached = false
            }
            mediaUri.startsWith("bili://") -> {
                // Lazy-resolve placeholder = online
                isDownloaded = false
                isCached = false
            }
            mediaUri.startsWith("http") -> {
                isDownloaded = false
                isCached = true
            }
            else -> {
                isDownloaded = false
                isCached = false
            }
        }

        // Look up song by mediaId (bvid) — O(1) DB query instead of loading all songs
        if (mediaId.isNotEmpty() && !mediaId.startsWith("local_")) {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.songDao().getSongById(mediaId)
            }
            currentSong = found
            if (found != null && found.isDownloaded && found.localPath != null) {
                isDownloaded = File(found.localPath).exists()
            }
        } else {
            currentSong = null
        }
    }

    // Update progress periodically (skip frequent polling when paused)
    LaunchedEffect(playbackState.isPlaying, playbackState.currentMediaItem) {
        while (true) {
            currentPosition = playerController.getCurrentPosition()
            duration = playerController.getDuration()
            kotlinx.coroutines.delay(if (playbackState.isPlaying) 100 else 500)
        }
    }

    val artworkUri = playbackState.currentMediaItem?.mediaMetadata?.artworkUri

    // Immersive background: cover art blurred into a full-screen backdrop,
    // tinted with a scrim so foreground content stays legible.
    // (Modifier.blur is a graceful no-op below API 31.)
    Box(modifier = Modifier.fillMaxSize()) {
        if (artworkUri != null) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(80.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { navController.navigateUp() }
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "正在播放",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (queueSize > 0) {
                        Text(
                            text = "${queueIndex + 1}/$queueSize",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                IconButton(
                    onClick = { showPlayQueue = true }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "播放队列",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 封面区域 — 动态尺寸：边长 = min(宽度 × 0.86, 本区域剩余高度)。
            // 在接近 4:3 的屏幕（如折叠屏展开态）上，高度预算不足时封面自动缩小，
            // 保证下方歌曲信息、进度条和播放/上一首/下一首/随机按钮始终可见可点。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints {
                    val coverSide = minOf(maxWidth * 0.86f, maxHeight)
                    Card(
                        modifier = Modifier
                            .size(coverSide)
                            .shadow(
                                elevation = 32.dp,
                                shape = RoundedCornerShape(32.dp),
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            ),
                        shape = RoundedCornerShape(32.dp),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
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
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(coverSide * 0.3f),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Song info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = playbackState.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未播放",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = playbackState.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Download/Cache status chip
                if (isDownloaded || isCached) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDownloaded) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = if (isDownloaded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isDownloaded) "本地播放" else "在线播放",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isDownloaded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Slim progress slider — thin 3dp track, compact 12dp thumb
            Column(modifier = Modifier.fillMaxWidth()) {
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
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    },
                    track = { sliderState ->
                        val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                        val fraction = if (range > 0f) {
                            ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
                        } else 0f
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-6).dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(currentPosition),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatTime(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback controls — dominant play/pause, quieter skip buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle button
                IconButton(
                    onClick = {
                        playerController.setShuffleMode(!playbackState.shuffleMode)
                    }
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "随机播放",
                        modifier = Modifier.size(24.dp),
                        tint = if (playbackState.shuffleMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // Previous button
                IconButton(
                    onClick = { playerController.skipToPrevious() }
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                // Main play/pause button — static, no pulse animation
                FilledIconButton(
                    onClick = {
                        if (playbackState.isPlaying) {
                            playerController.pause()
                        } else {
                            playerController.play()
                        }
                    },
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(44.dp)
                    )
                }

                // Next button
                IconButton(
                    onClick = { playerController.skipToNext() }
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                // Repeat button
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
                        modifier = Modifier.size(24.dp),
                        tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
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
