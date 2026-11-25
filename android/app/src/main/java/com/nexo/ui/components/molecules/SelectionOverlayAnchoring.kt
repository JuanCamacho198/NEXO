package com.nexo.ui.components.molecules

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
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

/** Keeps the caret off the pill's rounded ends, where it would hang from a curve. */
private const val CARET_EDGE_INSET_DP = 22

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
            fallbackOrigin: android.view.View,
        ): SelectionOverlaySpace {
            val view = webView ?: return SelectionOverlaySpace(rect)
            // The conversion resolves the overlay's frame from the navigator
            // fragment root found in the WebView's ancestry; `fallbackOrigin` is
            // only used when that cannot be found. See
            // [com.nexo.presentation.screen.readium.ReadiumOverlaySpace.translation].
            val translation = ReadiumOverlaySpace.translation(view, fallbackOrigin)
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

/** Where the caret goes and which way it points; see [caretPlacement]. */
data class CaretPlacement(
    val offset: IntOffset,
    val pointsDown: Boolean,
)

/**
 * Places the caret between a surface placed by [computeAnchor] and the
 * selection it belongs to.
 *
 * The caret hangs off the surface's edge that faces the selection, overlapping
 * it by [overlapPx] so its base hides the pill's outline there, and points at
 * the selection's horizontal centre, kept [edgeInsetPx] clear of the surface's
 * rounded ends. Returns `null` when the surface overlaps the selection
 * vertically (the pinned fallback in [computeAnchor]): there is no gap for a
 * caret to bridge and an arrow into the text would point at nothing useful.
 */
fun caretPlacement(
    selection: Rect,
    surface: IntOffset,
    surfaceWidth: Int,
    surfaceHeight: Int,
    caretWidth: Int,
    caretHeight: Int,
    edgeInsetPx: Int,
    overlapPx: Int,
): CaretPlacement? {
    val surfaceBottom = surface.y + surfaceHeight
    val pointsDown =
        when {
            surfaceBottom <= selection.top -> true
            surface.y >= selection.bottom -> false
            else -> return null
        }
    val minX = surface.x + edgeInsetPx
    val maxX = surface.x + surfaceWidth - edgeInsetPx - caretWidth
    if (maxX < minX) return null
    val centerX = selection.left + (selection.right - selection.left) / 2
    val x = (centerX - caretWidth / 2).coerceIn(minX, maxX)
    val y = if (pointsDown) surfaceBottom - overlapPx else surface.y - caretHeight + overlapPx
    return CaretPlacement(IntOffset(x, y), pointsDown)
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
 * `place()` positions the child in the coordinates of the box this layout was
 * placed into. The conversion in [SelectionOverlaySpace.of] resolves that same
 * box — the navigator fragment's root view — from the WebView's ancestry (see
 * [com.nexo.presentation.screen.readium.ReadiumOverlaySpace]), so the offset the
 * rule consumes and the origin `place()` measures from always agree. Using the
 * window origin here instead is exactly the double-count that landed every
 * surface 128px low: the fragment root sits inside the reader chrome's
 * `contentPadding`. The VIEWPORT must agree too: `LocalView.current.height` is
 * the whole window, and using it here made the rule believe the surface fit
 * where it did not. The layout's own constraints are the content box and are
 * used instead when they are bounded.
 *
 * @param selectionRect the selection in **Readium's space**, converted here
 * @param webView the navigator's WebView — the origin of [selectionRect]
 */
@Composable
fun AnchoredOverlayBox(
    selectionRect: Rect,
    webView: android.view.View?,
    viewportWidth: Int,
    viewportHeight: Int,
    gapDp: Int = 8,
    modifier: Modifier = Modifier,
    caret: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Only a fallback: the conversion resolves the overlay's real frame (the
    // navigator fragment root) from the WebView itself.
    val fallbackOrigin = LocalView.current

    val caretContent: @Composable () -> Unit = caret ?: {}
    Layout(
        modifier = modifier,
        contents = listOf(content, caretContent),
    ) { (surfaceMeasurables, caretMeasurables), constraints ->
        // Measure the surface with the SAME constraints the parent offered, so
        // the size used for placement is the size that will actually be drawn.
        val placeable = surfaceMeasurables.firstOrNull()?.measure(constraints)
        val width = placeable?.width ?: 0
        val height = placeable?.height ?: 0

        // The viewport the rule must reason about is the space this layout can
        // actually place into — the content box — not the whole window.
        // `constraints.maxHeight` is exactly that when the parent bounds the
        // overlay (the full-size reader Box does); the caller's window height
        // is the fallback when constraints are unbounded.
        val localViewportWidth = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: viewportWidth
        val localViewportHeight = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: viewportHeight

        val space = SelectionOverlaySpace.of(selectionRect, webView, fallbackOrigin)
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

        val caretPlaceable = caretMeasurables.firstOrNull()?.measure(Constraints())
        val caretAt =
            caretPlaceable?.let {
                caretPlacement(
                    selection = space.rect,
                    surface = anchor,
                    surfaceWidth = width,
                    surfaceHeight = height,
                    caretWidth = it.width,
                    caretHeight = it.height,
                    edgeInsetPx = CARET_EDGE_INSET_DP.dp.roundToPx(),
                    overlapPx = 1.dp.roundToPx(),
                )
            }

        // The overlay root is a full-size Box, so the layout occupies no space
        // of its own and the child is positioned freely by the anchor.
        layout(0, 0) {
            placeable?.place(anchor)
            if (caretPlaceable != null && caretAt != null) {
                caretPlaceable.placeWithLayer(caretAt.offset) { rotationZ = if (caretAt.pointsDown) 0f else 180f }
            }
        }
    }
}
