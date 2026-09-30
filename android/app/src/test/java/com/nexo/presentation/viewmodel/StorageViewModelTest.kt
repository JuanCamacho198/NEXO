package com.nexo.presentation.viewmodel

import com.nexo.domain.model.Book
import com.nexo.domain.model.BookStorageItem
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.StorageRepository
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Slice 7 (WS6) — Storage screen state machine.
 *
 * Proves: cache + books load together; "clear cache" clears only cache stores
 * and never books (ST2); per-book delete delegates to the shared book delete
 * flow (ST3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val storageRepository = mockk<StorageRepository>()
    private val cacheRepository = mockk<CacheRepository>(relaxed = true)
    private val libraryRepository = mockk<LibraryRepository>(relaxed = true)

    private val books =
        listOf(
            BookStorageItem(bookId = "b1", title = "Book One", sizeBytes = 1_000L),
            BookStorageItem(bookId = "b2", title = "Book Two", sizeBytes = 2_000L),
        )

    private fun stubMeasurements() {
        coEvery { storageRepository.bookStorageUsage() } returns books
        coEvery { cacheRepository.discoverCacheSizeBytes() } returns 100L
        coEvery { cacheRepository.imageCacheSizeBytes() } returns 200L
        coEvery { cacheRepository.readerCacheSizeBytes() } returns 300L
    }

    private fun viewModel(dispatcher: CoroutineDispatcher) =
        StorageViewModel(
            storageRepository = storageRepository,
            cacheRepository = cacheRepository,
            libraryRepository = libraryRepository,
            mainDispatcher = dispatcher,
        )

    @Test
    fun init_loadsCacheAndBooksTogether() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            stubMeasurements()

            val vm = viewModel(dispatcher)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertEquals(600L, state.cache.totalBytes)
            assertEquals(3_000L, state.booksTotalBytes)
            assertEquals(3_600L, state.totalBytes)
            assertEquals(books, state.books)
        }

    @Test
    fun clearCache_clearsOnlyCache_neverBooks() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            stubMeasurements()
            val vm = viewModel(dispatcher)
            advanceUntilIdle()

            vm.clearCache()
            advanceUntilIdle()

            coVerify(exactly = 1) { cacheRepository.clearDiscoverCache() }
            coVerify(exactly = 1) { cacheRepository.clearImageCache() }
            coVerify(exactly = 1) { cacheRepository.clearReaderCache() }
            // Clear-cache must never remove a book row.
            coVerify(exactly = 0) { libraryRepository.deleteBook(any()) }
            coVerify(exactly = 0) { libraryRepository.deleteBookLocalOnly(any()) }
            assertEquals(books, vm.uiState.value.books)
            assertFalse(vm.uiState.value.isClearingCache)
        }

    @Test
    fun requestDeleteBook_stagesTheBookForTheDialog() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            stubMeasurements()
            coEvery { libraryRepository.getBookById("b1") } returns book("b1", "Book One")
            val vm = viewModel(dispatcher)
            advanceUntilIdle()

            vm.requestDeleteBook("b1")
            advanceUntilIdle()

            assertEquals(
                "b1",
                vm.uiState.value.bookToDelete
                    ?.id,
            )
            coVerify(exactly = 0) { libraryRepository.deleteBook(any()) }
        }

    @Test
    fun confirmDeleteLocalAndDrive_delegatesToDeleteFlow() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            stubMeasurements()
            coEvery { libraryRepository.getBookById("b1") } returns book("b1", "Book One")
            coEvery { libraryRepository.deleteBook("b1") } returns Result.success(Unit)
            val vm = viewModel(dispatcher)
            advanceUntilIdle()

            vm.requestDeleteBook("b1")
            advanceUntilIdle()
            vm.confirmDeleteLocalAndDrive()
            advanceUntilIdle()

            coVerify(exactly = 1) { libraryRepository.deleteBook("b1") }
            coVerify(exactly = 0) { libraryRepository.deleteBookLocalOnly(any()) }
            assertNull(vm.uiState.value.bookToDelete)
        }

    @Test
    fun confirmDeleteLocalOnly_delegatesToLocalDeleteFlow() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            stubMeasurements()
            coEvery { libraryRepository.getBookById("b2") } returns book("b2", "Book Two")
            coEvery { libraryRepository.deleteBookLocalOnly("b2") } returns Result.success(Unit)
            val vm = viewModel(dispatcher)
            advanceUntilIdle()

            vm.requestDeleteBook("b2")
            advanceUntilIdle()
            vm.confirmDeleteLocalOnly()
            advanceUntilIdle()

            coVerify(exactly = 1) { libraryRepository.deleteBookLocalOnly("b2") }
            coVerify(exactly = 0) { libraryRepository.deleteBook(any()) }
            assertNull(vm.uiState.value.bookToDelete)
        }

    private fun book(
        id: String,
        title: String,
    ) = Book(
        id = id,
        title = title,
        author = "Author",
        coverPath = null,
        filePath = "/books/$id.epub",
        format = "epub",
        updatedAtEpochMillis = 1L,
    )
}
