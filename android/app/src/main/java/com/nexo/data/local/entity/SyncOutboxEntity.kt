package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["entity_id"]), Index(value = ["entity_type", "entity_id"])],
)
data class SyncOutboxEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String?,
    @ColumnInfo(name = "operation")
    val operation: String,
    @ColumnInfo(name = "payload")
    val payloadJson: String,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
)

enum class SyncOperation {
    CREATE,
    UPDATE,
    DELETE,
}

enum class SyncEntityType {
    BOOK,
    READING_PROGRESS,
    HIGHLIGHT,
    BOOKMARK,
    READING_SESSION,
}
