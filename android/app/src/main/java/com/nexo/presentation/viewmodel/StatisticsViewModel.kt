package com.nexo.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.presentation.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI state for the Statistics screen.
 */
data class StatisticsUiState(
    val isLoading: Boolean = true,
    val totalMinutesRead: Long = 0L,
    val currentStreak: Int = 0,
    val booksRead: Int = 0,
    val weeklyActivity: List<com.nexo.domain.model.DailyReadingActivity> = emptyList(),
    val goalProgress: Float = 0f,
    val favoriteGenres: List<String> = emptyList(),
    val errorMessage: String? = null,
)

@HiltViewModel
class StatisticsViewModel
    @Inject
    constructor(
        private val getStatisticsUseCase: GetStatisticsUseCase,
    ) : ViewModel() {
        private val _uiEvent = MutableSharedFlow<UiEvent>()
        val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

        val uiState: StateFlow<StatisticsUiState> =
            getStatisticsUseCase()
                .map { statistics ->
                    StatisticsUiState(
                        isLoading = false,
                        totalMinutesRead = statistics.totalMinutesRead,
                        currentStreak = statistics.currentStreak,
                        booksRead = statistics.booksRead,
                        weeklyActivity = statistics.weeklyActivity,
                        goalProgress = statistics.goalProgress,
                        favoriteGenres = statistics.favoriteGenres,
                    )
                }.catch { error ->
                    _uiEvent.emit(UiEvent.ShowSnackbar(error.message ?: "Failed to load statistics"))
                    emit(StatisticsUiState(isLoading = false, errorMessage = error.message))
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = StatisticsUiState(),
                )

        fun refresh() {
            getStatisticsUseCase.refresh()
        }
    }

class StatisticsViewModelFactory(
    private val getStatisticsUseCase: GetStatisticsUseCase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            return StatisticsViewModel(getStatisticsUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
