package com.nexo.presentation.viewmodel

import com.nexo.debug.SentryMetrics
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PP-1 A9: the Performance screen data source must return measured in-session
 * aggregates, never generated values. `save_highlight` (no P0 instrument)
 * must be absent so the screen renders an explicit no-data state.
 */
class RealPerformanceDataSourceTest {
    @Test
    fun `timings come from in-session aggregates only`() {
        SentryMetrics.clearForTest()
        val source = RealPerformanceDataSource(appContext = null)

        // No data yet: no rows at all (save_highlight included).
        assertTrue(source.generateTimings().isEmpty())

        val tags = mapOf("platform" to "android", "source" to "app_shell")
        SentryMetrics.distribution("app_cold_start", 2000, tags)
        SentryMetrics.distribution("reader_open", 500, tags)

        val timings = source.generateTimings()
        assertEquals(2, timings.size)

        val coldStart = timings.first { it.key == "cold_start" }
        assertNotNull(coldStart)
        assertEquals(2000L, coldStart.avgMs)
        assertEquals(2000L, coldStart.maxMs)

        val openReader = timings.first { it.key == "open_reader" }
        assertEquals(500L, openReader.avgMs)

        // save_highlight has no P0 instrument — never present (no-data state).
        assertTrue(timings.none { it.key == "save_highlight" })
        // sync_pull only appears after sync instrumentation ran this session.
        assertTrue(timings.none { it.key == "sync_pull" })
    }

    @Test
    fun `no random-derived values exist anywhere in the source`() {
        val source = RealPerformanceDataSource(appContext = null)
        runTest {
            val resources = source.loadResources()
            assertEquals(0L, resources.dbSizeBytes)
            val status = source.loadSyncStatus()
            assertEquals(0, status.outboxPending)
            val diagnostics = source.loadDiagnostics()
            assertEquals(0, diagnostics.crashes.size)
        }
    }
}
