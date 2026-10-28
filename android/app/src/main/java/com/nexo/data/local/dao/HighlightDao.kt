package com.nexo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE user_id = :userId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllHighlights(userId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE user_id = :userId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllHighlightsPaged(userId: String): PagingSource<Int, HighlightEntity>

    @Query("SELECT * FROM highlights WHERE user_id = :userId AND book_id = :bookId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeHighlightsForBook(
        bookId: String,
        userId: String,
    ): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE id = :id AND user_id = :userId LIMIT 1")
    suspend fun getHighlightById(
        id: String,
        userId: String,
    ): HighlightEntity?

    @Query("DELETE FROM highlights WHERE id = :id AND user_id = :userId")
    suspend fun deleteById(
        id: String,
        userId: String,
    )

    @Query("SELECT * FROM highlights WHERE user_id = :userId AND book_id = :bookId")
    suspend fun getHighlightsForBook(
        bookId: String,
        userId: String,
    ): List<HighlightEntity>

    @Upsert
    suspend fun upsert(highlight: HighlightEntity)

    @Upsert
    suspend fun upsertAll(highlights: List<HighlightEntity>)

    @Query("SELECT COUNT(*) FROM highlights WHERE user_id = :userId")
    suspend fun count(userId: String): Int

    @Query("SELECT DISTINCT tag FROM highlights WHERE user_id = :userId AND tag IS NOT NULL AND tag != '' AND deleted_at IS NULL ORDER BY tag ASC")
    fun observeAllTags(userId: String): Flow<List<String>>
}
