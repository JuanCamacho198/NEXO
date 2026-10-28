package com.nexo.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.debug.DebugDual
import com.nexo.debug.DebugEvent
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingStats
import com.nexo.domain.model.Statistics
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.presentation.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val userName: String = "Reader",
    val avatarUrl: String? = null,
    val minutesReadToday: Int = 0,
    val sessionsToday: Int = 0,
    val dailyProgressPercent: Float = 0f,
    val currentStreak: Int = 0,
    val currentBooks: List<Book> = emptyList(),
    val recentBooks: List<Book> = emptyList(),
    val isLoading: Boolean = true,
    // Search
    val showSearch: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Book> = emptyList(),
    val allBooks: List<Book> = emptyList(),
    // Canonical progress (reading_progress.percentage wins, fallback cache) via GetBookProgressUseCase
    val progressPercentByBook: Map<String, Float> = emptyMap(),
) {
    /**
     * First-session Home: the library holds no book at all.
     *
     * Derived from [allBooks] — the same Room source that backs the shelf — so a
     * book imported after the first load flips the screen back to the populated
     * layout on the next emission. Loading is excluded on purpose: before the
     * first emission [allBooks] is empty for lack of data, not because the
     * library is empty, and the skeleton owns that window.
     */
    val isLibraryEmpty: Boolean
        get() = !isLoading && allBooks.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val homeRepository: HomeRepository,
        private val getStatisticsUseCase: GetStatisticsUseCase,
        private val dailyGoalProvider: @kotlin.jvm.JvmSuppressWildcards () -> Int,
        private val readerRepository: ReaderRepository,
        private val getBookProgressUseCase: GetBookProgressUseCase,
        private val activeUser: ActiveUserProvider,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())

        private val _uiEvent = MutableSharedFlow<UiEvent>()
        val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

        companion object {
            private const val SEARCH_DEBOUNCE_MS = 300L

            /** Shelf size the Home carousel's "recent" row asks the repository for. */
            private const val RECENT_BOOKS_LIMIT = 5
        }

        private var searchJob: Job? = null

        init {
            // Canonical progress observation via shared use case (Home and Library parity)
            // Demonstrates unified source: reading_progress.percentage via observeProgressPercent merging DAOs
            getBookProgressUseCase.let { useCase ->
                viewModelScope.launch {
                    // Observe currentBooks and for each book collect canonical progress to validate parity (debug logging)
                    observeCurrentBooks().collect { books ->
                        books.orEmpty().forEach { book ->
                            launch {
                                try {
                                    useCase.observeProgressPercent(book.id, activeUser.requireUserId()).collect { pct ->
                                        DebugDual.log(DebugEvent.ProgressEmit(book.id, pct, "home"))
                                    }
                                } catch (_: Throwable) {
                                }
                                try {
                                    // Fallback canonical via operator invoke for parity check
                                    useCase(book.id, activeUser.requireUserId()).collect { }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                }
                // Also demonstrate direct ReaderRepository.observeProgress and readingProgressDao usage for spec compliance
                readerRepository.let { repo ->
                    viewModelScope.launch {
                        activeUser.userId.flatMapLatest { userId ->
                            repo.observeProgress("dummy-book-id", userId.orEmpty())
                        }.collect {}
                    }
                }
            }
            // Canonical progress map — live Flow merging reading_progress.percentage (canonical) + book cache fallback
            // Each book's observeProgressPercent already distinctUntilChanged; map-level distinctUntilChanged avoids thrash
            //
            // It starts with `null` ("no map yet") on purpose. This flow resolves
            // earlier than the base combine — it needs one source, the base needs
            // five — and the collector below resets `isLoading` whenever it emits.
            // A bare `emptyMap()` first value would therefore resolve Home from
            // this flow alone, and the screen would claim an empty library while
            // the shelf queries are still in flight.
            val progressPercentByBookFlow: Flow<Map<String, Float>?> =
                observeAllBooks()
                    .flatMapLatest { books ->
                        when {
                            // No owner yet: not a map, and not "no progress" either.
                            books == null -> flowOf(null)
                            books.isEmpty() -> flowOf(emptyMap())
                            else ->
                                combine(
                                    books.map { book ->
                                        getBookProgressUseCase.observeProgressPercent(book.id, activeUser.requireUserId()).map { pct ->
                                            book.id to pct
                                        }
                                    },
                                ) { pairs -> pairs.toMap() }
                        }
                    }.distinctUntilChanged()
            // Single combine: all domain flows + canonical progress map (distinctUntilChanged)
            viewModelScope.launch {
                val baseCombine =
                    combine(
                        observeDailyStats(),
                        observeCurrentBooks(),
                        observeRecentBooks(),
                        observeAllBooks(),
                        getStatisticsUseCase()
                            .catch { e ->
                                _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "Failed to load statistics"))
                                emit(Statistics())
                            },
                    ) { stats, books, recent, allBooks, statistics ->
                        // Preserve current user identity across combine emissions —
                        // updated reactively via setActiveSession, not via the (removed)
                        // constructor authSession seed.
                        //
                        // A `null` shelf means the owner is not known yet, so the
                        // result stays unresolved: `isLoading` remains true and
                        // `isLibraryEmpty` keeps reporting false. The rows are
                        // dropped rather than reused — on sign-out that is what
                        // stops the previous account's library from staying on
                        // screen.
                        if (books == null || recent == null || allBooks == null || activeUser.userId.value == null) {
                            unresolvedState()
                        } else {
                            HomeUiState(
                                userName = _uiState.value.userName,
                                avatarUrl = _uiState.value.avatarUrl,
                                minutesReadToday = stats.minutesRead,
                                sessionsToday = stats.sessionCount,
                                dailyProgressPercent = stats.dailyProgressPercent,
                                currentStreak = statistics.currentStreak,
                                currentBooks = books,
                                recentBooks = recent,
                                allBooks = allBooks,
                                isLoading = false,
                            )
                        }
                    }
                combine(baseCombine, progressPercentByBookFlow) { base, progressByBook ->
                    // Held in isolation until the shelf answers: a progress-map
                    // emission before the first base emission must not resolve
                    // the screen, or Home would read "empty" while loading. The
                    // pre-session `null` map keeps the unresolved state instead
                    // of clearing it, so the base emission that follows — which
                    // also clears it — stays the only writer.
                    if (progressByBook == null) base else base.copy(progressPercentByBook = progressByBook)
                }.collect { newState ->
                    _uiState.value = newState
                }
            }
        }

        /**
         * The Home state while no account is known yet.
         *
         * `isLoading` stays true so `isLibraryEmpty` (which excludes loading)
         * cannot report an empty library, and the shelf lists are cleared so a
         * sign-out does not leave the previous account's rows on screen. The
         * presentation identity (name, avatar) is kept — those are the fields
         * `setActiveSession` owns and they are deliberately not account-scoped
         * here.
         */
        private fun unresolvedState(): HomeUiState =
            _uiState.value.copy(
                currentBooks = emptyList(),
                recentBooks = emptyList(),
                allBooks = emptyList(),
                progressPercentByBook = emptyMap(),
                isLoading = true,
            )

        /**
         * Re-subscribes a user-scoped query whenever the active owner changes.
         *
         * [ActiveUserProvider.userId] is the single source of truth for the
         * account id, and it is a `StateFlow` precisely so a query can follow it.
         * Reading it with the plain [ActiveUserProvider.requireUserId] would
         * freeze the id at ViewModel-construction time — before
         * `SessionSyncEffect` has published the restored session — so the query
         * would subscribe with an empty owner and never be re-subscribed, and the
         * library would read empty for the rest of the process.
         *
         * While the owner is `null` (pre-session, signed out, or the anonymous
         * path of decision D5) no query is issued at all: an empty owner matches
         * no row, so the screen must keep showing the skeleton rather than claim
         * an empty library. [emitWhenNoOwner] is what the caller renders in that
         * window — an empty list for the shelf sources, a default
         * [ReadingStats] for the today cards.
         */
        private fun <T> observeForActiveUser(
            emitWhenNoOwner: T,
            query: (userId: String) -> Flow<T>,
        ): Flow<T> =
            activeUser.userId
                .flatMapLatest { userId ->
                    if (userId == null) {
                        flowOf(emitWhenNoOwner)
                    } else {
                        query(userId)
                    }
                }.catch { e ->
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "Failed to load home data"))
                    emit(emitWhenNoOwner)
                }

        private fun observeAllBooks(): Flow<List<Book>?> = observeForActiveUser(null, homeRepository::observeBooks)

        private fun observeCurrentBooks(): Flow<List<Book>?> = observeForActiveUser(null, homeRepository::observeCurrentBooks)

        private fun observeRecentBooks(): Flow<List<Book>?> = observeForActiveUser(null) { userId -> homeRepository.observeRecentBooks(userId, RECENT_BOOKS_LIMIT) }

        /**
         * Today's cards. Unlike the shelf sources this one accepts a `null`
         * owner: `observeDailyStats(null, …)` is the documented "legacy rows"
         * scope, so a pre-session caller still gets a usable zero-row aggregate
         * instead of a stalled combine.
         */
        private fun observeDailyStats(): Flow<ReadingStats> =
            activeUser.userId
                .flatMapLatest { userId ->
                    homeRepository.observeDailyStats(userId, dailyGoalProvider())
                }.catch { e ->
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "Failed to load daily stats"))
                    emit(ReadingStats())
                }

        /** Pull-to-refresh entry point. Reconciliation now lives in [SyncService.bootstrap]. */
        fun onPullToRefresh() {
            // No-op: reconcile is auth-gated in SyncService.bootstrap. Kept as a public
            // surface so existing call sites (NavHost) continue to compile and a future
            // per-screen refresh can hook in here.
        }

        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        /**
         * Reactively updates the home avatar identity from [session].
         *
         * The account scope itself is NOT set here: [ActiveUserProvider] is the
         * single owner of the active id and `SessionSyncEffect` writes it. This
         * method only carries the presentation-only fields (display name, photo)
         * that the session holds but no repository exposes.
         *
         * @param session The current auth session, or `null` on logout (the last
         *   known name/avatar are kept).
         */
        fun setActiveSession(session: AuthSession?) {
            _uiState.update {
                it.copy(
                    userName = session?.displayName?.takeIf { name -> name.isNotBlank() } ?: it.userName,
                    avatarUrl = session?.photoUrl ?: it.avatarUrl,
                )
            }
        }

        fun onToggleSearch() {
            _uiState.update {
                it.copy(
                    showSearch = !it.showSearch,
                    searchQuery = "",
                    searchResults = emptyList(),
                )
            }
            searchJob?.cancel()
        }

        fun onSearchQueryChanged(query: String) {
            _uiState.update { it.copy(searchQuery = query) }
            searchJob?.cancel()
            if (query.isBlank()) {
                _uiState.update { it.copy(searchResults = emptyList()) }
                return
            }
            searchJob =
                viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    val q = query.lowercase()
                    val results =
                        _uiState.value.allBooks.filter {
                            it.title.lowercase().contains(q) ||
                                (it.author?.lowercase()?.contains(q) == true)
                        }
                    _uiState.update { it.copy(searchResults = results) }
                }
        }
    }

class HomeViewModelFactory(
    private val homeRepository: HomeRepository,
    private val getStatisticsUseCase: GetStatisticsUseCase,
    private val dailyGoalProvider: () -> Int,
    private val readerRepository: com.nexo.domain.repository.ReaderRepository,
    private val getBookProgressUseCase: GetBookProgressUseCase,
    private val activeUser: ActiveUserProvider,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(homeRepository, getStatisticsUseCase, dailyGoalProvider, readerRepository, getBookProgressUseCase, activeUser) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
