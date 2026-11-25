package com.nexo.ui.components.molecules.reader.settings.sections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.ReaderSettings
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Side margin steps, in the engine's px-like units; index 2 is the default 16. */
internal val SIDE_MARGIN_STEPS = listOf(4, 8, 16, 24, 32, 44)

/** Top/bottom margin steps in dp; index 0 (none) is the default. */
internal val VERTICAL_MARGIN_STEPS = listOf(0, 12, 24, 40, 56, 72)

/** Index of the step closest to [value], so margins saved before these steps existed still land on one. */
internal fun nearestStep(
    steps: List<Int>,
    value: Int,
): Int = steps.indices.minBy { abs(steps[it] - value) }

/**
 * Side and top/bottom margins, each on its own stepped slider, next to a small
 * page drawn in the current theme that shows both margins as they change. The
 * book above the panel shows the real result at the same time.
 */
@Composable
fun MarginControls(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = settings.layoutPrefs
    val sideIndex = nearestStep(SIDE_MARGIN_STEPS, (layout.leftMargin + layout.rightMargin) / 2)
    val verticalIndex = nearestStep(VERTICAL_MARGIN_STEPS, layout.verticalMargin)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ReaderSheetColors.SurfaceRaised)
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarginDiagram(
            sideFraction = sideIndex / SIDE_MARGIN_STEPS.lastIndex.toFloat(),
            verticalFraction = verticalIndex / VERTICAL_MARGIN_STEPS.lastIndex.toFloat(),
            pageColor = hexToColor(settings.theme.bgHex),
            inkColor = hexToColor(settings.theme.textHex),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarginSlider(
                label = stringResource(R.string.reader_margins_sides),
                index = sideIndex,
                stepCount = SIDE_MARGIN_STEPS.size,
                onIndexChange = { i ->
                    val px = SIDE_MARGIN_STEPS[i]
                    onSettingsChanged(settings.copy(layoutPrefs = layout.copy(leftMargin = px, rightMargin = px)))
                },
            )
            MarginSlider(
                label = stringResource(R.string.reader_margins_vertical),
                index = verticalIndex,
                stepCount = VERTICAL_MARGIN_STEPS.size,
                onIndexChange = { i ->
                    onSettingsChanged(settings.copy(layoutPrefs = layout.copy(verticalMargin = VERTICAL_MARGIN_STEPS[i])))
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarginSlider(
    label: String,
    index: Int,
    stepCount: Int,
    onIndexChange: (Int) -> Unit,
) {
    val level = stringResource(R.string.reader_margin_level, index + 1, stepCount)
    val interactionSource = remember { MutableInteractionSource() }
    val colors =
        SliderDefaults.colors(
            thumbColor = ReaderSheetColors.Accent,
            activeTrackColor = ReaderSheetColors.Accent,
            inactiveTrackColor = ReaderSheetColors.Outline,
            activeTickColor = ReaderSheetColors.Surface,
            inactiveTickColor = ReaderSheetColors.TextSecondary,
        )
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ReaderSheetColors.TextPrimary,
            modifier = Modifier.padding(start = 8.dp),
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { value ->
                val next = value.roundToInt().coerceIn(0, stepCount - 1)
                // The slider reports every frame of a drag; only a new step is a change.
                if (next != index) onIndexChange(next)
            },
            valueRange = 0f..(stepCount - 1).toFloat(),
            steps = stepCount - 2,
            colors = colors,
            interactionSource = interactionSource,
            // Round thumb, like the size dots above: Material's default bar thumb
            // reads as a divider on a stepped track this short.
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = colors,
                    thumbSize = DpSize(20.dp, 20.dp),
                )
            },
            modifier =
                Modifier.semantics {
                    contentDescription = label
                    stateDescription = level
                },
        )
    }
}

/** A page in the reader's theme whose text block shrinks with the margins. */
@Composable
private fun MarginDiagram(
    sideFraction: Float,
    verticalFraction: Float,
    pageColor: Color,
    inkColor: Color,
) {
    val side by animateFloatAsState(sideFraction, label = "marginDiagramSide")
    val vertical by animateFloatAsState(verticalFraction, label = "marginDiagramVertical")
    Canvas(modifier = Modifier.size(width = 68.dp, height = 92.dp)) {
        val corner = CornerRadius(8.dp.toPx())
        drawRoundRect(color = pageColor, cornerRadius = corner)
        drawRoundRect(
            color = ReaderSheetColors.Outline,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )
        val insetX = lerp(5.dp.toPx(), 18.dp.toPx(), side)
        val insetY = lerp(7.dp.toPx(), 24.dp.toPx(), vertical)
        val stroke = 2.4.dp.toPx()
        val gap = 6.dp.toPx()
        var y = insetY + stroke / 2
        var line = 0
        while (y <= size.height - insetY - stroke / 2) {
            // Ragged last-line rhythm so the block reads as text, not stripes.
            val end = if (line % 4 == 3) lerp(insetX, size.width - insetX, 0.6f) else size.width - insetX
            drawLine(
                color = inkColor.copy(alpha = 0.55f),
                start = Offset(insetX, y),
                end = Offset(end, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            y += gap
            line++
        }
        // Margin guides: where the text block starts and ends.
        drawRect(
            color = ReaderSheetColors.Accent.copy(alpha = 0.18f),
            topLeft = Offset(insetX - stroke, insetY - stroke),
            size = Size(size.width - 2 * insetX + 2 * stroke, size.height - 2 * insetY + 2 * stroke),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private fun lerp(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + (stop - start) * fraction

private fun hexToColor(hex: String): Color =
    runCatching {
        val clean = hex.removePrefix("#")
        Color((if (clean.length == 6) "FF$clean" else clean).toLong(16))
    }.getOrDefault(Color.Gray)
