package com.nexo.domain.usecase

import com.nexo.domain.model.Book
import com.nexo.domain.model.DailyReadingActivity
import com.nexo.domain.model.Statistics
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.ReadingStatsData
import com.nexo.domain.repository.ReadingStatsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Tests for [GetStatisticsUseCase]:
 * - refresh trigger re-aggregation (R5)
 * - injected daily goal (REQ-daily-reading-goal-3, SCEN-3)
 * - user scoping via setUserId (REQ-reading-sessions-sync-6)
 * - today-anchored streak (REQ-streak-widget-3, SCEN-streak-1/2/3)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetStatisticsUseCaseTest {
    private val todayStart: Long =
        run {
            val cal =
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            cal.timeInMillis
        }

    private fun daysAgo(days: Long): Long = todayStart - TimeUnit.DAYS.toMillis(days)

    @Test
    fun refreshTrigger_picksUpNewDailyActivityOnRefresh() =
        runBlocking {
            val statsRepo =
                FakeReadingStatsRepository().apply {
                    dailyActivity = listOf(DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 5))
                }
            val useCase = GetStatisticsUseCase(statsRepo, FakeHomeRepository())

            val first = useCase().first()
            val firstToday = first.weeklyActivity.find { it.dateEpochMillis == todayStart }
            assertEquals(5L, firstToday?.minutesRead?.toLong() ?: -1L)

            // Mutate the underlying "table" — without refresh(), the use case flow
            // does not re-aggregate (the getDailyActivity() call only fires when
            // the refreshTrigger emits, which happens on refresh()).
            statsRepo.dailyActivity = listOf(DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 99))
            useCase.refresh()
            val second = useCase().first()
            val secondToday = second.weeklyActivity.find { it.dateEpochMillis == todayStart }
            assertEquals(99L, secondToday?.minutesRead?.toLong() ?: -1L)
        }

    @Test
    fun weeklyActivity_coversSevenDays_evenWhenNoData() =
        runBlocking {
            val useCase = GetStatisticsUseCase(FakeReadingStatsRepository(), FakeHomeRepository())
            val stats = useCase().first()
            assertEquals(7, stats.weeklyActivity.size)
            // Each day's minutes should default to 0 when no data is present
            assertEquals(0, stats.weeklyActivity.sumOf { it.minutesRead })
        }

    // ── Daily goal (REQ-daily-reading-goal-3, SCEN-daily-reading-goal-3) ──

    @Test
    fun goalProgress_usesInjectedGoalProvider_notHardcoded30() =
        runBlocking {
            val statsRepo =
                FakeReadingStatsRepository().apply {
                    dailyActivity = listOf(DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 22))
                }
            val useCase =
                GetStatisticsUseCase(
                    statsRepo,
                    FakeHomeRepository(),
                    dailyGoalProvider = { 45 },
                )

            val stats = useCase().first()

            assertEquals(
                22L,
                stats.weeklyActivity
                    .find { it.dateEpochMillis == todayStart }
                    ?.minutesRead
                    ?.toLong(),
            )
            // 22/45 ≈ 0.4889, NOT 22/30 ≈ 0.7333
            assertEquals(22f / 45f, stats.goalProgress, 0.001f)
        }

    @Test
    fun goalProgress_isClampedToOneWhenAboveGoal() =
        runBlocking {
            val statsRepo =
                FakeReadingStatsRepository().apply {
                    dailyActivity = listOf(DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 90))
                }
            val useCase =
                GetStatisticsUseCase(
                    statsRepo,
                    FakeHomeRepository(),
                    dailyGoalProvider = { 30 },
                )

            val stats = useCase().first()

            assertEquals(1f, stats.goalProgress, 0.001f)
        }

    @Test
    fun goalProgress_neverDividesByZero() =
        runBlocking {
            val statsRepo =
                FakeReadingStatsRepository().apply {
                    dailyActivity = listOf(DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 22))
                }
            val useCase =
                GetStatisticsUseCase(
                    statsRepo,
                    FakeHomeRepository(),
                    dailyGoalProvider = { 0 },
                )

            val stats = useCase().first()

            assertEquals(1f, stats.goalProgress, 0.001f)
        }

    // ── User scoping (REQ-reading-sessions-sync-6, SCEN-sync-9) ──

    @Test
    fun setUserId_scopesDailyAggregationToThatUser() =
        runBlocking {
            val statsRepo =
                FakeReadingStatsRepository().apply {
                    scopedActivity["user-a"] = listOf(DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 30))
                    scopedActivity["user-b"] = listOf(DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 7))
                }
            val useCase = GetStatisticsUseCase(statsRepo, FakeHomeRepository())

            useCase.setUserId("user-a")
            val statsA: Statistics = useCase().first()
            assertEquals(
                30L,
                statsA.weeklyActivity
                    .find { it.dateEpochMillis == todayStart }
                    ?.minutesRead
                    ?.toLong(),
            )

            useCase.setUserId("user-b")
            val statsB: Statistics = useCase().first()
            assertEquals(
                7L,
                statsB.weeklyActivity
                    .find { it.dateEpochMillis == todayStart }
                    ?.minutesRead
                    ?.toLong(),
            )
        }

    @Test
    fun setUserId_nullFallsBackToLegacyRows() =
        runBlocking {
            val statsRepo =
                FakeReadingStatsRepository().apply {
                    scopedActivity[null] = listOf(DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 3))
                }
            val useCase = GetStatisticsUseCase(statsRepo, FakeHomeRepository())

            useCase.setUserId(null)
            val stats = useCase().first()

            assertEquals(
                3L,
                stats.weeklyActivity
                    .find { it.dateEpochMillis == todayStart }
                    ?.minutesRead
                    ?.toLong(),
            )
        }

    // ── Streak (REQ-streak-widget-3, SCEN-streak-1/2/3) ──

    @Test
    fun streak_fiveConsecutiveDaysIncludingToday_isFive() =
        runBlocking {
            val activity =
                (0L..4L).map { dayOffset ->
                    DailyReadingActivity(dateEpochMillis = daysAgo(dayOffset), minutesRead = 10)
                }
            val statsRepo = FakeReadingStatsRepository().apply { dailyActivity = activity }
            val useCase = GetStatisticsUseCase(statsRepo, FakeHomeRepository())

            val stats = useCase().first()

            assertEquals(5, stats.currentStreak)
        }

    @Test
    fun streak_yesterdayOnly_isZeroBecauseTodayAnchored() =
        runBlocking {
            // Sessions yesterday but NONE today → streak must be 0 (SCEN-streak-2).
            val activity =
                listOf(
                    DailyReadingActivity(dateEpochMillis = daysAgo(1), minutesRead = 10),
                    DailyReadingActivity(dateEpochMillis = daysAgo(2), minutesRead = 10),
                )
            val statsRepo = FakeReadingStatsRepository().apply { dailyActivity = activity }
            val useCase = GetStatisticsUseCase(statsRepo, FakeHomeRepository())

            val stats = useCase().first()

            assertEquals(0, stats.currentStreak)
        }

    @Test
    fun streak_gapThenToday_isOne() =
        runBlocking {
            // 3-day streak, a gap day, then reading today → streak = 1 (SCEN-streak-3).
            val activity =
                listOf(
                    DailyReadingActivity(dateEpochMillis = todayStart, minutesRead = 10),
                    DailyReadingActivity(dateEpochMillis = daysAgo(2), minutesRead = 10),
                    DailyReadingActivity(dateEpochMillis = daysAgo(3), minutesRead = 10),
                    DailyReadingActivity(dateEpochMillis = daysAgo(4), minutesRead = 10),
                )
            val statsRepo = FakeReadingStatsRepository().apply { dailyActivity = activity }
            val useCase = GetStatisticsUseCase(statsRepo, FakeHomeRepository())

            val stats = useCase().first()

            assertEquals(1, stats.currentStreak)
        }

    @Test
    fun streak_noActivity_isZero() =
        runBlocking {
            val useCase = GetStatisticsUseCase(FakeReadingStatsRepository(), FakeHomeRepository())
            val stats = useCase().first()
            assertEquals(0, stats.currentStreak)
        }

    private class FakeReadingStatsRepository : ReadingStatsRepository {
        var dailyActivity: List<DailyReadingActivity> = emptyList()
        val scopedActivity = mutableMapOf<String?, List<DailyReadingActivity>>()
        private val stats = MutableStateFlow<ReadingStatsData?>(null)
        private val total = MutableStateFlow(0L)
        private val allStats = MutableStateFlow<List<ReadingStatsData>>(emptyList())

        override fun observeStats(bookId: String): Flow<ReadingStatsData?> = stats

        override fun observeTotalTime(): Flow<Long> = total

        override fun observeBookStats(): Flow<List<ReadingStatsData>> = allStats

        override suspend fun getDailyActivity(userId: String?): List<DailyReadingActivity> = scopedActivity[userId] ?: dailyActivity

        override suspend fun updateReadingTime(
            bookId: String,
            additionalMinutes: Long,
        ) = Unit

        override suspend fun deleteStats(bookId: String) = Unit
    }

    private class FakeHomeRepository : HomeRepository {
        override fun observeBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())

        override fun observeRecentBooks(limit: Int): Flow<List<Book>> = MutableStateFlow(emptyList())

        override fun observeCurrentBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())

        override fun observeDailyStats(
            userId: String?,
            goalMinutes: Int,
        ): Flow<com.nexo.domain.model.ReadingStats> =
            MutableStateFlow(
                com.nexo.domain.model
                    .ReadingStats(0, 0, 0f),
            )

        override suspend fun deleteBook(bookId: String): Result<Unit> = Result.success(Unit)
    }
}
