package com.nexo.presentation.viewmodel

import android.app.Application
import android.util.Log
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import com.nexo.testutil.activeUserProviderFor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelFullscreenTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @org.junit.After
    fun tearDown() = unmockkAll()

    @Test
    fun `chrome toggle flips isFullscreen on the slice flow`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            assertFalse(viewModel.chromeUiState.value.isFullscreen)

            viewModel.fullscreenManager.onToggleFullscreen()
            assertTrue(viewModel.chromeUiState.value.isFullscreen)

            viewModel.fullscreenManager.onToggleFullscreen()
            assertFalse(viewModel.chromeUiState.value.isFullscreen)
        }

    @Test
    fun `chrome slice is the single source after aggregate deletion`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            viewModel.fullscreenManager.onToggleFullscreen()

            assertTrue(viewModel.chromeUiState.value.isFullscreen)
        }

    @Test
    fun `chrome pass-through delegate is deleted`() {
        val names = ReaderViewModel::class.java.methods.map { it.name }
        assertFalse(names.contains("onToggleFullscreen"))
    }

    @Test
    fun `fullscreen state persists across multiple toggles within session`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            // Toggle on
            viewModel.fullscreenManager.onToggleFullscreen()
            assertTrue(viewModel.chromeUiState.value.isFullscreen)

            // Toggle off
            viewModel.fullscreenManager.onToggleFullscreen()
            assertFalse(viewModel.chromeUiState.value.isFullscreen)

            // Toggle on again
            viewModel.fullscreenManager.onToggleFullscreen()
            assertTrue(viewModel.chromeUiState.value.isFullscreen)

            // Toggle off again
            viewModel.fullscreenManager.onToggleFullscreen()
            assertFalse(viewModel.chromeUiState.value.isFullscreen)
        }

    @Test
    fun `fullscreen enters immersive on new book load`() =
        runTest {
            mockkStatic(Log::class)
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
            every { Log.d(any(), any()) } returns 0
            every { Log.d(any(), any(), any()) } returns 0

            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    activeUser = activeUserProviderFor(),
                    mainDispatcher = dispatcher,
                )

            // Load a new book (simulate)
            viewModel.loadBook("new-book", "/path/book.epub", "epub")

            // Reader should auto-enter immersive (fullscreen) reading mode.
            assertTrue(viewModel.chromeUiState.value.isFullscreen)
        }

    @Test
    fun `default state is not fullscreen`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            assertFalse(viewModel.chromeUiState.value.isFullscreen)
        }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun createViewModel(scheduler: TestCoroutineScheduler): ReaderViewModel {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        return ReaderViewModel(
            application = mockk<Application>(relaxed = true),
            readerRepository = FakeReaderRepository(),
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
            defaultBookId = null,
            activeUser = activeUserProviderFor(),
            mainDispatcher = dispatcher,
        )
    }
}
