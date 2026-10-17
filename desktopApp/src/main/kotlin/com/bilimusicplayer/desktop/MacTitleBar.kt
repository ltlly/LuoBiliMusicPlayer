package com.bilimusicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Frame
import java.awt.Point

/**
 * Borderless macOS chrome. The window is per-pixel transparent, so this strip
 * blends with the desktop behind it; the rest of the app paints an opaque
 * surface. Traffic lights are drawn here because an undecorated window has no
 * native ones.
 */
@Composable
fun MacTitleBar(window: ComposeWindow, onClose: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = surface.luminance() < 0.5f

    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            window.location = Point(
                window.location.x + dragAmount.x.toInt(),
                window.location.y + dragAmount.y.toInt(),
            )
        }
    }
    val doubleClickModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { toggleMaximize(window) })
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(dragModifier)
            .then(doubleClickModifier)
            .background(
                Brush.verticalGradient(
                    listOf(
                        surface.copy(alpha = if (dark) 0.80f else 0.74f),
                        surface.copy(alpha = if (dark) 0.52f else 0.46f),
                    ),
                ),
            )
            .drawBehind {
                val highlight = if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.60f)
                val shadow = if (dark) Color.Black.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.12f)
                drawLine(highlight, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
                drawLine(shadow, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.45f), Color.Transparent),
                        center = Offset(size.width * 0.22f, 0f),
                        radius = size.width * 0.65f,
                    ),
                )
            },
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrafficLight(Color(0xFFFF5F57), onClick = onClose)
            TrafficLight(Color(0xFFFEBC2E)) { window.extendedState = window.extendedState or Frame.ICONIFIED }
            TrafficLight(Color(0xFF28C840)) { toggleMaximize(window) }
        }

        Text(
            text = "BiliMusicPlayer",
            color = onSurface.copy(alpha = if (dark) 0.62f else 0.50f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
            modifier = Modifier.align(Alignment.Center).padding(start = 78.dp),
        )
    }
}

@Composable
private fun TrafficLight(color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
            .border(0.5.dp, Color.Black.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
    )
}

private fun toggleMaximize(window: ComposeWindow) {
    window.extendedState = if (window.extendedState and Frame.MAXIMIZED_BOTH != 0) {
        Frame.NORMAL
    } else {
        Frame.MAXIMIZED_BOTH
    }
}