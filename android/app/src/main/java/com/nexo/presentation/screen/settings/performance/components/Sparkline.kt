package com.nexo.presentation.screen.settings.performance.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Stateless 72×24 sparkline. Renders a line path plus dot per sample.
 * Shared by [com.nexo.presentation.screen.settings.performance.TimingRow].
 */
@Composable
fun Sparkline(
    samples: List<Float>,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val points = remember(samples) { samples }
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val min = points.minOrNull() ?: 0f
        val max = points.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(1f)
        val w = size.width
        val h = size.height
        val stepX = if (points.size > 1) w / (points.size - 1) else w

        drawLine(
            color = outline.copy(alpha = 0.7f),
            start = Offset(0f, h),
            end = Offset(w, h),
            strokeWidth = 1.dp.toPx(),
        )

        val path =
            Path().apply {
                points.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = h - ((v - min) / range) * h * 0.85f - h * 0.07f
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
        drawPath(
            path = path,
            color = primary,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
        )
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - ((v - min) / range) * h * 0.85f - h * 0.07f
            drawCircle(color = primary, radius = 1.6.dp.toPx(), center = Offset(x, y))
        }
    }
}
