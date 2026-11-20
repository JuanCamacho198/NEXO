package com.nexo.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Total length of the Home entrance, from the first section to the last one settling. */
private const val EntranceDurationMs = 900

/** Share of [EntranceDurationMs] each section spends fading in. */
private const val SectionWindow = 0.45f

/** Delay between consecutive sections, as a share of [EntranceDurationMs]. */
private const val SectionStagger = 0.12f

private val EntranceOffset = 24.dp

/** Scale a pressed card shrinks to; small enough to read as a press, not a jump. */
private const val PressedScale = 0.96f

/**
 * One shared clock for a screen's entrance.
 *
 * Every element derives its own fade from this single value instead of owning an
 * animation, so a lazy item that scrolls out and back in does not replay its
 * entrance. The played flag is saveable, so returning from the reader or
 * rotating the device does not replay it either.
 *
 * @param start Holds the clock at zero until true — e.g. until the first page of
 *   data arrives, so the entrance plays on the content rather than on a skeleton.
 */
@Composable
fun rememberEntranceProgress(start: Boolean = true): () -> Float {
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(start) {
        if (start && !played) {
            progress.animateTo(1f, tween(EntranceDurationMs, easing = LinearEasing))
            played = true
        }
    }
    return { progress.value }
}

/**
 * Fades and lifts an element in, [index] slots after the first one.
 *
 * The delay is capped so even a late index finishes inside the clock — items
 * past the cap simply arrive together with the last staggered one.
 *
 * Runs in the draw phase through [graphicsLayer], so the entrance never triggers
 * recomposition or relayout of the element it animates.
 */
fun Modifier.staggeredEnter(
    progress: () -> Float,
    index: Int,
): Modifier =
    composed {
        val offsetPx = with(LocalDensity.current) { EntranceOffset.toPx() }
        graphicsLayer {
            val start = (index * SectionStagger).coerceAtMost(1f - SectionWindow)
            val local = ((progress() - start) / SectionWindow).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(local)
            alpha = eased
            translationY = (1f - eased) * offsetPx
        }
    }

/**
 * [clickable] that also sinks the element slightly while pressed.
 *
 * Place it FIRST in the chain, before `background`/`border`: the scale then
 * covers the whole surface, and [shape] clips the ripple to it.
 */
fun Modifier.pressScaleClickable(
    shape: Shape = RectangleShape,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier =
    composed {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) PressedScale else 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            label = "pressScale",
        )
        this
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
    }
