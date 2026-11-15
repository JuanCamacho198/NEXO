package com.nexo.presentation.feature.highlights.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nexo.presentation.feature.highlights.utils.parseColorHex
import com.nexo.presentation.feature.highlights.utils.resolveFilterHex

@Composable
fun ColorFilterCircle(
    filterValue: String?,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    // Swatches sit on the dark sheet surface (#161F33). A dark border
    // disappears against it and hides the darker swatches (red, blue), so the
    // resting ring is a light hairline; selection thickens it to near-white.
    val borderColor = if (isSelected) Color(0xFFDDE2F8) else Color(0xFF718096)
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (filterValue == null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke =
                    Stroke(
                        width = borderWidth.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f),
                    )
                drawCircle(
                    color = borderColor,
                    radius = size.minDimension / 2f - borderWidth.toPx() / 2f,
                    style = stroke,
                )
            }
        } else {
            val fillColor = parseColorHex(resolveFilterHex(filterValue))
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(fillColor)
                        .border(width = borderWidth, color = borderColor, shape = CircleShape),
            )
        }
    }
}
