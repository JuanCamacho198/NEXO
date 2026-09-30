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

    @Query("SELECT COALESCE(SUM(duration_minutes), 0) FROM reading_sessions WHERE date = :date")
    fun getTotalMinutesForDate(date: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(duration_minutes), 0) FROM reading_sessions WHERE date = :date AND (userId = :userId OR userId = '')")
    fun getTotalMinutesForDateAndUser(
        date: Long,
        userId: String,
    ): Flow<Int>

    @Query("SELECT COALESCE(SUM(duration_minutes), 0) FROM reading_sessions")
    fun getTotalMinutes(): Flow<Int>

    @Query("SELECT COUNT(*) FROM reading_sessions")
    fun getSessionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE date = :date")
    fun getSessionCountForDate(date: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE date = :date AND (userId = :userId OR userId = '')")
    fun getSessionCountForDateAndUser(
        date: Long,
        userId: String,
    ): Flow<Int>

    @Query("SELECT * FROM reading_sessions WHERE book_id = :bookId ORDER BY start_time DESC")
    fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>>

    @Query("DELETE FROM reading_sessions WHERE book_id = :bookId")
    suspend fun deleteSessionsForBook(bookId: String)

    @Query("SELECT COUNT(*) FROM reading_sessions")
    suspend fun count(): Int

    @Query("SELECT date, COALESCE(SUM(duration_minutes), 0) as total_minutes FROM reading_sessions GROUP BY date ORDER BY date DESC")
    suspend fun getDailyMinutes(): List<DailyReadingMinutes>

    @Query(
        "SELECT date, COALESCE(SUM(duration_minutes), 0) as total_minutes FROM reading_sessions WHERE date >= :startDate GROUP BY date ORDER BY date ASC",
    )
    suspend fun getDailyMinutesFromDate(startDate: Long): List<DailyReadingMinutes>

    @Query("SELECT * FROM reading_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ReadingSessionEntity?

    @Query("SELECT * FROM reading_sessions")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Query(
        "SELECT date, COALESCE(SUM(duration_minutes), 0) as total_minutes FROM reading_sessions WHERE (userId = :userId OR userId = '') GROUP BY date ORDER BY date DESC",
    )
    suspend fun getDailyMinutesForUser(userId: String?): List<DailyReadingMinutes>
}
