package com.nexo.presentation.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OnboardingGoalScreenTest {
    @Test
    fun goalOptions_containsExactlyFourLevels() {
        assertEquals(4, GOAL_OPTIONS.size)
    }

    @Test
    fun goalOptions_minutesAscendFromRelaxedToIntense() {
        assertEquals(listOf(10, 20, 30, 45), GOAL_OPTIONS.map { it.minutes })
    }

    @Test
    fun goalOptions_titlesAreUnique() {
        val titleResIds = GOAL_OPTIONS.map { it.titleRes }
        assertEquals(titleResIds.size, titleResIds.distinct().size)
    }

    @Test
    fun goalOptions_everyOptionHasWiredTextResources() {
        GOAL_OPTIONS.forEach { option ->
            assertNotEquals(
                "titleRes must be set for minutes=${option.minutes}",
                0,
                option.titleRes,
            )
            assertNotEquals(
                "descriptionRes must be set for minutes=${option.minutes}",
                0,
                option.descriptionRes,
            )
            assertNotEquals(
                "valueRes must be set for minutes=${option.minutes}",
                0,
                option.valueRes,
            )
        }
    }
}
