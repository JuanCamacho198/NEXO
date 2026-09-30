package com.nexo.presentation.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.debug.DebugLog
import com.nexo.debug.SentryMetrics
import com.nexo.domain.usecase.DownloadAndImportBookUseCase
import com.nexo.domain.usecase.DownloadImportState
import com.nexo.presentation.viewmodel.DiscoverDetailStatus
import com.nexo.presentation.viewmodel.DiscoverStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable state of the "Ver todo" section list (RESULTADOS layout minus search). */
data class DiscoverSectionUiState(
    val sectionTitle: String = "",
    val status: DiscoverStatus = DiscoverStatus.LOADING,
    val books: List<CatalogBook> = emptyList(),
    val totalCount: Int = 0,
    val nextPage: Int? = null,
    val activePage: Int = 0,
    val errorCode: CatalogErrorCode? = null,
    val detail: CatalogBook? = null,
    val detailStatus: DiscoverDetailStatus = DiscoverDetailStatus.CLOSED,
    /** Download → import lifecycle for the detail book currently on screen. */
    val download: DownloadImportState = DownloadImportState.Idle,
)

/**
 * Backing ViewModel for [DiscoverSectionScreen].
 *
 * Pages through exactly one selector: [sort] (featured rail) or [sourceId]
 * (per-source list). Neither present — or both present — fails closed with an
 * empty page, so a malformed route can never trigger a composite search or a
 * crash. [term] is the per-source query; U3b drives it, v1 passes nothing.
 */
class DiscoverSectionViewModel(
    private val catalogProvider: CatalogProvider,
    sectionTitle: String,
    private val sort: CatalogFeaturedSort?,
    private val sourceId: String?,
    private val term: String = "",
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val downloadAndImportBookUseCase: DownloadAndImportBookUseCase? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoverSectionUiState(sectionTitle = sectionTitle))
    val uiState: StateFlow<DiscoverSectionUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadingBookId: String? = null

    init {
        loadFirstPage()
    }

    fun loadFirstPage() = load(page = 1, append = false)

    /** No-op once the list is exhausted: `nextPage == null` stops all fetching. */
    fun loadNextPage() {
        val state = _uiState.value
        val page = state.nextPage ?: return
        if (state.status == DiscoverStatus.LOADING || state.status == DiscoverStatus.LOADING_MORE) return
        load(page = page, append = true)
    }

    fun retry() {
        if (_uiState.value.activePage <= 1) loadFirstPage() else loadNextPage()
    }

    /** Opens the same base detail sheet as the search grid (existing fields only). */
    fun openDetail(id: String) {
        // Opening a different book supersedes any download in flight for the
        // previous one; reopening the same book keeps its progress/outcome.
        if (downloadingBookId != id) {
            downloadJob?.cancel()
            downloadJob = null
            downloadingBookId = null
            _uiState.update { it.copy(download = DownloadImportState.Idle) }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(detailStatus = DiscoverDetailStatus.LOADING, detail = null) }
            try {
                val detail = catalogProvider.getDetails(id)
                _uiState.update {
                    it.copy(detailStatus = DiscoverDetailStatus.LOADED, detail = detail)
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                val code = (err as? CatalogException)?.code ?: CatalogErrorCode.UPSTREAM_ERROR
                _uiState.update {
                    it.copy(
                        detailStatus =
                            if (code == CatalogErrorCode.NOT_FOUND) {
                                DiscoverDetailStatus.NOT_FOUND
                            } else {
                                DiscoverDetailStatus.ERROR
                            },
                        detail = null,
                    )
                }
            }
        }
    }

    fun dismissDetail() {
        _uiState.update { it.copy(detail = null, detailStatus = DiscoverDetailStatus.CLOSED) }
    }

    /**
     * Starts (or retries) the in-app download → import of the open detail book.
     * Mirrors [com.nexo.presentation.viewmodel.DiscoverViewModel.startDownload]:
     * guarded entries log instead of returning silently, a blank download URL
     * surfaces Failure (retryable via the existing CTA string), and Idle →
     * Downloading applies synchronously so progress shows on tap.
     *
     * The job lives in [viewModelScope]; dismissing the sheet does NOT cancel
     * it — only [cancelDownload] or opening a different book does.
     */
    fun startDownload() {
        val useCase = downloadAndImportBookUseCase
        if (useCase == null) {
            DebugLog.warn(TAG, "startDownload ignored: download unavailable")
            return
        }
        val book = _uiState.value.detail
        if (book == null) {
            DebugLog.warn(TAG, "startDownload ignored: no detail open")
            return
        }
        if (book.downloadUrl.isNullOrBlank()) {
            DebugLog.warn(TAG, "startDownload failed: blank downloadUrl for ${book.id}")
            _uiState.update {
                it.copy(download = DownloadImportState.Failure(CatalogErrorCode.UNAVAILABLE_DOWNLOAD))
            }
            return
        }
        if (downloadJob?.isActive == true) {
            DebugLog.warn(TAG, "startDownload ignored: download already in flight for ${book.id}")
            return
        }

        downloadingBookId = book.id
        SentryMetrics.count(
            "discover_download_start",
            mapOf("provider" to book.provider),
        )
        // Synchronous Idle → Downloading so the progress UI appears on tap,
        // before the use-case flow emits its first value.
        _uiState.update { it.copy(download = DownloadImportState.Downloading(0L, null)) }
        downloadJob =
            viewModelScope.launch(mainDispatcher) {
                useCase(book).collect { state ->
                    // The synchronous preset above already rendered progress; the
                    // flow's leading Idle would flicker back, so skip it.
                    if (state is DownloadImportState.Idle) return@collect
                    emitDownloadTerminal(state, book.provider)
                    _uiState.update { it.copy(download = state) }
                }
            }
    }

    /**
     * U3-2 download funnel: terminal counters only (start fires on launch above).
     * Attributes stay {provider[, code]} — NEVER book id, title, or user id.
     */
    private fun emitDownloadTerminal(
        state: DownloadImportState,
        provider: String,
    ) {
        when (state) {
            is DownloadImportState.Success ->
                SentryMetrics.count("discover_download_complete", mapOf("provider" to provider))
            is DownloadImportState.Failure -> {
                val code = state.error?.name
                val tags =
                    if (code != null) {
                        mapOf("provider" to provider, "code" to code)
                    } else {
                        mapOf("provider" to provider)
                    }
                SentryMetrics.count("discover_download_fail", tags)
            }
            else -> Unit
        }
    }

    /** User-initiated cancel: stops the job and returns the CTA to Idle. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadingBookId = null
        _uiState.update { it.copy(download = DownloadImportState.Idle) }
    }

    private fun load(
        page: Int,
        append: Boolean,
    ) {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch(mainDispatcher) {
                _uiState.update {
                    it.copy(
                        status = if (append) DiscoverStatus.LOADING_MORE else DiscoverStatus.LOADING,
                        errorCode = null,
                    )
                }
                try {
                    val result = fetchPage(page)
                    _uiState.update { state ->
                        if (append) {
                            val merged = ArrayList(state.books)
                            val seen = HashSet(merged.map { it.id })
                            for (book in result.results) {
                                if (seen.add(book.id)) merged.add(book)
                            }
                            state.copy(
                                status = DiscoverStatus.LOADED,
                                books = merged,
                                totalCount = result.totalCount,
                                nextPage = result.nextPage,
                                activePage = page,
                            )
                        } else {
                            state.copy(
                                status =
                                    if (result.results.isEmpty()) {
                                        DiscoverStatus.EMPTY
                                    } else {
                                        DiscoverStatus.LOADED
                                    },
                                books = result.results,
                                totalCount = result.totalCount,
                                nextPage = result.nextPage,
                                activePage = page,
                            )
                        }
                    }
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Throwable) {
                    val code = (err as? CatalogException)?.code ?: CatalogErrorCode.UPSTREAM_ERROR
                    _uiState.update { it.copy(status = DiscoverStatus.ERROR, errorCode = code) }
                }
            }
    }

    private suspend fun fetchPage(page: Int): PagedResult =
        when {
            sourceId != null && sort == null -> catalogProvider.searchSource(sourceId, term, page)
            sort != null && sourceId == null -> catalogProvider.featured(sort, page)
            // Neither or both selectors: fail closed, never a composite-wide search.
            else -> PagedResult(emptyList(), null, 0)
        }

    private companion object {
        /** Log tag for the guarded download entry points (never silent). */
        const val TAG = "DiscoverSectionViewModel"
    }
}

class DiscoverSectionViewModelFactory(
    private val catalogProvider: CatalogProvider,
    private val sectionTitle: String,
    private val sort: CatalogFeaturedSort?,
    private val sourceId: String?,
    private val term: String = "",
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val downloadAndImportBookUseCase: DownloadAndImportBookUseCase? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DiscoverSectionViewModel::class.java)) {
            return DiscoverSectionViewModel(
                catalogProvider = catalogProvider,
                sectionTitle = sectionTitle,
                sort = sort,
                sourceId = sourceId,
                term = term,
                mainDispatcher = mainDispatcher,
                downloadAndImportBookUseCase = downloadAndImportBookUseCase,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
