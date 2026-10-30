package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId AND user_id = :userId LIMIT 1")
    fun observeProgressForBook(
        bookId: String,
        userId: String,
    ): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId AND user_id = :userId LIMIT 1")
    suspend fun getProgressForBook(
        bookId: String,
        userId: String,
    ): ReadingProgressEntity?

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE user_id = :userId")
    suspend fun getAll(userId: String): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress WHERE user_id = :userId")
    fun observeAll(userId: String): Flow<List<ReadingProgressEntity>>

    @Query("SELECT COUNT(*) FROM reading_progress WHERE user_id = :userId")
    suspend fun count(userId: String): Int
}
