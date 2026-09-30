package com.nexo.presentation.viewmodel

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Slice 5 tests (SDD reader-facade-split, T6; SDD reader-uiState-cleanup S7).
 *
 * The annotation surface lives in [ReaderViewModel.interactionHolder] and is
 * re-exported as [ReaderViewModel.annotationUiState]. These tests pin:
 *  - the slice re-export mirrors the holder-owned state;
 *  - holder updates reach the slice with no aggregate in the path (S7);
 *  - the highlights-ordering guarantee from T1 (design §5) survives the
 *    slice exposure;
 *  - the 30 annotation delegates are deleted (reflection guard against
 *    re-introduction).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelAnnotationTest {
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

    // ── Slice re-export (SDD reader-facade-split, T6) ───────────────

    @Test
    fun `annotationUiState re-exports the interactionHolder state`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            holder.testSetInitialHighlights(listOf(highlight("a1"), highlight("a2")))
            runCurrent()

            val slice = viewModel.annotationUiState.value
            assertEquals(2, slice.highlights.size)
            assertEquals(listOf("a1", "a2"), slice.highlights.map { it.id })
        }

    @Test
    fun `holder highlights reach annotationUiState with no aggregate in the path`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            holder.testSetInitialHighlights(listOf(highlight("b1")))
            advanceUntilIdle()

            // S7: the 5+1-way combine overlay is deleted — the slice is the only
            // path from the holder to readers.
            assertEquals(1, viewModel.annotationUiState.value.highlights.size)
            assertEquals(
                "b1",
                viewModel.annotationUiState.value.highlights
                    .first()
                    .id,
            )
        }

    // ── Highlights-ordering preservation (T1 regression, still green) ─

    @Test
    fun `annotationUiState carries the latest holder highlights`() =
        runTest {
            // The T1 regression pin (ReaderHighlightsOrderingTest) covers the
            // latest-wins timing on a Room-backed flow. This test is the
            // slice-exposure complement: a direct holder update
            // (testSetInitialHighlights) must reach the slice re-export
            // (annotationUiState). Same agreement guarantee, simpler fixture.
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            holder.testSetInitialHighlights(listOf(highlight("h1"), highlight("h2")))
            advanceUntilIdle()

            val sliceIds =
                viewModel.annotationUiState.value.highlights
                    .map { it.id }
            assertEquals(listOf("h1", "h2"), sliceIds)
        }

    @Test
    fun `annotationUiState reflects the latest holder highlights across rapid updates`() =
        runTest {
            // T1's latest-wins guarantee relies on the holder observation; this
            // test pins the same idea through the slice re-export by issuing two
            // holder updates in succession and asserting the second one wins in
            // annotationUiState.
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            holder.testSetInitialHighlights(listOf(highlight("h1")))
            advanceUntilIdle()
            holder.testSetInitialHighlights(listOf(highlight("h1"), highlight("h2")))
            advanceUntilIdle()

            val sliceIds =
                viewModel.annotationUiState.value.highlights
                    .map { it.id }
            assertEquals(listOf("h1", "h2"), sliceIds)
        }

    // ── Delegate deletion (S7) ────────────────────────────────────

    @Test
    fun `annotation delegates are deleted`() {
        // S7 deleted all 30 annotation delegates — writes go through
        // viewModel.interactionHolder directly. This reflection guard fails
        // if any delegate is re-introduced on the VM.
        val names =
            listOf(
                "onHighlightTapped",
                "onTextSelectionEvent",
                "onTextSelection",
                "onSelectHighlightColor",
                "onCopySelectedText",
                "onDismissContextMenu",
                "onReadiumSelection",
                "onSelectionCleared",
                "onShowColorPickerPopover",
                "onDismissColorPickerPopover",
                "onShowNoteModal",
                "onDismissNoteModal",
                "onSaveNote",
                "onAnnotate",
                "onShowTagInput",
                "onDismissTagInput",
                "onTagTextChanged",
                "onSaveTag",
                "onShowDefinitionInput",
                "onDismissDefinitionInput",
                "onDefinitionTextChanged",
                "onSaveDefinition",
                "onAddToDictionary",
                "onShareSelectedText",
                "onReadiumHighlightColorSelected",
                "onReadiumDeleteHighlight",
                "onReadiumUpdateHighlightColor",
                "onDebugForceMenu",
                "onDebugForceColorPicker",
                "onToggleHighlightsPanel",
            )
        val methods = ReaderViewModel::class.java.methods.associateBy { it.name }
        for (name in names) {
            assertNull("ReaderViewModel.$name must stay deleted (S7)", methods[name])
        }
    }

    // ── Selection-pipeline via the slice (design §5) ────────────────

    @Test
    fun `selectionState updates flow through annotationUiState`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            val holder = viewModel.interactionHolder
            val stateField = holder::class.java.getDeclaredField("_state")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(holder) as MutableStateFlow<com.nexo.presentation.viewmodel.reader.ReaderInteractionState>
            state.value =
                state.value.copy(
                    selectionState = ReaderSelectionState.New(android.graphics.Rect(0, 0, 100, 50), "text", null),
                    selectedText = "text",
                )
            runCurrent()

            assertTrue(
                "annotationUiState must mirror holder selectionState",
                viewModel.annotationUiState.value.selectionState is ReaderSelectionState.New,
            )
            assertEquals("text", viewModel.annotationUiState.value.selectedText)
        }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun highlight(id: String): Highlight =
        Highlight(
            id = id,
            bookId = "book-1",
            cfiRange = "epubcfi(/6/2!/4/$id)",
            textContent = "text $id",
            note = null,
            color = HighlightColor.YELLOW.hex,
            updatedAtEpochMillis = 0L,
            deletedAtEpochMillis = null,
            locatorJson = null,
        )

    private fun createViewModel(
        scheduler: TestCoroutineScheduler,
        highlightsFlow: MutableStateFlow<List<Highlight>>? = null,
        defaultBookId: String? = null,
    ): ReaderViewModel {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val fake = if (highlightsFlow != null) FakeReaderRepository(highlightsFlow) else FakeReaderRepository()
        return ReaderViewModel(
            application = mockk<Application>(relaxed = true),
            readerRepository = fake,
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(fake),
            defaultBookId = defaultBookId,
            mainDispatcher = dispatcher,
        )
    }
}
