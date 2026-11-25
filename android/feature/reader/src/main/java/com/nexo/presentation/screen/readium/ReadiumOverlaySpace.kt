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
 * The overlay places in the **box the overlay actually occupies**: the
 * navigator fragment's root view (`FragmentContainerView`), which sits inside
 * the reader chrome's `contentPadding`. `Layout.place()` positions the surface
 * relative to that box, so the rect must be translated from the WebView's
 * origin into it.
 *
 * ## The three frames, none of them named by Compose
 *
 * 1. **The WebView's CSS viewport** — where the text lives; Readium measures here.
 * 2. **The window** — what `LocalView.current` and `getLocationInWindow` report.
 * 3. **The box the overlay occupies** — the fragment root, `place()`'s origin.
 *
 * The gap between (1) and (3) is a single vector, and it is **not** a constant:
 * it depends only on Readium's own page padding (`paddingTop`, which changes
 * with reader mode) because both (1) and (3) already include the same reader
 * inset. Measured on the `dev_clean` AVD the fragment root sits at window 128
 * (the cutout inset) and the WebView at 256 in scroll mode / 361 in paginated
 * mode — so the translation is 128 or 233, and 0 with no cutout.
 *
 * ## Why the offset is derived, never hard-coded — and never from the window
 *
 * The offset is the difference between two live, measurable origins:
 *
 * ```
 * offset = webView.getLocationInWindow() - fragmentRoot.getLocationInWindow()
 * ```
 *
 * **Both terms matter, and the second one is easy to get wrong.** Using the
 * `LocalView` window root (or the window origin) instead of the fragment root
 * adds the reader inset to the translation a second time and pushes every
 * surface down by exactly that inset — measured 128px on the `dev_clean` AVD,
 * which is the defect that shipped. The fragment root is found by walking UP
 * from the WebView to the `FragmentContainerView` ancestor; it is read from the
 * live hierarchy on every conversion, so the same code is correct for a
 * cutout-less phone, a rotated screen, and any reader mode.
 */
object ReadiumOverlaySpace {
    /**
     * Returns the vector that maps a Readium-space coordinate into the box the
     * overlay is placed in (the navigator fragment's root view).
     *
     * @param webView the navigator's `WebView`, whose viewport Readium measures in
     * @param fallback the view used to resolve the frame when the fragment root
     *   cannot be found in [webView]'s ancestry (kept for callers whose rect is
     *   already in the overlay's space; the conversion is then skipped anyway)
     */
    fun translation(
        webView: View,
        fallback: View,
    ): OffsetPx {
        val webViewLoc = IntArray(2)
        webView.getLocationInWindow(webViewLoc)
        val origin = fragmentRoot(webView) ?: fallback
        val originLoc = IntArray(2)
        origin.getLocationInWindow(originLoc)
        return OffsetPx(
            x = webViewLoc[0] - originLoc[0],
            y = webViewLoc[1] - originLoc[1],
        )
    }

    /**
     * The box the overlay occupies: the navigator fragment's root view.
     *
     * Found by walking up from the WebView to the first `FragmentContainerView`
     * ancestor. That view is an `AndroidView` in the Compose tree, placed in the
     * same padded content box as the overlay, so its window origin equals the
     * origin `Layout.place()` measures from. `null` when no fragment host exists
     * (the PDF path, or a rect that did not come from a navigator).
     */
    internal fun fragmentRoot(webView: View): View? {
        var node: View? = webView.parent as? View
        while (node != null) {
            if (node is androidx.fragment.app.FragmentContainerView) return node
            node = node.parent as? View
        }
        return null
    }

    /**
     * Returns [rect] (in Readium's space) expressed in the Compose content-box
     * space the overlays are placed in, or `null` when [rect] is not usable.
     *
     * Rounds half-up and orders the edges, so the result is a well-formed
     * [Rect] no matter which way Readium delivered the values.
     */
    fun toOverlayRect(
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
     * Puts the rect of a tapped highlight in the same space as a selection's.
     *
     * Readium 3.2.0's `EpubNavigatorFragment.adjustedToViewport(RectF)` adds the
     * page's top inset to `top` and leaves `bottom` alone. Measured on device: a
     * highlight tap reported top 741 / bottom 669 while a selection on the same
     * line reported 613 / 669, the gap being the 128px inset. Uncorrected, the
     * top lands below the bottom, the "inverted" rect gets normalised, and the
     * menu anchors onto the highlighted line instead of above it.
     *
     * @param pageInsetTop see [pageInsetTop]
     */
    fun decorationRect(
        reported: RectF,
        pageInsetTop: Int,
    ): RectF = RectF(reported.left, reported.top - pageInsetTop, reported.right, reported.bottom)

    /**
     * The inset Readium adds to a decoration's top: each page's root view is
     * padded by it, so the WebView sits exactly that far below the page top.
     */
    fun pageInsetTop(webView: View): Int = webView.top

    /**
     * Walks [root] for the [android.webkit.WebView] the reader is looking at.
     *
     * The WebView's own origin is the Readium viewport origin; the navigator
     * fragment's root is a `FrameLayout` that wraps it in the scroll layout, so
     * using the root instead of the WebView would silently add the wrapper's
     * padding as a second, invisible offset.
     */
    fun findWebView(root: View): android.webkit.WebView? {
        val all = mutableListOf<android.webkit.WebView>()
        collectWebViews(root, all)
        // Readium's pager keeps the neighbouring chapters loaded beside the
        // current one, often FIRST in the hierarchy. Taking the first WebView
        // measured the selection against an off-screen page 1080px away, and
        // every menu pinned itself to the screen's left edge. The page the
        // reader is looking at is the one with the most of itself on screen.
        val visible = android.graphics.Rect()
        return all.maxByOrNull { if (it.isShown && it.getGlobalVisibleRect(visible)) visible.width() * visible.height() else -1 }
    }

    private fun collectWebViews(
        node: View,
        into: MutableList<android.webkit.WebView>,
    ) {
        if (node is android.webkit.WebView) {
            into += node
            return
        }
        if (node is android.view.ViewGroup) {
            for (i in 0 until node.childCount) collectWebViews(node.getChildAt(i), into)
        }
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
data class OffsetPx(
    val x: Int,
    val y: Int,
)

private fun Float.toIntRounded(): Int = kotlin.math.round(this).toInt()
