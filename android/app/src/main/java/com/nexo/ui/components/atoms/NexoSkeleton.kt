package com.nexo.ui.components.atoms

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoColors

/**
 * App-generic loading placeholder: a rounded `bg_surface` block with an
 * optional alpha "shimmer" pulse.
 *
 * No skeleton/shimmer component existed in the app before this one; keep it
 * free of any Discover-specific sizing so other screens can reuse it.
 *
 * @param modifier Modifier applied to the box. Callers that need a non-square
 *   placeholder set `width`/`height` here.
 * @param size When non-null, forces a square placeholder of that size.
 * @param radius Corner radius of the block. Defaults to 8dp.
 * @param shimmer When true (default), the block pulses its alpha with a single
 *   shared infinite transition. When false it renders a static translucent block
 *   (useful in tests/previews where animations are unwanted).
 */
@Composable
fun NexoSkeletonBox(
    modifier: Modifier = Modifier,
    size: Dp? = null,
    radius: Dp = 8.dp,
    shimmer: Boolean = true,
) {
    val alpha =
        if (shimmer) {
            val transition = rememberInfiniteTransition(label = "nexoSkeleton")
            val animatedAlpha by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 0.9f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 900),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "nexoSkeletonAlpha",
            )
            animatedAlpha
        } else {
            0.6f
        }

    Box(
        modifier =
            modifier
                .then(if (size != null) Modifier.size(size) else Modifier)
                .clip(RoundedCornerShape(radius))
                .background(NexoColors.surface.copy(alpha = alpha)),
    )
}
