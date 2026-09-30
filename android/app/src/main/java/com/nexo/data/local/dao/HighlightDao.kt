package com.nexo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllHighlights(): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllHighlightsPaged(): PagingSource<Int, HighlightEntity>

    @Query("SELECT * FROM highlights WHERE book_id = :bookId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeHighlightsForBook(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE id = :id LIMIT 1")
    suspend fun getHighlightById(id: String): HighlightEntity?

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM highlights WHERE book_id = :bookId")
    suspend fun getHighlightsForBook(bookId: String): List<HighlightEntity>

    @Upsert
    suspend fun upsert(highlight: HighlightEntity)

    @Upsert
    suspend fun upsertAll(highlights: List<HighlightEntity>)

    @Query("SELECT COUNT(*) FROM highlights")
    suspend fun count(): Int

    @Query("SELECT DISTINCT tag FROM highlights WHERE tag IS NOT NULL AND tag != '' AND deleted_at IS NULL ORDER BY tag ASC")
    fun observeAllTags(): Flow<List<String>>
}
