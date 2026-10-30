package com.bilimusicplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bilimusicplayer.platform.FoldInfo

/** The same two composables survive window resizing; controls never sit on a hinge. */
@Composable
fun FoldAwarePlayerLayout(
    modifier: Modifier = Modifier,
    foldingFeature: FoldInfo? = null,
    artwork: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        modifier = modifier.onGloballyPositioned { origin = it.positionInWindow() },
        content = { Box { artwork() }; Box { controls() } },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = 24.dp.roundToPx()
        val fold = foldingFeature
        val horizontal = fold?.horizontal == true
        val foldTop = fold?.top?.minus(origin.y.toInt()) ?: 0
        val foldBottom = fold?.bottom?.minus(origin.y.toInt()) ?: 0
        val foldLeft = fold?.left?.minus(origin.x.toInt()) ?: 0
        val foldRight = fold?.right?.minus(origin.x.toInt()) ?: 0
        val tabletop = horizontal && foldTop > 100.dp.roundToPx() && foldBottom < height - 180.dp.roundToPx()
        val verticalFold = !horizontal && fold != null && foldLeft > 100.dp.roundToPx() && foldRight < width - 180.dp.roundToPx()
        val wide = width >= 600.dp.roundToPx() && height >= 280.dp.roundToPx()

        val coverWidth: Int
        val coverHeight: Int
        val controlWidth: Int
        val controlHeight: Int
        val controlX: Int
        val controlY: Int
        when {
            tabletop -> {
                coverWidth = width
                coverHeight = (foldTop - gap / 2).coerceAtLeast(0)
                controlX = 0
                controlY = (foldBottom + gap / 2).coerceAtMost(height)
                controlWidth = width
                controlHeight = height - controlY
            }
            wide || verticalFold -> {
                coverWidth = if (verticalFold) foldLeft - gap / 2 else (width - gap) / 2
                coverHeight = height
                controlX = if (verticalFold) foldRight + gap / 2 else coverWidth + gap
                controlY = 0
                controlWidth = width - controlX
                controlHeight = height
            }
            else -> {
                coverWidth = width
                controlHeight = minOf(height, 320.dp.roundToPx())
                coverHeight = (height - controlHeight - gap).coerceAtLeast(0)
                controlX = 0
                controlY = coverHeight + minOf(gap, height - controlHeight)
                controlWidth = width
            }
        }
        val cover = children[0].measure(Constraints.fixed(coverWidth.coerceAtLeast(0), coverHeight))
        val details = children[1].measure(Constraints.fixed(controlWidth.coerceAtLeast(0), controlHeight.coerceAtLeast(0)))
        layout(width, height) {
            cover.placeRelative(0, 0)
            details.placeRelative(controlX, controlY)
        }
    }
}
