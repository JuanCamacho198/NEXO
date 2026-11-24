package com.nexo.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.theme.NexoTheme

/**
 * Ribbon orange, from the NEXO mark (itself an orange notched ribbon): the body
 * runs from [RIBBON_ORANGE] to [RIBBON_ORANGE_DEEP] like the logo's gradient,
 * and [RIBBON_FOLD] is the darker top where it tucks under the page edge.
 */
internal val RIBBON_ORANGE = Color(0xFFF97316)
private val RIBBON_ORANGE_LIGHT = Color(0xFFFF9A1F)
private val RIBBON_ORANGE_DEEP = Color(0xFFF4511E)
private val RIBBON_FOLD = Color(0xFFB4410C)

/** V-notched ribbon tail, depth relative to the width. */
private const val NOTCH_DEPTH_RATIO = 0.55f

internal val RibbonShape =
    GenericShape { size, _ ->
        val notchDepth = size.width * NOTCH_DEPTH_RATIO
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(size.width / 2f, size.height - notchDepth)
        lineTo(0f, size.height)
        close()
    }

/**
 * Orange ribbon hanging from the top-right edge of the page while the page the
 * reader is on has a bookmark — the persistent counterpart of the header icon.
 *
 * It drops in with a soft bounce when it appears (a bookmark was just added,
 * or the reader reached a bookmarked page) and lifts away when it goes. It sits
 * under the header's bookmark button, so with the chrome shown it reads as
 * hanging from that button.
 */
@Composable
fun PageBookmarkRibbon(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.reader_bookmark_on_page)
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter =
                slideInVertically(
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
                    initialOffsetY = { -it },
                ) + fadeIn(tween(120)),
            exit =
                slideOutVertically(
                    animationSpec = tween(240, easing = FastOutLinearInEasing),
                    targetOffsetY = { -it },
                ) + fadeOut(tween(240)),
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(end = 22.dp)
                        .size(width = 28.dp, height = 84.dp)
                        .shadow(elevation = 6.dp, shape = RibbonShape)
                        .clip(RibbonShape)
                        .background(
                            Brush.verticalGradient(
                                0f to RIBBON_FOLD,
                                0.16f to RIBBON_ORANGE_LIGHT,
                                1f to RIBBON_ORANGE_DEEP,
                            ),
                        ).semantics { contentDescription = description },
            ) {
                // Woven edge: a faint light stripe down the left side.
                Box(
                    Modifier
                        .padding(start = 5.dp)
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.22f)),
                )
            }
        }
    }
}

/** Small static ribbon for lists and buttons. */
@Composable
internal fun RibbonGlyph(
    modifier: Modifier = Modifier,
    color: Color = RIBBON_ORANGE,
) {
    Box(
        modifier =
            modifier
                .size(width = 12.dp, height = 18.dp)
                .clip(RibbonShape)
                .background(color),
    )
}

@Preview(showBackground = true)
@Composable
private fun PageBookmarkRibbonDarkPreview() {
    NexoTheme(darkTheme = true) {
        PageBookmarkRibbon(visible = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun PageBookmarkRibbonLightPreview() {
    NexoTheme(darkTheme = false) {
        PageBookmarkRibbon(visible = true)
    }
}
