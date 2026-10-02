package com.nexo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllBooks(): Flow<List<BookEntity>>

    @Query(
        """
        SELECT books.* FROM books
        LEFT JOIN reading_progress ON reading_progress.book_id = books.id
        WHERE books.deleted_at IS NULL
          AND books.reading_state = 'reading'
          AND COALESCE(reading_progress.percentage, 0) < 100
        ORDER BY books.progress_updated_at DESC, books.updated_at DESC
        """,
    )
    fun observeReadingBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllBooksPaged(): PagingSource<Int, BookEntity>

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    fun observeBookById(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: String): BookEntity?

    @Query("UPDATE books SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :bookId")
    suspend fun deleteBook(
        bookId: String,
        deletedAt: Long,
    )

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: String)

    @Query("UPDATE books SET user_rating = :rating WHERE id = :bookId")
    suspend fun updateRating(
        bookId: String,
        rating: Int?,
    )

    @Query("UPDATE books SET status = :status, updated_at = :updatedAt WHERE id = :bookId")
    suspend fun updateStatus(
        bookId: String,
        status: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE books SET reading_state = 'reading', started_at = COALESCE(started_at, :updatedAt), updated_at = :updatedAt, state_version = state_version + 1 WHERE id = :bookId AND deleted_at IS NULL",
    )
    suspend fun startReading(
        bookId: String,
        updatedAt: Long,
    )

    // WU2b storage-layout-and-sync: reading position lives ONLY in reading_progress.
    // The retired `books.progress_percentage` cache has been dropped. `progress` is
    // still used to derive reading_state/completed_at, and `progress_updated_at`
    // keeps the reading-list ordering stable.
    @Query(
        "UPDATE books SET reading_state = CASE WHEN :progress >= 100 THEN 'completed' ELSE 'reading' END, completed_at = CASE WHEN :progress >= 100 THEN :updatedAt ELSE completed_at END, progress_updated_at = :updatedAt, updated_at = :updatedAt, state_version = state_version + 1 WHERE id = :bookId AND deleted_at IS NULL",
    )
    suspend fun updateReadingProgress(
        bookId: String,
        progress: Float,
        updatedAt: Long,
    )

    @Query(
        "UPDATE books SET reading_state = 'completed', completed_at = :updatedAt, progress_updated_at = :updatedAt, updated_at = :updatedAt, state_version = state_version + 1 WHERE id = :bookId AND deleted_at IS NULL",
    )
    suspend fun completeReading(
        bookId: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE books SET title = :title, author = :author, description = :description, cover_path = :coverPath, genre = :genre, language = :language, publisher = :publisher, tags = :tags, published_date = :publishedDate, updated_at = :updatedAt WHERE id = :bookId",
    )
    suspend fun updateMetadata(
        bookId: String,
        title: String,
        author: String?,
        description: String?,
        coverPath: String?,
        genre: String?,
        language: String?,
        publisher: String?,
        tags: String?,
        publishedDate: String?,
        updatedAt: Long,
    )

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int
}
