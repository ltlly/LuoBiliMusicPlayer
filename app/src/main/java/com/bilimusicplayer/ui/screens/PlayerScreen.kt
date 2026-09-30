package com.bilimusicplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.bilimusicplayer.BiliMusicApplication
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.service.PlaybackState
import com.bilimusicplayer.service.download.DownloadedAudioInfo
import com.bilimusicplayer.ui.components.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(navController: NavController) {
    val context = LocalContext.current
    val player = BiliMusicApplication.musicPlayerController
    val state by player.playbackState.collectAsStateWithLifecycle()
    val queue by player.queue.collectAsStateWithLifecycle()
    val foldingFeature = rememberFoldingFeature()
    val sheetState = rememberModalBottomSheetState()
    var showQueue by remember { mutableStateOf(false) }
    var sourceLabel by remember { mutableStateOf("在线播放") }
    var localAudioPath by remember { mutableStateOf<String?>(null) }
    val qualityInfo = remember(context) { DownloadedAudioInfo(context) }
    val qualityLabel by remember(localAudioPath) { qualityInfo.observeLabel(localAudioPath) }.collectAsStateWithLifecycle(initialValue = null)
    val artworkUrl = state.currentMediaItem?.mediaMetadata?.artworkUri?.toString()

    LaunchedEffect(state.currentMediaItem?.mediaId, state.currentMediaItem?.localConfiguration?.uri) {
        val media = state.currentMediaItem
        val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val uri = media?.localConfiguration?.uri
            val direct = uri?.takeIf { it.scheme == "file" }?.path
            if (direct != null && File(direct).exists()) direct
            else media?.mediaId?.let { AppDatabase.getDatabase(context).songDao().getSongById(it) }
                ?.takeIf { it.isDownloaded }?.localPath?.takeIf { File(it).exists() }
        }
        sourceLabel = if (path == null) "在线播放" else "本地播放"
        localAudioPath = path
    }

    // A simple gradient replaces two full-screen bitmaps and a live 80dp blur layer.
    val backdrop by animateColorAsState(MaterialTheme.colorScheme.primaryContainer, tween(220), label = "backdrop")
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            backdrop.copy(alpha = 0.65f), MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background
        ))).statusBarsPadding().navigationBarsPadding()
    ) {
        val wide = maxWidth >= 600.dp && maxHeight >= 560.dp && foldingFeature == null
        Column(Modifier.fillMaxSize().padding(horizontal = if (maxWidth >= 600.dp) 24.dp else 20.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.Default.KeyboardArrowDown, "收起播放器", Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("正在播放", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (queue.isEmpty()) "选择一首喜欢的音乐" else "${player.getCurrentMediaItemIndex() + 1} / ${queue.size}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列")
                }
            }
            FoldAwarePlayerLayout(
                modifier = Modifier.fillMaxWidth().weight(1f),
                foldingFeature = foldingFeature,
                artwork = {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth * 0.92f, maxHeight * 0.92f, 440.dp)
                        Surface(
                            Modifier.size(side), shape = RoundedCornerShape(28.dp),
                            shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surfaceVariant
                        ) { BiliCoverLarge(artworkUrl, Modifier.fillMaxSize()) }
                    }
                },
                controls = {
                    val controlScroll = rememberScrollState()
                    Column(
                        Modifier.widthIn(max = 480.dp).fillMaxSize()
                            .then(if (wide) Modifier else Modifier.verticalScroll(controlScroll))
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        PlayerDetails(state, sourceLabel, qualityLabel)
                        Spacer(Modifier.height(12.dp))
                        PlayerSeekBar(state)
                        Spacer(Modifier.height(8.dp))
                        PlayerTransport(state)
                        if (wide) {
                            Spacer(Modifier.height(16.dp))
                            Surface(
                                Modifier.fillMaxWidth().weight(1f),
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                            ) { PlayQueueContent(Modifier.fillMaxSize().padding(top = 12.dp)) }
                        }
                    }
                }
            )
        }
    }
    if (showQueue) PlayQueueSheet(sheetState, onDismiss = { showQueue = false })
}

@Composable
private fun PlayerDetails(state: PlaybackState, source: String, quality: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            state.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未播放",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Text(
            state.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty(),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)) {
            Text(
                listOfNotNull(source, quality).joinToString(" · "),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        state.errorMessage?.let {
            Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/** Progress recomposes this small subtree; seeking happens once when the finger lifts. */
@Composable
private fun PlayerSeekBar(state: PlaybackState) {
    val progress by rememberPlaybackProgress(state)
    val player = BiliMusicApplication.musicPlayerController
    PlaybackSeekBar(progress.position, progress.duration, state.isPlaying, state.currentMediaItem?.mediaId, player::seekTo)
}

@Composable
private fun PlayerTransport(state: PlaybackState) {
    val player = BiliMusicApplication.musicPlayerController
    val haptic = LocalHapticFeedback.current
    fun click(action: () -> Unit) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); action() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { click { player.setShuffleMode(!state.shuffleMode) } }) {
            Icon(Icons.Default.Shuffle, if (state.shuffleMode) "关闭随机播放" else "开启随机播放",
                tint = if (state.shuffleMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { click { player.skipToPrevious() } }) {
            Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(32.dp))
        }
        FilledIconButton(
            onClick = { click { if (state.isPlaying) player.pause() else player.play() } },
            modifier = Modifier.size(72.dp), shape = CircleShape
        ) {
            if (state.playbackState == Player.STATE_BUFFERING) {
                CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "暂停" else "播放", Modifier.size(38.dp))
            }
        }
        IconButton(onClick = { click { player.skipToNext() } }) {
            Icon(Icons.Default.SkipNext, "下一首", Modifier.size(32.dp))
        }
        IconButton(onClick = { click {
            player.setRepeatMode(when (state.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            })
        } }) {
            Icon(if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                when (state.repeatMode) { Player.REPEAT_MODE_ONE -> "单曲循环"; Player.REPEAT_MODE_ALL -> "列表循环"; else -> "循环已关闭" },
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
