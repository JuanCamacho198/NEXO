package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Retention pruning (FR-14). Each statement deletes only soft-deleted rows
 * whose tombstone is older than the cutoff; live rows (`deleted_at IS NULL`)
 * are never touched. Every statement is scoped to one account: pruning on
 * behalf of user A must never delete user B's rows.
 */
@Dao
interface RetentionDao {
    /**
     * Every distinct account that owns a soft-deleted row in any pruned table.
     * The retention worker walks these owners so pruning stays per-account even
     * though the job itself is device-wide.
     */
    @Query(
        "SELECT user_id FROM books WHERE deleted_at IS NOT NULL " +
            "UNION SELECT user_id FROM highlights WHERE deleted_at IS NOT NULL " +
            "UNION SELECT user_id FROM bookmarks WHERE deleted_at IS NOT NULL",
    )
    suspend fun distinctPrunableOwners(): List<String>

    @Query("DELETE FROM books WHERE user_id = :userId AND deleted_at IS NOT NULL AND deleted_at < :cutoffEpochMillis")
    suspend fun pruneBooks(
        userId: String,
        cutoffEpochMillis: Long,
    ): Int

    @Query("DELETE FROM highlights WHERE user_id = :userId AND deleted_at IS NOT NULL AND deleted_at < :cutoffEpochMillis")
    suspend fun pruneHighlights(
        userId: String,
        cutoffEpochMillis: Long,
    ): Int

    @Query("DELETE FROM bookmarks WHERE user_id = :userId AND deleted_at IS NOT NULL AND deleted_at < :cutoffEpochMillis")
    suspend fun pruneBookmarks(
        userId: String,
        cutoffEpochMillis: Long,
    ): Int
}
