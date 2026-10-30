package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE user_id = :userId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllBookmarks(userId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE user_id = :userId AND book_id = :bookId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeBookmarksForBook(
        bookId: String,
        userId: String,
    ): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id AND user_id = :userId LIMIT 1")
    suspend fun getBookmarkById(
        id: String,
        userId: String,
    ): BookmarkEntity?

    @Query("DELETE FROM bookmarks WHERE id = :id AND user_id = :userId")
    suspend fun deleteById(
        id: String,
        userId: String,
    )

    @Query("SELECT * FROM bookmarks WHERE user_id = :userId AND book_id = :bookId")
    suspend fun getBookmarksForBook(
        bookId: String,
        userId: String,
    ): List<BookmarkEntity>

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Upsert
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE user_id = :userId")
    suspend fun count(userId: String): Int
}
