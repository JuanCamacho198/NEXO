package com.nexo.presentation.feature.editmetadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatePickerFieldTest {
    // ── isoToEpochMillis ─────────────────────────────────────────────

    @Test
    fun `isoToEpochMillis null returns null`() {
        assertNull(isoToEpochMillis(null))
    }

    @Test
    fun `isoToEpochMillis blank returns null via exception`() {
        assertNull(isoToEpochMillis(""))
        assertNull(isoToEpochMillis("   "))
    }

    @Test
    fun `isoToEpochMillis invalid iso returns null`() {
        assertNull(isoToEpochMillis("not-a-date"))
        assertNull(isoToEpochMillis("2024-13-01"))
        assertNull(isoToEpochMillis("2024/01/15"))
        assertNull(isoToEpochMillis("abcd-01-01"))
    }

    @Test
    fun `isoToEpochMillis valid iso returns epoch at UTC midnight`() {
        // 1970-01-01 UTC -> 0
        assertEquals(0L, isoToEpochMillis("1970-01-01"))
        // 1970-01-02 UTC -> 86400000
        assertEquals(86_400_000L, isoToEpochMillis("1970-01-02"))
        // 2024-01-15 UTC -> 1705276800000
        assertEquals(1_705_276_800_000L, isoToEpochMillis("2024-01-15"))
    }

    @Test
    fun `isoToEpochMillis is UTC not local`() {
        // Same wall date must give same millis regardless of local TZ.
        // Verify by checking known UTC value, not local midnight.
        val millis = isoToEpochMillis("2024-06-15")!!
        // 2024-06-15 00:00 UTC = Instant, convert back should equal same iso
        assertEquals("2024-06-15", epochMillisToIso(millis))
    }

    @Test
    fun `isoToEpochMillis exception path returns null not throw`() {
        // Exception branch: e.g. month 99
        val result =
            try {
                isoToEpochMillis("2024-99-99")
            } catch (e: Exception) {
                throw AssertionError("should not throw, should return null", e)
            }
        assertNull(result)
    }

    // ── epochMillisToIso ─────────────────────────────────────────────

    @Test
    fun `epochMillisToIso null returns null`() {
        assertNull(epochMillisToIso(null))
    }

    @Test
    fun `epochMillisToIso 0 returns 1970-01-01`() {
        assertEquals("1970-01-01", epochMillisToIso(0L))
    }

    @Test
    fun `epochMillisToIso known millis returns iso date UTC`() {
        assertEquals("1970-01-02", epochMillisToIso(86_400_000L))
        assertEquals("2024-01-15", epochMillisToIso(1_705_276_800_000L))
    }

    // ── round-trip ───────────────────────────────────────────────────

    @Test
    fun `iso epoch round-trip is lossless`() {
        val dates =
            listOf(
                "1970-01-01",
                "2024-01-15",
                "2024-06-15",
                "1937-09-21",
                "2000-12-31",
                "2025-02-28",
                "2024-02-29", // leap year
            )
        for (iso in dates) {
            val millis = isoToEpochMillis(iso)
            assertTrue("isoToEpochMillis($iso) should not be null", millis != null)
            assertEquals(iso, epochMillisToIso(millis))
        }
    }

    @Test
    fun `epoch iso round-trip is lossless`() {
        val millisValues = listOf(0L, 86_400_000L, 1_705_276_800_000L)
        for (millis in millisValues) {
            val iso = epochMillisToIso(millis)!!
            assertEquals(millis, isoToEpochMillis(iso))
        }
    }

    // ── formatPublishedDate ──────────────────────────────────────────

    @Test
    fun `formatPublishedDate null and blank return dash`() {
        assertEquals("—", formatPublishedDate(null))
        assertEquals("—", formatPublishedDate(""))
        assertEquals("—", formatPublishedDate("   "))
    }

    @Test
    fun `formatPublishedDate returns iso as-is`() {
        assertEquals("2024-01-15", formatPublishedDate("2024-01-15"))
    }

    // ── LANGUAGE_OPTIONS ─────────────────────────────────────────────

    @Test
    fun `LANGUAGE_OPTIONS has 24 entries`() {
        assertEquals(24, LANGUAGE_OPTIONS.size)
    }

    @Test
    fun `LANGUAGE_OPTIONS contains expected codes`() {
        val expected = listOf("en", "es", "fr", "de", "it", "pt", "nl", "ru", "zh", "ja", "ko", "ar")
        for (code in expected) {
            assertTrue("LANGUAGE_OPTIONS should contain $code", LANGUAGE_OPTIONS.contains(code))
        }
    }

    @Test
    fun `LANGUAGE_OPTIONS has no duplicates`() {
        assertEquals(LANGUAGE_OPTIONS.size, LANGUAGE_OPTIONS.toSet().size)
    }

    @Test
    fun `LANGUAGE_OPTIONS are all lower-case two-letter codes`() {
        for (code in LANGUAGE_OPTIONS) {
            assertTrue("code $code should be 2 letters lowercase", code.matches(Regex("[a-z]{2}")))
        }
    }

    @Test
    fun `dynamic extra language prepended when not in options`() {
        // Reproduces LanguageDropdown remember block
        fun optionsFor(selectedCode: String?): List<String> =
            if (selectedCode != null && LANGUAGE_OPTIONS.none { it.equals(selectedCode, ignoreCase = true) }) {
                listOf(selectedCode) + LANGUAGE_OPTIONS
            } else {
                LANGUAGE_OPTIONS
            }

        val withExtra = optionsFor("xx")
        assertEquals(25, withExtra.size)
        assertEquals("xx", withExtra.first())
        assertTrue(withExtra.containsAll(LANGUAGE_OPTIONS))

        val caseInsensitive = optionsFor("EN")
        assertEquals(24, caseInsensitive.size)

        val known = optionsFor("en")
        assertEquals(24, known.size)

        val nullSel = optionsFor(null)
        assertEquals(24, nullSel.size)
    }

    @Test
    fun `displayLanguageName returns non-blank for known code`() {
        val name = displayLanguageName("en")
        assertTrue(name.isNotBlank())
    }
}
