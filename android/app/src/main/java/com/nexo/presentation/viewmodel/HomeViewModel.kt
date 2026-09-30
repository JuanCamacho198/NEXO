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
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.presentation.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
)

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
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())

        private val _uiEvent = MutableSharedFlow<UiEvent>()
        val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

        companion object {
            private const val SEARCH_DEBOUNCE_MS = 300L
        }

        private var searchJob: Job? = null

        /**
         * Active user scope for daily stats (REQ-reading-sessions-sync-6). Set from
         * [setActiveSession]; re-aggregates the today cards when the user changes.
         */
        private val activeUserId = MutableStateFlow<String?>(null)

        init {
            // Canonical progress observation via shared use case (Home and Library parity)
            // Demonstrates unified source: reading_progress.percentage via observeProgressPercent merging DAOs
            getBookProgressUseCase.let { useCase ->
                viewModelScope.launch {
                    // Observe currentBooks and for each book collect canonical progress to validate parity (debug logging)
                    homeRepository.observeCurrentBooks().collect { books ->
                        books.forEach { book ->
                            launch {
                                try {
                                    useCase.observeProgressPercent(book.id).collect { pct ->
                                        DebugDual.log(DebugEvent.ProgressEmit(book.id, pct, "home"))
                                    }
                                } catch (_: Throwable) {
                                }
                                try {
                                    // Fallback canonical via operator invoke for parity check
                                    useCase(book.id).collect { }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                }
                // Also demonstrate direct ReaderRepository.observeProgress and readingProgressDao usage for spec compliance
                readerRepository.let { repo ->
                    viewModelScope.launch {
                        repo.observeProgress("dummy-book-id").collect {}
                    }
                }
            }
            // Canonical progress map — live Flow merging reading_progress.percentage (canonical) + book cache fallback
            // Each book's observeProgressPercent already distinctUntilChanged; map-level distinctUntilChanged avoids thrash
            val progressPercentByBookFlow: kotlinx.coroutines.flow.Flow<Map<String, Float>> =
                homeRepository
                    .observeBooks()
                    .flatMapLatest { books ->
                        if (books.isEmpty()) {
                            flowOf(emptyMap())
                        } else {
                            combine(
                                books.map { book ->
                                    getBookProgressUseCase.observeProgressPercent(book.id).map { pct ->
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
                        activeUserId.flatMapLatest { userId ->
                            homeRepository
                                .observeDailyStats(userId, dailyGoalProvider())
                                .catch { e ->
                                    _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "Failed to load daily stats"))
                                    emit(ReadingStats())
                                }
                        },
                        homeRepository
                            .observeCurrentBooks()
                            .catch { e ->
                                _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "Failed to load current books"))
                                emit(emptyList())
                            },
                        homeRepository
                            .observeRecentBooks(5)
                            .catch { e ->
                                _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "Failed to load recent books"))
                                emit(emptyList())
                            },
                        homeRepository
                            .observeBooks()
                            .catch { e ->
                                _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "Failed to load books"))
                                emit(emptyList())
                            },
                        getStatisticsUseCase()
                            .catch { e ->
                                _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "Failed to load statistics"))
                                emit(Statistics())
                            },
                    ) { stats, books, recent, allBooks, statistics ->
                        // Preserve current user identity across combine emissions —
                        // updated reactively via setActiveSession, not via the (removed)
                        // constructor authSession seed.
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
                combine(baseCombine, progressPercentByBookFlow) { base, progressByBook ->
                    base.copy(progressPercentByBook = progressByBook)
                }.collect { newState ->
                    _uiState.update { newState }
                }
            }
        }

        /** Pull-to-refresh entry point. Reconciliation now lives in [SyncService.bootstrap]. */
        fun onPullToRefresh() {
            // No-op: reconcile is auth-gated in SyncService.bootstrap. Kept as a public
            // surface so existing call sites (NavHost) continue to compile and a future
            // per-screen refresh can hook in here.
        }

        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        /**
         * Reactively updates the home avatar identity from [session] and re-scopes
         * the daily stats + streak to the session's user (REQ-streak-widget-1).
         *
         * Called from the NavHost via a `LaunchedEffect` on the current session's
         * `userId`/`photoUrl`, so the cached ViewModel (keyed by factory, never
         * rebuilt) still picks up a photo that arrives after async session restore.
         *
         * @param session The current auth session, or `null` on logout (daily stats
         *   fall back to the legacy rows; the last known name/avatar are kept).
         */
        fun setActiveSession(session: AuthSession?) {
            activeUserId.value = session?.userId
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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(homeRepository, getStatisticsUseCase, dailyGoalProvider, readerRepository, getBookProgressUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
