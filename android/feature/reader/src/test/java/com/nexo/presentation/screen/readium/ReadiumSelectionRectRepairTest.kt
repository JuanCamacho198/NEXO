package com.nexo.presentation.screen.readium

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the contract of the selection-rect repair.
 *
 * Background: `readium-navigator` 3.2.0's `EpubNavigatorFragment` adds the
 * WebView's `paddingTop` to the selection rect's `top` ONLY, which inverts the
 * rect and stretches it. Measured on `El extranjero`, word `de`
 * (dpr 2.625, reader inset 128, paddingTop 236): the raw JS rect is
 * `[top=786, bottom=838]` and Readium delivers `top=1022, bottom=838`.
 *
 * These tests reproduce those numbers rather than an invented ones, so the
 * arithmetic that justified the fix stays executable.
 */
@RunWith(RobolectricTestRunner::class)
class ReadiumSelectionRectRepairTest {
    private val lineHeightPx = 96f

    // ── parseRawRect ─────────────────────────────────────────────

    @Test
    fun `parseRawRect reads the payload WebView actually delivers`() {
        // evaluateJavascript JSON-encodes the string the page returned.
        val encoded = "\"{\\\"left\\\":786.25,\\\"top\\\":838.1,\\\"right\\\":834.5,\\\"bottom\\\":890.0}\""

        val rect = ReadiumSelectionRectRepair.parseRawRect(encoded)

        assertNotNull(rect)
        assertEquals(786.25f, rect!!.left, 0.001f)
        assertEquals(838.1f, rect.top, 0.001f)
        assertEquals(834.5f, rect.right, 0.001f)
        assertEquals(890.0f, rect.bottom, 0.001f)
    }

    @Test
    fun `parseRawRect returns null for the no-selection sentinel`() {
        assertNull(ReadiumSelectionRectRepair.parseRawRect("null"))
        assertNull(ReadiumSelectionRectRepair.parseRawRect("\"null\""))
        assertNull(ReadiumSelectionRectRepair.parseRawRect(null))
        assertNull(ReadiumSelectionRectRepair.parseRawRect(""))
    }

    @Test
    fun `parseRawRect returns null rather than a partial rect for malformed input`() {
        assertNull(ReadiumSelectionRectRepair.parseRawRect("\"not json\""))
        assertNull(ReadiumSelectionRectRepair.parseRawRect("\"{\\\"left\\\":1}\""))
        // A non-finite edge must not become a rect the layout would trust.
        assertNull(ReadiumSelectionRectRepair.parseRawRect("\"{\\\"left\\\":0,\\\"top\\\":NaN,\\\"right\\\":5,\\\"bottom\\\":9}\""))
    }

    // ── repairTopOnlyPadding ─────────────────────────────────────

    @Test
    fun `repair undoes the measured inversion in the wrong place`() {
        // Exactly what Readium delivered for the word "de" (its `top` carries
        // the WebView's 236px padding; its `bottom` is the untouched line bottom).
        val delivered = RectF(316.9f, 1022.2f, 365.2f, 838.2f)

        val repaired = ReadiumSelectionRectRepair.repairTopOnlyPadding(delivered, lineHeightPx)

        // The bottom edge survived the defect, so the true line ends there.
        assertEquals(838.2f, repaired.bottom, 0.001f)
        assertEquals(838.2f - lineHeightPx, repaired.top, 0.001f)
        assertEquals(316.9f, repaired.left, 0.001f)
        assertEquals(365.2f, repaired.right, 0.001f)
        // Ordered and line-shaped, not 184px tall.
        assertEquals(true, repaired.bottom > repaired.top)
    }

    @Test
    fun `repair fixes an asymmetric error, which is why a constant offset cannot`() {
        // The defect's signature: the corrupted top edge is shifted by the whole
        // padding, while the surviving bottom edge is not shifted at all. A
        // constant offset could not represent this, so a magic number upstream
        // would move the bug rather than remove it.
        val paddingTop = 236f
        val lineHeight = 54f
        val rawTop = 786.2f
        val rawBottom = rawTop + lineHeight

        // What Readium delivers: top padded, bottom untouched -> inverted.
        val delivered = RectF(316.9f, rawTop + paddingTop, 365.2f, rawBottom)
        assertEquals(true, delivered.top > delivered.bottom)

        val repaired = ReadiumSelectionRectRepair.repairTopOnlyPadding(delivered, lineHeight)

        // The repair recovers the true line: it ends at the surviving bottom.
        assertEquals(rawTop, repaired.top, 0.001f)
        assertEquals(rawBottom, repaired.bottom, 0.001f)
        // And the two errors were never equal, which is the whole point.
        assertEquals(paddingTop, delivered.top - rawTop, 0.001f)
        assertEquals(0f, delivered.bottom - rawBottom, 0.001f)
    }

    @Test
    fun `repair leaves a well formed single line rect untouched`() {
        val sane = RectF(100f, 400f, 300f, 450f)

        val repaired = ReadiumSelectionRectRepair.repairTopOnlyPadding(sane, lineHeightPx)

        assertEquals(sane, repaired)
    }

    @Test
    fun `repair keeps a genuinely multi line rect as the caller measured it`() {
        // A wrapped selection is legitimately taller than one line but still
        // within the tolerance; it must not be narrowed to a single line.
        val wrapped = RectF(100f, 400f, 300f, 400f + lineHeightPx)

        val repaired = ReadiumSelectionRectRepair.repairTopOnlyPadding(wrapped, lineHeightPx)

        assertEquals(400f, repaired.top, 0.001f)
        assertEquals(400f + lineHeightPx, repaired.bottom, 0.001f)
    }

    @Test
    fun `repair narrows a rect stretched past the multi line tolerance`() {
        // Readium's stretched rect is lineHeight + paddingTop tall; at any
        // plausible padding that exceeds the tolerance for a real selection.
        val stretched = RectF(100f, 400f, 300f, 400f + lineHeightPx * 2f)

        val repaired = ReadiumSelectionRectRepair.repairTopOnlyPadding(stretched, lineHeightPx)

        assertEquals(400f + lineHeightPx * 2f - lineHeightPx, repaired.top, 0.001f)
    }

    @Test
    fun `repair is a no-op when the line height is unknown`() {
        val inverted = RectF(10f, 500f, 200f, 100f)

        val repaired = ReadiumSelectionRectRepair.repairTopOnlyPadding(inverted, 0f)

        assertEquals(inverted, repaired)
    }
}
