package com.nexo.presentation.viewmodel

import android.app.Application
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T1 slice-composition harness (SDD reader-facade-split).
 *
 * Each slice test asserts the VM re-export mirrors the holder-owned state.
 * T2 points the Search assertions at `searchUiState` (slice 1 shipped);
 * T3 points Chrome/Settings at `chromeUiState`/`settingsUiState`;
 * T4 points SleepTimer at `sleepTimerUiState` (glue covered in
 * ReaderViewModelSleepTimerTest); T5-T6 fill in session + annotation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSliceCompositionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `search slice mirrors holder state through VM re-export`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            viewModel.searchStateHolder.onToggleSearch()
            assertSearchMirror(
                viewModel,
                expectedActive = true,
                expectedQuery = "",
                message = "toggle must mirror into the VM re-export",
            )

            viewModel.searchStateHolder.onSearchQuery("odisea", null, null)
            advanceTimeBy(400)
            runCurrent()

            // Null publication: query mirrors, search settles with no results.
            assertSearchMirror(
                viewModel,
                expectedActive = true,
                expectedQuery = "odisea",
                message = "query must mirror into the VM re-export",
            )
            assertFalse(viewModel.searchUiState.value.isSearching)
            assertTrue(
                viewModel.searchUiState.value.searchResults
                    .isEmpty(),
            )
        }

    @Test
    fun `chrome slice mirrors holder state through VM re-export`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            assertFalse(viewModel.chromeUiState.value.isFullscreen)

            viewModel.fullscreenManager.onToggleFullscreen()

            assertTrue(viewModel.chromeUiState.value.isFullscreen)
            // No cross-slice emission: annotation-adjacent state untouched.
            assertFalse(viewModel.annotationUiState.value.showHighlightsSheet)
        }

    @Test
    fun `settings slice mirrors holder state through VM re-export`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            assertFalse(viewModel.settingsUiState.value.showSplitSettings)

            viewModel.settingsManager.onToggleSplitSettings()

            assertTrue(viewModel.settingsUiState.value.showSplitSettings)
            // No cross-slice emission: chrome state untouched.
            assertFalse(viewModel.chromeUiState.value.isFullscreen)
        }

    // ── Harness (per-slice mirror assertions; T3-T6 extend here) ────

    private fun assertSearchMirror(
        viewModel: ReaderViewModel,
        expectedActive: Boolean,
        expectedQuery: String,
        message: String,
    ) {
        val reExport = viewModel.searchUiState.value
        assertEquals(message, expectedActive, reExport.isSearchActive)
        assertEquals(message, expectedQuery, reExport.searchQuery)
    }

    @Test
    fun `sleepTimer slice mirrors holder state through VM re-export`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            assertFalse(viewModel.sleepTimerUiState.value.isActive)

            viewModel.sleepTimerManager.startTimer(
                com.nexo.presentation.viewmodel.reader.SleepTimerManager.END_OF_CHAPTER,
            )

            assertTrue(viewModel.sleepTimerUiState.value.isActive)
            // No cross-slice emission: chrome state untouched.
            assertFalse(viewModel.chromeUiState.value.isFullscreen)
        }

    @Test
    fun `session slice mirrors holder state through VM re-export`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            viewModel.lifecycleHolder.setEpubStateForTest(
                chapters =
                    listOf(
                        BookChapter(0, "c1", "Ch 1", "ch1.xhtml"),
                        BookChapter(1, "c2", "Ch 2", "ch2.xhtml"),
                    ),
                currentChapterIndex = 1,
                selectedBookId = "book-9",
            )

            assertEquals("book-9", viewModel.sessionUiState.value.selectedBookId)
            assertEquals(1, viewModel.sessionUiState.value.currentChapterIndex)
            // No cross-slice emission: chrome state untouched.
            assertFalse(viewModel.chromeUiState.value.isFullscreen)
        }

    // ── Annotation slice (SDD reader-facade-split, T6) ──────────────

    @Test
    fun `annotation slice mirrors holder state through VM re-export`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val highlight =
                com.nexo.domain.model.Highlight(
                    id = "hl-1",
                    bookId = "book-1",
                    cfiRange = "epubcfi(/6/2!/4/1)",
                    textContent = "highlighted",
                    note = null,
                    color = com.nexo.domain.model.HighlightColor.YELLOW.hex,
                    updatedAtEpochMillis = 0L,
                    deletedAtEpochMillis = null,
                )
            val holder = viewModel.interactionHolder
            holder.testSetInitialHighlights(listOf(highlight))
            runCurrent()

            // Slice re-export mirrors the annotation owner (interactionHolder).
            assertEquals(
                "annotationUiState must mirror interactionHolder.highlights",
                listOf("hl-1"),
                viewModel.annotationUiState.value.highlights
                    .map { it.id },
            )
            // No cross-slice emission: chrome / session state untouched.
            assertFalse(viewModel.chromeUiState.value.isFullscreen)
            assertNull(viewModel.sessionUiState.value.selectedBookId)
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
