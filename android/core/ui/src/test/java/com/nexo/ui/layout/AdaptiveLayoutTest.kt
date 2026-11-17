package com.nexo.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2: the large-screen decisions, tested as decisions.
 *
 * The rendering is not faked here — these are the breakpoints the surfaces
 * branch on, and the compact cases assert the phone layout is unchanged.
 */
class AdaptiveLayoutTest {
    @Test
    fun phoneWidths_stayCompact() {
        listOf(320, 360, 411, 599).forEach { width ->
            assertEquals("width=$width", AdaptiveColumns.Compact, AdaptiveLayout.columnsForWidth(width))
            assertEquals("width=$width", 2, AdaptiveLayout.gridColumnCount(width))
        }
    }

    @Test
    fun mediumWidth_addsOneColumn() {
        listOf(600, 700, 839).forEach { width ->
            assertEquals("width=$width", AdaptiveColumns.Medium, AdaptiveLayout.columnsForWidth(width))
            assertEquals("width=$width", 3, AdaptiveLayout.gridColumnCount(width))
        }
    }

    @Test
    fun expandedWidth_usesFourColumns() {
        listOf(840, 1024, 1280).forEach { width ->
            assertEquals("width=$width", AdaptiveColumns.Expanded, AdaptiveLayout.columnsForWidth(width))
            assertEquals("width=$width", 4, AdaptiveLayout.gridColumnCount(width))
        }
    }

    @Test
    fun readerCap_isIdentityBelowMedium() {
        // The phone must keep filling its width: null cap means "no max width".
        listOf(320, 360, 411, 599).forEach { width ->
            assertEquals("width=$width", width, AdaptiveLayout.readerMaxWidthDp(width))
        }
    }

    @Test
    fun readerCap_bitesFromMediumUp() {
        assertEquals(720, AdaptiveLayout.readerMaxWidthDp(600))
        assertEquals(720, AdaptiveLayout.readerMaxWidthDp(1024))
    }

    @Test
    fun twoPane_onlyAtExpanded() {
        // At medium the two panes would each be narrower than the phone layout.
        assertFalse(AdaptiveLayout.showsListAndDetail(600))
        assertTrue(AdaptiveLayout.showsListAndDetail(840))
    }
}
