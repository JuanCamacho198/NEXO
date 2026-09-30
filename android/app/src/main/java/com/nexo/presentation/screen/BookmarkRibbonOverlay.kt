package com.nexo.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import kotlinx.coroutines.delay

private val RIBBON_COLOR = Color(0xFFEF4444)

private const val NOTCH_DEPTH_RATIO = 0.45f

private val RibbonShape =
    GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        val notchDepth = w * NOTCH_DEPTH_RATIO
        moveTo(0f, 0f)
        lineTo(w, 0f)
        lineTo(w, h)
        lineTo(w / 2f, h - notchDepth)
        lineTo(0f, h)
        close()
    }

private const val SLIDE_IN_DURATION_MS = 350
private const val HOLD_DURATION_MS = 1_800L
private const val FADE_OUT_DURATION_MS = 400

/**
 * iOS-style animated red ribbon that appears at the top-right of the reader
 * to confirm that a bookmark was added.
 *
 * Lifecycle (driven by [LaunchedEffect] keyed on [visible]):
 * - When [visible] flips to true: the ribbon slides in from the top
 *   (tween 350ms, FastOutSlowInEasing), stays visible for 1.8s, fades out
 *   (400ms), then [onAnimationEnd] is invoked.
 * - When [visible] flips to false before the animation completes, the
 *   ribbon is dismissed and [onAnimationEnd] is invoked.
 *
 * The ribbon is purely a visual confirmation. Persistent page-anchored
 * bookmark markers require Readium decorations and are out of scope here.
 */
@Composable
fun BookmarkRibbonOverlay(
    visible: Boolean,
    onAnimationEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val announcement = stringResource(R.string.bookmark_ribbon_announcement)
    var phaseVisible by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            phaseVisible = true
            delay(HOLD_DURATION_MS)
            phaseVisible = false
            delay(FADE_OUT_DURATION_MS.toLong())
        } else {
            phaseVisible = false
        }
        if (visible) {
            onAnimationEnd()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = phaseVisible,
            enter =
                slideInVertically(
                    animationSpec =
                        tween(
                            durationMillis = SLIDE_IN_DURATION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    initialOffsetY = { -it },
                ) + fadeIn(animationSpec = tween(SLIDE_IN_DURATION_MS)),
            exit = fadeOut(animationSpec = tween(FADE_OUT_DURATION_MS)),
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 8.dp, end = 12.dp)
                        .size(width = 40.dp, height = 56.dp)
                        .clip(RibbonShape)
                        .background(RIBBON_COLOR)
                        .semantics { contentDescription = announcement },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BookmarkRibbonOverlayDarkPreview() {
    NexoTheme(darkTheme = true) {
        BookmarkRibbonOverlay(visible = true, onAnimationEnd = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun BookmarkRibbonOverlayLightPreview() {
    NexoTheme(darkTheme = false) {
        BookmarkRibbonOverlay(visible = true, onAnimationEnd = {})
    }
}
