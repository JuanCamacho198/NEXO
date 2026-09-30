package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.nexo.data.local.entity.SyncOutboxEntity

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox ORDER BY created_at ASC")
    suspend fun getPendingItems(): List<SyncOutboxEntity>

    @Insert
    suspend fun insert(item: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE sync_outbox SET retry_count = retry_count + 1, last_error = :error WHERE id = :id")
    suspend fun incrementRetryCount(
        id: String,
        error: String,
    )

    @Query("DELETE FROM sync_outbox WHERE retry_count >= :maxRetries")
    suspend fun pruneFailedItems(maxRetries: Int)

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT * FROM sync_outbox WHERE entity_type = :type AND entity_id = :entityId LIMIT 1")
    suspend fun getByTypeAndEntityId(
        type: String,
        entityId: String,
    ): SyncOutboxEntity?

    @Query("UPDATE sync_outbox SET payload = :payloadJson, retry_count = 0, last_error = NULL WHERE id = :id")
    suspend fun updatePayload(
        id: String,
        payloadJson: String,
    )

    /**
     * Coalesced upsert for READING_PROGRESS: one row per (type, entityId=bookId).
     * If a row already exists, update its payload in place (keep id, reset retry).
     * Otherwise insert. Valid JSON must be ensured by caller.
     */
    @Transaction
    suspend fun upsertCoalesced(item: SyncOutboxEntity) {
        val key = item.entityId ?: return insert(item)
        val existing = getByTypeAndEntityId(item.entityType, key)
        if (existing != null) {
            updatePayload(existing.id, item.payloadJson)
        } else {
            insert(item)
        }
    }
}
