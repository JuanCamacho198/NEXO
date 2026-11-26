package com.nexo.ui.components.molecules

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the popover's placement to the shared conversion.
 *
 * ## Why this exists
 *
 * Six reader surfaces position themselves through `AnchoredOverlayBox`, which
 * converts the selection rect inside [SelectionOverlaySpace.of]. The seventh —
 * `HighlightColorPickerPopover` — does NOT go through `AnchoredOverlayBox`: it
 * offsets itself with `Modifier.offset` and so ran the conversion a second time
 * via `rememberOverlayPlacement`. That second producer had no test: a comment
 * claimed "the test suite pins it", and nothing did.
 *
 * The popover's arithmetic is now [overlayPlacementRect], a plain function that
 * can be driven without composition. These tests assert it agrees with
 * [SelectionOverlaySpace.of] for the same inputs, so a divergence — a
 * re-implemented conversion, a dropped term, a double-counted inset — fails
 * here instead of on a reader's screen.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionOverlayPlacementTest {
    /**
     * A navigator-like fragment: a scroll container holding the WebView. Built
     * through a real fragment transaction because `FragmentContainerView`
     * rejects children added directly with `addView`.
     */
    class NavigatorLikeFragment : androidx.fragment.app.Fragment() {
        lateinit var webView: android.webkit.WebView

        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: android.view.ViewGroup?,
            savedInstanceState: android.os.Bundle?,
        ): android.view.View {
            webView = android.webkit.WebView(requireContext())
            return webView
        }
    }

    /**
     * A fragment-hosted hierarchy shaped like the navigator's: a window root at
     * Y 0, the reader content box at window Y 128 (the cutout), and the fragment
     * host inside it holding the WebView at window Y 361.
     *
     * The gap that matters is `webView@361 - fragmentHost@128 = 233`, and the
     * window root is the tempting-but-wrong origin: using it would yield 361 and
     * count the cutout a second time.
     */
    private class Hierarchy(
        insetX: Float = 0f,
    ) {
        val windowRoot: View
        val webView: View
        val fragmentHost: androidx.fragment.app.FragmentContainerView
        val windowRootY: Int

        init {
            val activity =
                org.robolectric.Robolectric
                    .buildActivity(androidx.fragment.app.FragmentActivity::class.java)
                    .setup()
                    .get()

            windowRoot = FrameLayout(activity)

            val contentBox = FrameLayout(activity).apply { y = 128f }
            windowRoot.addView(contentBox)

            fragmentHost =
                androidx.fragment.app.FragmentContainerView(activity).apply {
                    id = 7
                    y = 128f
                    x = insetX
                }
            contentBox.addView(fragmentHost)
            activity.setContentView(windowRoot)

            activity.supportFragmentManager
                .beginTransaction()
                .add(7, NavigatorLikeFragment(), "navigator")
                .commitNow()

            webView =
                (activity.supportFragmentManager.findFragmentById(7) as NavigatorLikeFragment).webView
            // Simulate Readium's page padding: the WebView sits 233px below its
            // scroll container's origin.
            webView.y = 233f
            webView.x = if (insetX != 0f) insetX * 2 else 0f

            windowRoot.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            windowRoot.layout(0, 0, 1080, 2400)

            windowRootY = IntArray(2).also { windowRoot.getLocationInWindow(it) }[1]
        }
    }

    private fun rect(
        top: Int,
        bottom: Int,
        left: Int = 300,
        right: Int = 640,
    ) = Rect(left, top, right, bottom)

    /**
     * The popover's conversion must be the SAME string of operations as the
     * shared one — asserted against [SelectionOverlaySpace.of], which is exactly
     * what `AnchoredOverlayBox` calls. If the popover ever grows its own copy of
     * the conversion, this is the assertion that catches the drift.
     */
    @Test
    fun `the popover conversion agrees with the shared AnchoredOverlayBox path`() {
        val hierarchy = Hierarchy()
        val selection = rect(top = 964, bottom = 1016)

        val popover = overlayPlacementRect(selection, hierarchy.webView, hierarchy.windowRoot)
        val shared = SelectionOverlaySpace.of(selection, hierarchy.webView, hierarchy.windowRoot).rect

        assertEquals(
            "the popover must use the same conversion as AnchoredOverlayBox, not a copy",
            shared,
            popover,
        )
    }

    /**
     * The bug this whole area just had: an origin that includes the reader inset
     * counts the cutout twice. The popover's rect must be measured from the
     * fragment host, not the window.
     */
    @Test
    fun `the popover conversion does not double count the cutout inset`() {
        val hierarchy = Hierarchy()
        val selection = rect(top = 964, bottom = 1016)

        val popover = overlayPlacementRect(selection, hierarchy.webView, hierarchy.windowRoot)

        // The conversion is measured from the navigator fragment host, so the
        // vertical shift is the WebView's offset within that host — Readium's
        // own 233px page padding — and never the reader inset.
        assertEquals("the converted top must exclude the reader inset", 964 + 233, popover.top)
        assertEquals("the line's height must survive", 52, popover.bottom - popover.top)

        // The defect's signature: measuring from the window root instead of the
        // fragment host keeps the reader inset. The wrong rect is exactly the
        // fragment host's own window origin lower than the right one.
        val doubleCounted = ReadiumSpace.doubleCounted(selection, hierarchy.webView)
        val fragmentHostWindowY = IntArray(2).also { hierarchy.fragmentHost.getLocationInWindow(it) }[1]
        assertEquals(
            "the defect's signature is the fragment host origin, and must be absent",
            fragmentHostWindowY - hierarchy.windowRootY,
            doubleCounted.top - popover.top,
        )
    }

    /**
     * A rect that never came from a WebView (the PDF reader) must pass through
     * untouched: `null` means "already in the overlay's space", and converting it
     * anyway would shift every PDF surface by a frame it never belonged to.
     */
    @Test
    fun `a rect without a webview passes through unconverted`() {
        val hierarchy = Hierarchy()
        val selection = rect(top = 964, bottom = 1016)

        val placement = overlayPlacementRect(selection, webView = null, fallbackOrigin = hierarchy.windowRoot)

        assertEquals("no WebView means no conversion", selection, placement)
    }

    /**
     * The horizontal axis converts too. A left inset (a landscape cutout) must
     * move `left`/`right`, not just the vertical edges — the popover derives its
     * X from this rect, so a dropped X term misplaces the picker sideways.
     */
    @Test
    fun `the popover conversion carries the horizontal terms`() {
        val hierarchy = Hierarchy(insetX = 48f)

        val selection = rect(top = 400, bottom = 460, left = 300, right = 640)
        val placement = overlayPlacementRect(selection, hierarchy.webView, hierarchy.windowRoot)

        // The horizontal shift is the WebView's offset within the fragment host
        // (its own x), so a left inset moves left/right rather than only the
        // vertical edges. The popover derives its X from this rect.
        val webViewWindowX = IntArray(2).also { hierarchy.webView.getLocationInWindow(it) }[0]
        val hostWindowX = IntArray(2).also { hierarchy.fragmentHost.getLocationInWindow(it) }[0]
        val shift = webViewWindowX - hostWindowX
        assertTrue("the fixture must produce a non-zero horizontal gap", shift != 0)
        assertEquals("left must be shifted by the horizontal gap", 300 + shift, placement.left)
        assertEquals("right must be shifted by the horizontal gap", 640 + shift, placement.right)
    }

    /**
     * The PDF.js path: the whole conversion is the CSS→device ratio, and NOTHING
     * else. Measured on the `dev_clean` AVD (420 dpi): the posted selection sat at
     * top 109 CSS px, `window.devicePixelRatio` was 2.625, and the WebView's Box
     * was at window y 128. Feeding the WebView to the anchoring as a source added
     * that 128 px a second time; these numbers pin the absence of the term.
     */
    @Test
    fun `a pdf selection is scaled by the css to device ratio and never shifted`() {
        val posted = Rect(126, 109, 149, 121)

        val anchor = pdfSelectionRectInOverlayPx(posted, cssToDevicePx = 2.625f)

        assertEquals("left is CSS × 2.625", 331, anchor.left)
        assertEquals("top is CSS × 2.625, not that plus the 128px inset", 286, anchor.top)
        assertEquals("right is CSS × 2.625", 391, anchor.right)
        assertEquals("bottom is CSS × 2.625", 318, anchor.bottom)
    }

    @Test
    fun `a pdf selection at density 1 is the identity`() {
        val posted = Rect(100, 200, 300, 260)

        assertEquals("nothing to convert when one CSS px is one device px", posted, pdfSelectionRectInOverlayPx(posted, 1f))
    }
}

/**
 * A deliberately WRONG conversion that mirrors the shipped double-count defect:
 * it measures from the window root rather than the navigator fragment host, so
 * it keeps the reader inset. Gives the cutout assertion a concrete value to
 * fail against.
 */
private object ReadiumSpace {
    fun doubleCounted(
        rect: Rect,
        webView: View,
    ): Rect {
        val webViewLoc = IntArray(2)
        webView.getLocationInWindow(webViewLoc)
        return Rect(
            rect.left + webViewLoc[0],
            rect.top + webViewLoc[1],
            rect.right + webViewLoc[0],
            rect.bottom + webViewLoc[1],
        )
    }
}
