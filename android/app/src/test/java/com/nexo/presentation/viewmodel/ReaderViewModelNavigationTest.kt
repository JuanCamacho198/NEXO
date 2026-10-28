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
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import com.nexo.testutil.activeUserProviderFor
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
                    activeUser = activeUserProviderFor(),
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
                    activeUser = activeUserProviderFor(),
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
                    activeUser = activeUserProviderFor(),
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
                    activeUser = activeUserProviderFor(),
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
                    activeUser = activeUserProviderFor(),
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

}
