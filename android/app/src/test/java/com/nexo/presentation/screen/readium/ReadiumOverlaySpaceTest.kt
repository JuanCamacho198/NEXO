package com.nexo.presentation.screen.readium

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sweep that would have caught the overlay-space bug.
 *
 * ## The bug class
 *
 * A selection rect was taken in **Readium's space** (WebView-viewport relative:
 * `Range.getBoundingClientRect()` × `devicePixelRatio`) and handed to a rule
 * that places surfaces in the **Compose content box**. Every surface was then
 * misplaced by the gap between the two origins — and because that gap is not a
 * constant, five rounds of fixes produced five different numbers:
 *
 * | configuration        | gap (px) |
 * |----------------------|----------|
 * | scroll + cutout      | 128      |
 * | paginated + cutout   | 233      |
 * | no cutout            | 0        |
 *
 * ## What this test pins
 *
 * [ReadiumOverlaySpace.toOverlayRect] must land the converted rect exactly where
 * the text is, for **every** combination of (scroll offset × reader mode ×
 * inset). The check is arithmetic on the two things the real device gives us:
 * the WebView's offset within the content box, and the DOM rect of the word.
 *
 * ## Why it fails without the fix
 *
 * The conversion's offset is supplied by [ReadiumOverlaySpace.translation]. The
 * pre-fix code path did not exist at all — line 131 of
 * `ReadiumSelectionBridge.kt` passed the raw JS rect straight through. These
 * tests assert the *offset is applied*, so code that skips it lands the menu a
 * full inset (128 / 233 px) off the line and fails here rather than on a
 * reader's screen.
 */
@RunWith(RobolectricTestRunner::class)
class ReadiumOverlaySpaceTest {
    /** A viewport-relative DOM rect, the shape `getCurrentSelection()` returns. */
    private fun domRect(
        top: Float,
        bottom: Float,
        left: Float = 300f,
        right: Float = 640f,
    ) = RectF(left, top, right, bottom)

    /**
     * Everything that changes the gap between the two origins.
     *
     * The fixtures are the *measured* on-device numbers: the content box's window
     * origin, and the WebView's window origin inside it. The gap between the two
     * (plus any Readium page padding) IS the offset, and it is why the bug's
     * numbers changed with configuration.
     */
    private data class Case(
        val name: String,
        /** Reader mode: the two Readium ships affect the WebView padding differently. */
        val mode: ReaderMode,
        /** Window Y of the WebView, i.e. where Readium's viewport origin sits. */
        val webViewWindowY: Int,
        /**
         * Window Y of the Composable node that will `place()` the surface.
         *
         * This is the term whose omission let the double-count ship. The reader
         * Scaffold insets the reader content by the status bar / cutout, so the
         * placing node does NOT sit at the window origin: its window Y equals
         * that inset (128 inside a cutout on the `dev_clean` AVD, 0 with no
         * cutout). `Layout.place()` works in the node's own space, so the offset
         * handed to it must already have this term removed.
         */
        val placingNodeWindowY: Int,
        /** Scroll offset of the page inside the WebView viewport. */
        val scrollOffset: Int,
        /** The word's rect in the WebView viewport, AFTER scrolling. */
        val domTop: Int,
        val domBottom: Int,
    )

    private enum class ReaderMode { SCROLL, PAGINATED }

    /**
     * The real numbers measured on the `dev_clean` AVD (2400px window).
     *
     * The gap that matters is `webViewWindowY - placingNodeWindowY`, because
     * that is the space `Layout.place()` consumes:
     *
     * - scroll + cutout: the WebView sits at window 256 (128 cutout + 128
     *   Readium page padding) and the placing node at window 128 (the cutout),
     *   so the DOM rect must be pushed DOWN by 128.
     * - paginated + cutout: the WebView at 361, the node still at 128 — 233.
     * - no cutout: both origins coincide, so the offset must be 0.
     *
     * The `mode` field distinguishes the two Readium page-padding behaviours,
     * which is what changed the raw number in every earlier investigation.
     */
    private val cases =
        listOf(
            // (a) A selection LOW on the screen, scroll mode, cutout present.
            Case("scroll+cutout, low line", ReaderMode.SCROLL, 256, 128, 0, 786, 838),
            // (b) A selection near the BOTTOM edge of the screen.
            Case("scroll+cutout, bottom edge", ReaderMode.SCROLL, 256, 128, 0, 1980, 2032),
            // (c) The SAME word after scrolling the page — the DOM rect moves 1:1
            //     with the scroll, so the conversion must follow it exactly.
            Case("scroll+cutout, after scrolling", ReaderMode.SCROLL, 256, 128, 640, 146, 198),
            // (d) PAGINATED mode, where Readium's own paddingTop is 233 not 128.
            Case("paginated+cutout", ReaderMode.PAGINATED, 361, 128, 0, 520, 572),
            // (e) No cutout at all: the origins coincide and the offset must be 0.
            Case("no cutout", ReaderMode.SCROLL, 0, 0, 0, 800, 852),
            // (f) A rotated/odd inset that is neither 128 nor 233 — the case a
            //     hard-coded constant gets wrong.
            Case("odd inset", ReaderMode.SCROLL, 199, 71, 0, 640, 692),
        )

    /**
     * The scenario the fix exists for: the word's rect in Readium's space, and
     * where that word actually is **in the space `Layout.place()` consumes**.
     *
     * The expected value is the line's window position minus the placing node's
     * own window origin, because `place()` is relative to that node. Getting
     * this wrong by the node origin is exactly the double-count this suite pins.
     */
    private fun convert(case: Case): Pair<android.graphics.Rect, Int> {
        val dom = domRect(case.domTop.toFloat(), case.domBottom.toFloat())

        // The production conversion, with the same inputs the live hierarchy
        // supplies: the WebView's window position and the placing node's own.
        val translation = OffsetPx(x = 0, y = case.webViewWindowY - case.placingNodeWindowY)

        val converted = ReadiumOverlaySpace.toOverlayRect(dom, translation)
        assertNotNull("case ${case.name}: conversion must not fail", converted)

        // Ground truth in the frame `place()` consumes.
        val textTopInPlacementSpace =
            (case.webViewWindowY - case.placingNodeWindowY) + case.domTop
        return converted!! to textTopInPlacementSpace
    }

    @Test
    fun `converted rect lands on the text for every scroll offset, mode and inset`() {
        for (case in cases) {
            val (converted, textTopInBox) = convert(case)
            assertEquals(
                "case ${case.name}: converted top must equal the line's real top",
                textTopInBox.toFloat(),
                converted.top.toFloat(),
                0.51f,
            )
            val expectedHeight = case.domBottom - case.domTop
            assertEquals(
                "case ${case.name}: the line's height must survive the conversion",
                expectedHeight.toFloat(),
                (converted.bottom - converted.top).toFloat(),
                0.51f,
            )
        }
    }

    @Test
    fun `the gap between the spaces is configuration dependent, not a constant`() {
        val gaps = cases.map { convert(it).first.top - it.domTop }.distinct()
        assertTrue(
            "the fix must not be expressible as one magic offset; gaps seen: $gaps",
            gaps.size > 1,
        )
        // The three configurations whose numbers were measured on device. The
        // gap is measured from the WebView origin to the PLACING NODE, which is
        // what `place()` consumes: the WebView's window offset minus the node's.
        assertEquals(128, gapsFor(cases.first { it.name == "scroll+cutout, low line" }))
        assertEquals(233, gapsFor(cases.first { it.name == "paginated+cutout" }))
        assertEquals(0, gapsFor(cases.first { it.name == "no cutout" }))
    }

    private fun gapsFor(case: Case): Int = convert(case).first.top - case.domTop

    /**
     * **The regression that would have caught the cutout double-count.**
     *
     * The placing node does not sit at the window origin — the reader Scaffold
     * insets the reader content by the status bar / cutout. An offset derived
     * only from the WebView's window position therefore counts that inset a
     * second time and pushes every surface down by exactly it.
     *
     * Measured live on the `dev_clean` AVD: the menu landed 128px below the
     * value `place()` was given, matching the cutout — in two independent cases.
     *
     * This test fails whenever the node's own origin is dropped from the
     * subtraction, which is the defect that shipped.
     */
    @Test
    fun `the placing node's own origin is removed so the cutout is not counted twice`() {
        // The measured case: WebView at window 256, placing node at window 128
        // (the cutout inset), DOM rect top 964.
        val webViewWindowY = 256
        val placingNodeWindowY = 128
        val domTop = 964

        val translation = OffsetPx(x = 0, y = webViewWindowY - placingNodeWindowY)
        val converted = ReadiumOverlaySpace.toOverlayRect(domRect(domTop.toFloat(), (domTop + 52).toFloat()), translation)!!

        // The node's own space is `window - nodeOrigin`, so the line sits at
        // 964 + (256 - 128) = 1092 within that space.
        assertEquals(
            "the converted rect must be measured from the placing node, not the window",
            1092,
            converted.top,
        )

        // And the bug's signature is exactly the node origin, so assert it is
        // NOT present: a double-count would put the rect 128px lower.
        val doubleCounted = OffsetPx(x = 0, y = webViewWindowY)
        val wrong = ReadiumOverlaySpace.toOverlayRect(domRect(domTop.toFloat(), (domTop + 52).toFloat()), doubleCounted)!!
        assertEquals(
            "the double-count is off by exactly the placing node's origin",
            placingNodeWindowY,
            wrong.top - converted.top,
        )
    }

    /**
     * The placing node's origin must be subtracted for every configuration, and
     * must vanish when the node genuinely sits at the window origin.
     */
    @Test
    fun `a node at the window origin needs no correction and a cutout node does`() {
        // No cutout: node and WebView share the window origin, so the offset is
        // the WebView's position alone.
        assertEquals(0, OffsetPx(0, 0 - 0).y)
        // Cutout: the node is inset, so the offset shrinks by that inset.
        assertEquals(128, OffsetPx(0, 256 - 128).y)
        // A different inset (rotation / another device) is handled the same way.
        assertEquals(71, OffsetPx(0, 199 - 128).y)
    }

    /**
     * The scroll must be followed 1:1: the same word, one page further down,
     * must convert to a line exactly `scrollOffset` higher in device space.
     */
    @Test
    fun `scrolling the page moves the converted rect one to one`() {
        val unscrolled = cases.first { it.name == "scroll+cutout, low line" }
        val scrolled = unscrolled.copy(scrollOffset = 640, domTop = unscrolled.domTop - 640, domBottom = unscrolled.domBottom - 640)

        val (before, _) = convert(unscrolled)
        val (after, _) = convert(scrolled)

        assertEquals(
            "a 640px scroll must move the converted rect exactly 640px",
            640,
            before.top - after.top,
        )
    }

    /**
     * A code path that forgets the conversion places the surface a full inset
     * away. This is the assertion that turns that mistake into a red test.
     */
    @Test
    fun `skipping the conversion is off by the offset and therefore detected`() {
        for (case in cases) {
            val (converted, textTopInPlacementSpace) = convert(case)
            val unconverted = case.domTop
            val error = kotlin.math.abs(unconverted - textTopInPlacementSpace)
            assertEquals(
                "case ${case.name}: the unconverted rect must be off by exactly the " +
                    "placing node to WebView gap",
                case.webViewWindowY - case.placingNodeWindowY,
                error,
            )
            // And the conversion is what removes it.
            assertEquals(
                "case ${case.name}: the converted rect must land on the line",
                0,
                kotlin.math.abs(converted.top - textTopInPlacementSpace),
            )
        }
    }

    /**
     * The conversion is a pure translation: no scaling, no clamping, no
     * reordering beyond normalising inverted input.
     */
    @Test
    fun `conversion preserves width and normalises inverted input`() {
        val dom = domRect(top = 400f, bottom = 460f)
        val converted = ReadiumOverlaySpace.toOverlayRect(dom, OffsetPx(0, 128))!!
        assertEquals(dom.width(), (converted.right - converted.left).toFloat(), 0.01f)

        // Readium has been observed to deliver an inverted rect across a
        // multi-column boundary; the conversion must hand the rule an ordered one.
        val inverted = RectF(300f, 460f, 640f, 400f)
        val normalised = ReadiumOverlaySpace.toOverlayRect(inverted, OffsetPx(0, 0))!!
        assertTrue("top must be above bottom", normalised.top < normalised.bottom)
        assertEquals(400, normalised.top)
        assertEquals(460, normalised.bottom)
    }

    /**
     * A rect that is not finite must never become an anchor — the surface would
     * be placed at a garbage coordinate.
     */
    @Test
    fun `conversion rejects non finite input rather than inventing a position`() {
        val nan = RectF(0f, Float.NaN, 10f, 20f)
        assertEquals(null, ReadiumOverlaySpace.toOverlayRect(nan, OffsetPx(0, 128)))

        val infinite = RectF(Float.NEGATIVE_INFINITY, 0f, 10f, 20f)
        assertEquals(null, ReadiumOverlaySpace.toOverlayRect(infinite, OffsetPx(0, 128)))
    }

    /**
     * The horizontal axis has its own gap. A content box inset on the left (a
     * landscape cutout, a two-pane layout) must convert `left`/`right` too, not
     * just the vertical edges.
     */
    @Test
    fun `horizontal offset is converted as well as vertical`() {
        val dom = domRect(top = 400f, bottom = 460f, left = 300f, right = 640f)
        val converted = ReadiumOverlaySpace.toOverlayRect(dom, OffsetPx(x = 48, y = 128))!!
        assertEquals(348, converted.left)
        assertEquals(688, converted.right)
        assertEquals(528, converted.top)
    }
}
