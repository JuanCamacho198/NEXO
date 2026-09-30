package com.nexo.data.repository

import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.ReadingSessionEntity
import com.nexo.data.local.entity.ReadingStatsEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.local.model.DailyReadingMinutes
import com.nexo.domain.model.DailyReadingActivity
import com.nexo.domain.repository.ReadingStatsData
import com.nexo.domain.sync.OutboxDrainScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the refactored ReadingStatsRepository — verifies the suspend
 * `getDailyActivity()` path (R5) and the book_stats → ReadingStatsData mapping.
 */
class ReadingStatsRepositoryImplTest {
    @Test
    fun getDailyActivity_aggregatesSessionsByDateFromSuspendDao() =
        runBlocking {
            val fakeSessionDao =
                FakeReadingSessionDao().apply {
                    getDailyMinutesForUserResult =
                        listOf(
                            DailyReadingMinutes(dateEpochMillis = 20240101L, totalMinutes = 25),
                            DailyReadingMinutes(dateEpochMillis = 20240102L, totalMinutes = 10),
                        )
                }
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = FakeReadingStatsDao(),
                    readingSessionDao = fakeSessionDao,
                )

            val result: List<DailyReadingActivity> = repository.getDailyActivity()

            assertEquals(2, result.size)
            assertEquals(DailyReadingActivity(dateEpochMillis = 20240101L, minutesRead = 25), result[0])
            assertEquals(DailyReadingActivity(dateEpochMillis = 20240102L, minutesRead = 10), result[1])
        }

    @Test
    fun getDailyActivity_isSuspendNotFlow_doesNotEmitOnSessionInsert() =
        runBlocking {
            val fakeSessionDao =
                FakeReadingSessionDao().apply {
                    getDailyMinutesForUserResult =
                        listOf(
                            DailyReadingMinutes(dateEpochMillis = 20240101L, totalMinutes = 5),
                        )
                }
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = FakeReadingStatsDao(),
                    readingSessionDao = fakeSessionDao,
                )

            val first = repository.getDailyActivity()
            assertEquals(1, first.size)
            assertEquals(5, first.first().minutesRead)

            // Mutate the underlying "table" — since getDailyActivity is a suspend
            // function (not a Flow), observers of the old call site do not receive
            // automatic updates. A fresh suspend call re-reads the data.
            fakeSessionDao.getDailyMinutesForUserResult =
                listOf(
                    DailyReadingMinutes(dateEpochMillis = 20240101L, totalMinutes = 99),
                )

            val second = repository.getDailyActivity()
            assertEquals(99, second.first().minutesRead)
            // First snapshot is unchanged — no auto re-emission.
            assertEquals(5, first.first().minutesRead)
        }

    @Test
    fun getDailyActivity_returnsEmptyListWhenNoSessions() =
        runBlocking {
            val fakeSessionDao =
                FakeReadingSessionDao().apply {
                    getDailyMinutesForUserResult = emptyList()
                }
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = FakeReadingStatsDao(),
                    readingSessionDao = fakeSessionDao,
                )

            val result = repository.getDailyActivity()
            assertTrue(result.isEmpty())
        }

    @Test
    fun getDailyActivity_passesUserIdThroughToScopedDao() =
        runBlocking {
            val fakeSessionDao =
                FakeReadingSessionDao().apply {
                    getDailyMinutesForUserResult =
                        listOf(
                            DailyReadingMinutes(dateEpochMillis = 20240101L, totalMinutes = 25),
                        )
                }
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = FakeReadingStatsDao(),
                    readingSessionDao = fakeSessionDao,
                )

            val result = repository.getDailyActivity(userId = "user-42")

            assertEquals(1, result.size)
            assertEquals(DailyReadingActivity(dateEpochMillis = 20240101L, minutesRead = 25), result[0])
            assertEquals("user-42", fakeSessionDao.lastUserId)
        }

    @Test
    fun recordReadingSession_insertsSessionAndEnqueuesReadingSessionOutbox() =
        runBlocking {
            val fakeSessionDao = FakeReadingSessionDao()
            val fakeOutboxDao = FakeSyncOutboxDao()
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = FakeReadingStatsDao(),
                    readingSessionDao = fakeSessionDao,
                    outboxDao = fakeOutboxDao,
                )

            repository.recordReadingSession(
                bookId = "book-1",
                startTimeEpochMillis = 1000L,
                durationMinutes = 5,
                userId = "user-42",
            )

            val inserted = fakeSessionDao.insertedSessions.single()
            assertEquals("book-1", inserted.bookId)
            assertEquals(1000L, inserted.startTimeEpochMillis)
            assertEquals(5, inserted.durationMinutes)
            assertEquals("user-42", inserted.userId)
            assertTrue("id should be deterministic sess_", inserted.id.startsWith("sess_"))
            assertTrue("updatedAtEpochMillis should be the LWW clock", inserted.updatedAtEpochMillis > 0L)

            val outbox = fakeOutboxDao.items.single()
            assertEquals(SyncEntityType.READING_SESSION.name, outbox.entityType)
            assertEquals(inserted.id, outbox.entityId)
            assertTrue(outbox.payloadJson.contains("\"id\":\"${inserted.id}\""))
            assertTrue(outbox.payloadJson.contains("\"userId\":\"user-42\""))
            assertTrue(outbox.payloadJson.contains("\"updatedAtEpochMillis\""))
        }

    @Test
    fun recordReadingSession_nullOutboxDao_recordsLocallyOnly() =
        runBlocking {
            val fakeSessionDao = FakeReadingSessionDao()
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = FakeReadingStatsDao(),
                    readingSessionDao = fakeSessionDao,
                    outboxDao = null,
                )

            repository.recordReadingSession(
                bookId = "book-1",
                startTimeEpochMillis = 2000L,
                durationMinutes = 3,
                userId = "",
            )

            assertEquals(1, fakeSessionDao.insertedSessions.size)
            assertEquals("", fakeSessionDao.insertedSessions.single().userId)
        }

    @Test
    fun recordReadingSession_schedulesDrainAfterSuccessfulEnqueue() =
        runBlocking {
            val scheduler = RecordingDrainScheduler()
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = FakeReadingStatsDao(),
                    readingSessionDao = FakeReadingSessionDao(),
                    outboxDao = FakeSyncOutboxDao(),
                    drainScheduler = scheduler,
                )

            repository.recordReadingSession(
                bookId = "book-1",
                startTimeEpochMillis = 3000L,
                durationMinutes = 7,
                userId = "user-42",
            )

            assertEquals(1, scheduler.scheduleCount)
        }

    @Test
    fun recordReadingSession_nullOutboxDao_doesNotScheduleDrain() =
        runBlocking {
            val scheduler = RecordingDrainScheduler()
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = FakeReadingStatsDao(),
                    readingSessionDao = FakeReadingSessionDao(),
                    outboxDao = null,
                    drainScheduler = scheduler,
                )

            repository.recordReadingSession(
                bookId = "book-1",
                startTimeEpochMillis = 4000L,
                durationMinutes = 2,
                userId = "user-42",
            )

            assertEquals(0, scheduler.scheduleCount)
        }

    @Test
    fun observeBookStats_mapsReadingStatsEntityToDomain() =
        runBlocking {
            val fakeStatsDao =
                FakeReadingStatsDao().apply {
                    allStatsState.value =
                        listOf(
                            ReadingStatsEntity(
                                bookId = "book-1",
                                totalMinutesRead = 60,
                                lastReadDateEpochMillis = 1000L,
                                sessionsCount = 4,
                            ),
                            ReadingStatsEntity(
                                bookId = "book-2",
                                totalMinutesRead = 30,
                                lastReadDateEpochMillis = 2000L,
                                sessionsCount = 2,
                            ),
                        )
                }
            val repository =
                ReadingStatsRepositoryImpl(
                    readingStatsDao = fakeStatsDao,
                    readingSessionDao = FakeReadingSessionDao(),
                )

            val result: List<ReadingStatsData> = repository.observeBookStats().first()

            assertEquals(2, result.size)
            assertEquals("book-1", result[0].bookId)
            assertEquals(60L, result[0].totalMinutesRead)
            assertEquals(4, result[0].sessionsCount)
            assertEquals("book-2", result[1].bookId)
            assertEquals(30L, result[1].totalMinutesRead)
        }

    // ── Fakes ───────────────────────────────────────────────────────

    private class RecordingDrainScheduler : OutboxDrainScheduler {
        var scheduleCount: Int = 0

        override suspend fun scheduleDrain() {
            scheduleCount++
        }
    }

    private class FakeReadingSessionDao : ReadingSessionDao {
        var getDailyMinutesResult: List<DailyReadingMinutes> = emptyList()
        var getDailyMinutesFromDateResult: List<DailyReadingMinutes> = emptyList()
        var getDailyMinutesForUserResult: List<DailyReadingMinutes> = emptyList()
        var lastUserId: String? = null
        val insertedSessions = mutableListOf<ReadingSessionEntity>()

        override suspend fun insert(session: ReadingSessionEntity) {
            insertedSessions.add(session)
        }

        override fun getTotalMinutesForDate(date: Long): Flow<Int> = MutableStateFlow(0)

        override fun getTotalMinutesForDateAndUser(
            date: Long,
            userId: String,
        ): Flow<Int> = MutableStateFlow(0)

        override fun getTotalMinutes(): Flow<Int> = MutableStateFlow(0)

        override fun getSessionCount(): Flow<Int> = MutableStateFlow(0)

        override fun getSessionCountForDate(date: Long): Flow<Int> = MutableStateFlow(0)

        override fun getSessionCountForDateAndUser(
            date: Long,
            userId: String,
        ): Flow<Int> = MutableStateFlow(0)

        override fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> = MutableStateFlow(emptyList())

        override suspend fun deleteSessionsForBook(bookId: String) = Unit

        override suspend fun count(): Int = 0

        override suspend fun getAll(): List<ReadingSessionEntity> = insertedSessions.toList()

        override suspend fun getDailyMinutes(): List<DailyReadingMinutes> = getDailyMinutesResult

        override suspend fun getDailyMinutesFromDate(startDate: Long): List<DailyReadingMinutes> = getDailyMinutesFromDateResult

        override suspend fun getById(id: String): ReadingSessionEntity? = insertedSessions.firstOrNull { it.id == id }

        override suspend fun getDailyMinutesForUser(userId: String?): List<DailyReadingMinutes> {
            lastUserId = userId
            return getDailyMinutesForUserResult
        }
    }

    private class FakeReadingStatsDao : ReadingStatsDao {
        val allStatsState = MutableStateFlow<List<ReadingStatsEntity>>(emptyList())
        val totalMinutesState = MutableStateFlow<Long?>(0L)
        val statsForBook = MutableStateFlow<ReadingStatsEntity?>(null)

        override fun observeStatsForBook(bookId: String): Flow<ReadingStatsEntity?> = statsForBook

        override fun observeAllStats(): Flow<List<ReadingStatsEntity>> = allStatsState

        override suspend fun upsert(stats: ReadingStatsEntity) {
            allStatsState.value =
                allStatsState.value
                    .filterNot { it.bookId == stats.bookId }
                    .plus(stats)
        }

        override fun observeTotalMinutesRead(): Flow<Long?> = totalMinutesState

        override fun observeTotalMinutesReadForUser(userId: String): Flow<Long?> = totalMinutesState

        override suspend fun deleteForBook(bookId: String) {
            allStatsState.value = allStatsState.value.filterNot { it.bookId == bookId }
        }
    }

    private class FakeSyncOutboxDao : SyncOutboxDao {
        val items = mutableListOf<SyncOutboxEntity>()

        override suspend fun getPendingItems(): List<SyncOutboxEntity> = items.toList()

        override suspend fun insert(item: SyncOutboxEntity) {
            items.add(item)
        }

        override suspend fun deleteById(id: String) {
            items.removeAll { it.id == id }
        }

        override suspend fun incrementRetryCount(
            id: String,
            error: String,
        ) = Unit

        override suspend fun pruneFailedItems(maxRetries: Int) = Unit

        override fun observePendingCount(): Flow<Int> = MutableStateFlow(items.size)

        override suspend fun getByTypeAndEntityId(
            type: String,
            entityId: String,
        ): SyncOutboxEntity? = items.firstOrNull { it.entityType == type && it.entityId == entityId }

        override suspend fun updatePayload(
            id: String,
            payloadJson: String,
        ) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) items[idx] = items[idx].copy(payloadJson = payloadJson)
        }

        override suspend fun upsertCoalesced(item: SyncOutboxEntity) {
            val existing = item.entityId?.let { getByTypeAndEntityId(item.entityType, it) }
            if (existing != null) updatePayload(existing.id, item.payloadJson) else items.add(item)
        }
    }
}
