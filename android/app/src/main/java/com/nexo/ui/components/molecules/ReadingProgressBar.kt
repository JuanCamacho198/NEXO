package com.nexo.ui.components.molecules

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Reader's progress bar with a draggable thumb and a row of
 * supporting controls below. Used inside the reader screen to show
 * the user where they are in the book and let them jump to any
 * position.
 *
 * Design (dark theme):
 * - Track: `#2F3445`, 4dp height, full width.
 * - Fill: `#ADC6FF`, 4dp height, animated width.
 * - Thumb: 12dp circle, white fill with 2dp `#ADC6FF` border and 4dp
 *   shadow, draggable — contrasted against the fill so it reads as a
 *   handle, not a same-color dot.
 * - Below: [Rotate icon] — position label centered — "NN%" right.
 * - Outer padding: 16/20/32/20 (start/end/bottom/top).
 *
 * @param progressPercent Current progress in `[0f, 100f]`. Values
 *   outside this range are `coerceIn`-clamped.
 * @param label Position label shown centered below the bar (e.g.
 *   `"3 / 10"` or `"45 / 200"`). When empty, the
 *   `R.string.reader_progress_empty` fallback is shown.
 * @param onProgressChange Optional callback invoked (with the new
 *   percentage in `[0f, 100f]`) while the user drags the thumb.
 *   When `null`, the thumb still renders but does not respond to
 *   drag gestures.
 * @param onRotateScreen Optional callback for the rotate-screen
 *   button on the left of the label row. When `null`, the button
 *   is replaced by an invisible 36dp spacer to keep the label
 *   centered.
 * @param modifier Modifier applied to the outer `Column`.
 *
 * **Visual**: 12dp-tall track container (4dp visible bar centered
 *   inside for a 4dp touch slop on each side). Fill is animated via
 *   `animateFloatAsState` for smooth transitions. Below: 36dp icon
 *   button or spacer, `labelMedium` position label
 *   (`#718096`), `labelMedium` bold percentage (`#ADC6FF`).
 * **Behavior**: drag anywhere on the 12dp-tall track (or the thumb) →
 *   invokes [onProgressChange] on every pointer-move with the new
 *   percentage, clamped to `[0f, 100f]`. The drag works in BOTH
 *   directions (left and right) — `change.position.x` is measured in
 *   the track's local coordinate space (0..trackWidth) because the
 *   `pointerInput` is attached to the outer track Box, not the thumb.
 *   Tap the rotate icon → [onRotateScreen]. The drag is wired via
 *   `pointerInput(Unit)`; if [onProgressChange] is `null`, the drag
 *   handler is wired but does nothing.
 * **Recomposition**: recomposes when `progressPercent`, `label`, or
 *   callbacks change. `trackWidth` is updated via `onSizeChanged`
 *   after layout.
 */
@Composable
fun ReadingProgressBar(
    progressPercent: Float,
    label: String,
    onProgressChange: ((Float) -> Unit)? = null,
    onRotateScreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progressPercent.coerceIn(0f, 100f)
    val progressFraction = clampedProgress / 100f

    // Animate the fill smoothly
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        label = "progressFill",
    )

    var trackWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp, top = 20.dp),
    ) {
        // ── Progress bar track + thumb ─────────────────────────────
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(12.dp) // extra height for touch target
                    .onSizeChanged { trackWidth = it.width }
                    .pointerInput(Unit) {
                        // Drag handler is wired on the OUTER track Box (not the
                        // thumb) so that `change.position.x` is measured in the
                        // track's local coordinate space (0..trackWidth). This
                        // makes the drag work in BOTH directions, anywhere on
                        // the track, and the formula below is correct.
                        //
                        // detectDragGestures's onDragStart captures the initial
                        // touch position; without it the first drag delta is
                        // relative to an unknown origin and the thumb jumps when
                        // dragging backwards. We track the last reported fraction
                        // and update it from the ABSOLUTE pointer x each move so
                        // the thumb follows the finger exactly in both directions.
                        if (onProgressChange != null) {
                            var dragFraction by androidx.compose.runtime.mutableFloatStateOf(-1f)
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragFraction =
                                        if (trackWidth > 0) {
                                            (offset.x / trackWidth).coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        }
                                    onProgressChange(dragFraction * 100f)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (trackWidth <= 0) return@detectDragGestures
                                    val newFraction =
                                        (change.position.x / trackWidth).coerceIn(0f, 1f)
                                    dragFraction = newFraction
                                    onProgressChange(newFraction * 100f)
                                },
                            )
                        }
                    },
        ) {
            // Track background
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF2F3445)),
            )

            // Fill
            if (trackWidth > 0) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(animatedProgress)
                            .height(4.dp)
                            .align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFADC6FF)),
                )
            }

            // Draggable thumb (visual only — drag is handled by the outer
            // track's `pointerInput` above so the entire track is a
            // touch target, not just the 12dp thumb circle). White fill
            // with a bar-color border so it reads as a handle instead of
            // a same-color dot fused to the end of the fill.
            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .align(Alignment.CenterStart)
                        .offset(
                            x =
                                with(density) {
                                    (trackWidth * animatedProgress)
                                        .toFloat()
                                        .coerceIn(0f, trackWidth.toFloat())
                                        .toDp()
                                } - 6.dp, // center the 12dp thumb on the position
                        ).shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFFF2F4F8))
                        .border(2.dp, Color(0xFFADC6FF), CircleShape),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Label row: [Rotate] [position] [percentage] ─────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Rotation button
            if (onRotateScreen != null) {
                IconButton(
                    onClick = onRotateScreen,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = NexoIcons.ScreenRotation,
                        contentDescription = stringResource(R.string.reader_rotate_screen),
                        tint = Color(0xFF718096),
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(36.dp))
            }

            // Page label
            Text(
                text = label.ifEmpty { stringResource(R.string.reader_progress_empty) },
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF718096),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
            )

            // Percentage
            Text(
                text = "${clampedProgress.toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFADC6FF),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReadingProgressBarDarkPreview() {
    NexoTheme(darkTheme = true) {
        ReadingProgressBar(
            progressPercent = 35f,
            label = "45 / 200",
            onProgressChange = {},
            onRotateScreen = {},
        )
    }
}

// Preview-only: fixed dark palette — light render is intentionally broken (see sdd/ui-previews-both-themes spec R7; color migration deferred)
@Preview(showBackground = true)
@Composable
private fun ReadingProgressBarLightPreview() {
    NexoTheme(darkTheme = false) {
        ReadingProgressBar(
            progressPercent = 35f,
            label = "45 / 200",
            onProgressChange = {},
            onRotateScreen = {},
        )
    }
}
