package com.nexo.data.remote.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class LastWriteWinsConflictResolverTest {
    private val resolver = LastWriteWinsConflictResolver<TestRecord>()

    @Test
    fun resolve_returnsRemote_whenLocalIsNull() {
        val remote = TestRecord(recordId = "1", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)

        val result = resolver.resolve(local = null, remote = remote)

        assertEquals(remote, result)
    }

    @Test
    fun resolve_returnsRemote_whenRemoteIsNewer() {
        val local = TestRecord(recordId = "1", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)
        val remote = TestRecord(recordId = "1", updatedAtEpochMillis = 2000L, deletedAtEpochMillis = null)

        val result = resolver.resolve(local = local, remote = remote)

        assertEquals(remote, result)
    }

    @Test
    fun resolve_returnsLocal_whenLocalIsNewer() {
        val local = TestRecord(recordId = "1", updatedAtEpochMillis = 2000L, deletedAtEpochMillis = null)
        val remote = TestRecord(recordId = "1", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)

        val result = resolver.resolve(local = local, remote = remote)

        assertEquals(local, result)
    }

    @Test
    fun resolve_returnsRemote_whenTimestampsEqual_andRemoteIdIsGreater() {
        val local = TestRecord(recordId = "a", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)
        val remote = TestRecord(recordId = "b", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)

        val result = resolver.resolve(local = local, remote = remote)

        assertEquals(remote, result)
    }

    @Test
    fun resolve_returnsLocal_whenTimestampsEqual_andLocalIdIsGreater() {
        val local = TestRecord(recordId = "b", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)
        val remote = TestRecord(recordId = "a", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)

        val result = resolver.resolve(local = local, remote = remote)

        assertEquals(local, result)
    }

    @Test
    fun resolve_returnsRemote_whenRemoteIsDeleted_andLocalIsNot() {
        val local = TestRecord(recordId = "1", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)
        val remote = TestRecord(recordId = "1", updatedAtEpochMillis = 500L, deletedAtEpochMillis = 2000L)

        val result = resolver.resolve(local = local, remote = remote)

        assertEquals(remote, result)
    }

    @Test
    fun resolve_returnsLocal_whenLocalIsDeleted_andRemoteIsNot() {
        val local = TestRecord(recordId = "1", updatedAtEpochMillis = 500L, deletedAtEpochMillis = 2000L)
        val remote = TestRecord(recordId = "1", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = null)

        val result = resolver.resolve(local = local, remote = remote)

        assertEquals(local, result)
    }

    @Test
    fun resolve_returnsRemote_whenBothDeleted_andRemoteDeletedLater() {
        val local = TestRecord(recordId = "1", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = 1000L)
        val remote = TestRecord(recordId = "1", updatedAtEpochMillis = 2000L, deletedAtEpochMillis = 2000L)

        val result = resolver.resolve(local = local, remote = remote)

        assertEquals(remote, result)
    }

    @Test
    fun resolve_returnsLocal_whenBothDeleted_andLocalDeletedLater() {
        val local = TestRecord(recordId = "1", updatedAtEpochMillis = 2000L, deletedAtEpochMillis = 2000L)
        val remote = TestRecord(recordId = "1", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = 1000L)

        val result = resolver.resolve(local = local, remote = remote)

        assertEquals(local, result)
    }

    @Test
    fun resolve_returnsLocal_whenBothDeleted_andTimestampsEqual() {
        // When both have same deletion time, local wins (>= comparison returns local)
        val local = TestRecord(recordId = "a", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = 1000L)
        val remote = TestRecord(recordId = "b", updatedAtEpochMillis = 1000L, deletedAtEpochMillis = 1000L)

        val result = resolver.resolve(local = local, remote = remote)

        // deletedAt comparison: 1000 >= 1000 = true, returns local
        assertEquals(local, result)
    }

    private data class TestRecord(
        override val recordId: String,
        override val updatedAtEpochMillis: Long,
        override val deletedAtEpochMillis: Long?,
    ) : VersionedSyncRecord
}
