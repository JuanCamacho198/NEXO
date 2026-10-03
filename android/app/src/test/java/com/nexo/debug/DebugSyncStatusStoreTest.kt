package com.nexo.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DebugSyncStatusStoreTest {
    @Before
    fun setUp() {
        DebugSyncStatusStore.reset()
    }

    @After
    fun tearDown() {
        DebugSyncStatusStore.reset()
    }

    @Test
    fun `starts empty`() {
        val snapshot = DebugSyncStatusStore.snapshot()
        assertNull(snapshot.lastSyncAtMs)
        assertNull(snapshot.lastFailure)
    }

    @Test
    fun `recordSuccess stamps time and clears failure`() {
        DebugSyncStatusStore.recordFailure("boom", nowMs = 100L)
        DebugSyncStatusStore.recordSuccess(nowMs = 200L)

        val snapshot = DebugSyncStatusStore.snapshot()
        assertEquals(200L, snapshot.lastSyncAtMs)
        assertNull(snapshot.lastFailure)
    }

    @Test
    fun `recordFailure stamps time and keeps message`() {
        DebugSyncStatusStore.recordFailure("drive unavailable", nowMs = 42L)

        val snapshot = DebugSyncStatusStore.snapshot()
        assertEquals(42L, snapshot.lastSyncAtMs)
        assertEquals("drive unavailable", snapshot.lastFailure)
    }
}
