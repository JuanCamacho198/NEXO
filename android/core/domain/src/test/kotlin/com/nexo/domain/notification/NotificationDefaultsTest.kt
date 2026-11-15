package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/** [defaultCategory] and the full [defaultInterruption] table plus its fallbacks. */
class NotificationDefaultsTest {
    @Test
    fun `defaultCategory maps nudge sources to NUDGE`() {
        assertEquals(Category.NUDGE, defaultCategory(NotificationSource.STREAK))
        assertEquals(Category.NUDGE, defaultCategory(NotificationSource.GOAL))
    }

    @Test
    fun `defaultCategory maps every other source to SYSTEM`() {
        val systemSources =
            NotificationSource.entries - setOf(NotificationSource.STREAK, NotificationSource.GOAL)
        for (source in systemSources) {
            assertEquals("source=$source", Category.SYSTEM, defaultCategory(source))
        }
    }

    @Test
    fun `defaultInterruption table maps all 15 entries`() {
        val table =
            mapOf(
                (NotificationSource.IMPORT to Severity.SUCCESS) to Interruption.SILENT,
                (NotificationSource.IMPORT to Severity.ERROR) to Interruption.IN_APP,
                (NotificationSource.SYNC to Severity.SUCCESS) to Interruption.SILENT,
                (NotificationSource.SYNC to Severity.ERROR) to Interruption.IN_APP,
                (NotificationSource.ADDONS to Severity.SUCCESS) to Interruption.SILENT,
                (NotificationSource.ADDONS to Severity.ERROR) to Interruption.IN_APP,
                (NotificationSource.UPDATE to Severity.INFO) to Interruption.SILENT,
                (NotificationSource.LIBRARY to Severity.SUCCESS) to Interruption.SILENT,
                (NotificationSource.STREAK to Severity.WARNING) to Interruption.SYSTEM,
                (NotificationSource.STREAK to Severity.ERROR) to Interruption.SYSTEM,
                (NotificationSource.STREAK to Severity.SUCCESS) to Interruption.SYSTEM,
                (NotificationSource.GOAL to Severity.INFO) to Interruption.SYSTEM,
                (NotificationSource.GOAL to Severity.WARNING) to Interruption.SYSTEM,
                (NotificationSource.GOAL to Severity.ERROR) to Interruption.SYSTEM,
                (NotificationSource.GOAL to Severity.SUCCESS) to Interruption.IN_APP,
            )
        assertEquals(15, table.size)
        for ((key, expected) in table) {
            val (source, severity) = key
            assertEquals(
                "$source:$severity",
                expected,
                defaultInterruption(source, severity, defaultCategory(source)),
            )
        }
    }

    @Test
    fun `defaultInterruption falls back to SYSTEM for nudges not in the table`() {
        // LIBRARY is a SYSTEM source; forcing the NUDGE category exercises the nudge fallback.
        assertEquals(
            Interruption.SYSTEM,
            defaultInterruption(NotificationSource.LIBRARY, Severity.INFO, Category.NUDGE),
        )
    }

    @Test
    fun `defaultInterruption falls back to IN_APP for non-nudge errors`() {
        assertEquals(
            Interruption.IN_APP,
            defaultInterruption(NotificationSource.UPDATE, Severity.ERROR, Category.SYSTEM),
        )
    }

    @Test
    fun `defaultInterruption falls back to SILENT otherwise`() {
        assertEquals(
            Interruption.SILENT,
            defaultInterruption(NotificationSource.IMPORT, Severity.INFO, Category.SYSTEM),
        )
    }
}
