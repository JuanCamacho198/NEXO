package com.nexo.domain.repository

import com.nexo.domain.model.Book
import com.nexo.domain.model.BookImportRequest
import com.nexo.domain.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface LibraryRepository {
    fun observeLibrary(): Flow<List<Book>>

    fun observeBookById(bookId: String): Flow<Book?>

    fun observeProgressForBook(bookId: String): Flow<ReadingProgress?>

    fun observeTotalReadingTime(): Flow<Long>

    fun observeReadingTimeByBook(): Flow<Map<String, Long>>

    suspend fun importBookFromEpub(
        request: BookImportRequest,
        inputStreamProvider: suspend () -> InputStream?,
    ): Result<Book>

    suspend fun importBookFromPdf(
        request: BookImportRequest,
        file: java.io.File,
    ): Result<Book>

    suspend fun deleteBook(bookId: String): Result<Unit>

    /** Local-only delete: soft-delete locally without queuing a cloud tombstone. */
    suspend fun deleteBookLocalOnly(bookId: String): Result<Unit>

    suspend fun updateBookRating(
        bookId: String,
        rating: Int?,
    )

    suspend fun updateBookStatus(
        bookId: String,
        status: String?,
    ): Result<Unit>

    suspend fun updateBookMetadata(
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
    ): Result<Unit>

    suspend fun getBookById(bookId: String): Book?

    /**
     * Best-effort duplicate lookup used before a catalog download: returns an
     * active library book with the same title (case-insensitive) and, when
     * both sides carry an author, the same author. Returns `null` when absent.
     *
     * Default `null` keeps lightweight fakes and partial implementations
     * compiling; the production repository overrides it.
     */
    suspend fun findBookByTitleAndAuthor(
        title: String,
        author: String?,
    ): Book? = null

    suspend fun startReading(bookId: String): Result<Unit> = Result.success(Unit)

    suspend fun updateReadingProgress(
        bookId: String,
        progress: Float,
    ): Result<Unit> = Result.success(Unit)

    suspend fun completeReading(bookId: String): Result<Unit> = Result.success(Unit)
}
