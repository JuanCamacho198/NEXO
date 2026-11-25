package com.nexo.ui.components.molecules

import android.graphics.Rect
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaretPlacementTest {
    private fun rect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    private val word = rect(left = 500, top = 600, right = 600, bottom = 650)

    private fun place(
        surface: IntOffset,
        selection: Rect = word,
        surfaceWidth: Int = 400,
        surfaceHeight: Int = 100,
    ) = caretPlacement(
        selection = selection,
        surface = surface,
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        caretWidth = 40,
        caretHeight = 20,
        edgeInsetPx = 50,
        overlapPx = 2,
    )

    @Test
    fun `menu above the text gets a caret under it pointing down at the word`() {
        val caret = place(surface = IntOffset(350, 470))!!

        assertTrue(caret.pointsDown)
        // hangs from the surface's bottom edge (470 + 100), overlapping it by 2px
        assertEquals(568, caret.offset.y)
        // centred on the word (550) minus half the caret
        assertEquals(530, caret.offset.x)
    }

    @Test
    fun `menu below the text gets a caret above it pointing up`() {
        val caret = place(surface = IntOffset(350, 680))!!

        assertFalse(caret.pointsDown)
        assertEquals(680 - 20 + 2, caret.offset.y)
    }

    @Test
    fun `caret stays off the rounded ends when the word is near the screen edge`() {
        val edgeWord = rect(left = 1000, top = 600, right = 1070, bottom = 650)
        val caret = place(surface = IntOffset(680, 470), selection = edgeWord)!!

        // the surface ends at 1080; the caret may go no further right than 1080 - 50 - 40
        assertEquals(990, caret.offset.x)
    }

    @Test
    fun `no caret when the surface had to overlap the selection`() {
        assertNull(place(surface = IntOffset(350, 580)))
    }
}
