package com.nexo.presentation.viewmodel.reader.lifecycle

import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Contract for opening a book publication via Readium.
 *
 * Exposes loading state and epoch to prevent stale async overwrites
 * (preservation: loadEpoch must increment per open attempt).
 */
interface BookLoader {
    val isLoading: StateFlow<Boolean>
    val loadTimeMs: StateFlow<Long?>
    val loadEpoch: Long

    suspend fun open(
        bookId: String,
        filePath: String,
        format: String,
    ): Result<Pair<Publication, Locator?>>
}

interface Clearable {
    fun onCleared()
}
