package com.bilimusicplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bilimusicplayer.core.LocalAppContainer
import com.bilimusicplayer.player.PlaybackState
import kotlinx.coroutines.delay

data class PlaybackProgress(val position: Long = 0, val duration: Long = 0) {
    val fraction: Float
        get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** No polling while paused, hidden, or in the background. Read state in the smallest UI scope. */
@Composable
fun rememberPlaybackProgress(playbackState: PlaybackState): State<PlaybackProgress> {
    val player = LocalAppContainer.current.player
    val owner = LocalLifecycleOwner.current
    val progress = remember { mutableStateOf(PlaybackProgress()) }
    LaunchedEffect(owner, playbackState.isPlaying, playbackState.current?.id, playbackState.positionMs, playbackState.durationMs) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val state = player.state.value
                progress.value = PlaybackProgress(state.positionMs, state.durationMs.coerceAtLeast(0))
                if (state.isPlaying) delay(250)
            } while (state.isPlaying)
        }
    }
    return progress
}
