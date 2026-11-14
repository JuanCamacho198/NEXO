package com.nexo.presentation.screen.readium

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.core.view.ViewCompat

/**
 * The one conversion between Readium's selection space and the space the
 * overlay places in.
 *
 * ## The two spaces
 *
 * Readium reports a selection rect in **WebView-viewport CSS pixels scaled by
 * `devicePixelRatio`** — i.e. the coordinate origin is the WebView's own
 * top-left content corner. Concretely: `window.readium.getCurrentSelection()`
 * returns a `DOMRect` from `Range.getBoundingClientRect()`, then the asset's
 * `V()` multiplies every edge by `devicePixelRatio` (see
 * [ReadiumSelectionRectRepair]).
 *
 * The overlay places in the **Compose content box of the reader**: the full-size
 * `Box` in `ReaderScreenContentHost` that wraps the navigator fragment and the
 * selection overlay. That box is already inset by the reader chrome's
 * `contentPadding`, so ITS origin is not the WebView's origin either — the
 * status bar pushes the WebView down inside it.
 *
 * The gap between the two origins is a single vector. It is **not** a constant:
 * it depends on the window insets, the display cutout, the reader mode
 * (reflowable scroll vs paginated, which changes Readium's own `paddingTop`),
 * and the orientation. Measured on the `dev_clean` AVD it is 128px in scroll
 * mode with a cutout, 233px in paginated mode with a cutout, and 0 with no
 * cutout — which is exactly why five rounds of magic offsets produced five
 * different numbers.
 *
 * ## Why the offset is derived, never hard-coded
 *
 * The offset is the vector from the WebView's CSS-viewport origin to the origin
 * of the node that will `place()` the overlay, and both are real, measurable
 * positions:
 *
 * ```
 * offset = webView.getLocationInWindow() - placingNode.positionInWindow()
 * ```
 *
 * **Both terms matter, and the second one is easy to get wrong.** The WebView's
 * window origin already contains the inset the reader's Scaffold applied (and,
 * in paginated mode, Readium's own page padding on top). The raw JS rect is
 * measured from that same WebView viewport, so it is *already past* the inset —
 * which means an offset that stops at the window origin adds the inset a
 * second time and pushes every surface down by exactly that inset. Measured on
 * the `dev_clean` AVD: 128px too low, matching the cutout.
 *
 * Subtracting the placing node's own window origin removes that double count
 * without naming the inset anywhere. It stays correct for a cutout-less phone,
 * a rotated screen, and any reader mode, because every input is read from the
 * live hierarchy rather than written down.
 */
internal object ReadiumOverlaySpace {
    /**
     * Returns the vector that maps a Readium-space coordinate into the space a
     * Compose `Layout` with the given placing origin positions its child in.
     *
     * @param webView the navigator's `WebView`, whose viewport Readium measures in
     * @param contentBox a view used only to resolve the window frame for
     *   [webView] when no placing origin is supplied
     * @param placingOriginY window-space Y of the origin `place()` measures
     *   from; 0 when that origin is the window origin
     */
    internal fun translation(
        webView: View,
        contentBox: View,
        placingOriginY: Int = 0,
    ): OffsetPx {
        val webViewLoc = IntArray(2)
        webView.getLocationInWindow(webViewLoc)
        val contentLoc = IntArray(2)
        contentBox.getLocationInWindow(contentLoc)
        return OffsetPx(
            x = webViewLoc[0] - contentLoc[0],
            y = webViewLoc[1] - contentLoc[1] - placingOriginY,
        )
    }

    /**
     * Returns [rect] (in Readium's space) expressed in the Compose content-box
     * space the overlays are placed in, or `null` when [rect] is not usable.
     *
     * Rounds half-up and orders the edges, so the result is a well-formed
     * [Rect] no matter which way Readium delivered the values.
     */
    internal fun toOverlayRect(
        rect: RectF,
        translation: OffsetPx,
    ): Rect? {
        if (!rect.left.isFinite() || !rect.top.isFinite()) return null
        if (!rect.right.isFinite() || !rect.bottom.isFinite()) return null
        val left = (rect.left + translation.x).toIntRounded()
        val right = (rect.right + translation.x).toIntRounded()
        val top = (rect.top + translation.y).toIntRounded()
        val bottom = (rect.bottom + translation.y).toIntRounded()
        return Rect(
            minOf(left, right),
            minOf(top, bottom),
            maxOf(left, right),
            maxOf(top, bottom),
        )
    }

    /**
     * Walks [root] for the first [android.webkit.WebView].
     *
     * The WebView's own origin is the Readium viewport origin; the navigator
     * fragment's root is a `FrameLayout` that wraps it in the scroll layout, so
     * using the root instead of the WebView would silently add the wrapper's
     * padding as a second, invisible offset.
     */
    internal fun findWebView(root: View): android.webkit.WebView? {
        if (root is android.webkit.WebView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findWebView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * False when the window's system bars are still visible, which is the only
     * case the translation must *not* apply in this shape.
     *
     * Kept as a named predicate so the single call site reads as an assertion
     * about the reader chrome rather than a bare insets check.
     */
    internal fun systemBarsShown(view: View): Boolean {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return true
        return insets.isVisible(
            androidx.core.view.WindowInsetsCompat.Type
                .systemBars(),
        )
    }
}

/** A pixel translation in window coordinates. */
internal data class OffsetPx(
    val x: Int,
    val y: Int,
)

private fun Float.toIntRounded(): Int = kotlin.math.round(this).toInt()
