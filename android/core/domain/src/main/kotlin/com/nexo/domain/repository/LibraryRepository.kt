package com.nexo.domain.repository

import com.nexo.domain.model.Book
import com.nexo.domain.model.BookImportRequest
import com.nexo.domain.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface LibraryRepository {
    fun observeLibrary(userId: String): Flow<List<Book>>

    fun observeBookById(
        bookId: String,
        userId: String,
    ): Flow<Book?>

    fun observeProgressForBook(
        bookId: String,
        userId: String,
    ): Flow<ReadingProgress?>

    fun observeTotalReadingTime(userId: String): Flow<Long>

    fun observeReadingTimeByBook(userId: String): Flow<Map<String, Long>>

    suspend fun importBookFromEpub(
        userId: String,
        request: BookImportRequest,
        inputStreamProvider: suspend () -> InputStream?,
    ): Result<Book>

    suspend fun importBookFromPdf(
        userId: String,
        request: BookImportRequest,
        file: java.io.File,
    ): Result<Book>

    suspend fun deleteBook(
        bookId: String,
        userId: String,
    ): Result<Unit>

    /** Local-only delete: soft-delete locally without queuing a cloud tombstone. */
    suspend fun deleteBookLocalOnly(
        bookId: String,
        userId: String,
    ): Result<Unit>

    suspend fun updateBookRating(
        bookId: String,
        userId: String,
        rating: Int?,
    )

    suspend fun updateBookStatus(
        bookId: String,
        userId: String,
        status: String?,
    ): Result<Unit>

    suspend fun updateBookMetadata(
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
    ): Result<Unit>

    suspend fun getBookById(
        bookId: String,
        userId: String,
    ): Book?

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
        userId: String,
    ): Book? = null

    suspend fun startReading(
        bookId: String,
        userId: String,
    ): Result<Unit> = Result.success(Unit)

    suspend fun updateReadingProgress(
        bookId: String,
        userId: String,
        progress: Float,
    ): Result<Unit> = Result.success(Unit)

    suspend fun completeReading(
        bookId: String,
        userId: String,
    ): Result<Unit> = Result.success(Unit)
}
