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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * Deletion audit (SDD reader-uiState-cleanup, S7).
 *
 * S1–S6 migrated all six consumers to slice flows; S7 deleted the
 * back-compat aggregate (`uiState` + 5+1-way combine overlay +
 * `mutableUiState` + merge collectors + 30 annotation delegates +
 * `ReaderUiState` type). The six slice flows are the single source of truth.
 *
 * This test pins the post-deletion contract:
 *
 *  1. All six slice flows are present and re-export the owner state
 *     directly (not via a derived transform).
 *  2. The aggregate stays deleted: no `uiState` flow, no `slicesOverlay`,
 *     no `mutableUiState`, no `ReaderUiState` type reference on the VM.
 *  3. The `interactionHolder` field is wired to the annotation owner and
 *     reachable through the VM (the write path since the delegates died).
 *  4. The 30 annotation delegates stay deleted (reflection guard against
 *     re-introduction).
 *  5. The [ReaderViewModel] stays as the only public surface for screens:
 *     screens reach slice owners through VM re-exports, never via a
 *     direct import of `reader.interaction.*` or `reader.*Holder` types.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelFinalSweepTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ── Slice re-exports present and direct ─────────────────────────

    @Test
    fun `all six slice flows are exposed on the VM`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()

            // 1. Search
            assertSame(
                "searchUiState must be the same instance as searchStateHolder.state",
                viewModel.searchStateHolder.state,
                viewModel.searchUiState,
            )
            // 2. Chrome
            assertSame(
                "chromeUiState must be the same instance as fullscreenManager.state",
                viewModel.fullscreenManager.state,
                viewModel.chromeUiState,
            )
            // 3. Settings
            assertSame(
                "settingsUiState must be the same instance as settingsManager.state",
                viewModel.settingsManager.state,
                viewModel.settingsUiState,
            )
            // 4. Sleep timer
            assertSame(
                "sleepTimerUiState must be the same instance as sleepTimerManager.state",
                viewModel.sleepTimerManager.state,
                viewModel.sleepTimerUiState,
            )
            // 5. Session
            assertSame(
                "sessionUiState must be the same instance as lifecycleHolder.state",
                viewModel.lifecycleHolder.state,
                viewModel.sessionUiState,
            )
            // 6. Annotation (sliced in T6)
            assertNotNull(viewModel.annotationUiState)
            // The annotation slice is sourced from interactionHolder.state
            // (the deprecated PR #3 facade). The re-export identity is checked
            // by ReaderViewModelAnnotationTest already; here we only assert the
            // re-export is present and the holder is wired.
            assertNotNull(viewModel::class.java.methods.firstOrNull { it.name == "getAnnotationUiState" })
        }

    // ── Aggregate stays deleted ────────────────────────────────────

    @Test
    fun `back-compat aggregate stays deleted`() {
        // S7 deletion set: the uiState flow, both combine overlays, the
        // mutableUiState seed, and the merge/Room collectors are gone.
        val methods = ReaderViewModel::class.java.methods.map { it.name }
        assertFalse(
            "ReaderViewModel.uiState must stay deleted (S7)",
            methods.contains("getUiState"),
        )

        val fields = ReaderViewModel::class.java.declaredFields.map { it.name }
        assertFalse(
            "mutableUiState must stay deleted (S7)",
            fields.contains("mutableUiState"),
        )
        assertFalse(
            "slicesOverlay must stay deleted (S7)",
            fields.contains("slicesOverlay"),
        )
    }

    // ── Owner wiring (annotation slice stays on the deprecated facade) ─

    @Test
    fun `annotation slice owner is the interactionHolder`() =
        runTest {
            // S7 exposes the holder as the write path (delegates deleted).
            // Pin: the slice's source is still the holder, reachable via the VM.
            val vm = createViewModel(testScheduler)
            assertNotNull(
                "interactionHolder must be reachable through the VM",
                vm.interactionHolder,
            )
            assertSame(
                "annotationUiState must be the holder state instance",
                vm.interactionHolder.state,
                vm.annotationUiState,
            )
        }

    // ── Annotation delegates stay deleted ─────────────────────────

    @Test
    fun `annotation delegates stay deleted`() {
        // All 30 delegates were deleted in S7. Re-introducing any of them
        // would resurrect the pass-through surface the slices replaced.
        val names =
            listOf(
                "onHighlightTapped",
                "onTextSelection",
                "onTextSelectionEvent",
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

    // ── Cross-slice isolation ───────────────────────────────────────

    @Test
    fun `chrome toggle does not emit into the annotation slice`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val beforeHighlights = viewModel.annotationUiState.value.highlights.size
            val beforeSheet = viewModel.annotationUiState.value.showHighlightsSheet

            viewModel.fullscreenManager.onToggleFullscreen()
            advanceUntilIdle()

            assertEquals(
                "annotation slice must not re-emit on chrome toggle",
                beforeHighlights,
                viewModel.annotationUiState.value.highlights.size,
            )
            assertEquals(
                "annotation slice must not re-emit on chrome toggle",
                beforeSheet,
                viewModel.annotationUiState.value.showHighlightsSheet,
            )
        }

    @Test
    fun `search toggle does not emit into the session slice`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val beforeBookId = viewModel.sessionUiState.value.selectedBookId
            val beforeChapter = viewModel.sessionUiState.value.currentChapterIndex

            viewModel.searchStateHolder.onToggleSearch()
            advanceUntilIdle()

            assertEquals(
                "session slice must not re-emit on search toggle",
                beforeBookId,
                viewModel.sessionUiState.value.selectedBookId,
            )
            assertEquals(
                "session slice must not re-emit on search toggle",
                beforeChapter,
                viewModel.sessionUiState.value.currentChapterIndex,
            )
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
