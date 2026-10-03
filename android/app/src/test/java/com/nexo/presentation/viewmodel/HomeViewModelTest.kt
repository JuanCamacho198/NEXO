package com.nexo.presentation.viewmodel

import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingStats
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.testutil.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(statsUseCase: GetStatisticsUseCase? = null): HomeViewModel {
        val mockRepo = mockk<HomeRepository>(relaxed = true)
        // Wire flows to avoid NPE when collecting (all 5 needed for combine)
        val dailyStatsFlow = MutableStateFlow(ReadingStats())
        val currentBooksFlow = MutableStateFlow<List<Book>>(emptyList())
        val recentBooksFlow = MutableStateFlow<List<Book>>(emptyList())
        val allBooksFlow = MutableStateFlow<List<Book>>(emptyList())

        io.mockk.every { mockRepo.observeDailyStats(any(), any()) } returns dailyStatsFlow
        io.mockk.every { mockRepo.observeCurrentBooks() } returns currentBooksFlow
        io.mockk.every { mockRepo.observeRecentBooks(any()) } returns recentBooksFlow
        io.mockk.every { mockRepo.observeBooks() } returns allBooksFlow

        val useCase =
            statsUseCase ?: GetStatisticsUseCase(
                readingStatsRepository = FakeReadingStatsRepository(),
                homeRepository = mockRepo,
                dailyGoalProvider = { 30 },
                zoneId = java.time.ZoneId.of("UTC"),
            )

        return HomeViewModel(
            homeRepository = mockRepo,
            getStatisticsUseCase = useCase,
            dailyGoalProvider = { 30 },
            readerRepository = mockk<ReaderRepository>(relaxed = true),
            getBookProgressUseCase = mockk<GetBookProgressUseCase>(relaxed = true),
        )
    }

    @Test
    fun homeUiState_sessionsToday_defaultsToZero() =
        runTest {
            val viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals("sessionsToday should default to 0", 0, state.sessionsToday)
        }

    @Test
    fun homeUiState_dailyProgressPercent_defaultsToZeroFloat() =
        runTest {
            val viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals("dailyProgressPercent should default to 0f", 0f, state.dailyProgressPercent, 0.001f)
        }

    @Test
    fun homeUiState_userName_defaultsToReader() =
        runTest {
            val viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals("userName should default to Reader", "Reader", state.userName)
        }

    @Test
    fun homeUiState_minutesReadToday_defaultsToZero() =
        runTest {
            val viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals("minutesReadToday should default to 0", 0, state.minutesReadToday)
        }

    @Test
    fun homeUiState_currentBooks_defaultsToEmptyList() =
        runTest {
            val viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals("currentBooks should default to empty list", emptyList<Any>(), state.currentBooks)
        }

    @Test
    fun homeUiState_recentBooks_defaultsToEmptyList() =
        runTest {
            val viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals("recentBooks should default to empty list", emptyList<Any>(), state.recentBooks)
        }

    @Test
    fun homeUiState_isLoading_defaultsToFalse() =
        runTest {
            val viewModel = createViewModel()
            // Wait a bit for init to collect (combine fires once all 5 flows have initial value)
            kotlinx.coroutines.delay(100)
            val state = viewModel.uiState.value
            assertEquals("isLoading should default to false", false, state.isLoading)
        }

    @Test
    fun homeUiState_currentStreak_defaultsToZero() =
        runTest {
            val viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals("currentStreak should default to 0", 0, state.currentStreak)
        }

    @Test
    fun combine_emitsCorrectState_whenAllFlowsEmit() =
        runTest {
            val mockRepo = mockk<HomeRepository>(relaxed = true)
            val dailyStatsFlow = MutableStateFlow(ReadingStats(minutesRead = 30, sessionCount = 2, dailyProgressPercent = 0.5f))
            val currentBooksFlow =
                MutableStateFlow<List<Book>>(
                    listOf(
                        Book(
                            id = "b1",
                            title = "Test",
                            author = "Author",
                            coverPath = null,
                            filePath = "/path",
                            format = "epub",
                            totalPages = 200,
                            updatedAtEpochMillis = 1L,
                        ),
                    ),
                )
            val recentBooksFlow = MutableStateFlow<List<Book>>(emptyList())
            val allBooksFlow = MutableStateFlow<List<Book>>(emptyList())

            io.mockk.every { mockRepo.observeDailyStats(any(), any()) } returns dailyStatsFlow
            io.mockk.every { mockRepo.observeCurrentBooks() } returns currentBooksFlow
            io.mockk.every { mockRepo.observeRecentBooks(any()) } returns recentBooksFlow
            io.mockk.every { mockRepo.observeBooks() } returns allBooksFlow

            val statsUseCase =
                GetStatisticsUseCase(
                    readingStatsRepository = FakeReadingStatsRepository(),
                    homeRepository = mockRepo,
                    dailyGoalProvider = { 30 },
                    zoneId = java.time.ZoneId.of("UTC"),
                )
            val viewModel =
                HomeViewModel(
                    homeRepository = mockRepo,
                    getStatisticsUseCase = statsUseCase,
                    dailyGoalProvider = { 30 },
                    readerRepository = mockk<ReaderRepository>(relaxed = true),
                    getBookProgressUseCase = mockk<GetBookProgressUseCase>(relaxed = true),
                )
            kotlinx.coroutines.delay(100)

            val state = viewModel.uiState.value
            assertEquals("combine should set minutesReadToday", 30, state.minutesReadToday)
            assertEquals("combine should set sessionsToday", 2, state.sessionsToday)
            assertEquals("combine should set dailyProgressPercent", 0.5f, state.dailyProgressPercent, 0.001f)
            assertEquals("combine should set currentBooks size", 1, state.currentBooks.size)
            assertEquals("combine should set currentBooks id", "b1", state.currentBooks[0].id)
            assertEquals("combine should set isLoading to false", false, state.isLoading)
        }

    @Test
    fun combine_wiresCurrentStreakFromStatisticsUseCase() =
        runTest {
            val mockRepo = mockk<HomeRepository>(relaxed = true)
            val dailyStatsFlow = MutableStateFlow(ReadingStats())
            val currentBooksFlow = MutableStateFlow<List<Book>>(emptyList())
            val recentBooksFlow = MutableStateFlow<List<Book>>(emptyList())
            val allBooksFlow = MutableStateFlow<List<Book>>(emptyList())

            io.mockk.every { mockRepo.observeDailyStats(any(), any()) } returns dailyStatsFlow
            io.mockk.every { mockRepo.observeCurrentBooks() } returns currentBooksFlow
            io.mockk.every { mockRepo.observeRecentBooks(any()) } returns recentBooksFlow
            io.mockk.every { mockRepo.observeBooks() } returns allBooksFlow

            // The 5th flow (statistics) carries the streak.
            val statsUseCase =
                GetStatisticsUseCase(
                    readingStatsRepository = FakeReadingStatsRepository(),
                    homeRepository = mockRepo,
                    dailyGoalProvider = { 30 },
                    zoneId = java.time.ZoneId.of("UTC"),
                )
            val viewModel =
                HomeViewModel(
                    homeRepository = mockRepo,
                    getStatisticsUseCase = statsUseCase,
                    dailyGoalProvider = { 30 },
                    readerRepository = mockk<ReaderRepository>(relaxed = true),
                    getBookProgressUseCase = mockk<GetBookProgressUseCase>(relaxed = true),
                )
            kotlinx.coroutines.delay(100)

            // No sessions recorded → streak stays 0 (today-anchored).
            assertEquals(0, viewModel.uiState.value.currentStreak)
        }

    @Test
    fun observeDailyStats_isScopedToActiveUserAndGoal() =
        runTest {
            val mockRepo = mockk<HomeRepository>(relaxed = true)
            val dailyStatsFlow = MutableStateFlow(ReadingStats())
            val currentBooksFlow = MutableStateFlow<List<Book>>(emptyList())
            val recentBooksFlow = MutableStateFlow<List<Book>>(emptyList())
            val allBooksFlow = MutableStateFlow<List<Book>>(emptyList())

            io.mockk.every { mockRepo.observeDailyStats(any(), any()) } returns dailyStatsFlow
            io.mockk.every { mockRepo.observeCurrentBooks() } returns currentBooksFlow
            io.mockk.every { mockRepo.observeRecentBooks(any()) } returns recentBooksFlow
            io.mockk.every { mockRepo.observeBooks() } returns allBooksFlow

            val statsUseCase =
                GetStatisticsUseCase(
                    readingStatsRepository = FakeReadingStatsRepository(),
                    homeRepository = mockRepo,
                    dailyGoalProvider = { 30 },
                    zoneId = java.time.ZoneId.of("UTC"),
                )
            val viewModel =
                HomeViewModel(
                    homeRepository = mockRepo,
                    getStatisticsUseCase = statsUseCase,
                    dailyGoalProvider = { 45 },
                    readerRepository = mockk<ReaderRepository>(relaxed = true),
                    getBookProgressUseCase = mockk<GetBookProgressUseCase>(relaxed = true),
                )
            kotlinx.coroutines.delay(100)

            // Set an active session → the daily-stats flow must be re-collected with
            // the user's id and the injected goal (45), not the hardcoded 30.
            viewModel.setActiveSession(
                com.nexo.domain.model.AuthSession(
                    userId = "user-42",
                    email = "u@example.com",
                    displayName = "U",
                ),
            )
            kotlinx.coroutines.delay(100)

            io.mockk.verify { mockRepo.observeDailyStats("user-42", 45) }
        }

    private class FakeReadingStatsRepository : com.nexo.domain.repository.ReadingStatsRepository {
        override fun observeStats(bookId: String): kotlinx.coroutines.flow.Flow<com.nexo.domain.repository.ReadingStatsData?> = MutableStateFlow(null)

        override fun observeTotalTime(): kotlinx.coroutines.flow.Flow<Long> = MutableStateFlow(0L)

        override fun observeBookStats(): kotlinx.coroutines.flow.Flow<List<com.nexo.domain.repository.ReadingStatsData>> = MutableStateFlow(emptyList())

        override suspend fun getDailyActivity(userId: String?): List<com.nexo.domain.model.DailyReadingActivity> = emptyList()

        override suspend fun updateReadingTime(
            bookId: String,
            additionalMinutes: Long,
        ) = Unit

        override suspend fun deleteStats(bookId: String) = Unit
    }
}
