package com.nexo.domain.repository

import com.nexo.domain.model.DailyReadingActivity
import kotlinx.coroutines.flow.Flow

interface ReadingStatsRepository {
    fun observeStats(bookId: String): Flow<ReadingStatsData?>

    fun observeTotalTime(): Flow<Long>

    fun observeBookStats(): Flow<List<ReadingStatsData>>

    /**
     * Aggregated reading minutes grouped by day.
     *
     * @param userId When non-null, only rows matching `userId = :userId OR userId = ''`
     *   are counted (legacy pre-auth rows remain visible); when null, all rows count.
     */
    suspend fun getDailyActivity(userId: String? = null): List<DailyReadingActivity>

    suspend fun updateReadingTime(
        bookId: String,
        additionalMinutes: Long,
    )

    suspend fun deleteStats(bookId: String)

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
