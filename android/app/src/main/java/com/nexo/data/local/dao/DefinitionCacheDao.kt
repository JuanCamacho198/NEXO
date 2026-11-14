package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexo.data.local.entity.DefinitionCacheEntity

/**
 * DAO for the local definition cache. Isolated table — no method touches
 * user-owned or sync tables; the cache is device-local and never synced.
 *
 * Every read and write takes a `userId`. There is deliberately no owner-less
 * overload: an unfiltered query is how account A's lookups reached account B
 * before the translation cache was scoped, and removing the possibility is
 * cheaper than remembering not to call it. The anonymous path never calls in
 * here at all — the repository short-circuits before the DAO.
 */
@Dao
interface DefinitionCacheDao {
    @Query("SELECT * FROM definition_cache WHERE user_id = :userId AND key = :key LIMIT 1")
    suspend fun get(
        userId: String,
        key: String,
    ): DefinitionCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: DefinitionCacheEntity)

    /**
     * Bounded retention for one owner: keeps the [keep] newest rows and deletes
     * the rest, oldest first. A row limit rather than a day-based cutoff, so a
     * predictable ceiling on disk does not evict the history of a reader who is
     * heavy one week and idle the next.
     *
     * Scoped to [userId] like every other method here: one account's sweep must
     * never delete another account's rows.
     */
    @Query(
        "DELETE FROM definition_cache WHERE user_id = :userId AND key NOT IN (" +
            "SELECT key FROM definition_cache WHERE user_id = :userId ORDER BY `created_at` DESC LIMIT :keep)",
    )
    suspend fun deleteOldestBeyond(
        userId: String,
        keep: Int,
    ): Int

    @Query("SELECT COUNT(*) FROM definition_cache WHERE user_id = :userId")
    suspend fun count(userId: String): Int

    /** The privacy escape hatch: forget every definition this account looked up. */
    @Query("DELETE FROM definition_cache WHERE user_id = :userId")
    suspend fun deleteAll(userId: String): Int
}
