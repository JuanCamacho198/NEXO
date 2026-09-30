package com.nexo.presentation.viewmodel.reader

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.UiEvent
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import kotlin.coroutines.ContinuationInterceptor

/**
 * Tests for [ReaderInteractionStateHolder] covering the key interaction
 * scenarios: selection lifecycle, highlight CRUD, annotations, panels,
 * and modals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderInteractionStateHolderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var readerRepository: ReaderRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var holder: ReaderInteractionStateHolder
    private lateinit var highlightsFlow: MutableStateFlow<List<Highlight>>

    private var capturedEvents = mutableListOf<UiEvent>()
    private val sampleRectF = RectF(100f, 200f, 400f, 280f)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.println(any<Int>(), any<String>(), any<String>()) } returns 0

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        highlightsFlow = MutableStateFlow(emptyList())
        readerRepository = mockk(relaxed = true)
        every { readerRepository.observeHighlights(any()) } returns highlightsFlow
        dictionaryRepository = mockk(relaxed = true)
        capturedEvents.clear()
    }

    @After
    fun tearDown() = unmockkAll()

    // ── Selection ─────────────────────────────────────────────────

    @Test
    fun `onReadiumSelection sets New selection state`() =
        runTest {
            holder = createHolder(scope = this)
            val locator = mockk<Locator>(relaxed = true)

            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "selected text from readium",
                existingHighlights = emptyList(),
            )

            val state = holder.state.value
            assertTrue(
                "selectionState should be New",
                state.selectionState is ReaderSelectionState.New,
            )
            assertEquals("selectedText should match", "selected text from readium", state.selectedText)
            val expectedRect = Rect(100, 200, 400, 280)
            assertEquals("selectionRect.left", expectedRect.left, state.selectionRect?.left)
            assertEquals("selectionRect.top", expectedRect.top, state.selectionRect?.top)
            assertEquals("selectionRect.right", expectedRect.right, state.selectionRect?.right)
            assertEquals("selectionRect.bottom", expectedRect.bottom, state.selectionRect?.bottom)
        }

    @Test
    fun `onHighlightTapped sets Existing selection state`() =
        runTest {
            holder = createHolder(scope = this)
            val highlight = createSampleHighlight(id = "h1")

            holder.onHighlightTapped(highlight, sampleRectF)

            val state = holder.state.value
            assertTrue(
                "selectionState should be Existing",
                state.selectionState is ReaderSelectionState.Existing,
            )
            assertEquals("selectedText should match highlight", "highlighted text", state.selectedText)
        }

    @Test
    fun `onSelectionCleared clears selection state`() =
        runTest {
            holder = createHolder(scope = this)
            // First create a selection
            val locator = mockk<Locator>(relaxed = true)
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "some text",
                existingHighlights = emptyList(),
            )
            assertTrue(
                "Should have New selection initially",
                holder.state.value.selectionState is ReaderSelectionState.New,
            )

            // When: clear selection
            holder.onSelectionCleared(
                currentActiveHighlightId = null,
                currentHighlightTapDebounceUntil = 0L,
            )

            // Then: state is reset
            val state = holder.state.value
            assertEquals(
                "selectionState should be None",
                ReaderSelectionState.None,
                state.selectionState,
            )
            assertNull("selectedText cleared", state.selectedText)
            assertNull("selectionRect cleared", state.selectionRect)
            assertFalse("showColorPickerPopover cleared", state.showColorPickerPopover)
        }

    // ── Context Menu Dismiss ──────────────────────────────────────

    @Test
    fun `onDismissContextMenu clears all modal state`() =
        runTest {
            holder = createHolder(scope = this)
            // Set up a highlight first so modals are accessible
            val highlight = createSampleHighlight(id = "h1")
            holder.onHighlightTapped(highlight, sampleRectF)

            // Open note modal and verify
            holder.onShowNoteModal()
            assertTrue("note modal should be on", holder.state.value.showNoteModal)
            assertTrue("activeNoteText should be ''", holder.state.value.activeNoteText == "")

            // When: dismiss context menu
            holder.onDismissContextMenu()

            // Then: note modal cleared
            var state = holder.state.value
            assertEquals("selectionState None", ReaderSelectionState.None, state.selectionState)
            assertNull("selectedText cleared", state.selectedText)
            assertNull("selectionRect cleared", state.selectionRect)
            assertFalse("showNoteModal cleared", state.showNoteModal)
            assertEquals("activeNoteText cleared", "", state.activeNoteText)

            // Re-setup and check color picker
            holder.onHighlightTapped(highlight, sampleRectF)
            holder.onShowColorPickerPopover()
            assertTrue("color picker should be on", holder.state.value.showColorPickerPopover)

            holder.onDismissContextMenu()
            state = holder.state.value
            assertFalse("showColorPickerPopover cleared", state.showColorPickerPopover)

            // Re-setup and check tag input
            holder.onHighlightTapped(highlight, sampleRectF)
            holder.onShowTagInput()
            assertTrue("tag input should be on", holder.state.value.showTagInput)

            holder.onDismissContextMenu()
            state = holder.state.value
            assertFalse("showTagInput cleared", state.showTagInput)

            // Re-setup and check definition input
            holder.onHighlightTapped(highlight, sampleRectF)
            holder.onShowDefinitionInput()
            assertTrue("definition input should be on", holder.state.value.showDefinitionInput)

            holder.onDismissContextMenu()
            state = holder.state.value
            assertFalse("showDefinitionInput cleared", state.showDefinitionInput)
        }

    // ── Panels ────────────────────────────────────────────────────

    @Test
    fun `onToggleHighlightsPanel flips panel boolean`() =
        runTest {
            holder = createHolder(scope = this)

            assertFalse("default should be off", holder.state.value.showHighlightsSheet)

            holder.onToggleHighlightsPanel()
            assertTrue("should be on after toggle", holder.state.value.showHighlightsSheet)

            holder.onToggleHighlightsPanel()
            assertFalse("should be off after second toggle", holder.state.value.showHighlightsSheet)
        }

    // ── Color Picker ──────────────────────────────────────────────

    @Test
    fun `onShowColorPickerPopover opens popover`() =
        runTest {
            holder = createHolder(scope = this)

            assertFalse("default should be off", holder.state.value.showColorPickerPopover)

            holder.onShowColorPickerPopover()
            assertTrue("should be on after open", holder.state.value.showColorPickerPopover)

            holder.onDismissColorPickerPopover()
            assertFalse("should be off after dismiss", holder.state.value.showColorPickerPopover)
        }

    // ── Note Modal ────────────────────────────────────────────────

    @Test
    fun `onSaveNote persists via repository`() =
        runTest {
            holder = createHolder(scope = this)
            // Set up: need an Existing highlight in the coordinator so onSaveNote works
            val highlight = createSampleHighlight(id = "h1")
            setupHighlightInState(highlight)
            holder.onHighlightTapped(highlight, sampleRectF)
            holder.onShowNoteModal()
            assertTrue("note modal should be open", holder.state.value.showNoteModal)

            // When: save note
            holder.onSaveNote("my note text")
            advanceUntilIdle()

            // Then: repository was called to upsert the updated highlight
            val slot = slot<Highlight>()
            coVerify { readerRepository.upsertHighlight(capture(slot)) }

            val captured = slot.captured
            assertEquals("should be the same highlight", "h1", captured.id)
            assertEquals("note should be updated", "my note text", captured.note)

            // And: modal state is cleared
            assertFalse("note modal dismissed", holder.state.value.showNoteModal)
            assertEquals("activeNoteText cleared", "", holder.state.value.activeNoteText)
        }

    // ── Tag Input ─────────────────────────────────────────────────

    @Test
    fun `onSaveTag persists via repository`() =
        runTest {
            holder = createHolder(scope = this)
            val highlight = createSampleHighlight(id = "h1")
            setupHighlightInState(highlight)
            holder.onHighlightTapped(highlight, sampleRectF)
            holder.onShowTagInput()
            assertTrue("tag input should be open", holder.state.value.showTagInput)

            // When: save tag
            holder.onSaveTag("ficción")
            advanceUntilIdle()

            // Then: repository was called
            val slot = slot<Highlight>()
            coVerify { readerRepository.upsertHighlight(capture(slot)) }
            assertEquals("tag should be saved", "ficción", slot.captured.tag)

            // And: tag input dismissed
            assertFalse("tag input dismissed", holder.state.value.showTagInput)
        }

    @Test
    fun `onTagTextChanged updates tag text`() =
        runTest {
            holder = createHolder(scope = this)

            holder.onTagTextChanged("test tag")

            assertEquals("activeTagText should update", "test tag", holder.state.value.activeTagText)
        }

    // ── Definition Input ──────────────────────────────────────────

    @Test
    fun `onAddToDictionary saves selected word directly without opening input`() =
        runTest {
            val events = mutableListOf<UiEvent>()
            holder =
                ReaderInteractionStateHolder(
                    readerRepository = readerRepository,
                    dictionaryRepository = dictionaryRepository,
                    scope = this,
                    onEvent = { events.add(it) },
                )
            // Set up a selection so onAddToDictionary has text to save
            val locator = mockk<Locator>(relaxed = true)
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "vocabulario",
                existingHighlights = emptyList(),
            )
            // dictionaryRepository is a relaxed mock — `exists` returns false
            // and `save` returns Result.success(null) by default.

            // When: user taps Dictionary button
            holder.onAddToDictionary()
            testScheduler.advanceUntilIdle()

            // Then: dictionary input did NOT open
            assertFalse("definition input should NOT open", holder.state.value.showDefinitionInput)
            // And: selection was dismissed (selectedText cleared)
            assertNull("selection should be cleared", holder.state.value.selectedText)
            assertTrue(
                "selectionState should be None",
                holder.state.value.selectionState is ReaderSelectionState.None,
            )
            // And: a snackbar was emitted
            assertTrue(
                "snackbar should be emitted",
                events.any { it is com.nexo.presentation.UiEvent.ShowSnackbar },
            )
        }

    @Test
    fun `onDefinitionTextChanged updates definition text`() =
        runTest {
            holder = createHolder(scope = this)

            holder.onDefinitionTextChanged("my definition")

            assertEquals(
                "activeDefinitionText should update",
                "my definition",
                holder.state.value.activeDefinitionText,
            )
        }

    // ── Share ─────────────────────────────────────────────────────

    @Test
    fun `onShareSelectedText emits share event`() =
        runTest {
            val events = mutableListOf<UiEvent>()
            holder =
                ReaderInteractionStateHolder(
                    readerRepository = readerRepository,
                    dictionaryRepository = dictionaryRepository,
                    scope = this,
                    onEvent = { events.add(it) },
                )
            // Set up a selection first so there's text to share
            val locator = mockk<Locator>(relaxed = true)
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "shareable text",
                existingHighlights = emptyList(),
            )

            // When: share
            holder.onShareSelectedText(holder.state.value.selectedText)

            // Then: share event was emitted
            assertTrue("should have emitted ShareText", events.any { it is UiEvent.ShareText })
        }

    @Test
    fun `onCopySelectedText emits snackbar event`() =
        runTest {
            val events = mutableListOf<UiEvent>()
            holder =
                ReaderInteractionStateHolder(
                    readerRepository = readerRepository,
                    dictionaryRepository = dictionaryRepository,
                    scope = this,
                    onEvent = { events.add(it) },
                )
            // Set up a selection
            val locator = mockk<Locator>(relaxed = true)
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "copiable text",
                existingHighlights = emptyList(),
            )

            holder.onCopySelectedText()

            assertTrue(
                "should have emitted ShowSnackbar",
                events.any { it is UiEvent.ShowSnackbar },
            )
        }

    // ── Bookmarks ─────────────────────────────────────────────────

    @Test
    fun `createBookmark persists via repository`() =
        runTest {
            holder = createHolder(scope = this)

            holder.createBookmark(
                bookId = "test-book",
                cfiLocation = "epubcfi(/6/4)",
                titleOrSnippet = "Chapter 4",
            )
            advanceUntilIdle()

            val slot = slot<com.nexo.domain.model.Bookmark>()
            coVerify { readerRepository.upsertBookmark(capture(slot)) }

            assertEquals("test-book", slot.captured.bookId)
            assertEquals("epubcfi(/6/4)", slot.captured.cfiLocation)
        }

    // ── Debug ─────────────────────────────────────────────────────

    @Test
    fun `onDebugForceMenu toggles debug force menu`() =
        runTest {
            holder = createHolder(scope = this)

            assertFalse("debugForceMenu should start false", holder.state.value.debugForceMenu)

            holder.onDebugForceMenu()
            assertTrue("debugForceMenu should be true", holder.state.value.debugForceMenu)
            assertTrue(
                "selectionState should be Existing for debug",
                holder.state.value.selectionState is ReaderSelectionState.Existing,
            )

            // Second call toggles off
            holder.onDebugForceMenu()
            assertEquals(
                "selectionState should be None after toggle-off",
                ReaderSelectionState.None,
                holder.state.value.selectionState,
            )
            assertFalse(
                "debugForceMenu should be false after toggle-off",
                holder.state.value.debugForceMenu,
            )
        }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Sets up a highlight in the holder's state so CRUD methods that look up
     * _state.value.highlights.find { it.id == activeId } can find it.
     */
    private fun setupHighlightInState(highlight: Highlight) {
        holder.testSetInitialHighlights(listOf(highlight))
    }

    private fun createHolder(scope: CoroutineScope): ReaderInteractionStateHolder {
        // Extract the scope's dispatcher so launched coroutines share the same
        // TestCoroutineScheduler as the runTest scope. Without this, coroutines
        // launched on Dispatchers.Main (UnconfinedTestDispatcher with its OWN
        // scheduler) would never execute when we call advanceUntilIdle().
        val testDispatcher =
            scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
                ?: Dispatchers.Main
        return ReaderInteractionStateHolder(
            readerRepository = readerRepository,
            dictionaryRepository = dictionaryRepository,
            scope = scope,
            onEvent = { capturedEvents.add(it) },
            mainDispatcher = testDispatcher,
        )
    }

    private fun createSampleHighlight(id: String): Highlight =
        Highlight(
            id = id,
            bookId = "test-book",
            cfiRange = "epubcfi(/6/4)",
            textContent = "highlighted text",
            note = null,
            color = HighlightColor.YELLOW.hex,
            updatedAtEpochMillis = System.currentTimeMillis(),
            deletedAtEpochMillis = null,
            locatorJson = null,
        )
}
