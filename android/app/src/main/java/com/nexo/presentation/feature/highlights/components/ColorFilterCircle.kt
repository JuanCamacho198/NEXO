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

@Composable
fun ColorFilterCircle(
    filterValue: String?,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) Color.White else Color(0xFF4A5568)
    val borderWidth = if (isSelected) 1.5.dp else 1.dp

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
            val fillColor = parseColorHex(filterValue)
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
