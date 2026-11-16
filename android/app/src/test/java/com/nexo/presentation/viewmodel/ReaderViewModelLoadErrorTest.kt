package com.nexo.presentation.viewmodel

import android.app.Application
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import com.nexo.testutil.activeUserProviderFor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Pins the reader's load-failure message as observable ViewModel behaviour.
 *
 * `loadBookWithFallback` falls back to `lifecycleHolder.reportLoadError` when it
 * cannot resolve a path. The message it reports is user-facing text, so it is
 * driven by a string resource. These tests assert the text the reader surfaces,
 * not how the ViewModel obtains it, so the resource resolution can move to the
 * construction site without the behaviour changing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelLoadErrorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loadBookWithFallback with blank book id and no path surfaces book not found`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()

            // Blank id + blank path skips every load attempt, so the reader must
            // report the load error instead of leaving the spinner running.
            viewModel.loadBookWithFallback(bookId = "", filePath = null, format = "epub")
            advanceUntilIdle()

            val state = viewModel.lifecycleHolder.state.value
            assertFalse("a failed load must not leave the spinner running", state.isLoading)
            assertEquals("Book not found", state.error)
        }

    @Test
    fun `loadBookWithFallback with unresolvable book id surfaces book not found`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()

            // Blank file path forces the library re-resolution; the library has
            // no such book, so the reader must report the same load error.
            viewModel.loadBookWithFallback(bookId = "missing-book", filePath = null, format = "epub")
            advanceUntilIdle()

            val state = viewModel.lifecycleHolder.state.value
            assertFalse("a failed load must not leave the spinner running", state.isLoading)
            assertEquals("Book not found", state.error)
        }

    private fun createViewModel(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): ReaderViewModel {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val fake = FakeReaderRepository()
        val libraryRepository = mockk<LibraryRepository>()
        coEvery { libraryRepository.getBookById(any(), any()) } returns null
        return ReaderViewModel(
            application = mockk<Application>(relaxed = true),
            readerRepository = fake,
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(fake),
            defaultBookId = null,
            libraryRepository = libraryRepository,
            activeUser = activeUserProviderFor(),
            mainDispatcher = dispatcher,
            bookNotFoundMessage = "Book not found",
        )
    }
}
