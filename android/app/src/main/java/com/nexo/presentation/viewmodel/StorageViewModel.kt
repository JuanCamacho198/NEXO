package com.nexo.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexo.domain.model.Book
import com.nexo.domain.model.BookStorageItem
import com.nexo.domain.model.CacheUsage
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.StorageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Storage settings screen (WS6).
 *
 * @property isLoading `true` until the first cache/book measurement lands.
 * @property cache Byte breakdown of the disposable caches.
 * @property books Per-book local footprint (backing file + cover, deduped).
 * @property bookToDelete Book staged for the shared remove dialog, or `null`.
 * @property isClearingCache `true` while "clear cache" runs; books are untouched.
 */
data class StorageUiState(
    val isLoading: Boolean = true,
    val cache: CacheUsage = CacheUsage(),
    val books: List<BookStorageItem> = emptyList(),
    val bookToDelete: Book? = null,
    val isClearingCache: Boolean = false,
) {
    /** Total bytes held by the library books. */
    val booksTotalBytes: Long get() = books.sumOf { it.sizeBytes }

    /** Everything the screen accounts for: caches plus library books. */
    val totalBytes: Long get() = cache.totalBytes + booksTotalBytes
}

/**
 * Storage settings ViewModel (WS6).
 *
 * Measures the cache breakdown ([CacheRepository]) and the per-book footprint
 * ([StorageRepository]); each repository dispatches its own work to
 * `Dispatchers.IO`, and the two measurements run in parallel here.
 *
 * Clear-cache is deliberately separate from book deletion: [clearCache] only
 * ever touches cache stores, while per-book removal is routed through the
 * shared confirm dialog and the [LibraryRepository] delete flow (which also
 * removes the backing file after sync settles).
 */
class StorageViewModel(
    private val storageRepository: StorageRepository,
    private val cacheRepository: CacheRepository,
    private val libraryRepository: LibraryRepository,
    private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(StorageUiState())

    /** Raw [StorageUiState] for the Storage screen. */
    val uiState: StateFlow<StorageUiState> = mutableUiState.asStateFlow()

    init {
        refresh()
    }

    /** Re-measures cache + books and publishes the fresh state. */
    fun refresh() {
        viewModelScope.launch(mainDispatcher) { load() }
    }

    /**
     * Clears every cache store and re-measures. Books are never candidates:
     * this path performs no [LibraryRepository] delete call.
     */
    fun clearCache() {
        viewModelScope.launch(mainDispatcher) {
            mutableUiState.update { it.copy(isClearingCache = true) }
            cacheRepository.clearDiscoverCache()
            cacheRepository.clearImageCache()
            cacheRepository.clearReaderCache()
            load()
        }
    }

    /** Stages [bookId] for the shared remove dialog (resolves the full book first). */
    fun requestDeleteBook(bookId: String) {
        viewModelScope.launch(mainDispatcher) {
            val book = libraryRepository.getBookById(bookId) ?: return@launch
            mutableUiState.update { it.copy(bookToDelete = book) }
        }
    }

    /** Dismisses the remove dialog without deleting. */
    fun dismissDeleteDialog() {
        mutableUiState.update { it.copy(bookToDelete = null) }
    }

    /** Local-only delete: soft-deletes and removes the local file, keeping Drive bytes. */
    fun confirmDeleteLocalOnly() = confirmDelete(localOnly = true)

    /** Local + Drive delete: soft-deletes, tombstones and removes the local file. */
    fun confirmDeleteLocalAndDrive() = confirmDelete(localOnly = false)

    /** Deletes orphan book files left by timed-out deletes or failed imports. */
    fun sweepOrphans() {
        viewModelScope.launch(mainDispatcher) {
            storageRepository.sweepOrphanBookFiles()
            load()
        }
    }

    private fun confirmDelete(localOnly: Boolean) {
        val book = mutableUiState.value.bookToDelete ?: return
        viewModelScope.launch(mainDispatcher) {
            if (localOnly) {
                libraryRepository.deleteBookLocalOnly(book.id)
            } else {
                libraryRepository.deleteBook(book.id)
            }
            mutableUiState.update { it.copy(bookToDelete = null) }
            load()
        }
    }

    private suspend fun load() {
        coroutineScope {
            val cacheDeferred = async { loadCache() }
            val booksDeferred = async { storageRepository.bookStorageUsage() }
            val cache = cacheDeferred.await()
            val books = booksDeferred.await()
            mutableUiState.update {
                it.copy(isLoading = false, isClearingCache = false, cache = cache, books = books)
            }
        }
    }

    private suspend fun loadCache(): CacheUsage =
        CacheUsage(
            discoverCacheBytes = cacheRepository.discoverCacheSizeBytes(),
            imageCacheBytes = cacheRepository.imageCacheSizeBytes(),
            readerCacheBytes = cacheRepository.readerCacheSizeBytes(),
        )
}
