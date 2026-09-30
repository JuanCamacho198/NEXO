package com.nexo.domain.usecase

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.domain.repository.ReaderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Shared use case for canonical reading progress.
 *
 * Both HomeViewModel and LibraryViewModel collect this flow so Home "Continuar"
 * and Library BookCard always show the same percentage for the same bookId.
 * Source of truth is reading_progress.percentage (canonical); books.progress_percentage
 * is derived cache and deprecated for UI reads.
 *
 * Exposes [observeProgressPercent] merging readingProgressDao.observeProgressForBook
 * and bookDao progress, canonical reading_progress.percentage wins.
 */
class GetBookProgressUseCase(
    private val readerRepository: ReaderRepository,
    private val readingProgressDao: ReadingProgressDao? = null,
    private val bookDao: BookDao? = null,
) {
    /**
     * Observe canonical progress percentage for a bookId.
     * Emits distinct values to avoid UI thrash.
     * Uses ReaderRepository (canonical) when DAOs not wired.
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
     * Canonical progress percent merging readingProgressDao and bookDao.
     * reading_progress.percentage is canonical; bookDao progress is fallback cache.
     */
    @Suppress("DEPRECATION")
    fun observeProgressPercent(bookId: String): Flow<Float> {
        val dao = readingProgressDao
        val bDao = bookDao
        if (dao == null || bDao == null) {
            return invoke(bookId)
        }
        val canonicalFlow: Flow<Float?> = dao.observeProgressForBook(bookId).map { it?.percentage }
        val bookFlow: Flow<Float?> = bDao.observeBookById(bookId).map { it?.progressPercentage }
        return combine(canonicalFlow, bookFlow) { canonical, bookPct ->
            canonical ?: bookPct ?: 0f
        }.distinctUntilChanged()
    }
}
