package com.nexo.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.R
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.CatalogSources
import com.nexo.data.remote.catalog.addonSource
import com.nexo.debug.DebugLog
import com.nexo.debug.SentryMetrics
import com.nexo.domain.access.LegalAccess
import com.nexo.domain.access.resolveAccess
import com.nexo.domain.connectivity.AlwaysOnlineConnectivityObserver
import com.nexo.domain.connectivity.ConnectivityObserver
import com.nexo.domain.usecase.DownloadAndImportBookUseCase
import com.nexo.domain.usecase.DownloadImportState
import com.nexo.presentation.feature.discover.AccessResolverState
import com.nexo.presentation.feature.discover.AddonReadState
import com.nexo.presentation.feature.discover.DiscoverRailState
import com.nexo.presentation.feature.discover.mapAccessState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Screen-level status for the Discover search flow. */
enum class DiscoverStatus {
    IDLE,
    LOADING,
    LOADING_MORE,
    LOADED,
    EMPTY,
    ERROR,
    OFFLINE,
}

/** Detail pane status, independent from the search-list status. */
enum class DiscoverDetailStatus {
    CLOSED,
    LOADING,
    LOADED,
    NOT_FOUND,
    ERROR,
}

/**
 * In-memory source narrowing for the results grid. [AllSources] is the default;
 * [Source] pins exactly one `CatalogSourceInfo.sourceId`. A filter whose source
 * disappears mid-session (addon disabled/uninstalled) degrades back to
 * [AllSources] instead of throwing or rendering an empty phantom filter.
 */
sealed interface DiscoverSourceFilter {
    data object AllSources : DiscoverSourceFilter

    data class Source(
        val sourceId: String,
    ) : DiscoverSourceFilter
}

/** Immutable UI state exposed by [DiscoverViewModel]. */
data class DiscoverUiState(
    val query: String = "",
    val status: DiscoverStatus = DiscoverStatus.IDLE,
    val books: List<CatalogBook> = emptyList(),
    val totalCount: Int = 0,
    val nextPage: Int? = null,
    val activePage: Int = 0,
    val errorCode: CatalogErrorCode? = null,
    val detail: CatalogBook? = null,
    val detailStatus: DiscoverDetailStatus = DiscoverDetailStatus.CLOSED,
    /**
     * U5 "Dónde leerlo" access state for the open detail book, mapped via
     * [mapAccessState]: loading/skeleton while the detail fetch is in
     * flight, then loaded/empty/error/offline/consent-gated.
     */
    val accessState: AccessResolverState = AccessResolverState.Loading,
    /**
     * U5 addon-resolved items sheet for the open detail book. [Hidden] by
     * default; [openAddonRead] drives Resolving → Loaded/Empty/Error or the
     * [AddonReadState.ConsentRequired] gate.
     */
    val addonRead: AddonReadState = AddonReadState.Hidden,
    /** Display name of the addon owning [addonRead] (manifest name). */
    val addonReadName: String = "",
    /**
     * IDLE featured rails, in design order (newest first, then popular).
     * Empty while a search flow owns the screen; each rail independently resolves
     * to Hidden so a failed rail never renders a placeholder section.
     */
    val rails: List<DiscoverRailState> = emptyList(),
    /** Mirrors [ConnectivityObserver.isOnline]; drives the pre-emptive OFFLINE state. */
    val isOnline: Boolean = true,
    /** True while a page fetch is in flight; drives the spinner in the search field. */
    val isSearching: Boolean = false,
    /** Download → import lifecycle for the detail book currently on screen. */
    val download: DownloadImportState = DownloadImportState.Idle,
    /** Sources available for the filter chips, in provider order. */
    val sources: List<CatalogSourceInfo> = emptyList(),
    /** Active source filter; [DiscoverSourceFilter.AllSources] is the default. */
    val sourceFilter: DiscoverSourceFilter = DiscoverSourceFilter.AllSources,
    /** addonId → display name, resolved from [sources] via the injected lookup. */
    val attributionNames: Map<String, String> = emptyMap(),
) {
    /** [books] narrowed in memory by the active [sourceFilter]. */
    val visibleBooks: List<CatalogBook>
        get() =
            when (val filter = sourceFilter) {
                DiscoverSourceFilter.AllSources -> books
                is DiscoverSourceFilter.Source -> books.filter { it.provider == filter.sourceId }
            }
}

class DiscoverViewModel(
    private val catalogProvider: CatalogProvider,
    private val connectivityObserver: ConnectivityObserver = AlwaysOnlineConnectivityObserver,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
    private val downloadAndImportBookUseCase: DownloadAndImportBookUseCase? = null,
    /**
     * addonId → display name used to resolve source attribution. Injectable so
     * tests can pin attribution without a live registry; production leaves it
     * at the fail-closed default and relies on the addon source's own name.
     */
    private val addonNames: (String) -> String? = { null },
    /**
     * Registers a registry-mutation observer; called once from init so an
     * install/enable/disable/uninstall refreshes the source set (and therefore
     * the filter chips and addon rails). Null means "registry not observed".
     */
    private val registerAddonChangeListener: (((Int) -> Unit) -> Unit)? = null,
    /**
     * U5: per-addon disclosure-consent lookup for the access section's
     * consent gate. Production wires the registry's durable store; the
     * default (all-consented) keeps unit tests focused on the mapping.
     */
    private val addonConsent: (String) -> Boolean = { true },
    /**
     * U5: persists a disclosure-consent decision. Production wires the
     * registry's durable store (record/revoke); defaults no-op for tests.
     */
    private val onAddonConsentChange: (addonId: String, granted: Boolean) -> Unit = { _, _ -> },
    /**
     * U5: resolves an addon book to its provider items. Production builds
     * an ephemeral provider over the installed manifest; null (tests, or
     * uninstalled addon) resolves to [AddonReadState.Empty].
     */
    private val addonResolve: (suspend (addonId: String, book: CatalogBook) -> LegalAccess)? = null,
) : ViewModel() {
    private val initialOnline = connectivityObserver.current()

    private val _uiState =
        MutableStateFlow(
            DiscoverUiState(
                status = if (initialOnline) DiscoverStatus.IDLE else DiscoverStatus.OFFLINE,
                isOnline = initialOnline,
            ),
        )
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var lastAttemptedPage = 0
    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var railsJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadingBookId: String? = null

    /**
     * Monotonic token identifying the newest search. A cancelled job compares
     * its captured token against this value before touching `isSearching`, so
     * a superseded job can never clear the flag of the job that replaced it.
     */
    private var activeSearchId = 0

    init {
        viewModelScope.launch(mainDispatcher) {
            connectivityObserver.isOnline.collect { online ->
                val wasOnline = _uiState.value.isOnline
                _uiState.update { it.copy(isOnline = online) }
                when {
                    !online -> onConnectivityLost()
                    !wasOnline -> onConnectivityRestored()
                }
            }
        }
        refreshSources()
        registerAddonChangeListener?.invoke {
            refreshSources()
            refreshRails()
        }
        refreshRails()
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        debounceJob?.cancel()
        if (query.isBlank()) {
            cancelInFlightSearch()
            resetToIdle()
            return
        }
        // A new query supersedes any in-flight search: cancel it so stale
        // results can never render, then schedule the debounced latest search.
        cancelInFlightSearch()
        debounceJob =
            viewModelScope.launch(mainDispatcher) {
                delay(debounceMillis)
                beginSearch(page = 1, append = false)
            }
    }

    /** Immediate search (IME action, trending/suggestion chip, or retry). */
    fun searchFirstPage() {
        debounceJob?.cancel()
        if (_uiState.value.query.isBlank()) {
            cancelInFlightSearch()
            resetToIdle()
            return
        }
        beginSearch(page = 1, append = false)
    }

    fun loadNextPage() {
        val state = _uiState.value
        val page = state.nextPage ?: return
        if (state.status == DiscoverStatus.LOADING || state.status == DiscoverStatus.LOADING_MORE) {
            return
        }
        beginSearch(page = page, append = true)
    }

    /** Changes the in-memory source filter applied to the results grid. */
    fun setSourceFilter(filter: DiscoverSourceFilter) {
        _uiState.update { it.copy(sourceFilter = filter) }
    }

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
            _uiState.update {
                it.copy(
                    detailStatus = DiscoverDetailStatus.LOADING,
                    detail = null,
                    accessState = AccessResolverState.Loading,
                )
            }
            try {
                val detail = catalogProvider.getDetails(id)
                _uiState.update {
                    val addonId = CatalogSources.addonIdOf(detail.provider)
                    it.copy(
                        detailStatus = DiscoverDetailStatus.LOADED,
                        detail = detail,
                        accessState =
                            mapAccessState(
                                isOnline = it.isOnline,
                                consentRequiredAddonId = addonId,
                                hasConsent = addonId?.let(addonConsent) ?: true,
                                access = resolveAccess(detail),
                                failed = false,
                            ),
                    )
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                val code = codeOf(err)
                _uiState.update {
                    it.copy(
                        detailStatus =
                            if (code == CatalogErrorCode.NOT_FOUND) {
                                DiscoverDetailStatus.NOT_FOUND
                            } else {
                                DiscoverDetailStatus.ERROR
                            },
                        detail = null,
                        accessState =
                            mapAccessState(
                                isOnline = it.isOnline,
                                consentRequiredAddonId = null,
                                hasConsent = true,
                                access = null,
                                failed = true,
                            ),
                    )
                }
            }
        }
    }

    fun dismissDetail() {
        _uiState.update {
            it.copy(
                detail = null,
                detailStatus = DiscoverDetailStatus.CLOSED,
                accessState = AccessResolverState.Loading,
                addonRead = AddonReadState.Hidden,
                addonReadName = "",
            )
        }
    }

    /**
     * U5: opens the addon items sheet for an addon-sourced detail book.
     *
     * Fail-closed: without disclosure consent no resolve runs (zero I/O)
     * and the sheet prompts for consent; with consent the ephemeral
     * provider resolve runs and maps to Loaded/Empty/Error. A null
     * [addonResolve] (tests) resolves consented books to Empty.
     */
    fun openAddonRead(
        addonId: String,
        addonName: String,
    ) {
        val book = _uiState.value.detail ?: return
        if (book.provider != addonSource(addonId)) return
        pendingAddonReadId = addonId
        _uiState.update { it.copy(addonReadName = addonName) }
        if (!addonConsent(addonId)) {
            _uiState.update { it.copy(addonRead = AddonReadState.ConsentRequired) }
            return
        }
        resolveAddonRead(addonId, book)
    }

    /** U5: hides the addon items sheet (resolve job keeps its outcome). */
    fun dismissAddonRead() {
        pendingAddonReadId = null
        _uiState.update { it.copy(addonRead = AddonReadState.Hidden, addonReadName = "") }
    }

    /** U5: retries the pending addon resolve (no-op without a pending id). */
    fun retryAddonRead() {
        val addonId = pendingAddonReadId ?: return
        val book = _uiState.value.detail ?: return
        resolveAddonRead(addonId, book)
    }

    /**
     * U5: records disclosure consent for [addonId] durably, then refreshes
     * both the access section gate and the pending addon resolve.
     */
    fun grantAccessConsent(addonId: String) {
        onAddonConsentChange(addonId, true)
        val book = _uiState.value.detail
        if (book != null) {
            _uiState.update {
                it.copy(
                    accessState =
                        mapAccessState(
                            isOnline = it.isOnline,
                            consentRequiredAddonId = CatalogSources.addonIdOf(book.provider),
                            hasConsent = true,
                            access = resolveAccess(book),
                            failed = false,
                        ),
                )
            }
            if (_uiState.value.addonRead == AddonReadState.ConsentRequired &&
                pendingAddonReadId == addonId
            ) {
                resolveAddonRead(addonId, book)
            }
        }
    }

    /**
     * U5: records consent for the pending addon sheet ([openAddonRead]
     * target) and resolves it. No-op without a pending id.
     */
    fun grantAddonReadConsent() {
        pendingAddonReadId?.let { grantAccessConsent(it) }
    }

    /**
     * U5: "not now" — no consent is recorded; the access section falls back
     * to Empty and a pending addon sheet hides. Nothing is resolved.
     */
    fun denyAccessConsent() {
        pendingAddonReadId = null
        _uiState.update {
            it.copy(
                accessState =
                    if (it.accessState is AccessResolverState.ConsentRequired) {
                        AccessResolverState.Empty
                    } else {
                        it.accessState
                    },
                addonRead = AddonReadState.Hidden,
                addonReadName = "",
            )
        }
    }

    private var pendingAddonReadId: String? = null

    private fun resolveAddonRead(
        addonId: String,
        book: CatalogBook,
    ) {
        val resolve = addonResolve
        if (resolve == null) {
            _uiState.update { it.copy(addonRead = AddonReadState.Empty) }
            return
        }
        viewModelScope.launch(mainDispatcher) {
            _uiState.update { it.copy(addonRead = AddonReadState.Resolving) }
            try {
                val access = resolve(addonId, book)
                _uiState.update {
                    it.copy(
                        addonRead =
                            if (access.options.isEmpty() && !access.canDownloadInApp) {
                                AddonReadState.Empty
                            } else {
                                AddonReadState.Loaded(access)
                            },
                    )
                }
            } catch (err: CancellationException) {
                throw err
            } catch (_: Throwable) {
                _uiState.update { it.copy(addonRead = AddonReadState.Error) }
            }
        }
    }

    /**
     * Starts (or retries) the in-app download → import of the open detail book.
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
     * Attributes stay {provider[, code]} from CatalogErrorCode; NEVER book id,
     * title, query, or user id. Duplicate/Idle/Downloading/Importing emit nothing.
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

    fun retry() {
        if (lastAttemptedPage <= 1) {
            searchFirstPage()
        } else {
            _uiState.update { it.copy(nextPage = lastAttemptedPage) }
            loadNextPage()
        }
    }

    private fun beginSearch(
        page: Int,
        append: Boolean,
    ) {
        searchJob?.cancel()
        val searchId = ++activeSearchId
        lastAttemptedPage = page

        // Pre-emptive offline: never start a network attempt.
        if (!_uiState.value.isOnline) {
            _uiState.update {
                it.copy(status = DiscoverStatus.OFFLINE, errorCode = null, isSearching = false)
            }
            return
        }

        searchJob =
            viewModelScope.launch(mainDispatcher) {
                _uiState.update {
                    it.copy(
                        status = if (append) DiscoverStatus.LOADING_MORE else DiscoverStatus.LOADING,
                        errorCode = null,
                        isSearching = true,
                    )
                }
                try {
                    val result = catalogProvider.search(_uiState.value.query, page)
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
                                nextPage = result.nexoPage,
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
                                nextPage = result.nexoPage,
                                activePage = page,
                            )
                        }
                    }
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Throwable) {
                    val code = codeOf(err)
                    _uiState.update { it.copy(status = effectiveStatusFor(code), errorCode = code) }
                } finally {
                    if (searchId == activeSearchId) {
                        _uiState.update { it.copy(isSearching = false) }
                    }
                }
            }
    }

    private fun cancelInFlightSearch() {
        searchJob?.cancel()
        searchJob = null
        activeSearchId++
        _uiState.update { it.copy(isSearching = false) }
    }

    private fun onConnectivityLost() {
        debounceJob?.cancel()
        cancelInFlightSearch()
        railsJob?.cancel()
        railsJob = null
        _uiState.update { it.copy(status = DiscoverStatus.OFFLINE, rails = emptyList()) }
    }

    private fun onConnectivityRestored() {
        if (lastAttemptedPage >= 1) {
            retry()
        } else {
            resetToIdle()
        }
    }

    private fun resetToIdle() {
        val current = _uiState.value
        _uiState.value =
            DiscoverUiState(
                query = current.query,
                status = if (current.isOnline) DiscoverStatus.IDLE else DiscoverStatus.OFFLINE,
                isOnline = current.isOnline,
                // Keep the source catalog and its derived chips across a cleared query.
                sources = current.sources,
                sourceFilter = current.sourceFilter,
                attributionNames = current.attributionNames,
            )
        refreshRails()
    }

    /** Re-reads the source catalog and re-derives chips + attribution names. */
    private fun refreshSources() {
        applySources(catalogProvider.listSources())
    }

    /**
     * Applies a fresh source list. A filter pinned to a source that is no longer
     * present (addon disabled/uninstalled mid-session) falls back to
     * [DiscoverSourceFilter.AllSources] so filtering degrades instead of crashing.
     */
    private fun applySources(sources: List<CatalogSourceInfo>) {
        _uiState.update { state ->
            val knownIds = sources.map { it.sourceId }.toSet()
            val filter = state.sourceFilter
            val nextFilter =
                if (filter is DiscoverSourceFilter.Source && filter.sourceId !in knownIds) {
                    DiscoverSourceFilter.AllSources
                } else {
                    filter
                }
            state.copy(
                sources = sources,
                sourceFilter = nextFilter,
                attributionNames = resolveAttributionNames(sources),
            )
        }
    }

    /**
     * addonId → display name for [sources] of kind ADDON. The injected
     * [addonNames] lookup wins when it has an answer; otherwise the source's own
     * advertised name is used. Non-addon sources contribute nothing.
     */
    private fun resolveAttributionNames(sources: List<CatalogSourceInfo>): Map<String, String> =
        sources
            .mapNotNull { info ->
                CatalogSources.addonIdOf(info.sourceId)?.let { addonId ->
                    addonId to (addonNames(addonId) ?: info.name)
                }
            }.toMap()

    /**
     * Starts the IDLE rails without ever blocking the shell: both rails are
     * published as Loading right after IDLE renders, then resolved asynchronously
     * in parallel. Offline IDLE never starts rails at all - the OFFLINE state
     * governs the screen.
     *
     * The capability decision lives in the provider layer (`featured` is
     * asynchronous and fail-closed) rather than behind `supportsFeatured()` here:
     * an adapter such as LiveCatalogProvider can only report that flag for an
     * already-built composite, so gating on it here would permanently hide rails
     * on a cold start.
     */
    private fun refreshRails() {
        railsJob?.cancel()
        railsJob = null
        val state = _uiState.value
        if (!state.isOnline || state.status != DiscoverStatus.IDLE) {
            _uiState.update { it.copy(rails = emptyList()) }
            return
        }
        _uiState.update { it.copy(rails = RAIL_SPECS.map { DiscoverRailState.Loading }) }
        railsJob =
            viewModelScope.launch(mainDispatcher) {
                val loaded =
                    coroutineScope {
                        RAIL_SPECS.map { spec -> async { loadRail(spec) } }.awaitAll()
                    }
                // The composite is only inspectable after the first featured call
                // (LiveCatalogProvider.listSources() reads the already-built
                // composite), so the source set is re-read here and per-addon rails
                // are derived from it.
                val sources = catalogProvider.listSources()
                val addonRails =
                    coroutineScope {
                        sources
                            .filter { it.kind == CatalogSourceKind.ADDON }
                            .map { source -> async { loadAddonRail(source) } }
                            .awaitAll()
                    }
                applySources(sources)
                _uiState.update { it.copy(rails = loaded + addonRails) }
            }
    }

    /**
     * Fail-closed rail resolution: an upstream error or an empty page yields
     * [DiscoverRailState.Hidden], so the section is skipped entirely instead of
     * rendering an empty rail or a placeholder header.
     */
    private suspend fun loadRail(spec: RailSpec): DiscoverRailState =
        try {
            val result = catalogProvider.featured(spec.sort, page = 1)
            if (result.results.isEmpty()) {
                DiscoverRailState.Hidden
            } else {
                DiscoverRailState.Loaded(
                    sectionTitleRes = spec.sectionTitleRes,
                    sort = spec.sort,
                    sourceId = null,
                    books = result.results,
                    totalCount = result.totalCount,
                )
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: Throwable) {
            DiscoverRailState.Hidden
        }

    /**
     * Per-addon rail: `searchSource(sourceId, ADDON_RAIL_TERM, 1)`. Empty,
     * upstream error, or a source that vanished (uninstall) all fail closed to
     * [DiscoverRailState.Hidden] — an addon never crowds the IDLE shell with an
     * empty or placeholder section.
     */
    private suspend fun loadAddonRail(source: CatalogSourceInfo): DiscoverRailState =
        try {
            val result = catalogProvider.searchSource(source.sourceId, ADDON_RAIL_TERM, page = 1)
            if (result.results.isEmpty()) {
                DiscoverRailState.Hidden
            } else {
                DiscoverRailState.Loaded(
                    sectionTitleRes = R.string.discover_rail_from,
                    sort = null,
                    sourceId = source.sourceId,
                    addonName = source.name,
                    books = result.results,
                    totalCount = result.totalCount,
                )
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: Throwable) {
            DiscoverRailState.Hidden
        }

    private data class RailSpec(
        val sectionTitleRes: Int,
        val sort: CatalogFeaturedSort,
    )

    /**
     * OFFLINE precedence (design PART 1 Decision 4): the pre-emptive observer
     * state wins; a NETWORK_ERROR only maps to OFFLINE when the observer already
     * reports offline at failure time (read through `!isOnline`). RATE_LIMITED
     * and every other upstream failure stay ERROR, so a single flaky socket is
     * never misreported as "no connection".
     */
    private fun effectiveStatusFor(code: CatalogErrorCode): DiscoverStatus =
        when {
            !_uiState.value.isOnline -> DiscoverStatus.OFFLINE
            code == CatalogErrorCode.NETWORK_ERROR -> DiscoverStatus.ERROR
            else -> DiscoverStatus.ERROR
        }

    private fun codeOf(err: Throwable): CatalogErrorCode = (err as? CatalogException)?.code ?: CatalogErrorCode.UPSTREAM_ERROR

    companion object {
        /** Log tag for the guarded download entry points (never silent). */
        private const val TAG = "DiscoverViewModel"

        /** Task-phase parameter (design §7.5): injectable for tests. */
        const val DEFAULT_DEBOUNCE_MS = 250L

        /**
         * Fixed query for per-addon IDLE rails. Addon manifests expose a search
         * template only (no featured endpoint), so a deterministic broad term
         * keeps the rail cacheable and comparable across catalogs. "the" is the
         * most widely matching English stopword and is deliberately not
         * user-editable.
         */
        const val ADDON_RAIL_TERM = "the"

        /**
         * IDLE rail order. The titles name the real upstream semantics: a provider
         * only exposes newest-by-id and all-time download count, never a
         * week-scoped popularity window.
         */
        private val RAIL_SPECS =
            listOf(
                RailSpec(R.string.discover_rail_newest, CatalogFeaturedSort.NEWEST),
                RailSpec(R.string.discover_rail_popular, CatalogFeaturedSort.POPULAR),
            )
    }
}

class DiscoverViewModelFactory(
    private val catalogProvider: CatalogProvider,
    private val connectivityObserver: ConnectivityObserver = AlwaysOnlineConnectivityObserver,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val debounceMillis: Long = DiscoverViewModel.DEFAULT_DEBOUNCE_MS,
    private val downloadAndImportBookUseCase: DownloadAndImportBookUseCase? = null,
    private val addonNames: (String) -> String? = { null },
    private val registerAddonChangeListener: (((Int) -> Unit) -> Unit)? = null,
    private val addonConsent: (String) -> Boolean = { true },
    private val onAddonConsentChange: (String, Boolean) -> Unit = { _, _ -> },
    private val addonResolve: (suspend (String, CatalogBook) -> LegalAccess)? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DiscoverViewModel::class.java)) {
            return DiscoverViewModel(
                catalogProvider = catalogProvider,
                connectivityObserver = connectivityObserver,
                mainDispatcher = mainDispatcher,
                debounceMillis = debounceMillis,
                downloadAndImportBookUseCase = downloadAndImportBookUseCase,
                addonNames = addonNames,
                registerAddonChangeListener = registerAddonChangeListener,
                addonConsent = addonConsent,
                onAddonConsentChange = onAddonConsentChange,
                addonResolve = addonResolve,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
