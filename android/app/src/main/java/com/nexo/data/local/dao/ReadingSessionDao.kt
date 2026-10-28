package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexo.data.local.entity.ReadingSessionEntity
import com.nexo.data.local.model.DailyReadingMinutes
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ReadingSessionEntity)

    @Query("SELECT COALESCE(SUM(duration_minutes), 0) FROM reading_sessions WHERE date = :date AND userId = :userId")
    fun getTotalMinutesForDateAndUser(
        date: Long,
        userId: String,
    ): Flow<Int>

    @Query("SELECT COALESCE(SUM(duration_minutes), 0) FROM reading_sessions WHERE userId = :userId")
    fun getTotalMinutes(userId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE userId = :userId")
    fun getSessionCount(userId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE date = :date AND userId = :userId")
    fun getSessionCountForDateAndUser(
        date: Long,
        userId: String,
    ): Flow<Int>

    @Query("SELECT * FROM reading_sessions WHERE book_id = :bookId AND userId = :userId ORDER BY start_time DESC")
    fun observeSessionsForBook(
        bookId: String,
        userId: String,
    ): Flow<List<ReadingSessionEntity>>

    @Query("DELETE FROM reading_sessions WHERE book_id = :bookId AND userId = :userId")
    suspend fun deleteSessionsForBook(
        bookId: String,
        userId: String,
    )

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE userId = :userId")
    suspend fun count(userId: String): Int

    @Query(
        "SELECT date, COALESCE(SUM(duration_minutes), 0) as total_minutes FROM reading_sessions WHERE userId = :userId GROUP BY date ORDER BY date DESC",
    )
    suspend fun getDailyMinutes(userId: String): List<DailyReadingMinutes>

    @Query(
        "SELECT date, COALESCE(SUM(duration_minutes), 0) as total_minutes FROM reading_sessions WHERE date >= :startDate AND userId = :userId GROUP BY date ORDER BY date ASC",
    )
    suspend fun getDailyMinutesFromDate(
        startDate: Long,
        userId: String,
    ): List<DailyReadingMinutes>

    @Query("SELECT * FROM reading_sessions WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getById(
        id: String,
        userId: String,
    ): ReadingSessionEntity?

    @Query("SELECT * FROM reading_sessions WHERE userId = :userId")
    suspend fun getAll(userId: String): List<ReadingSessionEntity>

    @Query(
        "SELECT date, COALESCE(SUM(duration_minutes), 0) as total_minutes FROM reading_sessions WHERE userId = :userId GROUP BY date ORDER BY date DESC",
    )
    suspend fun getDailyMinutesForUser(userId: String): List<DailyReadingMinutes>
}
