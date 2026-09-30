package com.nexo.presentation.viewmodel.reader.lifecycle

import android.util.Log
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.presentation.viewmodel.reader.ReaderLifecycleState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records reading time and session with 60s ticker.
 * Preserves verbatim: READING_TIME_TICK_MS, activeUserId, flushReadingTime,
 * and onCleared() cancellation via injected CoroutineScope.
 */
class ReadingSessionRecorder(
    private val state: MutableStateFlow<ReaderLifecycleState>,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val readingStatsRepository: ReadingStatsRepository,
) : Clearable {
    @Volatile
    var activeUserId: String = ""
        private set

    private var readingTimeTickerJob: Job? = null
    private var sessionStartTime: Long = 0L

    companion object {
        private const val TAG = "ReadingSessionRecorder"
        private const val READING_TIME_TICK_MS = 60_000L
        private const val MILLIS_PER_MINUTE = 60_000L
    }

    fun setActiveUserId(userId: String) {
        activeUserId = userId
    }

    fun onReaderOpened() {
        if (sessionStartTime > 0L) return
        sessionStartTime = System.currentTimeMillis()
        readingTimeTickerJob?.cancel()
        readingTimeTickerJob =
            scope.launch(mainDispatcher) {
                while (isActive) {
                    delay(READING_TIME_TICK_MS)
                    flushReadingTime(minimumMinutes = 1L)
                }
            }
    }

    fun onReaderPaused() {
        readingTimeTickerJob?.cancel()
        readingTimeTickerJob = null
        flushReadingTime(minimumMinutes = 1L)
    }

    fun onReaderBackgrounded() {
        onReaderPaused()
    }

    override fun onCleared() {
        onReaderPaused()
    }

    private fun flushReadingTime(minimumMinutes: Long = 0L) {
        val bookId = state.value.selectedBookId ?: return
        if (sessionStartTime <= 0L) return
        val now = System.currentTimeMillis()
        val elapsedMs = now - sessionStartTime
        val computedMinutes = elapsedMs / MILLIS_PER_MINUTE
        val additionalMinutes =
            if (minimumMinutes > 0L) {
                computedMinutes.coerceAtLeast(minimumMinutes)
            } else {
                computedMinutes
            }
        if (additionalMinutes <= 0L) return
        val intervalStart = sessionStartTime
        scope.launch(mainDispatcher) {
            readingStatsRepository.updateReadingTime(bookId, additionalMinutes)
            readingStatsRepository.recordReadingSession(
                bookId = bookId,
                startTimeEpochMillis = intervalStart,
                durationMinutes = additionalMinutes.toInt(),
                userId = activeUserId,
            )
            Log.d(TAG, "Recorded $additionalMinutes minutes for book $bookId")
        }
        sessionStartTime = now
    }
}
