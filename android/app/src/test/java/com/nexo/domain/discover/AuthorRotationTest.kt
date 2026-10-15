package com.nexo.domain.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * S1: the author rail rotation is a pure function of the LOCAL calendar day.
 * The index advances exactly one slot per day, is stable within a day, and the
 * three-entry window wraps at the end of the curated list.
 */
class AuthorRotationTest {
    @Test
    fun rotation_hasTenCuratedAuthorsInDesktopOrder() {
        assertEquals(10, AUTHOR_ROTATION.size)
        assertEquals(
            listOf(
                "doyle",
                "poe",
                "wilde",
                "verne",
                "kafka",
                "dickens",
                "london",
                "wells",
                "twain",
                "melville",
            ),
            AUTHOR_ROTATION.map { it.slug },
        )
    }

    @Test
    fun startIndex_dayOneIsZero_dayTenIsNine_dayElevenWrapsToZero() {
        val year = 2026
        assertEquals(0, startIndexFor(LocalDate.of(year, 1, 1)))
        assertEquals(9, startIndexFor(LocalDate.of(year, 1, 10)))
        assertEquals(0, startIndexFor(LocalDate.of(year, 1, 11)))
    }

    @Test
    fun startIndex_advancesByExactlyOnePerLocalDay() {
        val base = startIndexFor(LocalDate.of(2026, 3, 4))
        assertEquals((base + 1) % 10, startIndexFor(LocalDate.of(2026, 3, 5)))
        assertEquals((base + 2) % 10, startIndexFor(LocalDate.of(2026, 3, 6)))
    }

    @Test
    fun startIndex_isStableForTheSameDay() {
        val date = LocalDate.of(2026, 7, 15)
        assertEquals(startIndexFor(date), startIndexFor(date))
        assertEquals(startIndexFor(date), startIndexFor(LocalDate.of(2026, 7, 15)))
    }

    @Test
    fun startIndex_usesLocalDayOfYearAcrossMonthBoundaries() {
        // dayOfYear: Jan 31 -> 31, Feb 1 -> 32, so both map to (day-1) mod 10 = 0 and 1.
        assertEquals(0, startIndexFor(LocalDate.of(2026, 1, 31)))
        assertEquals(1, startIndexFor(LocalDate.of(2026, 2, 1)))
    }

    @Test
    fun entriesFor_returnsThreeConsecutiveAuthorsFromTheStartIndex() {
        val date = LocalDate.of(2026, 1, 1)
        val start = startIndexFor(date)
        val entries = entriesFor(date)
        assertEquals(DISCOVER_RAIL_COUNT, entries.size)
        assertEquals(3, DISCOVER_RAIL_COUNT)
        assertEquals(AUTHOR_ROTATION[start], entries[0])
        assertEquals(AUTHOR_ROTATION[(start + 1) % 10], entries[1])
        assertEquals(AUTHOR_ROTATION[(start + 2) % 10], entries[2])
    }

    @Test
    fun entriesFor_windowWrapsAroundTheEndOfTheList() {
        // Day 10 -> index 9 (melville); the window must wrap to doyle and poe.
        val entries = entriesFor(LocalDate.of(2026, 1, 10))
        assertEquals(listOf("melville", "doyle", "poe"), entries.map { it.slug })
    }

    @Test
    fun entriesFor_dayElevenMatchesDayOneWindow() {
        val dayOne = entriesFor(LocalDate.of(2026, 1, 1)).map { it.slug }
        val dayEleven = entriesFor(LocalDate.of(2026, 1, 11)).map { it.slug }
        assertEquals(dayOne, dayEleven)
    }

    @Test
    fun entriesFor_neverRepeatsAnAuthorWithinADay() {
        for (dayOfYear in 1..20) {
            val slugs = entriesFor(LocalDate.ofYearDay(2026, dayOfYear)).map { it.slug }
            assertEquals(3, slugs.toSet().size)
        }
    }

    @Test
    fun entriesFor_advancesTheWindowByOnePerDay() {
        val today = entriesFor(LocalDate.of(2026, 5, 20)).map { it.slug }
        val tomorrow = entriesFor(LocalDate.of(2026, 5, 21)).map { it.slug }
        assertNotEquals(today, tomorrow)
        assertEquals(today.drop(1), tomorrow.take(2))
    }

    @Test
    fun entriesFor_supportsACustomCount() {
        val entries = entriesFor(LocalDate.of(2026, 1, 1), count = 1)
        assertEquals(1, entries.size)
        assertTrue(entries.size <= AUTHOR_ROTATION.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun entriesFor_rejectsACountAboveTheListLength() {
        entriesFor(LocalDate.of(2026, 1, 1), count = AUTHOR_ROTATION.size + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun entriesFor_rejectsAZeroCount() {
        entriesFor(LocalDate.of(2026, 1, 1), count = 0)
    }
}
