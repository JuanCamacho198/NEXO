package com.nexo.ui.components.molecules

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tag on the scrolling action region — used by the instrumented layout assertions. */
internal const val MENU_ACTIONS_TAG = "menu_actions"

/** Test tag on the menu pill itself, so the instrumented tests measure the MENU and not its host. */
internal const val MENU_PILL_TAG = "menu_pill"

/**
 * Horizontal pill row that scrolls its [actions] when they exceed the
 * available width, with an edge FADE on each side that still has content
 * beyond it.
 *
 * Shared by [TextSelectionMenu] and [FloatingContextMenu] so both menus get
 * the same overflow treatment without merging their action sets.
 *
 * **Why a fade and not an arrow or a partial item.** A fade reads the same in
 * both scroll directions, in both themes, and at any content width. An arrow
 * implies a discrete step the scroll does not take; a deliberately clipped item
 * depends on that item's width to signal at all. The fade is geometry-free.
 *
 * **Why the fade is liveness-gated.** A fade on an edge with nothing beyond it
 * is a lie — it claims hidden content that does not exist. Each side renders
 * only while `scrollState` reports more content in that direction, so a menu
 * that fits shows no fade at all.
 *
 * The [leading] slot stays OUTSIDE the scroll region: the palette trigger and
 * its divider are not a long list, and keeping them pinned means "appearance"
 * never scrolls out of reach while the user hunts for an action.
 *
 * @param leading Non-scrolling content pinned before the scroll region.
 * @param actions Scrolling action content.
 * @param modifier Modifier applied to the outer `Box`.
 */
@Composable
internal fun MenuScrollRow(
    leading: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val showStartFade by remember { derivedStateOf { scrollState.value > 0 } }
    val showEndFade by remember { derivedStateOf { scrollState.value < scrollState.maxValue } }
    // Solid at the menu's own surface, transparent toward the content, so the
    // fade blends into the pill instead of drawing a band across it.
    val surfaceColor = SelectionMenuColors.Surface

    Row(
        modifier = modifier.testTag(MENU_PILL_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        leading()

        Box(modifier = Modifier.weight(1f, fill = false)) {
            Row(
                modifier =
                    Modifier
                        .horizontalScroll(scrollState)
                        .testTag(MENU_ACTIONS_TAG)
                        .drawWithContent {
                            drawContent()
                            val fadeWidth = FADE_WIDTH.toPx()
                            if (showStartFade) {
                                drawRect(
                                    brush = Brush.horizontalGradient(colors = listOf(surfaceColor, Color.Transparent)),
                                    topLeft = Offset.Zero,
                                    size = Size(fadeWidth, size.height),
                                )
                            }
                            if (showEndFade) {
                                drawRect(
                                    brush = Brush.horizontalGradient(colors = listOf(Color.Transparent, surfaceColor)),
                                    topLeft = Offset(size.width - fadeWidth, 0f),
                                    size = Size(fadeWidth, size.height),
                                )
                            }
                        },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                actions()
            }
        }
    }
}

/** Width of each edge fade. Wide enough to read as a gradient, narrow enough to not hide an icon. */
private val FADE_WIDTH = 16.dp
