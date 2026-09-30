package com.nexo.ui.components.molecules

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** Reserve at the top of the viewport (header + status bar) — don't let the menu hide behind the chrome. */
const val HEADER_RESERVE_DP = 80

/** Reserve at the bottom (progress bar + nav) so the menu never tucks behind the footer when flipped below. */
const val FOOTER_RESERVE_DP = 72

/**
 * Computes the [IntOffset] (px) to anchor the floating menu near the selection.
 * Pure function — no composition, fully unit-testable.
 * Default is above the selection; if there isn't enough room it flips below.
 * Horizontally centered on the selection and clamped to viewport edges.
 */
fun computeAnchor(
    selectionRectPx: Rect,
    menuWidthPx: Int,
    menuHeightPx: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    gapDp: Int = 8,
    density: Density,
): IntOffset {
    val gapPx = with(density) { gapDp.dp.toPx() }.toInt()
    val headerReservePx = with(density) { HEADER_RESERVE_DP.dp.toPx() }.toInt()
    val footerReservePx = with(density) { FOOTER_RESERVE_DP.dp.toPx() }.toInt()

    val aboveTop = (selectionRectPx.top - menuHeightPx - gapPx).coerceAtLeast(0)
    val belowTop = selectionRectPx.bottom + gapPx
    val placeAbove = selectionRectPx.top - menuHeightPx - gapPx >= headerReservePx
    val fitsBelow = belowTop + menuHeightPx <= viewportHeight - footerReservePx
    val y =
        when {
            placeAbove -> aboveTop
            fitsBelow -> belowTop
            else -> aboveTop
        }

    val selectionCenterX = selectionRectPx.left + ((selectionRectPx.right - selectionRectPx.left) / 2)
    val rawX = selectionCenterX - (menuWidthPx / 2)
    val maxLeft = (viewportWidth - menuWidthPx).coerceAtLeast(0)
    val x = rawX.coerceIn(0, maxLeft)

    return IntOffset(x, y)
}

/**
 * Thin wrapper that owns per-branch anchoring state (menuWidthPx/menuHeightPx)
 * and positions [content] via [computeAnchor]. Each call-site gets its own
 * `remember { mutableIntStateOf }` — MUST NOT be hoisted to the orchestrator.
 */
@Composable
fun AnchoredOverlayBox(
    selectionRect: Rect,
    viewportWidth: Int,
    viewportHeight: Int,
    gapDp: Int = 8,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var menuWidthPx by remember { mutableIntStateOf(0) }
    var menuHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val anchor =
        computeAnchor(
            selectionRectPx = selectionRect,
            menuWidthPx = menuWidthPx,
            menuHeightPx = menuHeightPx,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            gapDp = gapDp,
            density = density,
        )

    Box(
        modifier =
            modifier
                .offset { anchor }
                .onGloballyPositioned { coords ->
                    menuWidthPx = coords.size.width
                    menuHeightPx = coords.size.height
                }.padding(8.dp),
    ) {
        content()
    }
}
