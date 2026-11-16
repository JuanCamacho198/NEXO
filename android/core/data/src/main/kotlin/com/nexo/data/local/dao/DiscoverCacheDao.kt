package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexo.data.local.entity.DiscoverCacheEntity

/**
 * DAO for the isolated catalog cache. No method touches user_books,
 * outbox, or sync tables — isolation is structural, not conventional.
 */
@Dao
interface DiscoverCacheDao {
    @Query("SELECT * FROM discover_cache WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): DiscoverCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: DiscoverCacheEntity)

    @Query("DELETE FROM discover_cache WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT COUNT(*) FROM discover_cache")
    suspend fun count(): Int

    /** Sum of payload bytes only (DB pages/index excluded) — the size-cap metric. */
    @Query("SELECT COALESCE(SUM(LENGTH(CAST(payload AS BLOB))), 0) FROM discover_cache")
    suspend fun payloadBytes(): Long

    /** Payload bytes held by a single key, so a REPLACE can be measured net of it. */
    @Query("SELECT COALESCE(SUM(LENGTH(CAST(payload AS BLOB))), 0) FROM discover_cache WHERE `key` = :key")
    suspend fun payloadBytesForKey(key: String): Long

    /** Removes TTL-dead rows (`fetched_at + ttl_s < now`); the `== now` boundary is kept. */
    @Query("DELETE FROM discover_cache WHERE fetched_at + ttl_s < :nowEpochSecs")
    suspend fun deleteExpired(nowEpochSecs: Long): Int

    /** Removes rows left behind under the pre-`v3` key namespaces (never served since the bump). */
    @Query(
        "DELETE FROM discover_cache WHERE `key` LIKE 'p:v1:%' OR `key` LIKE 'd:v1:%' OR `key` LIKE 'f:v1:%' OR `key` LIKE 'p:v2:%' OR `key` LIKE 'd:v2:%' OR `key` LIKE 'f:v2:%'",
    )
    suspend fun deleteLegacyNamespaces(): Int

    /** Removes every discover-cache row. Books/covers are not in this table. */
    @Query("DELETE FROM discover_cache")
    suspend fun deleteAll(): Int
}
