package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.NotificationEntity

/**
 * DAO for the notification history. Isolated from the sync/book tables —
 * notifications are local-only delivery history, never synced.
 */
@Dao
interface NotificationDao {
    /** Newest-first, as stored; retention is applied by the repository. */
    @Query("SELECT * FROM notifications ORDER BY created_at DESC")
    suspend fun listNewestFirst(): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NotificationEntity?

    @Upsert
    suspend fun upsert(notification: NotificationEntity)

    @Query("UPDATE notifications SET read_at = :readAt WHERE id = :id")
    suspend fun markRead(
        id: String,
        readAt: Long,
    )

    @Query("UPDATE notifications SET read_at = :readAt WHERE read_at IS NULL")
    suspend fun markAllRead(readAt: Long)

    @Query("DELETE FROM notifications")
    suspend fun clear()

    @Query("DELETE FROM notifications WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM notifications WHERE id NOT IN (SELECT id FROM notifications ORDER BY created_at DESC LIMIT :keep)")
    suspend fun deleteBeyondCount(keep: Int): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE read_at IS NULL")
    suspend fun unreadCount(): Int

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun count(): Int
}
