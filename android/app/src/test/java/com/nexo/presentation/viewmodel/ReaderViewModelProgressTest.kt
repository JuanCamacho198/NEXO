package com.nexo.presentation.viewmodel

import android.app.Application
import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsData
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.testutil.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelProgressTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun updateProgress_writesLocalProgressThroughRepository() =
        runTest {
            val repository = FakeReaderRepository()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = "book-10",
                    mainDispatcher = dispatcher,
                )

            viewModel.lifecycleHolder.updateProgress(
                bookId = "book-10",
                cfiLocation = "epubcfi(/6/2[chapter-1]!/4/1:0)",
                percentage = 25f,
            )

            val saved = repository.lastUpserted
            assertNotNull(saved)
            assertEquals("progress-book-10", saved?.id)
            assertEquals("book-10", saved?.bookId)
            assertEquals("epubcfi(/6/2[chapter-1]!/4/1:0)", saved?.cfiLocation)
            assertEquals(25f, saved?.percentage)
            assertTrue((saved?.updatedAtEpochMillis ?: 0L) > 0L)
        }

    @Test
    fun goToPage_validPageNavigatesAndUpdatesProgress() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = FakeReaderRepository()
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            setPdfState(viewModel, selectedBookId = "book-42", totalPdfPages = 10)
            viewModel.lifecycleHolder.goToPage(3)
            advanceUntilIdle()

            val saved = repository.lastUpserted
            assertNotNull(saved)
            assertEquals("book-42", saved?.bookId)
            assertEquals("pdfpage:2", saved?.cfiLocation)
            assertEquals(30f, saved?.percentage ?: 0f, 0.001f)

            val uiState = viewModel.sessionUiState.value
            assertEquals(10, uiState.totalPdfPages)
            assertEquals(2, uiState.currentPdfPage)
            val counterLabel = "Page ${3} of ${uiState.totalPdfPages}"
            assertEquals("Page 3 of 10", counterLabel)
        }

    @Test
    fun goToPdfPage_updatesCurrentPageAndProgress() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = FakeReaderRepository()
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            setPdfState(viewModel, selectedBookId = "book-42", totalPdfPages = 10)
            viewModel.lifecycleHolder.goToPdfPage(6)
            advanceUntilIdle()

            assertEquals(6, viewModel.sessionUiState.value.currentPdfPage)
            assertEquals("pdfpage:6", repository.lastUpserted?.cfiLocation)
        }

    @Test
    fun nextAndPreviousPdfNavigation_keepsCurrentPageConsistent() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = FakeReaderRepository()
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            setPdfState(
                viewModel,
                selectedBookId = "book-42",
                totalPdfPages = 10,
                currentPdfPage = 4,
            )

            viewModel.lifecycleHolder.goToNextPdfPage()
            advanceUntilIdle()
            assertEquals(5, viewModel.sessionUiState.value.currentPdfPage)

            viewModel.lifecycleHolder.goToPreviousPdfPage()
            advanceUntilIdle()
            assertEquals(4, viewModel.sessionUiState.value.currentPdfPage)
            assertEquals("pdfpage:4", repository.lastUpserted?.cfiLocation)
        }

    @Test
    fun goToPage_invalidPageIgnored() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = FakeReaderRepository()
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            setPdfState(viewModel, selectedBookId = "book-42", totalPdfPages = 10)
            viewModel.lifecycleHolder.goToPage(0)
            viewModel.lifecycleHolder.goToPage(11)
            advanceUntilIdle()

            assertEquals(null, repository.lastUpserted)
        }

    @Test
    fun goToPdfPage_updatesPageWithoutRenderer() =
        runTest(StandardTestDispatcher()) {
            // With the Readium-based PDF architecture, goToPdfPage only manages state.
            // Rendering is handled by the reader content composable, not the ViewModel.
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = FakeReaderRepository()
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            setPdfState(viewModel, selectedBookId = "book-42", totalPdfPages = 10)
            viewModel.lifecycleHolder.goToPdfPage(2)
            advanceUntilIdle()

            assertEquals(2, viewModel.sessionUiState.value.currentPdfPage)
            assertEquals("pdfpage:2", repository.lastUpserted?.cfiLocation)
        }

    // ── Progress Display Tests ─────────────────────────────────────

    @Test
    fun `progressPercent updates correctly when navigating EPUB chapters`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = FakeReaderRepository()
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )
            val chapters =
                listOf(
                    BookChapter(0, "c1", "Ch 1", "ch1.xhtml"),
                    BookChapter(1, "c2", "Ch 2", "ch2.xhtml"),
                    BookChapter(2, "c3", "Ch 3", "ch3.xhtml"),
                    BookChapter(3, "c4", "Ch 4", "ch4.xhtml"),
                    BookChapter(4, "c5", "Ch 5", "ch5.xhtml"),
                )
            setEpubStateWithChapters(viewModel, chapters = chapters, currentChapterIndex = 0)

            // Chapter 0/5 → 20%
            assertEquals(20f, viewModel.sessionUiState.value.progressPercent, 0.01f)
            assertEquals("1 / 5", viewModel.sessionUiState.value.progressLabel)

            // Navigate to chapter 2/5 → 60%
            setEpubStateWithChapters(viewModel, chapters = chapters, currentChapterIndex = 2)
            assertEquals(60f, viewModel.sessionUiState.value.progressPercent, 0.01f)
            assertEquals("3 / 5", viewModel.sessionUiState.value.progressLabel)

            // Last chapter 4/5 → 99% (capped — real overall % requires Readium locator)
            setEpubStateWithChapters(viewModel, chapters = chapters, currentChapterIndex = 4)
            assertEquals(99f, viewModel.sessionUiState.value.progressPercent, 0.01f)
            assertEquals("5 / 5", viewModel.sessionUiState.value.progressLabel)
        }

    @Test
    fun `progressPercent updates correctly when navigating PDF pages`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = FakeReaderRepository()
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            // 10 pages, on page 0 → 10%
            setPdfStateWithPages(viewModel, totalPages = 10, currentPage = 0)
            assertEquals(10f, viewModel.sessionUiState.value.progressPercent, 0.01f)
            assertEquals("1 / 10", viewModel.sessionUiState.value.progressLabel)

            // Page 4/10 → 50%
            setPdfStateWithPages(viewModel, totalPages = 10, currentPage = 4)
            assertEquals(50f, viewModel.sessionUiState.value.progressPercent, 0.01f)
            assertEquals("5 / 10", viewModel.sessionUiState.value.progressLabel)

            // Last page 9/10 → 100%
            setPdfStateWithPages(viewModel, totalPages = 10, currentPage = 9)
            assertEquals(100f, viewModel.sessionUiState.value.progressPercent, 0.01f)
            assertEquals("10 / 10", viewModel.sessionUiState.value.progressLabel)
        }

    @Test
    fun `progressPercent is 0 when no chapters or pages loaded`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = FakeReaderRepository()
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = repository,
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(repository),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            assertEquals(0f, viewModel.sessionUiState.value.progressPercent, 0.01f)
            assertEquals("", viewModel.sessionUiState.value.progressLabel)
        }

    private fun setEpubStateWithChapters(
        viewModel: ReaderViewModel,
        chapters: List<BookChapter>,
        currentChapterIndex: Int,
    ) {
        viewModel.lifecycleHolder.setEpubStateForTest(
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
        )
    }

    private fun setPdfStateWithPages(
        viewModel: ReaderViewModel,
        totalPages: Int,
        currentPage: Int,
    ) {
        viewModel.lifecycleHolder.setPdfStateForTest(
            totalPages = totalPages,
            currentPage = currentPage,
        )
    }

    private fun setPdfState(
        viewModel: ReaderViewModel,
        selectedBookId: String,
        totalPdfPages: Int,
        currentPdfPage: Int = 0,
    ) {
        viewModel.lifecycleHolder.setPdfStateForTest(
            selectedBookId = selectedBookId,
            totalPages = totalPdfPages,
            currentPage = currentPdfPage,
        )
    }

    private class FakeReaderRepository : ReaderRepository {
        private val progressFlow = MutableStateFlow<ReadingProgress?>(null)
        var lastUpserted: ReadingProgress? = null

        override fun observeProgress(bookId: String): Flow<ReadingProgress?> = progressFlow

        override suspend fun upsertProgress(progress: ReadingProgress) {
            lastUpserted = progress
            progressFlow.value = progress
        }

        override suspend fun updateBookReadingState(
            bookId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) {
            // No-op in fake
        }

        override fun observeAllHighlights(): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override fun observeHighlights(bookId: String): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override suspend fun upsertHighlight(highlight: Highlight) = Unit

        override fun observeAllBookmarks(): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override suspend fun upsertBookmark(bookmark: Bookmark) = Unit

        override suspend fun getProgressForBook(bookId: String): com.nexo.domain.model.ReadingProgress? = null

        override suspend fun getHighlightsForBook(bookId: String): List<com.nexo.domain.model.Highlight> = emptyList()

        override suspend fun getBookmarksForBook(bookId: String): List<com.nexo.domain.model.Bookmark> = emptyList()
    }

    private class FakeReadingStatsRepository : ReadingStatsRepository {
        override fun observeStats(bookId: String): Flow<ReadingStatsData?> = MutableStateFlow(null)

        override fun observeTotalTime(): Flow<Long> = MutableStateFlow(0L)

        override suspend fun updateReadingTime(
            bookId: String,
            additionalMinutes: Long,
        ) = Unit

        override suspend fun deleteStats(bookId: String) = Unit

        override fun observeBookStats(): kotlinx.coroutines.flow.Flow<List<com.nexo.domain.repository.ReadingStatsData>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())

        override suspend fun getDailyActivity(userId: String?): List<com.nexo.domain.model.DailyReadingActivity> = emptyList()
    }
}
