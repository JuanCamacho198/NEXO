package com.nexo.domain.repository

import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingStats
import kotlinx.coroutines.flow.Flow

interface HomeRepository {
    fun observeBooks(userId: String): Flow<List<Book>>

    fun observeRecentBooks(
        userId: String,
        limit: Int = 5,
    ): Flow<List<Book>>

    fun observeCurrentBooks(userId: String): Flow<List<Book>>

    fun observeDailyStats(
        userId: String? = null,
        goalMinutes: Int = 30,
    ): Flow<ReadingStats>

    suspend fun deleteBook(
        bookId: String,
        userId: String,
    ): Result<Unit>
}
