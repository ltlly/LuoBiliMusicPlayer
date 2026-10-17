package com.bilimusicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.awt.MouseInfo
import java.awt.Point

/**
 * Themed glass strip under macOS's transparent native title bar. The native
 * traffic lights and resize behaviour are kept, so the window is always
 * visible and focusable.
 */
@Composable
fun MacTitleBar(window: ComposeWindow) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = surface.luminance() < 0.5f

    // Compose reports deltas in pixels and a moving window also moves the local
    // pointer, so accumulating deltas makes the window run away. Follow the real
    // screen pointer instead; AWT screen coordinates are already in points.
    val dragModifier = Modifier.pointerInput(window) {
        var grabOffset: Point? = null
        detectDragGestures(
            onDragStart = {
                grabOffset = MouseInfo.getPointerInfo()?.location?.let { pointer ->
                    Point(pointer.x - window.location.x, pointer.y - window.location.y)
                }
            },
            onDragEnd = { grabOffset = null },
            onDragCancel = { grabOffset = null },
            onDrag = { change, _ ->
                change.consume()
                val offset = grabOffset
                val pointer = MouseInfo.getPointerInfo()?.location
                if (offset != null && pointer != null) {
                    window.location = Point(pointer.x - offset.x, pointer.y - offset.y)
                }
            },
        )
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
                        surface.copy(alpha = if (dark) 0.82f else 0.76f),
                        surface.copy(alpha = if (dark) 0.55f else 0.50f),
                    ),
                ),
            )
            .drawBehind {
                val highlight = if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.60f)
                val shadow = if (dark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.10f)
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

private fun toggleMaximize(window: ComposeWindow) {
    window.extendedState = if (window.extendedState and Frame.MAXIMIZED_BOTH != 0) {
        Frame.NORMAL
    } else {
        Frame.MAXIMIZED_BOTH
    }
}