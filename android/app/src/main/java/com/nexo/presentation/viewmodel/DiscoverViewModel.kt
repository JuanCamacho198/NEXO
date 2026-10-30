package com.nexo.presentation.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.R
import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.BUILTIN_STANDARD_EBOOKS
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogRailLog
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
import com.nexo.domain.discover.TOPIC_ROTATION
import com.nexo.domain.discover.TopicEntry
import com.nexo.domain.discover.entriesFor
import com.nexo.domain.session.ActiveUserProvider
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
import java.time.LocalDate

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

/**
 * Rails sharing one upstream. When every rail of a group fails together the
 * reader gets ONE notice instead of an identical inline error per rail: the
 * author shelves share the Google Books quota and fail as a unit. Standard
 * Ebooks is a group of one, so its grouped notice and its inline error agree.
 *
 * A group never names its rails: [DiscoverViewModel.retryRailGroup] rebuilds
 * them from the same deterministic plan, so a retry re-resolves exactly its own
 * group and never refetches a shelf that worked.
 */
enum class DiscoverRailGroup(
    @param:StringRes val labelRes: Int,
) {
    GoogleBooks(R.string.discover_rail_group_google_books),
    Gutendex(R.string.discover_rail_group_gutendex),
    StandardEbooks(R.string.discover_rail_group_standard_ebooks),
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
     * IDLE rails, in design order: the day's two author shelves, the Gutendex
     * popular shelf and the Standard Ebooks shelf, followed by any per-addon
     * rails. Empty while a search flow owns the screen; each rail independently
     * resolves to Hidden so a failed rail never renders a placeholder section.
     */
    val rails: List<DiscoverRailState> = emptyList(),
    /**
     * Sources whose rails ALL failed. Non-empty only once the rail set settled
     * as errors — never while a rail is still [DiscoverRailState.Loading] — so
     * the grouped notice can never appear over a shelf that may still land.
     */
    val failedRailGroups: List<DiscoverRailGroup> = emptyList(),
    /**
     * Groups whose notice the reader dismissed, for this session only. A
     * permanently rate-limited source therefore never becomes permanent noise,
     * while a fresh failure of another group still speaks up.
     */
    val dismissedRailGroups: Set<DiscoverRailGroup> = emptySet(),
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

    /**
     * IDLE rails narrowed in memory by the active [sourceFilter]. A rail whose
     * books all belong to other sources collapses to [DiscoverRailState.Hidden]
     * so a shelf never renders a bare header — the same silent-hide the rails
     * already apply to an empty page. "All" returns [rails] untouched.
     */
    val visibleRails: List<DiscoverRailState>
        get() =
            when (val filter = sourceFilter) {
                DiscoverSourceFilter.AllSources -> rails
                is DiscoverSourceFilter.Source -> rails.map { rail -> rail.narrowTo(filter.sourceId) }
            }

    /**
     * Rail groups whose single notice the IDLE body renders below the shelves.
     * Undismissed groups only, and only for failures the current source filter
     * still lets through: a filter that hides every rail of a group must not
     * leave a notice about shelves the reader cannot see.
     */
    val visibleRailGroupNotices: List<DiscoverRailGroup>
        get() {
            // Only a group whose notice was dismissed is filtered below. An empty
            // `failedRailGroups` is already an empty result; an empty dismissal
            // set must NOT short-circuit, or no notice could ever appear.
            if (failedRailGroups.isEmpty()) return emptyList()
            val visible = visibleRails
            val filter = sourceFilter
            return failedRailGroups.filter { group ->
                if (group in dismissedRailGroups) return@filter false
                if (filter !is DiscoverSourceFilter.Source) return@filter true
                groupMatches(visible, filter.sourceId)
            }
        }
}

/** Rails carry no source filter of their own, so narrowing is a presentation concern. */
private fun DiscoverRailState.narrowTo(sourceId: String): DiscoverRailState =
    if (this !is DiscoverRailState.Loaded) {
        this
    } else {
        val matching = books.filter { it.provider == sourceId }
        if (matching.isEmpty()) DiscoverRailState.Hidden else copy(books = matching)
    }

/**
 * True when [filtered] (a source-narrowed rail list) still carries a rail from
 * [sourceId]. A failed rail narrows to itself, so the notice survives a filter
 * that has nothing to show for it; a Loaded rail must actually keep a book.
 */
private fun groupMatches(
    filtered: List<DiscoverRailState>,
    sourceId: String,
): Boolean =
    filtered.any { rail ->
        when (rail) {
            is DiscoverRailState.Loaded -> rail.books.isNotEmpty() && rail.sourceId == sourceId
            is DiscoverRailState.Error -> rail.sourceId == sourceId
            DiscoverRailState.Hidden, DiscoverRailState.Loading -> false
        }
    }

class DiscoverViewModel(
    private val catalogProvider: CatalogProvider,
    private val connectivityObserver: ConnectivityObserver = AlwaysOnlineConnectivityObserver,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
    private val downloadAndImportBookUseCase: DownloadAndImportBookUseCase? = null,
    /**
     * Active account id source. Defaults to a standalone provider so tests and
     * non-wired callers compile; production passes the app-scoped instance.
     */
    private val activeUser: ActiveUserProvider = ActiveUserProvider(),
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
                useCase(activeUser.requireUserId(), book).collect { state ->
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
     * Starts the IDLE rails without ever blocking the shell: every rail is
     * published as Loading right after IDLE renders, then resolved asynchronously
     * in parallel. Offline IDLE never starts rails at all - the OFFLINE state
     * governs the screen.
     *
     * The plan is index-stable and fixed-length: the day's two rotating Gutendex
     * topic shelves first, then the Gutendex popular shelf, then the Standard
     * Ebooks newest-releases shelf. A failing source keeps its own inline error;
     * a source whose every rail failed adds ONE grouped notice below the
     * shelves. Either way it never renumbers the row. Per-addon rails are
     * appended afterwards, once `listSources()` can report the installed set.
     */
    private fun refreshRails() {
        railsJob?.cancel()
        railsJob = null
        val state = _uiState.value
        if (!state.isOnline || state.status != DiscoverStatus.IDLE) {
            _uiState.update { it.copy(rails = emptyList(), failedRailGroups = emptyList()) }
            return
        }
        val specs = railSpecs()
        _uiState.update { it.copy(rails = specs.map { DiscoverRailState.Loading }) }
        railsJob =
            viewModelScope.launch(mainDispatcher) {
                val loaded =
                    coroutineScope {
                        specs.map { spec -> async { loadRail(spec) } }.awaitAll()
                    }
                // The composite is only inspectable after the first rail call
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
                val groupNotices = failedGroupsOf(specs, loaded)
                _uiState.update {
                    it.copy(
                        rails = loaded + addonRails,
                        failedRailGroups = groupNotices,
                        // A dismissal only survives while its group is still failing:
                        // a fresh failure of the same group speaks up again.
                        dismissedRailGroups = it.dismissedRailGroups.intersect(groupNotices.toSet()),
                    )
                }
            }
    }

    /**
     * Groups whose every rail settled as [DiscoverRailState.Error]. A single
     * failing rail is deliberately excluded: it keeps its own inline error and
     * retry, so the group notice never repeats the same sentence about it.
     * Loading rails are never [DiscoverRailState.Error], so a group still in
     * flight can never be reported as failed.
     */
    private fun failedGroupsOf(
        specs: List<RailSpec>,
        rails: List<DiscoverRailState>,
    ): List<DiscoverRailGroup> {
        val groups = specs.map(::groupOf)
        return DiscoverRailGroup.entries.filter { group ->
            val indexes = groups.indices.filter { groups[it] == group }
            indexes.isNotEmpty() && indexes.all { rails.getOrNull(it) is DiscoverRailState.Error }
        }
    }

    /**
     * Retry for a grouped notice: re-resolves ONLY the rails of [group] that
     * are currently failed and leaves every other shelf exactly as it was —
     * including a sibling of the same source that is already serving books. The
     * two author rails share one Google Books quota, so refetching a working
     * rail would burn a request for nothing.
     */
    fun retryRailGroup(group: DiscoverRailGroup) {
        val specs = railSpecs()
        val retried =
            specs.indices.filter { index ->
                groupOf(specs[index]) == group &&
                    _uiState.value.rails.getOrNull(index) is DiscoverRailState.Error
            }
        // Nothing failed in this group has a rail in today's plan: a retry has
        // no rail to re-resolve and must not touch a working sibling.
        if (retried.isEmpty()) return
        railsJob?.cancel()
        railsJob =
            viewModelScope.launch(mainDispatcher) {
                _uiState.update { state ->
                    val rails = state.rails.toMutableList()
                    retried.forEach { index ->
                        if (index < rails.size) rails[index] = DiscoverRailState.Loading
                    }
                    state.copy(rails = rails, failedRailGroups = emptyList())
                }
                coroutineScope {
                    retried.forEach { index ->
                        launch {
                            val settled = loadRail(specs[index])
                            _uiState.update { state ->
                                val rails = state.rails.toMutableList()
                                if (index < rails.size) rails[index] = settled
                                state.copy(rails = rails)
                            }
                        }
                    }
                }
                _uiState.update { state ->
                    state.copy(failedRailGroups = failedGroupsOf(specs, state.rails))
                }
            }
    }

    /**
     * Retry for ONE rail's own inline error: re-resolves exactly that rail and
     * never its siblings, so a failing author shelf cannot drag a working one
     * back through the shared Google Books quota. A rail that is not currently
     * [DiscoverRailState.Error] (or an index outside the plan) is left alone.
     */
    fun retryRail(index: Int) {
        val specs = railSpecs()
        if (index !in specs.indices) return
        if (_uiState.value.rails.getOrNull(index) !is DiscoverRailState.Error) return
        railsJob?.cancel()
        railsJob =
            viewModelScope.launch(mainDispatcher) {
                _uiState.update { state ->
                    val rails = state.rails.toMutableList()
                    if (index < rails.size) rails[index] = DiscoverRailState.Loading
                    state.copy(rails = rails, failedRailGroups = emptyList())
                }
                val settled = loadRail(specs[index])
                _uiState.update { state ->
                    val rails = state.rails.toMutableList()
                    if (index < rails.size) rails[index] = settled
                    state.copy(rails = rails)
                }
                _uiState.update { state ->
                    state.copy(failedRailGroups = failedGroupsOf(specs, state.rails))
                }
            }
    }

    /** The group a plan rail belongs to; Standard Ebooks is a group of one. */
    private fun groupOf(spec: RailSpec): DiscoverRailGroup =
        when (spec) {
            is RailSpec.Topic -> DiscoverRailGroup.Gutendex
            is RailSpec.Popular -> DiscoverRailGroup.Gutendex
            is RailSpec.StandardEbooks -> DiscoverRailGroup.StandardEbooks
        }

    /** Hides one grouped notice for the rest of the session; no I/O, no refetch. */
    fun dismissRailGroup(group: DiscoverRailGroup) {
        _uiState.update { it.copy(dismissedRailGroups = it.dismissedRailGroups + group) }
    }

    /**
     * The IDLE rail plan: the day's topic shelves followed by the two
     * fixed-source shelves. [LocalDate.now] is read here, at rail-refresh time,
     * so a session that crosses local midnight picks up the new rotation on the
     * next refresh — the rotation is never frozen at construction.
     *
     * The title mapping lives in this layer deliberately: `TopicEntry.titleKey`
     * is a stable string key because the domain layer must not carry Android
     * resource ids, and [R.string] ids are resolved here.
     *
     * Fail closed on the title: a rotation entry whose title key has no resource
     * is DROPPED rather than carried with an unresolved title, and its term is
     * consequently never handed to the upstream either. [TOPIC_ROTATION] is a
     * closed, probed table and only a member of it can become a topic rail.
     */
    private fun railSpecs(date: LocalDate = LocalDate.now()): List<RailSpec> {
        val topics =
            entriesFor(date).mapNotNull { entry ->
                val term = entry.term.takeIf { it in TOPIC_ROTATION_TERMS } ?: return@mapNotNull null
                val titleRes = TOPIC_TITLE_RES[entry.titleKey] ?: return@mapNotNull null
                RailSpec.Topic(titleRes, term)
            }
        return topics +
            RailSpec.Popular(R.string.discover_rail_gutendex_popular) +
            RailSpec.StandardEbooks(R.string.discover_rail_standard_ebooks)
    }

    /**
     * Fail-closed rail resolution: an empty page yields
     * [DiscoverRailState.Hidden] — the section is skipped entirely instead of
     * rendering an empty rail. An upstream failure is different: it yields
     * [DiscoverRailState.Error] with the stable [CatalogErrorCode], so a failed
     * shelf keeps its own retry, and a source whose every rail failed can report
     * itself once as a group.
     *
     * An EMPTY page is never an error and never a connectivity failure: the
     * source answered and had nothing to give, which is not the same statement as
     * "you are offline". Only a thrown transport/upstream failure reaches the
     * [DiscoverRailState.Error] branch below.
     *
     * Coverless books are KEPT: the rail card degrades to a text card (title and
     * author on a theme surface) instead of an empty frame. Only a genuinely
     * empty page hides the shelf, so an all-coverless rail still has content.
     * This is rail-only on purpose: search keeps showing every match.
     */
    private suspend fun loadRail(spec: RailSpec): DiscoverRailState =
        try {
            val result =
                when (spec) {
                    // The `?topic=` selector, never a free-text search: the same words
                    // passed to `search` answer 6 books against 3 530 from `topic`.
                    is RailSpec.Topic -> catalogProvider.topic(spec.topicTerm, page = 1)
                    is RailSpec.Popular -> catalogProvider.featured(CatalogFeaturedSort.POPULAR, page = 1)
                    is RailSpec.StandardEbooks ->
                        catalogProvider.searchSource(BUILTIN_STANDARD_EBOOKS, STANDARD_EBOOKS_RAIL_TERM, page = 1)
                }
            if (result.results.isEmpty()) {
                // An empty page is not an error: say so, and say which rail and
                // which upstream term produced it. Without this a hidden shelf is
                // indistinguishable from a swallowed exception.
                CatalogRailLog.hidden(spec.railLogId, spec.railLogSource, spec.railLogTerm, "empty page")
                DiscoverRailState.Hidden
            } else {
                CatalogRailLog.loaded(spec.railLogId, spec.railLogSource, spec.railLogTerm, result.results.size)
                DiscoverRailState.Loaded(
                    sectionTitleRes = spec.sectionTitleRes,
                    sort = spec.sort,
                    sourceId = spec.sourceId,
                    books = result.results,
                    totalCount = result.totalCount,
                    topicTerm = spec.topicTerm,
                )
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: Throwable) {
            CatalogRailLog.hidden(
                spec.railLogId,
                spec.railLogSource,
                spec.railLogTerm,
                "exception ${err.javaClass.simpleName}: ${err.message}",
            )
            DiscoverRailState.Error(codeOf(err), spec.sourceId ?: BUILTIN_GUTENDEX)
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
                CatalogRailLog.hidden("addon:${source.sourceId}", source.sourceId, ADDON_RAIL_TERM, "empty page")
                DiscoverRailState.Hidden
            } else {
                CatalogRailLog.loaded("addon:${source.sourceId}", source.sourceId, ADDON_RAIL_TERM, result.results.size)
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
            CatalogRailLog.hidden(
                "addon:${source.sourceId}",
                source.sourceId,
                ADDON_RAIL_TERM,
                "exception ${err.javaClass.simpleName}: ${err.message}",
            )
            DiscoverRailState.Hidden
        }

    /**
     * One IDLE rail, as a closed union so each upstream is reached through the
     * resolution it actually supports: a topic shelf through a per-source
     * Gutendex `topic` search of a term from [TOPIC_ROTATION], the popular shelf
     * through the closed [CatalogFeaturedSort] ordering, the Standard Ebooks
     * shelf through its own source. The union is why [loadRail] needs no
     * capability probing.
     */
    private sealed interface RailSpec {
        val sectionTitleRes: Int

        /** Featured ordering for a rail served by `featured`; null otherwise. */
        val sort: CatalogFeaturedSort? get() = null

        /** Per-source selector for a rail served by `searchSource`; null otherwise. */
        val sourceId: String? get() = null

        /** Topic term carried so "See all" can page the same query. */
        val topicTerm: String? get() = null

        /** Stable rail name for [CatalogRailLog] — never a resource id. */
        val railLogId: String

        /** Upstream this rail is served by, for [CatalogRailLog]. */
        val railLogSource: String

        /** Selector term sent upstream, for [CatalogRailLog]; null when none. */
        val railLogTerm: String? get() = null

        data class Topic(
            override val sectionTitleRes: Int,
            override val topicTerm: String,
        ) : RailSpec {
            override val sourceId: String get() = BUILTIN_GUTENDEX

            override val railLogId: String get() = "topic"

            override val railLogSource: String get() = BUILTIN_GUTENDEX

            override val railLogTerm: String get() = topicTerm
        }

        data class Popular(
            override val sectionTitleRes: Int,
        ) : RailSpec {
            override val sort: CatalogFeaturedSort get() = CatalogFeaturedSort.POPULAR

            override val sourceId: String get() = BUILTIN_GUTENDEX

            override val railLogId: String get() = "popular"

            override val railLogSource: String get() = BUILTIN_GUTENDEX

            override val railLogTerm: String get() = "sort=popular"
        }

        data class StandardEbooks(
            override val sectionTitleRes: Int,
        ) : RailSpec {
            override val sourceId: String get() = BUILTIN_STANDARD_EBOOKS

            override val railLogId: String get() = "standard-ebooks"

            override val railLogSource: String get() = BUILTIN_STANDARD_EBOOKS

            override val railLogTerm: String get() = STANDARD_EBOOKS_RAIL_TERM
        }
    }

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
         * Term passed for the Standard Ebooks shelf. The feed publishes one
         * newest-releases page and has no query parameter, so the blank term is
         * the only query it can answer — the provider treats a real keyword as
         * unsupported and answers only blank. Same value as desktop's
         * `STANDARD_EBOOKS_RAIL_QUERY`.
         */
        private const val STANDARD_EBOOKS_RAIL_TERM = ""

        /** The closed rotation terms; only a member may ever reach `?topic=`. */
        private val TOPIC_ROTATION_TERMS: Set<String> = TOPIC_ROTATION.map { it.term }.toSet()

        /**
         * Topic rail title keys (S1's [TopicEntry.titleKey] strings) to their
         * string resources. Kept beside the rails so a key with no resource is
         * caught by TopicRailTitlesTest instead of surfacing an unresolved
         * shelf.
         */
        private val TOPIC_TITLE_RES: Map<String, Int> =
            mapOf(
                "discover_rail_topic_science_fiction" to R.string.discover_rail_topic_science_fiction,
                "discover_rail_topic_adventure" to R.string.discover_rail_topic_adventure,
                "discover_rail_topic_history" to R.string.discover_rail_topic_history,
                "discover_rail_topic_travel" to R.string.discover_rail_topic_travel,
                "discover_rail_topic_poetry" to R.string.discover_rail_topic_poetry,
                "discover_rail_topic_philosophy" to R.string.discover_rail_topic_philosophy,
                "discover_rail_topic_drama" to R.string.discover_rail_topic_drama,
                "discover_rail_topic_romance" to R.string.discover_rail_topic_romance,
                "discover_rail_topic_mystery" to R.string.discover_rail_topic_mystery,
                "discover_rail_topic_horror" to R.string.discover_rail_topic_horror,
            )
    }
}

class DiscoverViewModelFactory(
    private val catalogProvider: CatalogProvider,
    private val connectivityObserver: ConnectivityObserver = AlwaysOnlineConnectivityObserver,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val debounceMillis: Long = DiscoverViewModel.DEFAULT_DEBOUNCE_MS,
    private val downloadAndImportBookUseCase: DownloadAndImportBookUseCase? = null,
    private val activeUser: ActiveUserProvider = ActiveUserProvider(),
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
                activeUser = activeUser,
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
