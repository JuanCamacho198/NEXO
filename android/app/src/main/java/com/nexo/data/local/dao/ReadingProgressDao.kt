package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId LIMIT 1")
    fun observeProgressForBook(bookId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId LIMIT 1")
    suspend fun getProgressForBook(bookId: String): ReadingProgressEntity?

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress")
    suspend fun getAll(): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT COUNT(*) FROM reading_progress")
    suspend fun count(): Int
}
