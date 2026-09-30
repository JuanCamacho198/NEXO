package com.nexo.presentation.viewmodel

import android.app.Application
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsData
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.reader.ReaderInteractionState
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.readium.r2.shared.publication.Locator

/**
 * Tests for [ReaderViewModel] selection state transitions through the
 * annotation slice.
 *
 * Several production methods call unmocked Android APIs (e.g. `Log.d`,
 * `SystemClock.elapsedRealtime()`). `Log` and `SystemClock` are mocked once
 * for the whole class so that any production call through them works.
 *
 * State is driven through `viewModel.interactionHolder` (the S7 write path)
 * and asserted against `viewModel.annotationUiState` (the read path).
 * `Rect.toString()` is not mockable in pure JVM unit tests, so the one test
 * that needs a raw selection writes holder state directly via reflection
 * instead of calling `onTextSelection` (which logs `rect=$rect`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSelectionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun mockAndroidApi() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.println(any<Int>(), any<String>(), any<String>()) } returns 0

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L
    }

    @After
    fun unmockAndroidApi() = unmockkAll()

    // ── Selection state transitions ──────────────────────────────────

    @Test
    fun `selection set on the holder mirrors into annotationUiState`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val rect = Rect(100, 200, 300, 250)
            val holderState = holderStateOf(viewModel)
            // Cannot call holder.onTextSelection() directly because it builds
            // rect=$rect string → Rect.toString() → not mocked.
            holderState.value =
                holderState.value.copy(
                    selectedText = "selected text",
                    selectionRect = rect,
                    selectionState = ReaderSelectionState.New(rect, "selected text", null),
                )

            val state = viewModel.annotationUiState.value
            assertEquals("selected text", state.selectedText)
            assertNotNull(state.selectionRect)
            assertTrue(state.selectionState is ReaderSelectionState.New)
        }

    @Test
    fun `onSelectHighlightColor on existing highlight clears selection`() =
        runTest {
            val viewModel = createViewModel(testScheduler, defaultBookId = "book-1")
            val holder = viewModel.interactionHolder
            val highlight = createHighlight()
            val rectF = RectF(100f, 200f, 300f, 250f)
            // Set up coordinator via highlight tap (needed by interactionHolder)
            holder.onHighlightTapped(highlight, rectF)

            // Inline of the deleted VM delegate: session reads live, selectedText
            // from the holder's current state.
            val session = viewModel.sessionUiState.value
            holder.onSelectHighlightColor(
                color = HighlightColor.YELLOW.hex,
                selectedBookId = session.selectedBookId,
                readiumSelectionLocator = session.readiumSelectionLocator,
                selectedText = holder.state.value.selectedText,
                bookFormat = session.bookFormat,
                currentPdfPage = session.currentPdfPage,
                currentChapterIndex = session.currentChapterIndex,
            )

            val state = viewModel.annotationUiState.value
            assertTrue(state.selectionState is ReaderSelectionState.None)
            assertNull(state.selectedText)
            assertNull(state.selectionRect)
        }

    @Test
    fun `onCopySelectedText clears selection state`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val locator = createLocator()
            val rectF = RectF(100f, 200f, 300f, 250f)
            // Set up coordinator via readium selection
            holder.onReadiumSelection(
                locator = locator,
                rect = rectF,
                text = "copy this",
                existingHighlights = holder.state.value.highlights,
            )

            holder.onCopySelectedText()

            val state = viewModel.annotationUiState.value
            assertTrue("selectionState should be None", state.selectionState is ReaderSelectionState.None)
        }

    @Test
    fun `onDismissContextMenu clears all selection state`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val locator = createLocator()
            val rectF = RectF(100f, 200f, 300f, 250f)
            // Set up coordinator via readium selection
            holder.onReadiumSelection(
                locator = locator,
                rect = rectF,
                text = "text",
                existingHighlights = holder.state.value.highlights,
            )

            holder.onDismissContextMenu()

            val state = viewModel.annotationUiState.value
            assertTrue("selectionState should be None", state.selectionState is ReaderSelectionState.None)
            assertNull("selectedText cleared", state.selectedText)
            assertNull("selectionRect cleared", state.selectionRect)
        }

    @Test
    fun `onHighlightTapped sets Existing selection state`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val highlight = createHighlight()
            val rectF = RectF(100f, 200f, 300f, 250f)

            holder.onHighlightTapped(highlight, rectF)

            val state = viewModel.annotationUiState.value
            assertEquals(highlight.textContent, state.selectedText)
            assertNotNull(state.selectionRect)
            assertTrue("selectionState should be Existing", state.selectionState is ReaderSelectionState.Existing)
            val existing = state.selectionState as ReaderSelectionState.Existing
            assertEquals(highlight.id, existing.highlight.id)
        }

    @Test
    fun `onReadiumSelection within highlight debounce does not overwrite Existing when text matches`() =
        runTest {
            // The 2s debounce after a highlight tap only suppresses the polling
            // loop when the new selection text matches the tapped highlight's
            // text. In that case the user is re-selecting the same highlight
            // (or a sub-range) and the FloatingContextMenu (Existing) should
            // remain open.
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val highlight = createHighlight()
            val highlightRect = RectF(100f, 200f, 300f, 250f)
            holder.onHighlightTapped(highlight, highlightRect)

            val locator = createLocator()
            val selectionRect = RectF(110f, 210f, 310f, 260f)
            // Text matches the highlight's textContent ("highlighted text")
            holder.onReadiumSelection(
                locator = locator,
                rect = selectionRect,
                text = "highlighted text",
                existingHighlights = holder.state.value.highlights,
            )

            val state = viewModel.annotationUiState.value
            // activeHighlightId is managed internally by SelectionCoordinator —
            // asserted via selectionState; the coordinator id is internal.
            assertTrue(
                "selectionState should remain Existing (debounce active, text matches)",
                state.selectionState is ReaderSelectionState.Existing,
            )
        }

    @Test
    fun `onReadiumSelection within highlight debounce overrides Existing when text differs`() =
        runTest {
            // Regression test: a new text selection that doesn't match any
            // existing highlight must transition to New, even if the debounce
            // from a previous highlight tap is still active. Otherwise the UI
            // would show the FloatingContextMenu (Tag/Delete) instead of the
            // TextSelectionMenu (Dictionary/Copy/Share).
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val highlight = createHighlight()
            val highlightRect = RectF(100f, 200f, 300f, 250f)
            holder.onHighlightTapped(highlight, highlightRect)

            val locator = createLocator()
            val selectionRect = RectF(110f, 210f, 310f, 260f)
            holder.onReadiumSelection(
                locator = locator,
                rect = selectionRect,
                text = "completely different text",
                existingHighlights = holder.state.value.highlights,
            )

            val state = viewModel.annotationUiState.value
            assertTrue(
                "selectionState should be New (text differs from highlight)",
                state.selectionState is ReaderSelectionState.New,
            )
        }

    @Test
    fun `onSelectionCleared within highlight debounce does not clear`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val highlight = createHighlight()
            val highlightRect = RectF(100f, 200f, 300f, 250f)
            holder.onHighlightTapped(highlight, highlightRect)

            holder.onSelectionCleared()

            val state = viewModel.annotationUiState.value
            // activeHighlightId is managed internally by SelectionCoordinator
            assertTrue(
                "selectionState should remain Existing (debounce active)",
                state.selectionState is ReaderSelectionState.Existing,
            )
        }

    // ── Input panel toggles ─────────────────────────────────────────

    @Test
    fun `onShowColorPickerPopover updates color picker state`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            // This method does not call any Android APIs directly
            holder.onShowColorPickerPopover()

            val state = viewModel.annotationUiState.value
            assertTrue(state.showColorPickerPopover)
        }

    @Test
    fun `onShowTagInput updates tag input state`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val highlight = createHighlight()
            val rectF = RectF(100f, 200f, 300f, 250f)

            // Tap the highlight first: onShowTagInput resolves the active
            // highlight through the selection coordinator.
            holder.testSetInitialHighlights(listOf(highlight))
            holder.onHighlightTapped(highlight, rectF)

            holder.onShowTagInput()

            val state = viewModel.annotationUiState.value
            assertTrue("showTagInput should be true", state.showTagInput)
        }

    @Test
    fun `onShowDefinitionInput updates definition input state`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val highlight = createHighlight()
            val rectF = RectF(100f, 200f, 300f, 250f)
            holder.onHighlightTapped(highlight, rectF)

            holder.onShowDefinitionInput()

            val state = viewModel.annotationUiState.value
            assertTrue(state.showDefinitionInput)
        }

    // ── Helpers ─────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun holderStateOf(viewModel: ReaderViewModel): MutableStateFlow<ReaderInteractionState> {
        val field = viewModel.interactionHolder::class.java.getDeclaredField("_state")
        field.isAccessible = true
        return field.get(viewModel.interactionHolder) as MutableStateFlow<ReaderInteractionState>
    }

    private fun createHighlight(): Highlight =
        Highlight(
            id = "highlight-1",
            bookId = "book-1",
            cfiRange = "epubcfi(/6/2!/4/1)",
            textContent = "highlighted text",
            note = null,
            color = HighlightColor.YELLOW.hex,
            updatedAtEpochMillis = 0L,
            deletedAtEpochMillis = null,
            locatorJson = null, // avoid JSONObject in unit tests
        )

    private fun createLocator(): Locator = mockk(relaxed = true)

    private fun createViewModel(
        scheduler: TestCoroutineScheduler,
        defaultBookId: String? = null,
    ): ReaderViewModel {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        return ReaderViewModel(
            application = mockk<Application>(relaxed = true),
            readerRepository = FakeReaderRepository(),
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
            defaultBookId = defaultBookId,
            mainDispatcher = dispatcher,
        )
    }

    private class FakeReaderRepository : ReaderRepository {
        override fun observeProgress(bookId: String): Flow<ReadingProgress?> = MutableStateFlow(null)

        override suspend fun upsertProgress(progress: ReadingProgress) = Unit

        override suspend fun updateBookReadingState(
            bookId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) = Unit

        override suspend fun getProgressForBook(bookId: String): ReadingProgress? = null

        override fun observeAllHighlights(): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override fun observeHighlights(bookId: String): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override suspend fun upsertHighlight(highlight: Highlight) = Unit

        override suspend fun getHighlightsForBook(bookId: String): List<Highlight> = emptyList()

        override fun observeAllBookmarks(): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override suspend fun upsertBookmark(bookmark: Bookmark) = Unit

        override suspend fun getBookmarksForBook(bookId: String): List<Bookmark> = emptyList()

        override fun observeAllTags(): Flow<List<String>> = MutableStateFlow(emptyList())
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
