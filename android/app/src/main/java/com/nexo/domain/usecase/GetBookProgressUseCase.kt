package com.nexo.domain.usecase

import com.nexo.domain.repository.ReaderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Shared use case for canonical reading progress.
 *
 * Both HomeViewModel and LibraryViewModel collect this flow so Home "Continuar"
 * and Library BookCard always show the same percentage for the same bookId.
 * `reading_progress.percentage` is the single source of truth; the retired
 * `books.progress_percentage` cache column was dropped in WU2b, so there is no
 * cache fallback to merge.
 */
class GetBookProgressUseCase(
    private val readerRepository: ReaderRepository,
) {
    /**
     * Observe canonical progress percentage for a bookId.
     * Emits distinct values to avoid UI thrash.
     */
    operator fun invoke(bookId: String): Flow<Float> =
        readerRepository
            .observeProgress(bookId)
            .map { it?.percentage ?: 0f }
            .distinctUntilChanged()

    /**
     * Observe full ReadingProgress (includes locatorJson/cfi) if needed.
     */
    fun observeProgress(bookId: String) = readerRepository.observeProgress(bookId)

    /**
     * Canonical progress percent from `reading_progress.percentage`.
     */
    fun observeProgressPercent(bookId: String): Flow<Float> = invoke(bookId)
}
