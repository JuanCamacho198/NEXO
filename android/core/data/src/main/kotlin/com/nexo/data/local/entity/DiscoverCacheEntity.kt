package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Isolated TTL cache for public-domain catalog pages/details.
 * Separate table by design: catalog traffic never touches user_books/outbox.
 * Keys: p:{provider}:{query}:{page} (24h) for search pages,
 *       d:{provider}:{id} (7d) for details. Payload is opaque JSON.
 * Mirrors desktop `discover_cache` (migration 0016).
 */
@Entity(
    tableName = "discover_cache",
    indices = [Index(value = ["fetched_at"])],
)
data class DiscoverCacheEntity(
    @PrimaryKey
    val key: String,
    @ColumnInfo(name = "payload")
    val payloadJson: String,
    @ColumnInfo(name = "fetched_at")
    val fetchedAtEpochSecs: Long,
    @ColumnInfo(name = "ttl_s")
    val ttlSecs: Long,
)
