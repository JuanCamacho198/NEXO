package com.nexo.domain.notification

import kotlinx.coroutines.flow.StateFlow

/**
 * Global "the reader is open" signal.
 *
 * Policy rule 3 silences every interruptive surface while the reader is open
 * (the tray still records). The reader lifecycle (`ReaderLifecycleStateHolder`)
 * sets this true while a book is displayed and clears it when the reader
 * closes, so any notification emitted from elsewhere in the app respects the
 * reader's focus without each emit site knowing about the reader.
 *
 * Small and process-wide by design: a `StateFlow<Boolean>` nothing else reads.
 */
object ReadingActivity {
    private val _isReading = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True while a book is open in the reader. */
    val isReading: StateFlow<Boolean> = _isReading

    /** Convenience snapshot for the policy input (avoids a collection at emit time). */
    val isReadingNow: Boolean get() = _isReading.value

    fun setReading(value: Boolean) {
        _isReading.value = value
    }
}
