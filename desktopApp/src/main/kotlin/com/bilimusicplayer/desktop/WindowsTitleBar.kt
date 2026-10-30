package com.bilimusicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point

/**
 * Windows/Linux 无边框窗口的自定义标题栏：品牌名 + 拖拽移动 + 双击最大化 +
 * 最小化/最大化/关闭按钮，外观与 MacTitleBar 一致。
 */
@Composable
fun WindowsTitleBar(window: ComposeWindow, onClose: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = surface.luminance() < 0.5f

    // Follow the real screen pointer like MacTitleBar — see its comment.
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
        detectTapGestures(onDoubleTap = { toggleMaximizeWin(window) })
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .then(dragModifier)
            .then(doubleClickModifier)
            .background(
                Brush.verticalGradient(
                    listOf(
                        surface.copy(alpha = if (dark) 0.86f else 0.80f),
                        surface.copy(alpha = if (dark) 0.60f else 0.55f),
                    ),
                ),
            )
            .drawBehind {
                val shadow = if (dark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.12f)
                drawLine(shadow, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
            }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logo：蓝色音符方块
        val accent = MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.linearGradient(
                        listOf(accent, accent.copy(alpha = 0.65f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("♪", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "BiliMusicPlayer",
            color = onSurface.copy(alpha = if (dark) 0.70f else 0.55f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 8.dp),
        )

        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

        // 窗口控制按钮
        TitleButton("-", onSurface, dark) { window.extendedState = Frame.ICONIFIED }
        TitleButton("□", onSurface, dark) { toggleMaximizeWin(window) }
        TitleButton("✕", onSurface, dark) { onClose() }
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun TitleButton(glyph: String, onSurface: Color, dark: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(32.dp, 28.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .drawBehind {
                drawRect(onSurface.copy(alpha = if (dark) 0.0f else 0.0f))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = onSurface.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

private fun toggleMaximizeWin(window: ComposeWindow) {
    window.extendedState = if (window.extendedState and Frame.MAXIMIZED_BOTH != 0) {
        Frame.NORMAL
    } else {
        Frame.MAXIMIZED_BOTH
    }
}
