package com.nexo.presentation.viewmodel

import android.app.Application
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Slice 4 tests (SDD reader-facade-split, T5).
 *
 * The session state lives in [ReaderLifecycleStateHolder] (and its lifecycle
 * collaborators) and is re-exported as `sessionUiState`. The pending-CFI and
 * typography-reflow wiring moved into the lifecycle owner first (C1); this
 * file pins the slice flow and the delegate
 * deletions. `loadBook` stays on the VM: it orchestrates holders +
 * fullscreen + the pending-CFI wait, it is not a pass-through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSessionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `session state lands on the slice flow`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val chapters =
                listOf(
                    BookChapter(0, "c1", "Ch 1", "ch1.xhtml"),
                    BookChapter(1, "c2", "Ch 2", "ch2.xhtml"),
                    BookChapter(2, "c3", "Ch 3", "ch3.xhtml"),
                )
            viewModel.lifecycleHolder.setEpubStateForTest(
                chapters = chapters,
                currentChapterIndex = 1,
                selectedBookId = "book-7",
            )

            val slice = viewModel.sessionUiState.value
            assertEquals("book-7", slice.selectedBookId)
            assertEquals(3, slice.chapters.size)
            assertEquals(1, slice.currentChapterIndex)
            assertEquals("epub", slice.bookFormat)

            // No cross-slice emission: chrome/timer state untouched.
            assertFalse(viewModel.chromeUiState.value.isFullscreen)
            assertFalse(viewModel.sleepTimerUiState.value.isActive)
        }

    @Test
    fun `pending CFI navigates through the session owner without VM glue`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.lifecycleHolder.setEpubStateForTest(
                chapters =
                    listOf(
                        BookChapter(0, "c1", "Ch 1", "ch1.xhtml"),
                        BookChapter(1, "c2", "Ch 2", "ch2.xhtml"),
                        BookChapter(2, "c3", "Ch 3", "ch3.xhtml"),
                    ),
                currentChapterIndex = 0,
            )
            viewModel.lifecycleHolder.navigateToCfiAfterLoad("epubcfi(/6/3)")

            viewModel.lifecycleHolder.applyPendingCfi()
            advanceUntilIdle()

            assertNull(viewModel.lifecycleHolder.pendingCfiAfterLoad)
            assertEquals(2, viewModel.sessionUiState.value.currentChapterIndex)
        }

    @Test
    fun `pdf progress emits via sessionUiState`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.lifecycleHolder.setPdfStateForTest(
                selectedBookId = "book-42",
                totalPages = 10,
                currentPage = 0,
            )

            viewModel.lifecycleHolder.goToPdfPage(6)
            advanceUntilIdle()

            assertEquals(6, viewModel.sessionUiState.value.currentPdfPage)
            assertEquals(10, viewModel.sessionUiState.value.totalPdfPages)
        }

    @Test
    fun `session pass-through delegates are deleted`() {
        val names = ReaderViewModel::class.java.methods.map { it.name }
        assertFalse(names.contains("goToNextChapter"))
        assertFalse(names.contains("goToPreviousChapter"))
        assertFalse(names.contains("goToChapter"))
        assertFalse(names.contains("goToNextPdfPage"))
        assertFalse(names.contains("goToPreviousPdfPage"))
        assertFalse(names.contains("goToPage"))
        assertFalse(names.contains("goToPdfPage"))
        assertFalse(names.contains("onTapZone"))
        assertFalse(names.contains("onProgressChange"))
        assertFalse(names.contains("restoreProgressForBook"))
        assertFalse(names.contains("setActiveUserId"))
        assertFalse(names.contains("updateProgress"))
        assertFalse(names.contains("onReaderOpened"))
        assertFalse(names.contains("onReaderPaused"))
        assertFalse(names.contains("onReaderBackgrounded"))
        assertFalse(names.contains("onToggleTocSheet"))
        assertFalse(names.contains("onReadiumLocatorChanged"))
        assertFalse(names.contains("onReadiumViewportChanged"))
        assertFalse(names.contains("onPdfDocumentLoaded"))
        assertFalse(names.contains("navigateToCfiAfterLoad"))
        assertFalse(names.contains("applyPendingCfi"))
        // loadBook stays: VM orchestration, not a pass-through.
        assertTrue(names.contains("loadBook"))
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun createViewModel(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): ReaderViewModel {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val fake = FakeReaderRepository()
        return ReaderViewModel(
            application = mockk<Application>(relaxed = true),
            readerRepository = fake,
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(fake),
            defaultBookId = null,
            mainDispatcher = dispatcher,
        )
    }
}
