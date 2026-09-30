package com.bilimusicplayer.ui.components

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bilimusicplayer.BiliMusicApplication
import com.bilimusicplayer.service.PlaybackState
import kotlinx.coroutines.delay

data class PlaybackProgress(val position: Long = 0, val duration: Long = 0) {
    val fraction: Float
        get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** No polling while paused, hidden, or in the background. Read state in the smallest UI scope. */
@Composable
fun rememberPlaybackProgress(playbackState: PlaybackState): State<PlaybackProgress> {
    val owner = LocalLifecycleOwner.current
    val progress = remember { mutableStateOf(PlaybackProgress()) }
    LaunchedEffect(owner, playbackState.isPlaying, playbackState.currentMediaItem,
        playbackState.currentPosition, playbackState.duration) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val controller = BiliMusicApplication.musicPlayerController
                progress.value = PlaybackProgress(controller.getCurrentPosition(), controller.getDuration().coerceAtLeast(0))
                if (playbackState.isPlaying) delay(250)
            } while (playbackState.isPlaying)
        }
    }
    return progress
}
