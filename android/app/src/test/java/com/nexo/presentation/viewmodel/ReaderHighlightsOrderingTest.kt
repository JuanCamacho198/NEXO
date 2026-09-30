package com.nexo.presentation.viewmodel

import android.app.Application
import com.nexo.domain.model.Highlight
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Latest-wins pin for highlight observation (SDD reader-facade-split, design s5;
 * SDD reader-uiState-cleanup S7).
 *
 * The VM observes highlights through exactly one site — the annotation owner's
 * `observeBook` (Room `observeHighlights(bookId)` collected into the
 * interaction holder, re-exported as `annotationUiState`) — giving latest-wins
 * timing. These tests pin that guarantee against the slice, with no
 * back-compat aggregate in the path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderHighlightsOrderingTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `rapid consecutive highlight updates resolve latest-wins`() =
        runTest {
            val highlightsFlow = MutableStateFlow<List<Highlight>>(emptyList())
            val viewModel = createViewModel(testScheduler, highlightsFlow)
            // S7: the holder observes Room directly (wired via onBookLoaded in
            // production); drive the surviving mechanism.
            viewModel.interactionHolder.observeBook("book-1")
            runCurrent()

            highlightsFlow.value = listOf(highlight("h1"))
            runCurrent()
            highlightsFlow.value = listOf(highlight("h1"), highlight("h2"))
            runCurrent()

            assertEquals(
                listOf("h1", "h2"),
                viewModel.annotationUiState.value.highlights
                    .map { it.id },
            )
        }

    @Test
    fun `highlight emissions never duplicate through the holder observation`() =
        runTest {
            val highlightsFlow = MutableStateFlow<List<Highlight>>(emptyList())
            val viewModel = createViewModel(testScheduler, highlightsFlow)
            viewModel.interactionHolder.observeBook("book-1")
            runCurrent()

            highlightsFlow.value = listOf(highlight("h1"), highlight("h2"))
            runCurrent()
            runCurrent()

            // Single source: the annotation slice must equal exactly the latest
            // Room emission — no merge paths, no duplication.
            val visible = viewModel.annotationUiState.value.highlights
            assertEquals(listOf("h1", "h2"), visible.map { it.id })
        }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun highlight(id: String): Highlight =
        Highlight(
            id = id,
            bookId = "book-1",
            cfiRange = "cfi-$id",
            textContent = "text $id",
            note = null,
            color = "#FFEB3B",
            updatedAtEpochMillis = 0L,
            deletedAtEpochMillis = null,
        )

    private fun createViewModel(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        highlightsFlow: MutableStateFlow<List<Highlight>>,
    ): ReaderViewModel {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val fake = FakeReaderRepository(highlightsFlow)
        return ReaderViewModel(
            application = mockk<Application>(relaxed = true),
            readerRepository = fake,
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(fake),
            defaultBookId = "book-1",
            mainDispatcher = dispatcher,
        )
    }
}
