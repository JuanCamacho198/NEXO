package com.nexo.data.repository

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingStats
import com.nexo.domain.model.isActiveReadingCandidate
import com.nexo.domain.repository.HomeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.Calendar

class HomeRepositoryImpl(
    private val bookDao: BookDao,
    private val readingProgressDao: ReadingProgressDao,
    private val readingSessionDao: ReadingSessionDao,
) : HomeRepository {
    override fun observeBooks(): Flow<List<Book>> =
        combine(bookDao.observeAllBooks(), readingProgressDao.observeAll()) { entities, progresses ->
            val progressById = progresses.associateBy { it.bookId }
            entities.map { entity ->
                val canonical = progressById[entity.id]
                if (canonical != null) entity.toBookWithCanonical(canonical) else entity.toBook()
            }
        }

    override fun observeRecentBooks(limit: Int): Flow<List<Book>> =
        combine(bookDao.observeAllBooks(), readingProgressDao.observeAll()) { entities, progresses ->
            val progressById = progresses.associateBy { it.bookId }
            entities.take(limit).map { entity ->
                val canonical = progressById[entity.id]
                if (canonical != null) entity.toBookWithCanonical(canonical) else entity.toBook()
            }
        }

    override fun observeCurrentBooks(): Flow<List<Book>> =
        combine(bookDao.observeReadingBooks(), readingProgressDao.observeAll()) { entities, progresses ->
            val progressById = progresses.associateBy { it.bookId }
            entities
                .map { entity ->
                    val canonical = progressById[entity.id]
                    if (canonical != null) entity.toBookWithCanonical(canonical) else entity.toBook()
                }.filter { it.isActiveReadingCandidate() }
        }

    override fun observeDailyStats(
        userId: String?,
        goalMinutes: Int,
    ): Flow<ReadingStats> {
        val todayStart = getTodayStartMillis()
        return if (userId != null && userId.isNotBlank()) {
            combine(
                readingSessionDao.getTotalMinutesForDateAndUser(todayStart, userId),
                readingSessionDao.getSessionCountForDateAndUser(todayStart, userId),
            ) { minutes, sessions ->
                ReadingStats(
                    minutesRead = minutes,
                    sessionCount = sessions,
                    dailyProgressPercent = calculateProgress(minutes, goalMinutes),
                )
            }
        } else {
            combine(
                readingSessionDao.getTotalMinutesForDate(todayStart),
                readingSessionDao.getSessionCountForDate(todayStart),
            ) { minutes, sessions ->
                ReadingStats(
                    minutesRead = minutes,
                    sessionCount = sessions,
                    dailyProgressPercent = calculateProgress(minutes, goalMinutes),
                )
            }
        }
    }

    override suspend fun deleteBook(bookId: String): Result<Unit> =
        runCatching {
            val now = System.currentTimeMillis()
            bookDao.deleteBook(bookId, now)
            readingSessionDao.deleteSessionsForBook(bookId)
        }

    private fun getTodayStartMillis(): Long {
        val calendar =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        return calendar.timeInMillis
    }

    private fun calculateProgress(
        minutesRead: Int,
        dailyGoal: Int,
    ): Float {
        // Daily goal comes from the injected provider (user's own goal), never hardcoded.
        return (minutesRead.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    @Suppress("DEPRECATION")
    private fun com.nexo.data.local.entity.BookEntity.toBook(): Book =
        Book(
            id = id,
            title = title,
            author = author,
            description = description,
            coverPath = coverPath,
            filePath = filePath,
            format = format,
            totalPages = totalPages,
            userRating = userRating,
            updatedAtEpochMillis = updatedAtEpochMillis,
            status = status,
            readingState = readingState,
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = completedAtEpochMillis,
            progressPercentage = progressPercentage,
            progressUpdatedAtEpochMillis = progressUpdatedAtEpochMillis,
            stateVersion = stateVersion,
        )

    private fun com.nexo.data.local.entity.BookEntity.toBookWithCanonical(
        canonical: com.nexo.data.local.entity.ReadingProgressEntity,
    ): Book =
        Book(
            id = id,
            title = title,
            author = author,
            description = description,
            coverPath = coverPath,
            filePath = filePath,
            format = format,
            totalPages = totalPages,
            userRating = userRating,
            updatedAtEpochMillis = updatedAtEpochMillis,
            status = status,
            readingState = readingState,
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = completedAtEpochMillis,
            progressPercentage = canonical.percentage,
            progressUpdatedAtEpochMillis = canonical.updatedAtEpochMillis,
            stateVersion = stateVersion,
        )
}
