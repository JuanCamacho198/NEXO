package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: String): BookmarkEntity?

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId")
    suspend fun getBookmarksForBook(bookId: String): List<BookmarkEntity>

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Upsert
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Int
}
