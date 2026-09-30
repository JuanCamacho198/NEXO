package com.nexo.presentation.viewmodel.reader

/**
 * State of the sleep timer state machine.
 *
 * Transitions:
 *   IDLE (default) → startTimer(N) → COUNTING
 *   IDLE (default) → startTimer(MIN_VALUE) → COUNTING(EOC)
 *   COUNTING → tick reaches 0 → FINISHED
 *   COUNTING(EOC) → onChapterChanged() → FINISHED
 *   COUNTING → cancel() → IDLE
 *   FINISHED → dismissOverlay() → IDLE
 */
data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingSecs: Int = 0,
    val isFinished: Boolean = false,
    val isEndOfChapter: Boolean = false,
    val presetMinutes: Int? = null,
)

/**
 * Sleep-timer slice of the reader facade split (SDD reader-facade-split, slice 3).
 * Single owner: only [SleepTimerManager] holds the MutableStateFlow and
 * mutating funs; the VM re-exports it as `sleepTimerUiState`.
 */
typealias SleepTimerUiState = SleepTimerState
