package com.nexo.presentation.viewmodel.reader

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Tests for [SelectionCoordinator] state machine transitions,
 * exercised through [ReaderInteractionStateHolder]'s public API.
 *
 * Timing-sensitive transitions use mocked [SystemClock] so debounce
 * and ignore windows can be controlled deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionCoordinatorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var readerRepository: ReaderRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var holder: ReaderInteractionStateHolder

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

        readerRepository = mockk(relaxed = true)
        dictionaryRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `Idle transitions to NewSelection on new text selection`() =
        runTest {
            holder = createHolder(scope = this)
            val locator = mockk<Locator>(relaxed = true)

            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "selected text",
                existingHighlights = emptyList(),
            )

            val state = holder.state.value
            assertTrue("selectionState should be New", state.selectionState is ReaderSelectionState.New)
            assertEquals("selectedText should be set", "selected text", state.selectedText)
        }

    @Test
    fun `Idle transitions to ExistingHighlight on highlight tap`() =
        runTest {
            holder = createHolder(scope = this)
            val highlight = createSampleHighlight(id = "h1")

            holder.onHighlightTapped(highlight, sampleRectF)

            val state = holder.state.value
            assertTrue("selectionState should be Existing", state.selectionState is ReaderSelectionState.Existing)
            assertEquals("selectedText should match highlight text", "highlighted text", state.selectedText)
        }

    @Test
    fun `ExistingHighlight ignores onReadiumSelection within debounce period when text matches`() =
        runTest {
            // Given: current time is 1000L
            every { SystemClock.elapsedRealtime() } returns 1000L
            holder = createHolder(scope = this)
            val highlight = createSampleHighlight(id = "h1")
            holder.onHighlightTapped(highlight, sampleRectF)
            // Now coordinator is ExistingHighlight with debounceUntil = 1000L + 2000 = 3000L

            // When: onReadiumSelection is called with text that MATCHES the
            // tapped highlight, before the debounce expires.
            every { SystemClock.elapsedRealtime() } returns 1500L // Still before 3000L
            val locator = mockk<Locator>(relaxed = true)
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "highlighted text", // matches highlight.textContent
                existingHighlights = listOf(highlight),
            )

            // Then: selection state should remain Existing (not overwritten by New)
            val state = holder.state.value
            assertTrue(
                "selectionState should remain Existing during debounce (matching text)",
                state.selectionState is ReaderSelectionState.Existing,
            )
        }

    @Test
    fun `ExistingHighlight overrides debounce when selection text does not match highlight`() =
        runTest {
            // Regression test: a NEW text selection (long-press + drag) that
            // overlaps an existing highlight must NOT be stuck on the
            // FloatingContextMenu (Existing). The polling loop must be allowed
            // to transition the state to New when the selected text clearly
            // doesn't match the highlight's stored text.
            every { SystemClock.elapsedRealtime() } returns 1000L
            holder = createHolder(scope = this)
            val highlight = createSampleHighlight(id = "h1")
            holder.onHighlightTapped(highlight, sampleRectF)
            // coordinator = ExistingHighlight(debounceUntil = 1000 + 2000 = 3000L)

            // When: onReadiumSelection fires within the debounce window with
            // a selection text that does NOT match the highlight.
            every { SystemClock.elapsedRealtime() } returns 1500L
            val locator = mockk<Locator>(relaxed = true)
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "entirely different fresh selection",
                existingHighlights = listOf(highlight),
            )

            // Then: selection transitions to New, not Existing.
            val state = holder.state.value
            assertTrue(
                "selectionState should be New (text doesn't match highlight — debounce overridden)",
                state.selectionState is ReaderSelectionState.New,
            )
        }

    @Test
    fun `NewSelection transitions to cleared on dismiss menu`() =
        runTest {
            holder = createHolder(scope = this)
            val locator = mockk<Locator>(relaxed = true)

            // Start with a New selection
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "selected text",
                existingHighlights = emptyList(),
            )
            assertTrue("Should start as New", holder.state.value.selectionState is ReaderSelectionState.New)

            // When: dismiss the context menu
            holder.onDismissContextMenu()

            // Then: selection state is cleared
            val state = holder.state.value
            assertEquals("selectionState should be None", ReaderSelectionState.None, state.selectionState)
            assertNull("selectedText cleared", state.selectedText)
            assertNull("selectionRect cleared", state.selectionRect)
            assertFalse("showColorPickerPopover cleared", state.showColorPickerPopover)
            assertFalse("showNoteModal cleared", state.showNoteModal)
        }

    @Test
    fun `MenuClosed ignores onReadiumSelection within ignore window`() =
        runTest {
            // Given: current time is 1000L
            every { SystemClock.elapsedRealtime() } returns 1000L
            holder = createHolder(scope = this)
            val locator = mockk<Locator>(relaxed = true)

            // Start with a New selection
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "selected text",
                existingHighlights = emptyList(),
            )

            // Dismiss creates MenuClosed(1000L)
            holder.onDismissContextMenu()

            // When: onReadiumSelection fires shortly after (ignore window = 1500ms)
            every { SystemClock.elapsedRealtime() } returns 1100L // Only 100ms after close
            val locator2 = mockk<Locator>(relaxed = true)
            holder.onReadiumSelection(
                locator = locator2,
                rect = sampleRectF,
                text = "new text after close",
                existingHighlights = emptyList(),
            )

            // Then: selection state should remain None (ignored)
            val state = holder.state.value
            assertEquals(
                "selectionState should stay None inside ignore window",
                ReaderSelectionState.None,
                state.selectionState,
            )
        }

    @Test
    fun `ExistingHighlight allows selection after debounce expires`() =
        runTest {
            // Given: initial time is 1000L
            every { SystemClock.elapsedRealtime() } returns 1000L
            holder = createHolder(scope = this)
            val highlight = createSampleHighlight(id = "h1")
            holder.onHighlightTapped(highlight, sampleRectF)
            // coordinator = ExistingHighlight(debounceUntil = 1000 + 2000 = 3000L)

            // When: onReadiumSelection fires after debounce has expired
            every { SystemClock.elapsedRealtime() } returns 5000L // Past debounce
            val locator = mockk<Locator>(relaxed = true)
            holder.onReadiumSelection(
                locator = locator,
                rect = sampleRectF,
                text = "fresh selection after debounce",
                existingHighlights = emptyList(),
            )

            // Then: selection should transition to NewSelection
            val state = holder.state.value
            assertTrue(
                "selectionState should transition to New after debounce expires",
                state.selectionState is ReaderSelectionState.New,
            )
            assertEquals("new text should be set", "fresh selection after debounce", state.selectedText)
        }

    // ── Helpers ──────────────────────────────────────────────────

    private fun createHolder(scope: CoroutineScope): ReaderInteractionStateHolder =
        ReaderInteractionStateHolder(
            readerRepository = readerRepository,
            dictionaryRepository = dictionaryRepository,
            scope = scope,
            onEvent = {},
        )

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
