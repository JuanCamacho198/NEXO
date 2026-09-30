package com.nexo.presentation.viewmodel

import android.app.Application
import com.nexo.domain.model.SearchResult
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSearchTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `search toggle flips isSearchActive on the slice flow`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            assertFalse(viewModel.searchUiState.value.isSearchActive)

            viewModel.searchStateHolder.onToggleSearch()
            assertTrue(viewModel.searchUiState.value.isSearchActive)

            viewModel.searchStateHolder.onToggleSearch()
            assertFalse(viewModel.searchUiState.value.isSearchActive)
        }

    @Test
    fun `onClearSearch resets search state`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.searchStateHolder.onToggleSearch()
            viewModel.searchStateHolder.onSearchQuery("test", null, null)

            viewModel.onClearSearch()

            val state = viewModel.searchUiState.value
            assertEquals("", state.searchQuery)
            assertTrue(state.searchResults.isEmpty())
            assertFalse(state.isSearching)
        }

    @Test
    fun `onDismissSearch resets search state and hides sheet`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.searchStateHolder.onToggleSearch()
            viewModel.searchStateHolder.onSearchQuery("test", null, null)

            viewModel.onDismissSearch()

            val state = viewModel.searchUiState.value
            assertFalse(state.isSearchActive)
            assertEquals("", state.searchQuery)
            assertTrue(state.searchResults.isEmpty())
            assertFalse(state.isSearching)
        }

    @Test
    fun `search slice carries query state after toggle and query`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.searchStateHolder.onToggleSearch()
            viewModel.searchStateHolder.onSearchQuery("odisea", null, null)
            advanceTimeBy(400)
            runCurrent()

            val state = viewModel.searchUiState.value
            assertTrue(state.isSearchActive)
            assertEquals("odisea", state.searchQuery)
            assertTrue(state.searchResults.isEmpty())
            assertFalse(state.isSearching)
        }

    @Test
    fun `toggle and query pass-through delegates are deleted`() {
        val names = ReaderViewModel::class.java.methods.map { it.name }
        assertFalse(names.contains("onToggleSearch"))
        assertFalse(names.contains("onSearchQuery"))
    }

    @Test
    fun `onSearchResultSelected with same chapter dismisses without navigation`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    mainDispatcher = dispatcher,
                )

            setEpubState(
                viewModel,
                chapters =
                    listOf(
                        BookChapter(0, "c1", "Ch 1", "ch1.xhtml"),
                        BookChapter(1, "c2", "Ch 2", "ch2.xhtml"),
                    ),
                currentChapterIndex = 1,
            )

            val result =
                SearchResult(
                    text = "...sample text...",
                    offset = 0,
                    chapterIndex = 1,
                )
            viewModel.onSearchResultSelected(result)

            assertEquals(1, viewModel.sessionUiState.value.currentChapterIndex)
            assertFalse(viewModel.searchUiState.value.isSearchActive)
        }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun createViewModel(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): ReaderViewModel {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        return ReaderViewModel(
            application = mockk<Application>(relaxed = true),
            readerRepository = FakeReaderRepository(),
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
            defaultBookId = null,
            mainDispatcher = dispatcher,
        )
    }

    // Slice 4 (SDD reader-facade-split, T5): session state is owner-held —
    // seed it through the session owner, not the VM merge flow.
    private fun setEpubState(
        viewModel: ReaderViewModel,
        chapters: List<BookChapter>,
        currentChapterIndex: Int,
    ) {
        viewModel.lifecycleHolder.setEpubStateForTest(
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
        )
    }
}
