package com.nexo.domain.repository

import com.nexo.domain.model.DailyReadingActivity
import kotlinx.coroutines.flow.Flow

interface ReadingStatsRepository {
    fun observeStats(
        bookId: String,
        userId: String,
    ): Flow<ReadingStatsData?>

    fun observeTotalTime(userId: String): Flow<Long>

    fun observeBookStats(userId: String): Flow<List<ReadingStatsData>>

    /**
     * Aggregated reading minutes grouped by day.
     *
     * @param userId Only rows owned by this user are counted. `null` means no
     *   account is active, which yields no rows: unowned rows are never visible
     *   to a signed-in account.
     */
    suspend fun getDailyActivity(userId: String? = null): List<DailyReadingActivity>

    suspend fun updateReadingTime(
        bookId: String,
        userId: String,
        additionalMinutes: Long,
    )

    suspend fun deleteStats(
        bookId: String,
        userId: String,
    )

    /**
     * Persists a reading session locally (deterministic id) and enqueues a
     * READING_SESSION outbox item for cloud sync. Default no-op keeps fakes
     * and callers that don't need session recording compiling.
     */
    suspend fun recordReadingSession(
        bookId: String,
        startTimeEpochMillis: Long,
        durationMinutes: Int,
        userId: String = "",
    ) = Unit
}

data class ReadingStatsData(
    val bookId: String,
    val totalMinutesRead: Long,
    val lastReadDateEpochMillis: Long,
    val sessionsCount: Int,
)
