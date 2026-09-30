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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelNavigationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `navigateToCfiAfterLoad stores pending CFI`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            viewModel.lifecycleHolder.navigateToCfiAfterLoad("pdfpage:5")

            assertEquals("pdfpage:5", viewModel.lifecycleHolder.pendingCfiAfterLoad)
        }

    @Test
    fun `applyPendingCfi with EPUB CFI navigates to correct chapter`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )
            val chapters =
                listOf(
                    BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"),
                    BookChapter(1, "ch2", "Chapter 2", "ch2.xhtml"),
                    BookChapter(2, "ch3", "Chapter 3", "ch3.xhtml"),
                )
            viewModel.lifecycleHolder.setEpubStateForTest(chapters = chapters)
            viewModel.lifecycleHolder.navigateToCfiAfterLoad("epubcfi(/6/3)")

            viewModel.lifecycleHolder.applyPendingCfi()
            advanceUntilIdle()

            // CFI /6/3 extracts chapter index 3, which maps to 0-based index 2
            assertEquals("Should navigate to chapter index 2 (third chapter)", 2, viewModel.sessionUiState.value.currentChapterIndex)
        }

    @Test
    fun `applyPendingCfi with PDF page navigates to correct page`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )
            viewModel.lifecycleHolder.setPdfStateForTest(
                selectedBookId = "book-42",
                totalPages = 10,
                currentPage = 0,
            )
            viewModel.lifecycleHolder.navigateToCfiAfterLoad("pdfpage:5")

            viewModel.lifecycleHolder.applyPendingCfi()
            advanceUntilIdle()

            assertEquals("Should navigate to page 5", 5, viewModel.sessionUiState.value.currentPdfPage)
        }

    @Test
    fun `applyPendingCfi clears pending CFI after navigation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )
            viewModel.lifecycleHolder.setPdfStateForTest(
                selectedBookId = "book-42",
                totalPages = 10,
            )
            viewModel.lifecycleHolder.navigateToCfiAfterLoad("pdfpage:3")

            viewModel.lifecycleHolder.applyPendingCfi()
            advanceUntilIdle()

            assertNull("Pending CFI should be null after application", viewModel.lifecycleHolder.pendingCfiAfterLoad)
        }

    @Test
    fun `navigateToCfiAfterLoad applies CFI immediately when book is already loaded`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )
            val chapters =
                listOf(
                    BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"),
                    BookChapter(1, "ch2", "Chapter 2", "ch2.xhtml"),
                    BookChapter(2, "ch3", "Chapter 3", "ch3.xhtml"),
                )
            // Simulate a book that is already loaded (isLoading = false, readiumPublication != null)
            viewModel.lifecycleHolder.setBookLoadedForTest(publication = mockk(relaxed = true))
            viewModel.lifecycleHolder.setEpubStateForTest(chapters = chapters)

            viewModel.lifecycleHolder.navigateToCfiAfterLoad("epubcfi(/6/3)")
            advanceUntilIdle()

            // applyPendingCfi should fire immediately: pending cleared and chapter navigated
            assertNull("Pending CFI should be cleared after immediate apply", viewModel.lifecycleHolder.pendingCfiAfterLoad)
            assertEquals("Should navigate to chapter index 2 (third chapter)", 2, viewModel.sessionUiState.value.currentChapterIndex)
        }

    // ── Private fakes ────────────────────────────────────────────────

    private class FakeReaderRepository : ReaderRepository {
        override fun observeProgress(bookId: String): Flow<ReadingProgress?> = MutableStateFlow(null)

        override suspend fun upsertProgress(progress: ReadingProgress) = Unit

        override suspend fun updateBookReadingState(
            bookId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) = Unit

        override fun observeAllHighlights(): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override fun observeHighlights(bookId: String): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override suspend fun upsertHighlight(highlight: Highlight) = Unit

        override fun observeAllBookmarks(): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override suspend fun upsertBookmark(bookmark: Bookmark) = Unit

        override suspend fun getProgressForBook(bookId: String): ReadingProgress? = null

        override suspend fun getHighlightsForBook(bookId: String): List<Highlight> = emptyList()

        override suspend fun getBookmarksForBook(bookId: String): List<Bookmark> = emptyList()
    }

    private class FakeReadingStatsRepository : ReadingStatsRepository {
        override fun observeStats(bookId: String): Flow<ReadingStatsData?> = MutableStateFlow(null)

        override fun observeTotalTime(): Flow<Long> = MutableStateFlow(0L)

        override suspend fun updateReadingTime(
            bookId: String,
            additionalMinutes: Long,
        ) = Unit

        override suspend fun deleteStats(bookId: String) = Unit

        override fun observeBookStats(): Flow<List<ReadingStatsData>> = MutableStateFlow(emptyList())

        override suspend fun getDailyActivity(userId: String?): List<com.nexo.domain.model.DailyReadingActivity> = emptyList()
    }
}
