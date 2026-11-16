package com.nexo.presentation.screen.readium

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadiumTapZonesTest {
    private val width = 1000

    @Test
    fun isCenterTap_centerOfViewport_returnsTrue() {
        assertTrue(isCenterTap(x = 500f, viewportWidth = width, edgePercent = 0.15))
    }

    @Test
    fun isCenterTap_insideLeftEdge_returnsFalse() {
        assertFalse(isCenterTap(x = 100f, viewportWidth = width, edgePercent = 0.15))
    }

    @Test
    fun isCenterTap_insideRightEdge_returnsFalse() {
        assertFalse(isCenterTap(x = 900f, viewportWidth = width, edgePercent = 0.15))
    }

    @Test
    fun isCenterTap_exactlyOnLeftBoundary_returnsTrue() {
        assertTrue(isCenterTap(x = 150f, viewportWidth = width, edgePercent = 0.15))
    }

    @Test
    fun isCenterTap_exactlyOnRightBoundary_returnsTrue() {
        assertTrue(isCenterTap(x = 850f, viewportWidth = width, edgePercent = 0.15))
    }

    @Test
    fun isCenterTap_justInsideLeftEdge_returnsFalse() {
        assertFalse(isCenterTap(x = 149f, viewportWidth = width, edgePercent = 0.15))
    }

    @Test
    fun isCenterTap_justInsideRightEdge_returnsFalse() {
        assertFalse(isCenterTap(x = 851f, viewportWidth = width, edgePercent = 0.15))
    }

    @Test
    fun isCenterTap_zeroWidth_returnsFalse() {
        assertFalse(isCenterTap(x = 0f, viewportWidth = 0, edgePercent = 0.15))
    }

    @Test
    fun isCenterTap_negativeWidth_returnsFalse() {
        assertFalse(isCenterTap(x = 0f, viewportWidth = -10, edgePercent = 0.15))
    }

    @Test
    fun tapEdgePercent_isFifteenPerCent() {
        assertTrue(TAP_EDGE_PERCENT == 0.15)
    }
}
