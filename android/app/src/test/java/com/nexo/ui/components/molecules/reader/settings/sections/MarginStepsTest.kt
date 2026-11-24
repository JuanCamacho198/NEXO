package com.nexo.ui.components.molecules.reader.settings.sections

import org.junit.Assert.assertEquals
import org.junit.Test

class MarginStepsTest {
    @Test
    fun defaultsLandOnTheirSteps() {
        assertEquals(16, SIDE_MARGIN_STEPS[nearestStep(SIDE_MARGIN_STEPS, 16)])
        assertEquals(0, VERTICAL_MARGIN_STEPS[nearestStep(VERTICAL_MARGIN_STEPS, 0)])
    }

    @Test
    fun legacyValuesSnapToTheClosestStep() {
        // The old "wide" preset (32) and a hand-set 30 both read as the 32 step.
        assertEquals(32, SIDE_MARGIN_STEPS[nearestStep(SIDE_MARGIN_STEPS, 30)])
        assertEquals(44, SIDE_MARGIN_STEPS[nearestStep(SIDE_MARGIN_STEPS, 100)])
    }
}
