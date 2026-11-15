package com.nexo.presentation.viewmodel.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the sleep timer state machine used by [ReaderViewModel].
 *
 * Creates a countdown timer (normal mode) or waits for chapter change (EOC mode).
 * Exposes [state] as an observable [StateFlow] that the ViewModel re-exports
 * as `sleepTimerUiState` (the single source of truth since SDD
 * reader-uiState-cleanup S7 deleted the aggregate).
 *
 * @param scope CoroutineScope for lifecycle-aware timer job (e.g. viewModelScope)
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null

    companion object {
        /** Sentinel value signalling end-of-chapter mode. */
        const val END_OF_CHAPTER = Int.MIN_VALUE
    }

    /**
     * Start the sleep timer.
     *
     * - [minutes] = [END_OF_CHAPTER] activates end-of-chapter mode (timer fires on chapter change).
     * - [minutes] > 0 starts a countdown that fires after [minutes] minutes.
     * - [minutes] <= 0 (except END_OF_CHAPTER) is a no-op.
     *
     * Calling again while a timer is running cancels the previous timer first.
     */
    fun startTimer(minutes: Int) {
        val isEoc = minutes == END_OF_CHAPTER

        // Cancel any previous timer
        timerJob?.cancel()
        timerJob = null

        if (isEoc) {
            _state.update {
                SleepTimerState(
                    isActive = true,
                    isEndOfChapter = true,
                )
            }
            return
        }

        if (minutes <= 0) return

        _state.update {
            SleepTimerState(
                isActive = true,
                remainingSecs = minutes * 60,
                presetMinutes = minutes,
            )
        }

        timerJob =
            scope.launch {
                while (isActive && _state.value.remainingSecs > 0) {
                    delay(1000L)
                    val remaining = _state.value.remainingSecs - 1
                    if (remaining <= 0) {
                        _state.value = SleepTimerState(isFinished = true)
                    } else {
                        _state.update { it.copy(remainingSecs = remaining) }
                    }
                }
            }
    }

    /**
     * Cancel the timer and reset all state to IDLE.
     * Safe to call when already idle (no-op).
     */
    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _state.value = SleepTimerState()
    }

    /**
     * Dismiss the finished overlay.
     * Only transitions from FINISHED to IDLE; no-op in any other state.
     */
    fun dismissOverlay() {
        if (_state.value.isFinished) {
            _state.value = SleepTimerState()
        }
    }

    /**
     * Notify the manager that a chapter change occurred.
     * Only triggers in end-of-chapter mode; no-op otherwise.
     */
    fun onChapterChanged() {
        if (_state.value.isEndOfChapter) {
            _state.value = SleepTimerState(isFinished = true)
        }
    }

    /**
     * Format seconds as "M:SS" for display.
     * Returns "0:00" for negative values.
     */
    fun formatRemaining(secs: Int): String {
        if (secs < 0) return "0:00"
        val minutes = secs / 60
        val seconds = secs % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
