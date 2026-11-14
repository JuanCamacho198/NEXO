package com.nexo.ui.components.molecules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE DEFINITION PANEL'S BODY MUST BE CAPPED, AND THE CAP MUST NEVER COLLAPSE IT.
 *
 * The shipped defect: `AnchoredDefinitionPanel` had no height cap and no
 * internal scroll, so a word with several senses grew the card until it consumed
 * the screen and pushed the Close button past the fold. The cap is a pure
 * function of the room the overlay has, so it is asserted here with numbers
 * rather than described in a comment.
 *
 * What can fail: a cap that is unbounded (the defect), a cap that COLLAPSES the
 * body to nothing in a short landscape viewport, and a cap that exceeds the room
 * the card actually has.
 */
class DefinitionPanelHeightTest {
    private val chrome = 220
    private val minBody = 96

    @Test
    fun `a long definition is capped well below a portrait viewport`() {
        // A 2200px-tall inset box with a ~220px chrome reserve.
        val cap = definitionPanelMaxHeightPx(availableHeightPx = 2200, chromeHeightPx = chrome, minHeightPx = minBody)
        assertEquals("half the room, under the chrome reserve", 1210, cap)
        assertTrue("the cap must be a bound, not the whole screen", cap < 2200)
    }

    @Test
    fun `a landscape viewport gets a smaller, still usable cap`() {
        // A ~600px-tall inset box: room for the body is 380, the fraction bound 330.
        val portrait = definitionPanelMaxHeightPx(2200, chrome, minBody)
        val landscape = definitionPanelMaxHeightPx(600, chrome, minBody)
        assertEquals(330, landscape)
        assertTrue("landscape must get less room than portrait", landscape < portrait)
        assertTrue("and still more than the floor", landscape > minBody)
    }

    @Test
    fun `a viewport too short for the chrome still gives the body its floor`() {
        // 300px of room minus 220px of chrome leaves 80, below the 96 floor.
        val cap = definitionPanelMaxHeightPx(availableHeightPx = 300, chromeHeightPx = chrome, minHeightPx = minBody)
        assertEquals("the floor wins over a negative room", minBody, cap)
    }

    @Test
    fun `an unbounded constraint falls back to the floor rather than to no cap`() {
        val cap = definitionPanelMaxHeightPx(availableHeightPx = 0, chromeHeightPx = chrome, minHeightPx = minBody)
        assertEquals(minBody, cap)
    }

    @Test
    fun `the cap never exceeds the room the overlay was given`() {
        // Sweep the range that matters: portrait phones, landscape, split screen.
        for (available in intArrayOf(0, 300, 600, 900, 1200, 2200, 4000)) {
            val cap = definitionPanelMaxHeightPx(available, chrome, minBody)
            assertTrue(
                "cap $cap must be at least the floor and never grow with no bound (available=$available)",
                cap >= minBody && (available <= 0 || cap <= available),
            )
        }
    }
}
