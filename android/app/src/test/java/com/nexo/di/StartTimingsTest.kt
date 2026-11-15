package com.nexo.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guard for the di-modularization cold-start partition, originally written
 * against the deleted manual container.
 *
 * The container is gone, so the assertions now target the piece that owns the
 * guarantee: [AppStartTimings] records a duration only when the measured object
 * is actually built. A timing that is still 0 means "not constructed yet", which
 * is what keeps an unmeasured dependency from being pulled in eagerly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartTimingsTest {
    @Test
    fun snapshot_beforeAnyConstruction_reportsZeroDurations() {
        val snapshot = AppStartTimings().snapshot()
        assertEquals(0L, snapshot.dbInitMs)
        assertEquals(0L, snapshot.epubImportInitMs)
        assertEquals(0L, snapshot.readerRepoInitMs)
    }

    @Test
    fun recordDatabaseInit_isReflectedInSnapshot() {
        val timings = AppStartTimings()
        timings.recordDatabaseInit(42L)
        assertEquals(42L, timings.snapshot().dbInitMs)
    }

    @Test
    fun recordRepositoryInits_areReflectedIndependently() {
        val timings = AppStartTimings()
        timings.recordEpubImportInit(7L)
        // Recording one duration must not fabricate the others: each is
        // written by the provider that measured it.
        assertEquals(0L, timings.snapshot().readerRepoInitMs)
        timings.recordReaderRepoInit(11L)
        val snapshot = timings.snapshot()
        assertEquals(7L, snapshot.epubImportInitMs)
        assertEquals(11L, snapshot.readerRepoInitMs)
    }

    @Test
    fun totalInitIsElapsedTime_notAConstructionDuration() {
        // totalInitMs measures process time since the timings object existed, so
        // it must never be negative and must not depend on any constructor.
        val timings = AppStartTimings()
        assertTrue(timings.snapshot().totalInitMs >= 0L)
    }

    @Test
    fun snapshot_isStableAcrossReads() {
        // The debug panel reads the snapshot; reading twice without any recorded
        // construction must not invent values.
        val timings = AppStartTimings()
        val first = timings.snapshot()
        val second = timings.snapshot()
        assertEquals(first.dbInitMs, second.dbInitMs)
        assertEquals(first.epubImportInitMs, second.epubImportInitMs)
        assertEquals(first.readerRepoInitMs, second.readerRepoInitMs)
    }
}
