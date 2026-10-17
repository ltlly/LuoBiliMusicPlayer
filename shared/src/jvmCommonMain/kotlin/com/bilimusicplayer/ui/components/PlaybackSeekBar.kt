package com.bilimusicplayer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A finger owns the displayed position until release; player updates cannot reset the drag. */
@Composable
fun PlaybackSeekBar(position: Long, duration: Long, playing: Boolean, trackId: String?, onSeek: (Long) -> Unit) {
    var dragged by remember(trackId) { mutableStateOf<Float?>(null) }
    var dragDuration by remember(trackId) { mutableStateOf(0L) }
    var pending by remember(trackId) { mutableStateOf<Long?>(null) }
    val latestSeek by rememberUpdatedState(onSeek)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(fraction, tween(if (playing) 200 else 0, easing = LinearEasing), label = "seek_progress")
    LaunchedEffect(position, pending) {
        if (pending != null && kotlin.math.abs(position - pending!!) < 1500) pending = null
    }
    LaunchedEffect(pending) {
        if (pending != null) { kotlinx.coroutines.delay(2000); pending = null }
    }
    val displayed = dragged ?: pending?.let { if (duration > 0) (it.toFloat() / duration).coerceIn(0f, 1f) else 0f } ?: animated
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = displayed,
            onValueChange = {
                if (dragged == null) dragDuration = duration
                dragged = it
            },
            onValueChangeFinished = {
                dragged?.let {
                    val target = (it * dragDuration).toLong().coerceIn(0, dragDuration.coerceAtLeast(0))
                    pending = target
                    latestSeek(target)
                }
                dragged = null
            },
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player_seek")
                .semantics { contentDescription = "播放进度" }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(dragged?.let { (it * dragDuration).toLong() } ?: pending ?: position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(time(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
private fun time(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
