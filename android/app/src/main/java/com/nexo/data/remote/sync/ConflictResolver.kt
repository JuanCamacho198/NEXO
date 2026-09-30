package com.nexo.data.remote.sync

interface ConflictResolver<T> {
    fun resolve(
        local: T?,
        remote: T,
    ): T
}

interface VersionedSyncRecord {
    val recordId: String
    val updatedAtEpochMillis: Long
    val deletedAtEpochMillis: Long?
}

/**
 * LWW resolver unified for all 5 tables:
 * reading_progress, highlights, bookmarks, reading_sessions, user_books.
 * Rule: remote.updatedAt > local → remote; < → local; == → recordId
 * lexicographic (remote > local → remote else local). Tombstone:
 * deletedAt comparison as above — any deleted beats non-deleted, both
 * deleted → later deletedAt wins, tie → local.
 */
class LastWriteWinsConflictResolver<T : VersionedSyncRecord> : ConflictResolver<T> {
    override fun resolve(
        local: T?,
        remote: T,
    ): T {
        val localRecord = local ?: return remote

        if (localRecord.deletedAtEpochMillis != null || remote.deletedAtEpochMillis != null) {
            return resolveTombstone(localRecord, remote)
        }

        return chooseLatest(localRecord, remote)
    }

    private fun resolveTombstone(
        local: T,
        remote: T,
    ): T {
        val localDeletedAt = local.deletedAtEpochMillis
        val remoteDeletedAt = remote.deletedAtEpochMillis

        if (localDeletedAt != null && remoteDeletedAt == null) return local
        if (localDeletedAt == null && remoteDeletedAt != null) return remote
        if (localDeletedAt != null && remoteDeletedAt != null) {
            return if (localDeletedAt >= remoteDeletedAt) local else remote
        }
        return chooseLatest(local, remote)
    }

    private fun chooseLatest(
        local: T,
        remote: T,
    ): T {
        if (remote.updatedAtEpochMillis > local.updatedAtEpochMillis) return remote
        if (remote.updatedAtEpochMillis < local.updatedAtEpochMillis) return local
        return if (remote.recordId > local.recordId) remote else local
    }
}
