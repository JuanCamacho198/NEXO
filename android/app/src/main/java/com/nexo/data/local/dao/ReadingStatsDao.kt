package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexo.data.local.entity.ReadingStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingStatsDao {
    @Query("SELECT * FROM reading_stats WHERE book_id = :bookId")
    fun observeStatsForBook(bookId: String): Flow<ReadingStatsEntity?>

    @Query("SELECT * FROM reading_stats")
    fun observeAllStats(): Flow<List<ReadingStatsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: ReadingStatsEntity)

    @Query("SELECT SUM(totalMinutesRead) FROM reading_stats")
    fun observeTotalMinutesRead(): Flow<Long?>

    @Query("SELECT SUM(totalMinutesRead) FROM reading_stats WHERE userId = :userId OR userId = ''")
    fun observeTotalMinutesReadForUser(userId: String): Flow<Long?>

    @Query("DELETE FROM reading_stats WHERE book_id = :bookId")
    suspend fun deleteForBook(bookId: String)
}
