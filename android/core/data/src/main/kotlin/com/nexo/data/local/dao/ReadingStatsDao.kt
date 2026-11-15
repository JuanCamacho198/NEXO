package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexo.data.local.entity.ReadingStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingStatsDao {
    @Query("SELECT * FROM reading_stats WHERE book_id = :bookId AND user_id = :userId")
    fun observeStatsForBook(
        bookId: String,
        userId: String,
    ): Flow<ReadingStatsEntity?>

    @Query("SELECT * FROM reading_stats WHERE user_id = :userId")
    fun observeAllStats(userId: String): Flow<List<ReadingStatsEntity>>

    /**
     * REPLACE is keyed on the primary key `(user_id, book_id)`, so one account's
     * stats can no longer evict another account's row for the same book.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: ReadingStatsEntity)

    @Query("SELECT SUM(totalMinutesRead) FROM reading_stats WHERE user_id = :userId")
    fun observeTotalMinutesReadForUser(userId: String): Flow<Long?>

    @Query("DELETE FROM reading_stats WHERE book_id = :bookId AND user_id = :userId")
    suspend fun deleteForBook(
        bookId: String,
        userId: String,
    )
}
