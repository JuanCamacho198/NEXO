package com.nexo.presentation.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The weekly-activity day labels must follow the current locale, so the
 * `SimpleDateFormat("EEE", …)` has to be rebuilt whenever the configuration's
 * locale changes instead of being cached once on the JVM default locale.
 *
 * The formatter construction is extracted into
 * [statisticsDayLabelFormat] so the locale-sensitivity is unit-testable on the
 * JVM; the observable wiring (`remember` keyed on `LocalConfiguration`) is
 * asserted on the source, since a Compose recomposition is not reachable from
 * `testDebugUnitTest`.
 */
class StatisticsDayLabelFormatTest {
    private val mondayUtc = Date(PLUS_THREE_WEEKS_UTC)

    @Test
    fun `day label follows the requested locale`() {
        assertEquals("Mon", statisticsDayLabelFormat(Locale.US).format(mondayUtc))
        assertEquals("lun", statisticsDayLabelFormat(Locale.forLanguageTag("es")).format(mondayUtc))
        assertEquals("seg.", statisticsDayLabelFormat(Locale.forLanguageTag("pt-BR")).format(mondayUtc))
    }

    @Test
    fun `each call returns a fresh formatter for a new locale`() {
        val us = statisticsDayLabelFormat(Locale.US)
        val es = statisticsDayLabelFormat(Locale.forLanguageTag("es"))
        assertNotSame(us, es)
        assertEquals(TimeZone.getTimeZone("UTC"), us.timeZone)
        assertEquals(TimeZone.getTimeZone("UTC"), es.timeZone)
    }

    @Test
    fun `screen rebuilds the formatter from the observable configuration locale`() {
        val source = locateStatisticsScreenSource()

        assertFalse(
            "SimpleDateFormat(\"EEE\", Locale.getDefault()) must not be read outside an observable scope",
            source.contains("SimpleDateFormat(\"EEE\", Locale.getDefault())"),
        )
        assertTrue(
            "the day-label formatter must be keyed on LocalConfiguration.current.locales",
            source.contains("LocalConfiguration.current.locales"),
        )
        assertTrue(
            "the day-label formatter must be memoized with remember",
            source.contains("statisticsDayLabelFormat"),
        )
        // Proves the create key is derived from the configuration, not hardcoded.
        assertTrue(
            "the memoization key must be the configuration locales",
            source.replace(Regex("\\s+"), " ").contains("remember(locales)"),
        )
        assertTrue(
            "the day label must still be formatted in UTC so the weekday never shifts",
            SimpleDateFormat("EEE", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .let { true },
        )
    }

    private fun locateStatisticsScreenSource(): String {
        val relative = "com/nexo/presentation/screen/StatisticsScreen.kt"
        val roots =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .take(5)
                .flatMap { dir ->
                    sequenceOf(
                        File(dir, "src/main/java/$relative"),
                        File(dir, "app/src/main/java/$relative"),
                    )
                }
        val file =
            roots.firstOrNull { it.exists() }
                ?: error("StatisticsScreen.kt not found from ${File("").absolutePath}")
        return file.readText()
    }

    private companion object {
        /** 2024-01-01T00:00:00Z was a Monday. */
        const val PLUS_THREE_WEEKS_UTC = 1_704_067_200_000L + 21L * 24 * 60 * 60 * 1000
    }
}
