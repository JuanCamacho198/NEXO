package com.nexo.domain.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * S1: the topic rail rotation is a pure function of the LOCAL calendar day.
 * The index advances exactly one slot per day, is stable within a day, and the
 * two-entry window wraps at the end of the curated list.
 */
class TopicRotationTest {
    @Test
    fun rotation_hasTenCuratedTopicsInDesktopOrder() {
        assertEquals(10, TOPIC_ROTATION.size)
        assertEquals(
            listOf(
                "science-fiction",
                "adventure",
                "history",
                "travel",
                "poetry",
                "philosophy",
                "drama",
                "romance",
                "mystery",
                "horror",
            ),
            TOPIC_ROTATION.map { it.slug },
        )
    }

    @Test
    fun rotation_pinsTheTwoRequiredLeadingShelves() {
        // The target names these two explicitly; both are members and both lead.
        assertEquals("science fiction", TOPIC_ROTATION[0].term)
        assertEquals("adventure", TOPIC_ROTATION[1].term)
        // Measured-thinnest term stays last so it never leads a day.
        assertEquals("horror", TOPIC_ROTATION.last().term)
    }

    @Test
    fun rotation_termsAreCleanTopicValues() {
        for (entry in TOPIC_ROTATION) {
            assertTrue("term must not be blank", entry.term.isNotBlank())
            assertTrue("term must not carry a colon: ${entry.term}", ':' !in entry.term)
            assertNotEquals("popular", entry.term)
            assertTrue("key must be namespaced: ${entry.titleKey}", entry.titleKey.startsWith("discover_rail_topic_"))
        }
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
    fun entriesFor_returnsTwoConsecutiveTopicsFromTheStartIndex() {
        val date = LocalDate.of(2026, 1, 1)
        val start = startIndexFor(date)
        val entries = entriesFor(date)
        assertEquals(DISCOVER_RAIL_COUNT, entries.size)
        assertEquals(2, DISCOVER_RAIL_COUNT)
        assertEquals(TOPIC_ROTATION[start], entries[0])
        assertEquals(TOPIC_ROTATION[(start + 1) % 10], entries[1])
    }

    @Test
    fun entriesFor_windowWrapsAroundTheEndOfTheList() {
        // Day 10 -> index 9 (horror); the window must wrap to science fiction.
        val entries = entriesFor(LocalDate.of(2026, 1, 10))
        assertEquals(listOf("horror", "science-fiction"), entries.map { it.slug })
    }

    @Test
    fun entriesFor_dayElevenMatchesDayOneWindow() {
        val dayOne = entriesFor(LocalDate.of(2026, 1, 1)).map { it.slug }
        val dayEleven = entriesFor(LocalDate.of(2026, 1, 11)).map { it.slug }
        assertEquals(dayOne, dayEleven)
    }

    @Test
    fun entriesFor_neverRepeatsATopicWithinADay() {
        for (dayOfYear in 1..20) {
            val slugs = entriesFor(LocalDate.ofYearDay(2026, dayOfYear)).map { it.slug }
            assertEquals(DISCOVER_RAIL_COUNT, slugs.toSet().size)
        }
    }

    @Test
    fun entriesFor_advancesTheWindowByOnePerDay() {
        val today = entriesFor(LocalDate.of(2026, 5, 20)).map { it.slug }
        val tomorrow = entriesFor(LocalDate.of(2026, 5, 21)).map { it.slug }
        assertNotEquals(today, tomorrow)
        assertEquals(today.drop(1), tomorrow.take(1))
    }

    @Test
    fun entriesFor_supportsACustomCount() {
        val entries = entriesFor(LocalDate.of(2026, 1, 1), count = 1)
        assertEquals(1, entries.size)
        assertTrue(entries.size <= TOPIC_ROTATION.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun entriesFor_rejectsACountAboveTheListLength() {
        entriesFor(LocalDate.of(2026, 1, 1), count = TOPIC_ROTATION.size + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun entriesFor_rejectsAZeroCount() {
        entriesFor(LocalDate.of(2026, 1, 1), count = 0)
    }
}
