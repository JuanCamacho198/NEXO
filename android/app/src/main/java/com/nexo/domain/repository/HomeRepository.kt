package com.nexo.domain.repository

import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingStats
import kotlinx.coroutines.flow.Flow

interface HomeRepository {
    fun observeBooks(): Flow<List<Book>>

    fun observeRecentBooks(limit: Int = 5): Flow<List<Book>>

    fun observeCurrentBooks(): Flow<List<Book>>

    fun observeDailyStats(
        userId: String? = null,
        goalMinutes: Int = 30,
    ): Flow<ReadingStats>

    suspend fun deleteBook(bookId: String): Result<Unit>
}
