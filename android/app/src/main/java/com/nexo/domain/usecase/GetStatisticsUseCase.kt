package com.nexo.domain.usecase

import com.nexo.domain.model.DailyReadingActivity
import com.nexo.domain.model.ReadingDay
import com.nexo.domain.model.Statistics
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.ReadingStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GetStatisticsUseCase(
    private val readingStatsRepository: ReadingStatsRepository,
    private val homeRepository: HomeRepository,
    private val dailyGoalProvider: () -> Int = { 30 },
    /**
     * Explicit zone for the reading-day contract. Production passes it at the
     * DI composition root; tests pass a fixed zone.
     */
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val refreshTrigger = MutableStateFlow(Unit)

    /**
     * Active user scope for daily aggregation (REQ-reading-sessions-sync-6).
     * `null` means "legacy rows only" (userId = ''); set from the NavHost
     * session effect via [setUserId].
     */
    private val userFilter = MutableStateFlow<String?>(null)

    fun refresh() {
        refreshTrigger.value = Unit
    }

    fun setUserId(userId: String?) {
        userFilter.value = userId
    }

    operator fun invoke(): Flow<Statistics> =
        combine(
            readingStatsRepository.observeTotalTime(),
            readingStatsRepository.observeBookStats(),
            refreshTrigger
                .combine(userFilter) { _, userId -> userId }
                .flatMapLatest { userId ->
                    flow { emit(readingStatsRepository.getDailyActivity(userId)) }
                },
            homeRepository.observeBooks(),
        ) { totalMinutes, bookStats, dailyActivity, books ->
            val todayStart = getTodayStartMillis()
            val todayMinutes =
                dailyActivity
                    .filter { it.dateEpochMillis == todayStart }
                    .sumOf { it.minutesRead }
                    .toLong()

            Statistics(
                totalMinutesRead = totalMinutes,
                currentStreak = calculateStreak(dailyActivity, todayStart),
                booksRead = bookStats.count { it.totalMinutesRead >= BOOKS_READ_MINUTES },
                weeklyActivity = lastSevenDaysActivity(dailyActivity, todayStart),
                goalProgress =
                    (todayMinutes.toFloat() / dailyGoalProvider().coerceAtLeast(1))
                        .coerceIn(0f, 1f),
                favoriteGenres =
                    books
                        .mapNotNull { it.description?.split(",")?.firstOrNull() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(5),
            )
        }

    /**
     * Today-anchored streak: counts consecutive days with recorded reading
     * sessions ending today. With no session today the streak is 0
     * (REQ-streak-widget-3, SCEN-streak-2).
     */
    private fun calculateStreak(
        dailyActivity: List<DailyReadingActivity>,
        todayStart: Long,
    ): Int {
        val activeDates =
            dailyActivity
                .filter { it.minutesRead > 0 }
                .map { it.dateEpochMillis }
                .toSortedSet()

        if (activeDates.isEmpty()) return 0
        if (!activeDates.contains(todayStart)) return 0

        var currentDate = todayStart
        var streak = 0

        while (activeDates.contains(currentDate)) {
            streak++
            currentDate -= TimeUnit.DAYS.toMillis(1)
        }

        return streak
    }

    private fun lastSevenDaysActivity(
        dailyActivity: List<DailyReadingActivity>,
        todayStart: Long,
    ): List<DailyReadingActivity> {
        val activityByDate = dailyActivity.associateBy { it.dateEpochMillis }
        val days = mutableListOf<DailyReadingActivity>()
        repeat(7) { offset ->
            val date = todayStart - TimeUnit.DAYS.toMillis((6 - offset).toLong())
            days.add(activityByDate[date] ?: DailyReadingActivity(dateEpochMillis = date, minutesRead = 0))
        }
        return days
    }

    private fun getTodayStartMillis(): Long = ReadingDay.todayStartMillis(zoneId)

    companion object {
        private const val BOOKS_READ_MINUTES = 300L
    }
}
