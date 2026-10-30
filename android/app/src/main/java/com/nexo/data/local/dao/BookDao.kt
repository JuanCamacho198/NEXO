package com.nexo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nexo.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

/** Raw `books.id` + `books.file_path` pair used by the WU6 layout migration. */
data class StoredBookPath(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "file_path") val filePath: String,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE user_id = :userId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllBooks(userId: String): Flow<List<BookEntity>>

    @Query(
        """
        SELECT books.* FROM books
        LEFT JOIN reading_progress ON reading_progress.book_id = books.id
        WHERE books.user_id = :userId
          AND books.deleted_at IS NULL
          AND books.reading_state = 'reading'
          AND COALESCE(reading_progress.percentage, 0) < 100
        ORDER BY books.progress_updated_at DESC, books.updated_at DESC
        """,
    )
    fun observeReadingBooks(userId: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE user_id = :userId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllBooksPaged(userId: String): PagingSource<Int, BookEntity>

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("SELECT * FROM books WHERE id = :bookId AND user_id = :userId LIMIT 1")
    fun observeBookById(
        bookId: String,
        userId: String,
    ): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId AND user_id = :userId LIMIT 1")
    suspend fun getBookById(
        bookId: String,
        userId: String,
    ): BookEntity?

    @Query("UPDATE books SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :bookId AND user_id = :userId")
    suspend fun deleteBook(
        bookId: String,
        userId: String,
        deletedAt: Long,
    )

    @Query("DELETE FROM books WHERE id = :bookId AND user_id = :userId")
    suspend fun deleteById(
        bookId: String,
        userId: String,
    )

    @Query("UPDATE books SET user_rating = :rating WHERE id = :bookId AND user_id = :userId")
    suspend fun updateRating(
        bookId: String,
        userId: String,
        rating: Int?,
    )

    @Query("UPDATE books SET status = :status, updated_at = :updatedAt WHERE id = :bookId AND user_id = :userId")
    suspend fun updateStatus(
        bookId: String,
        userId: String,
        status: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE books SET reading_state = 'reading', started_at = COALESCE(started_at, :updatedAt), updated_at = :updatedAt, state_version = state_version + 1 WHERE id = :bookId AND user_id = :userId AND deleted_at IS NULL",
    )
    suspend fun startReading(
        bookId: String,
        userId: String,
        updatedAt: Long,
    )

    // WU2b storage-layout-and-sync: reading position lives ONLY in reading_progress.
    // The retired `books.progress_percentage` cache has been dropped. `progress` is
    // still used to derive reading_state/completed_at, and `progress_updated_at`
    // keeps the reading-list ordering stable.
    @Query(
        "UPDATE books SET reading_state = CASE WHEN :progress >= 100 THEN 'completed' ELSE 'reading' END, completed_at = CASE WHEN :progress >= 100 THEN :updatedAt ELSE completed_at END, progress_updated_at = :updatedAt, updated_at = :updatedAt, state_version = state_version + 1 WHERE id = :bookId AND user_id = :userId AND deleted_at IS NULL",
    )
    suspend fun updateReadingProgress(
        bookId: String,
        userId: String,
        progress: Float,
        updatedAt: Long,
    )

    @Query(
        "UPDATE books SET reading_state = 'completed', completed_at = :updatedAt, progress_updated_at = :updatedAt, updated_at = :updatedAt, state_version = state_version + 1 WHERE id = :bookId AND user_id = :userId AND deleted_at IS NULL",
    )
    suspend fun completeReading(
        bookId: String,
        userId: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE books SET title = :title, author = :author, description = :description, cover_path = :coverPath, genre = :genre, language = :language, publisher = :publisher, tags = :tags, published_date = :publishedDate, updated_at = :updatedAt WHERE id = :bookId AND user_id = :userId",
    )
    suspend fun updateMetadata(
        bookId: String,
        userId: String,
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

    @Query("SELECT COUNT(*) FROM books WHERE user_id = :userId")
    suspend fun count(userId: String): Int

    // ── WU6 layout migration ───────────────────────────────────────────────

    @Query("SELECT id, file_path FROM books WHERE user_id = :userId AND file_path IS NOT NULL")
    suspend fun allStoredPaths(userId: String): List<StoredBookPath>

    /** Every account that owns a stored path, for the device-wide layout migration. */
    @Query("SELECT DISTINCT user_id FROM books WHERE file_path IS NOT NULL")
    suspend fun distinctStoredPathOwners(): List<String>

    @Query("UPDATE books SET file_path = :filePath WHERE id = :bookId AND user_id = :userId")
    suspend fun updateFilePath(
        bookId: String,
        userId: String,
        filePath: String,
    )

    @Transaction
    suspend fun rewriteFilePaths(
        userId: String,
        rows: List<StoredBookPath>,
    ) {
        rows.forEach { updateFilePath(it.id, userId, it.filePath) }
    }
}
