package com.nexo.presentation.viewmodel

import app.cash.turbine.test
import com.nexo.domain.model.Book
import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.model.ReadingStats
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.UiEvent
import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [HighlightsViewModel].
 *
 * NOTE: [HighlightsViewModel.uiState] uses `stateIn(WhileSubscribed(5000))`,
 * which only emits AFTER the first subscriber starts collecting.  Every test
 * must launch a background collection before reading `.value`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HighlightsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Start collecting uiState in the background so `stateIn` emits. */
    private fun <T> collectUiState(viewModel: HighlightsViewModel) {
        // backgroundScope in runTest is available — this is called inside runTest
    }

    @Test
    fun `initial state has no tag filter`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", tag = "cita"),
                    createHighlight(id = "2", tag = "idea"),
                )
            val viewModel = createViewModel(highlights = highlights)

            val state = viewModel.uiState.first()
            assertNull(state.tagFilter)
            assertEquals(2, state.filteredHighlights.size)
        }

    @Test
    fun `onTagFilterChanged filters highlights by tag`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", tag = "cita", textContent = "Quote one"),
                    createHighlight(id = "2", tag = "idea", textContent = "Idea one"),
                    createHighlight(id = "3", tag = "cita", textContent = "Quote two"),
                    createHighlight(id = "4", tag = null, textContent = "No tag"),
                )
            val viewModel = createViewModel(highlights = highlights)
            viewModel.uiState.first() // trigger stateIn subscription

            viewModel.onTagFilterChanged("cita")

            val state = viewModel.uiState.first()
            assertEquals("cita", state.tagFilter)
            assertEquals(2, state.filteredHighlights.size)
            assertTrue(state.filteredHighlights.all { it.tag == "cita" })
        }

    @Test
    fun `onTagFilterChanged with null clears filter`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", tag = "cita"),
                    createHighlight(id = "2", tag = "idea"),
                )
            val viewModel = createViewModel(highlights = highlights)
            viewModel.uiState.first() // trigger stateIn subscription

            viewModel.onTagFilterChanged("cita")
            viewModel.onTagFilterChanged(null)

            val state = viewModel.uiState.first()
            assertNull(state.tagFilter)
            assertEquals(2, state.filteredHighlights.size)
        }

    @Test
    fun `availableTags extracted from highlights`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", tag = "cita"),
                    createHighlight(id = "2", tag = "idea"),
                    createHighlight(id = "3", tag = "cita"),
                    createHighlight(id = "4", tag = "ficci\u00F3n"),
                    createHighlight(id = "5", tag = null),
                )
            val viewModel = createViewModel(highlights = highlights)

            val state = viewModel.uiState.first()
            assertEquals(listOf("cita", "ficci\u00F3n", "idea"), state.availableTags)
        }

    @Test
    fun `onTypeFilterChanged filters by highlight type`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", type = "quote"),
                    createHighlight(id = "2", type = "idea"),
                    createHighlight(id = "3", type = "passage"),
                )
            val viewModel = createViewModel(highlights = highlights)
            viewModel.uiState.first() // trigger stateIn subscription

            viewModel.onTypeFilterChanged("quotes")

            val state = viewModel.uiState.first()
            assertEquals("quotes", state.typeFilter)
            assertEquals(1, state.filteredHighlights.size)
            assertEquals("quote", state.filteredHighlights.first().type)
        }

    @Test
    fun `onColorFilterChanged filters by color`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", color = HighlightColor.YELLOW.hex),
                    createHighlight(id = "2", color = HighlightColor.BLUE.hex),
                    createHighlight(id = "3", color = HighlightColor.YELLOW.hex),
                )
            val viewModel = createViewModel(highlights = highlights)
            viewModel.uiState.first() // trigger stateIn subscription

            viewModel.onColorFilterChanged(HighlightColor.YELLOW.hex)

            val state = viewModel.uiState.first()
            assertEquals(setOf(HighlightColor.YELLOW.hex), state.colorFilter)
            assertEquals(2, state.filteredHighlights.size)
        }

    @Test
    fun `combined tag and type filter`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", tag = "cita", type = "quote"),
                    createHighlight(id = "2", tag = "cita", type = "idea"),
                    createHighlight(id = "3", tag = "idea", type = "quote"),
                )
            val viewModel = createViewModel(highlights = highlights)
            viewModel.uiState.first() // trigger stateIn subscription

            viewModel.onTagFilterChanged("cita")
            viewModel.onTypeFilterChanged("quotes")

            val state = viewModel.uiState.first()
            assertEquals(1, state.filteredHighlights.size)
            assertEquals("1", state.filteredHighlights.first().id)
        }

    @Test
    fun `multiSelectFilter matches any selected color`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", color = HighlightColor.YELLOW.hex),
                    createHighlight(id = "2", color = HighlightColor.GREEN.hex),
                    createHighlight(id = "3", color = HighlightColor.BLUE.hex),
                )
            val viewModel = createViewModel(highlights = highlights)
            viewModel.uiState.first()

            viewModel.onColorFilterChanged(HighlightColor.YELLOW.hex)
            viewModel.onColorFilterChanged(HighlightColor.GREEN.hex)

            val state = viewModel.uiState.first()
            assertEquals(setOf(HighlightColor.YELLOW.hex, HighlightColor.GREEN.hex), state.colorFilter)
            assertEquals(2, state.filteredHighlights.size)
            assertTrue(state.filteredHighlights.all { it.color != HighlightColor.BLUE.hex })
        }

    @Test
    fun `onColorFilterChanged toggles colors`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", color = HighlightColor.YELLOW.hex),
                )
            val viewModel = createViewModel(highlights = highlights)
            viewModel.uiState.first()

            viewModel.onColorFilterChanged(HighlightColor.YELLOW.hex)
            val stateAfterAdd = viewModel.uiState.first()
            assertEquals(setOf(HighlightColor.YELLOW.hex), stateAfterAdd.colorFilter)

            viewModel.onColorFilterChanged(HighlightColor.YELLOW.hex)
            val stateAfterRemove = viewModel.uiState.first()
            assertEquals(emptySet<String>(), stateAfterRemove.colorFilter)
        }

    @Test
    fun `onColorFilterReset clears all colors`() =
        runTest {
            val highlights =
                listOf(
                    createHighlight(id = "1", color = HighlightColor.YELLOW.hex),
                    createHighlight(id = "2", color = HighlightColor.BLUE.hex),
                )
            val viewModel = createViewModel(highlights = highlights)
            viewModel.uiState.first()

            viewModel.onColorFilterChanged(HighlightColor.YELLOW.hex)
            viewModel.onColorFilterChanged(HighlightColor.BLUE.hex)
            val stateBeforeReset = viewModel.uiState.first()
            assertEquals(2, stateBeforeReset.colorFilter.size)

            viewModel.onColorFilterReset()
            val stateAfterReset = viewModel.uiState.first()
            assertEquals(emptySet<String>(), stateAfterReset.colorFilter)
            assertEquals(2, stateAfterReset.filteredHighlights.size)
        }

    @Test
    fun `onCopyHighlight emits copy event`() =
        runTest {
            val highlight = createHighlight(textContent = "Hello world")
            val viewModel = createViewModel(highlights = listOf(highlight))
            viewModel.uiState.first()

            viewModel.uiEvent.test {
                viewModel.onCopyHighlight(highlight)

                val event = awaitItem()
                assertTrue(event is UiEvent.CopyToClipboard)
                assertEquals("Hello world", (event as UiEvent.CopyToClipboard).text)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `onConfirmColorChange persists and emits snackbar`() =
        runTest {
            val highlight = createHighlight(color = HighlightColor.YELLOW.hex)
            val readerRepo = FakeReaderRepository(listOf(highlight))
            val viewModel = HighlightsViewModel(readerRepo, FakeHomeRepository())
            viewModel.uiState.first()

            viewModel.onChangeHighlightColor(highlight)

            viewModel.uiEvent.test {
                viewModel.onConfirmColorChange(HighlightColor.GREEN.hex)

                assertEquals(HighlightColor.GREEN.hex, readerRepo.lastUpsertedHighlight?.color)
                val event = awaitItem()
                assertTrue(event is UiEvent.ShowSnackbar)
                assertEquals("Color changed", (event as UiEvent.ShowSnackbar).message)
                cancelAndIgnoreRemainingEvents()
            }

            val state = viewModel.uiState.first()
            assertNull(state.selectedHighlightForColorChange)
        }

    @Test
    fun `onSaveHighlightTag persists and emits snackbar`() =
        runTest {
            val highlight = createHighlight(tag = null)
            val readerRepo = FakeReaderRepository(listOf(highlight))
            val viewModel = HighlightsViewModel(readerRepo, FakeHomeRepository())
            viewModel.uiState.first()

            viewModel.onAddHighlightTag(highlight)

            viewModel.uiEvent.test {
                viewModel.onSaveHighlightTag("my-tag")

                assertEquals("my-tag", readerRepo.lastUpsertedHighlight?.tag)
                val event = awaitItem()
                assertTrue(event is UiEvent.ShowSnackbar)
                assertEquals("Tag saved", (event as UiEvent.ShowSnackbar).message)
                cancelAndIgnoreRemainingEvents()
            }

            val state = viewModel.uiState.first()
            assertNull(state.selectedHighlightForTagEdit)
            assertEquals("", state.editTagText)
        }

    @Test
    fun `onViewInBook emits navigation event`() =
        runTest {
            val highlight =
                createHighlight(
                    bookId = "book-42",
                    textContent = "memorable passage",
                )
            val viewModel = createViewModel(highlights = listOf(highlight))
            viewModel.uiState.first()

            viewModel.uiEvent.test {
                viewModel.onViewInBook(highlight)

                val event = awaitItem()
                assertTrue(event is UiEvent.OpenBookAtLocation)
                assertEquals("book-42", (event as UiEvent.OpenBookAtLocation).bookId)
                assertEquals("epubcfi(/6/2)", (event as UiEvent.OpenBookAtLocation).cfiRange)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun createHighlight(
        id: String = "hl-${java.util.UUID.randomUUID()}",
        bookId: String = "book-1",
        tag: String? = null,
        color: String = HighlightColor.YELLOW.hex,
        textContent: String = "Sample text $id",
        type: String? = null,
        note: String? = null,
    ): Highlight =
        Highlight(
            id = id,
            bookId = bookId,
            cfiRange = "epubcfi(/6/2)",
            textContent = textContent,
            note = note,
            color = color,
            updatedAtEpochMillis = 0L,
            deletedAtEpochMillis = null,
            tag = tag,
            type = type,
        )

    private fun createViewModel(
        highlights: List<Highlight> = emptyList(),
        books: List<Book> = emptyList(),
    ): HighlightsViewModel {
        val readerRepo = FakeReaderRepository(highlights)
        val homeRepo = FakeHomeRepository(books)
        return HighlightsViewModel(readerRepo, homeRepo)
    }

    private class FakeReaderRepository(
        private val highlights: List<Highlight> = emptyList(),
    ) : ReaderRepository {
        private val highlightsFlow = MutableStateFlow(highlights)
        var lastUpsertedHighlight: Highlight? = null
            private set

        override fun observeProgress(bookId: String): Flow<com.nexo.domain.model.ReadingProgress?> = MutableStateFlow(null)

        override suspend fun upsertProgress(progress: com.nexo.domain.model.ReadingProgress) = Unit

        override suspend fun updateBookReadingState(
            bookId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) = Unit

        override suspend fun getProgressForBook(bookId: String): com.nexo.domain.model.ReadingProgress? = null

        override fun observeAllHighlights(): Flow<List<Highlight>> = highlightsFlow

        override fun observeHighlights(bookId: String): Flow<List<Highlight>> = highlightsFlow

        override suspend fun upsertHighlight(highlight: Highlight) {
            lastUpsertedHighlight = highlight
        }

        override suspend fun getHighlightsForBook(bookId: String): List<Highlight> = highlights

        override fun observeAllTags(): Flow<List<String>> =
            MutableStateFlow(
                highlights.mapNotNull { it.tag }.distinct().sorted(),
            )

        override fun observeAllBookmarks(): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override suspend fun upsertBookmark(bookmark: Bookmark) = Unit

        override suspend fun getBookmarksForBook(bookId: String): List<Bookmark> = emptyList()
    }

    private class FakeHomeRepository(
        private val books: List<Book> = emptyList(),
    ) : HomeRepository {
        private val booksFlow = MutableStateFlow(books)

        override fun observeBooks(): Flow<List<Book>> = booksFlow

        override fun observeRecentBooks(limit: Int): Flow<List<Book>> = booksFlow

        override fun observeCurrentBooks(): Flow<List<Book>> = booksFlow

        override fun observeDailyStats(
            userId: String?,
            goalMinutes: Int,
        ): Flow<ReadingStats> = MutableStateFlow(ReadingStats())

        override suspend fun deleteBook(bookId: String): Result<Unit> = Result.success(Unit)
    }
}
