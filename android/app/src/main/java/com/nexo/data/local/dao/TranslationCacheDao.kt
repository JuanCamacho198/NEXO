package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexo.data.local.entity.TranslationCacheEntity

/**
 * DAO for the local translation cache. Isolated table — no method touches
 * user-owned or sync tables; the cache is device-local and never synced.
 */
@Dao
interface TranslationCacheDao {
    @Query(
        "SELECT * FROM translation_cache WHERE lang_pair = :langPair AND word_normalized = :wordNormalized LIMIT 1",
    )
    suspend fun get(
        langPair: String,
        wordNormalized: String,
    ): TranslationCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: TranslationCacheEntity)

    @Query("DELETE FROM translation_cache WHERE `created_at` < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int

    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun count(): Int
}
