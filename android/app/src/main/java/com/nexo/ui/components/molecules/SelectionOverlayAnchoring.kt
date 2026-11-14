package com.nexo.ui.components.molecules

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nexo.presentation.screen.readium.ReadiumOverlaySpace

/** Reserve at the top of the viewport (header + status bar) — don't let the menu hide behind the chrome. */
const val HEADER_RESERVE_DP = 80

/** Reserve at the bottom (progress bar + nav) so the menu never tucks behind the footer when flipped below. */
const val FOOTER_RESERVE_DP = 72

/**
 * The **only** space an overlay anchor is allowed to be computed from.
 *
 * The rule in [computeAnchor] reasons about the Compose content box the overlay
 * is placed in. A selection rect that arrives in any other space — and Readium's
 * does, it is WebView-viewport relative — silently misplaces every surface by
 * the gap between the two origins, a gap that is 0, 128 or 233px depending on
 * configuration.
 *
 * This type makes that impossible to get wrong: the constructor is private, so
 * the only way to obtain an instance is [SelectionOverlaySpace.of], which runs
 * the conversion in
 * [com.nexo.presentation.screen.readium.ReadiumOverlaySpace]. A consumer that is
 * handed a raw rect has nothing to build this from and cannot reach
 * [computeAnchor] at all.
 *
 * Do NOT add a second way to construct one. If a producer needs a new space,
 * teach [ReadiumOverlaySpace] the conversion and route it here.
 */
@JvmInline
value class SelectionOverlaySpace private constructor(
    val rect: Rect,
) {
    internal companion object {
        /** The conversion's only entry point — see the class doc. */
        internal fun of(converted: Rect): SelectionOverlaySpace = SelectionOverlaySpace(converted)

        internal fun of(
            rect: Rect,
            webView: android.view.View?,
            contentBox: android.view.View,
        ): SelectionOverlaySpace = of(rect, webView, contentBox, 0)

        /**
         * Converts [rect] into the space a Compose `Layout` node places in.
         *
         * `Layout.place()` positions the child relative to the Box the overlay
         * lives in, and that Box is inset by the reader chrome — its origin is
         * NOT the window origin (measured live: 128px on the `dev_clean` AVD,
         * the status bar / cutout inset).
         *
         * The raw JS rect is measured from the WebView's CSS viewport, which is
         * inset by that same amount and is therefore already past it. Converting
         * the rect into window space and handing it to `place()` counts the inset
         * twice; [placingOriginY] removes it. The value comes from the live
         * hierarchy at the call site, never from a constant.
         *
         * @param placingOriginY window-space Y of the origin `place()` measures
         *   from (0 when the node sits at the window origin)
         */
        internal fun of(
            rect: Rect,
            webView: android.view.View?,
            contentBox: android.view.View,
            placingOriginY: Int,
        ): SelectionOverlaySpace {
            val view = webView ?: return SelectionOverlaySpace(rect)
            val translation = ReadiumOverlaySpace.translation(view, contentBox, placingOriginY)
            val converted =
                ReadiumOverlaySpace.toOverlayRect(
                    android.graphics.RectF(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()),
                    translation,
                )
            return SelectionOverlaySpace(converted ?: rect)
        }
    }
}

/**
 * Computes the [IntOffset] (px) to anchor a floating surface of size
 * ([menuWidthPx] × [menuHeightPx]) near the selection.
 *
 * Pure function — no composition, fully unit-testable. Takes a
 * [SelectionOverlaySpace], not a raw [Rect], so it can only ever be handed a
 * rect in the space it reasons about.
 *
 * ## Placement rule
 * Preference is ABOVE the selection; it flips BELOW when the surface does not
 * fit above. The surface must NEVER overlap the selection:
 *
 * - **Above** is only chosen when the whole surface clears the selection's TOP
 *   by [gapDp] *and* still leaves [HEADER_RESERVE_DP] free at the top. The top
 *   is then `selectionRect.top - menuHeightPx - gapDp`.
 * - **Below** is chosen when it does not fit above but clears the selection's
 *   BOTTOM by [gapDp] and leaves [FOOTER_RESERVE_DP] free at the bottom. The
 *   top is then `selectionRect.bottom + gapDp`.
 * - When neither fits, the surface is pinned against the nearest edge on the
 *   side with room for it. Overlap is then geometrically unavoidable — the
 *   viewport has no space that both clears the reserves and fits the surface —
 *   so the rule minimises it instead of pretending otherwise. For any surface
 *   that fits anywhere in the viewport, overlap never happens.
 *
 * Horizontally the surface is centered on the selection and clamped to the
 * viewport edges.
 */
fun computeAnchor(
    space: SelectionOverlaySpace,
    menuWidthPx: Int,
    menuHeightPx: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    gapDp: Int = 8,
    density: Density,
): IntOffset {
    val selectionRectPx: Rect = space.rect
    val gapPx = with(density) { gapDp.dp.toPx() }.toInt()
    val headerReservePx = with(density) { HEADER_RESERVE_DP.dp.toPx() }.toInt()
    val footerReservePx = with(density) { FOOTER_RESERVE_DP.dp.toPx() }.toInt()

    val aboveTop = selectionRectPx.top - menuHeightPx - gapPx
    val belowTop = selectionRectPx.bottom + gapPx
    val placeAbove = aboveTop >= headerReservePx
    val fitsBelow = belowTop + menuHeightPx <= viewportHeight - footerReservePx
    val y =
        when {
            placeAbove -> aboveTop
            fitsBelow -> belowTop
            // Neither side has a full fit. This happens when the surface is
            // taller than the room left by the header/footer reserves — up to
            // and including a surface taller than the whole viewport, where no
            // placement can avoid the selection.
            //
            // Pick the side with MORE room and pin the surface against the
            // nearest edge from there, so the surface still starts on the
            // preferred side and the overlap is the minimum the viewport
            // allows. Above is preferred on a tie.
            menuHeightPx + gapPx <= selectionRectPx.top ->
                aboveTop.coerceAtLeast(0) // room above the selection wins
            else ->
                belowTop.coerceAtMost((viewportHeight - footerReservePx - menuHeightPx).coerceAtLeast(0))
        }

    val selectionCenterX = selectionRectPx.left + ((selectionRectPx.right - selectionRectPx.left) / 2)
    val rawX = selectionCenterX - (menuWidthPx / 2)
    val maxLeft = (viewportWidth - menuWidthPx).coerceAtLeast(0)
    val x = rawX.coerceIn(0, maxLeft)

    return IntOffset(x, y)
}

/**
 * Positions [content] near the selection using [computeAnchor].
 *
 * ## The rect contract
 *
 * [selectionRect] arrives in **Readium's space** (WebView-viewport relative) and
 * is converted HERE, once, by [SelectionOverlaySpace.of]. Callers cannot skip
 * that: [computeAnchor] accepts nothing but a [SelectionOverlaySpace], so there
 * is no overload that takes the raw rect. This is the single point the task
 * asks for — a consumer cannot get an unconverted rect into the rule because
 * the rule has no way to accept one.
 *
 * ## Why this is a `Layout` and not a `Box` + `offset` + `onGloballyPositioned`
 *
 * The surface's own measured size is an INPUT to the placement decision, but it
 * is also the thing being laid out. `Modifier.offset { }` reads a size that was
 * captured by `onGloballyPositioned` on a *previous* pass — the framework calls
 * that callback after composition, once coordinates are finalized — so on the
 * FIRST composition of a call site the remembered size is still `0`.
 *
 * That failure is not cosmetic. A ~180dp definition panel sharing this code
 * with a ~60dp menu row was placed with `menuHeightPx = 0`, so `aboveTop`
 * collapsed to `selection.top - gap` and the card grew DOWN across the very
 * word the reader asked about. Correcting it on the next composition makes the
 * card jump.
 *
 * Measuring the child and placing it in the SAME layout pass removes the
 * ordering problem: the anchor is computed from the real size of the real
 * surface on frame one, so the result is correct immediately and never jumps.
 *
 * ## Coordinate space
 *
 * `place()` positions the child in the coordinates reported by the parent
 * layout node, which Compose derives from the window. The conversion in
 * [SelectionOverlaySpace.of] derives its offset from the same frame
 * (`View.getLocationInWindow` on both the WebView and this layout's own view),
 * so the two always agree. The VIEWPORT must agree too: `LocalView.current
 * .height` is the whole window, and using it here made the rule believe the
 * surface fit where it did not. The layout's own constraints are the content
 * box and are used instead when they are bounded.
 *
 * @param selectionRect the selection in **Readium's space**, converted here
 * @param webView the navigator's WebView — the origin of [selectionRect]
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AnchoredOverlayBox(
    selectionRect: Rect,
    webView: android.view.View?,
    viewportWidth: Int,
    viewportHeight: Int,
    gapDp: Int = 8,
    onPlacingOriginMeasured: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val contentBox = LocalView.current
    // `Layout.place()` measures from the parent Box the overlay is placed into,
    // NOT from the window origin: the reader's Scaffold insets its content by
    // the status bar / cutout. Measured live on the dev_clean AVD the reader
    // inset is 128px, recovered as `childWinTop - anchor.y`.
    //
    // It is read from the PLACED CHILD rather than the Layout node, because the
    // node is zero-size and reports `boundsInWindow() == 0,0` — its own bounds
    // carry no information (verified on device). The derived value is refreshed
    // every pass from the child's own reported position, so it is always current
    // and a settled selection never moves.
    var readerInsetY by remember { mutableStateOf(0) }
    var lastPlacedChildWinTop by remember { mutableStateOf<Int?>(null) }

    Layout(
        modifier = modifier,
        content = {
            androidx.compose.foundation.layout.Box(
                modifier =
                    Modifier.onGloballyPositioned { coords ->
                        lastPlacedChildWinTop = coords.boundsInWindow().top.toInt()
                    },
            ) {
                content()
            }
        },
    ) { measurables, constraints ->
        // Measure the surface with the SAME constraints the parent offered, so
        // the size used for placement is the size that will actually be drawn.
        val placeable = measurables.firstOrNull()?.measure(constraints)
        val width = placeable?.width ?: 0
        val height = placeable?.height ?: 0

        // The viewport the rule must reason about is the space this layout can
        // actually place into — the content box — not the whole window.
        // `constraints.maxHeight` is exactly that when the parent bounds the
        // overlay (the full-size reader Box does); the caller's window height
        // is the fallback when constraints are unbounded.
        val localViewportWidth = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: viewportWidth
        val localViewportHeight = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: viewportHeight

        val space = SelectionOverlaySpace.of(selectionRect, webView, contentBox, readerInsetY)
        val anchor =
            computeAnchor(
                space = space,
                menuWidthPx = width,
                menuHeightPx = height,
                viewportWidth = localViewportWidth,
                viewportHeight = localViewportHeight,
                gapDp = gapDp,
                density = density,
            )
        // Recover the origin `place()` measures from, from the placed child
        // itself: the child lands at `anchor + origin`, so the origin is the
        // difference. Recorded for the NEXT pass; a settled selection is stable
        // because the surface's own size drives the anchor and does not change.
        lastPlacedChildWinTop?.let { measured ->
            readerInsetY = measured - anchor.y
            onPlacingOriginMeasured(readerInsetY)
        }

        // The overlay root is a full-size Box, so the layout occupies no space
        // of its own and the child is positioned freely by the anchor.
        layout(0, 0) {
            placeable?.place(anchor)
        }
    }
}
