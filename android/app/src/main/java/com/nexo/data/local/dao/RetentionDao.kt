package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Retention pruning (FR-14). Each statement deletes only soft-deleted rows
 * whose tombstone is older than the cutoff; live rows (`deleted_at IS NULL`)
 * are never touched.
 */
@Dao
interface RetentionDao {
    @Query("DELETE FROM books WHERE deleted_at IS NOT NULL AND deleted_at < :cutoffEpochMillis")
    suspend fun pruneBooks(cutoffEpochMillis: Long): Int

    @Query("DELETE FROM highlights WHERE deleted_at IS NOT NULL AND deleted_at < :cutoffEpochMillis")
    suspend fun pruneHighlights(cutoffEpochMillis: Long): Int

    @Query("DELETE FROM bookmarks WHERE deleted_at IS NOT NULL AND deleted_at < :cutoffEpochMillis")
    suspend fun pruneBookmarks(cutoffEpochMillis: Long): Int
}
