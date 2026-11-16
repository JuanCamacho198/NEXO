package com.nexo.domain.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Runs the SAME shared fixture as the desktop Rust test
 * (`desktop/src-tauri/src/reading_day.rs`). One file, no copies, no
 * transcribed expectations: if either client drifts, that client's test fails.
 */
class ReadingDayFixtureTest {
    private fun fixtureFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "packages/reading-day-fixtures/reading-day-fixtures.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("reading-day-fixtures.json not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun sharedFixture_matchesLocalDayContract() {
        val cases = JSONArray(fixtureFile().readText())
        assertTrue("shared fixture must pin the load-bearing cases", cases.length() >= 8)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val instant = Instant.parse(case.getString("instant"))
            val zoneOffsetMinutes = case.getInt("zoneOffsetMinutes")
            val zone = ZoneOffset.ofTotalSeconds(zoneOffsetMinutes * 60)
            assertEquals(
                "instant=${case.getString("instant")} zoneOffsetMinutes=$zoneOffsetMinutes",
                case.getString("expectedDay"),
                ReadingDay.readingDay(instant, zone).toString(),
            )
        }
    }

    @Test
    fun utcPlus13AndMinus11_landOnDifferentCorrectDays() {
        val instant = Instant.parse("2026-10-01T12:00:00Z")
        assertEquals("2026-10-02", ReadingDay.readingDay(instant, ZoneOffset.ofHours(13)).toString())
        assertEquals("2026-10-01", ReadingDay.readingDay(instant, ZoneOffset.ofHours(-11)).toString())
    }

    @Test
    fun canonicalDayKey_isUtcMidnightOfTheLocalDay() {
        val instant = Instant.parse("2026-10-01T12:00:00Z")
        val key = ReadingDay.readingDayStartMillis(instant, ZoneOffset.ofHours(13))
        // Recovering the day must not need the writer's zone.
        assertEquals(
            "2026-10-02",
            Instant
                .ofEpochMilli(key)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString(),
        )
    }
}
