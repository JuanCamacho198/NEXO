package com.nexo.data.repository

import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.ReadingSessionEntity
import com.nexo.data.local.entity.ReadingStatsEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.domain.model.DailyReadingActivity
import com.nexo.domain.model.readingSessionId
import com.nexo.domain.repository.ReadingStatsData
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.sync.OutboxDrainScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

class ReadingStatsRepositoryImpl(
    private val readingStatsDao: ReadingStatsDao,
    private val readingSessionDao: ReadingSessionDao,
    private val outboxDao: SyncOutboxDao? = null,
    /**
     * S6 scheduler seam. Nullable so non-wired callers/tests keep the previous
     * local-only behavior; production injects the WorkManager-backed scheduler
     * and a drain is requested after a successful reading-session enqueue.
     */
    private val drainScheduler: OutboxDrainScheduler? = null,
) : ReadingStatsRepository {
    override fun observeStats(bookId: String): Flow<ReadingStatsData?> =
        readingStatsDao.observeStatsForBook(bookId).map { entity ->
            entity?.let {
                ReadingStatsData(
                    bookId = it.bookId,
                    totalMinutesRead = it.totalMinutesRead,
                    lastReadDateEpochMillis = it.lastReadDateEpochMillis,
                    sessionsCount = it.sessionsCount,
                )
            }
        }

    override fun observeTotalTime(): Flow<Long> = readingStatsDao.observeTotalMinutesRead().map { it ?: 0L }

    override fun observeBookStats(): Flow<List<ReadingStatsData>> =
        readingStatsDao.observeAllStats().map { entities ->
            entities.map {
                ReadingStatsData(
                    bookId = it.bookId,
                    totalMinutesRead = it.totalMinutesRead,
                    lastReadDateEpochMillis = it.lastReadDateEpochMillis,
                    sessionsCount = it.sessionsCount,
                )
            }
        }

    override suspend fun getDailyActivity(userId: String?): List<DailyReadingActivity> =
        readingSessionDao.getDailyMinutesForUser(userId).map {
            DailyReadingActivity(dateEpochMillis = it.dateEpochMillis, minutesRead = it.totalMinutes)
        }

    override suspend fun updateReadingTime(
        bookId: String,
        additionalMinutes: Long,
    ) {
        val now = System.currentTimeMillis()
        val existingEntity = readingStatsDao.observeStatsForBook(bookId).firstOrNull()

        if (existingEntity != null) {
            readingStatsDao.upsert(
                existingEntity.copy(
                    totalMinutesRead = existingEntity.totalMinutesRead + additionalMinutes,
                    lastReadDateEpochMillis = now,
                    sessionsCount = existingEntity.sessionsCount + 1,
                ),
            )
        } else {
            readingStatsDao.upsert(
                ReadingStatsEntity(
                    bookId = bookId,
                    totalMinutesRead = additionalMinutes,
                    lastReadDateEpochMillis = now,
                    sessionsCount = 1,
                ),
            )
        }
    }

    override suspend fun deleteStats(bookId: String) {
        readingStatsDao.deleteForBook(bookId)
    }

    override suspend fun recordReadingSession(
        bookId: String,
        startTimeEpochMillis: Long,
        durationMinutes: Int,
        userId: String,
    ) {
        val now = System.currentTimeMillis()
        val date = todayStartMillis()
        val id = readingSessionId(userId, bookId, startTimeEpochMillis)

        readingSessionDao.insert(
            ReadingSessionEntity(
                id = id,
                bookId = bookId,
                startTimeEpochMillis = startTimeEpochMillis,
                durationMinutes = durationMinutes,
                date = date,
                userId = userId,
                updatedAtEpochMillis = now,
            ),
        )

        // READING_SESSION per id — never coalesced, one row per session (desktop parity).
        // entityId MUST be the deterministic session id, not bookId, otherwise multiple
        // sessions for the same book overwrite each other in the outbox and the
        // READING_SESSION flush per-id semantics are violated. Payload is valid JSON.
        val payloadJson =
            JSONObject()
                .put("id", id)
                .put("bookId", bookId)
                .put("startTimeEpochMillis", startTimeEpochMillis)
                .put("durationMinutes", durationMinutes)
                .put("date", date)
                .put("userId", userId)
                .put("updatedAtEpochMillis", now)
                .toString()
        require(payloadJson.isNotEmpty()) { "payloadJson must be non-empty valid JSON" }
        // Validate JSON object (throws if invalid) — ensures outbox never stores "{}" or malformed
        JSONObject(payloadJson)
        val dao = outboxDao ?: return
        dao.insert(
            SyncOutboxEntity(
                id = "outbox-${UUID.randomUUID()}",
                entityType = SyncEntityType.READING_SESSION.name,
                entityId = id,
                operation = SyncOperation.UPDATE.name,
                payloadJson = payloadJson,
                createdAtEpochMillis = now,
            ),
        )
        // S6: request a drain after a successful enqueue.
        drainScheduler?.scheduleDrain()
    }

    private fun todayStartMillis(): Long {
        val calendar =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        return calendar.timeInMillis
    }
}
