package com.nexo.presentation.viewmodel

import android.app.Application
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.reader.SleepTimerManager
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Slice 3 tests (SDD reader-facade-split, T4).
 *
 * The timer state lives in [SleepTimerManager] and is re-exported as
 * `sleepTimerUiState`. The chapter-forwarding glue
 * (`onChapterChanged = { sleepTimerManager.onChapterChanged() }`) is the
 * single retained glue line and is covered explicitly below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSleepTimerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `timer start lands on the slice flow`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            viewModel.sleepTimerManager.startTimer(SleepTimerManager.END_OF_CHAPTER)

            val slice = viewModel.sleepTimerUiState.value
            assertTrue(slice.isActive)
            assertTrue(slice.isEndOfChapter)
        }

    @Test
    fun `chapter change reaches the timer through the glue line`() =
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
            viewModel.sleepTimerManager.startTimer(SleepTimerManager.END_OF_CHAPTER)

            // Session-owned navigation; the glue forwards the chapter event.
            viewModel.lifecycleHolder.goToChapter(1)

            assertTrue(viewModel.sleepTimerUiState.value.isFinished)
        }

    @Test
    fun `chapter change without an active timer is a no-op`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.lifecycleHolder.setEpubStateForTest(
                chapters =
                    listOf(
                        BookChapter(0, "c1", "Ch 1", "ch1.xhtml"),
                        BookChapter(1, "c2", "Ch 2", "ch2.xhtml"),
                    ),
                currentChapterIndex = 0,
            )

            viewModel.lifecycleHolder.goToChapter(1)

            assertFalse(viewModel.sleepTimerUiState.value.isFinished)
        }

    @Test
    fun `timer pass-through delegates are deleted`() {
        val names = ReaderViewModel::class.java.methods.map { it.name }
        assertFalse(names.contains("startSleepTimer"))
        assertFalse(names.contains("cancelSleepTimer"))
        assertFalse(names.contains("dismissSleepTimerOverlay"))
        assertFalse(names.contains("formatSleepTimerRemaining"))
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
