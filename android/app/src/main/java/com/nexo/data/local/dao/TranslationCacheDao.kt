package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexo.data.local.entity.TranslationCacheEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the local translation cache. Isolated table — no method touches
 * user-owned or sync tables; the cache is device-local and never synced.
 *
 * Every read and write takes a `userId`. There is deliberately no owner-less
 * overload: an unfiltered query is how account A's lookups reached account B
 * before T3 of `translation-preferences`, and removing the possibility is
 * cheaper than remembering not to call it.
 */
@Dao
interface TranslationCacheDao {
    @Query(
        "SELECT * FROM translation_cache " +
            "WHERE user_id = :userId AND lang_pair = :langPair AND word_normalized = :wordNormalized LIMIT 1",
    )
    suspend fun get(
        userId: String,
        langPair: String,
        wordNormalized: String,
    ): TranslationCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: TranslationCacheEntity)

    /** Retention sweep for one owner: rows older than the cutoff are pruned. */
    @Query("DELETE FROM translation_cache WHERE user_id = :userId AND `created_at` < :cutoffEpochMillis")
    suspend fun deleteOlderThan(
        userId: String,
        cutoffEpochMillis: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM translation_cache WHERE user_id = :userId")
    suspend fun count(userId: String): Int

    /**
     * Translation history for one owner, newest first. The history screen reads
     * this; the cache-retention sweep shares the table, so see
     * [TranslationHistoryRepository] for why that is acceptable.
     */
    @Query("SELECT * FROM translation_cache WHERE user_id = :userId ORDER BY `created_at` DESC")
    fun history(userId: String): Flow<List<TranslationCacheEntity>>

    /** The privacy escape hatch: forget every translation this account looked up. */
    @Query("DELETE FROM translation_cache WHERE user_id = :userId")
    suspend fun deleteAll(userId: String): Int
}
