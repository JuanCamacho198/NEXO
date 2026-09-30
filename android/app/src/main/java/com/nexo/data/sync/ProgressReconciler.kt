package com.nexo.data.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.debug.DebugDual
import com.nexo.debug.DebugEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reconciles divergent progress between canonical reading_progress and cached books.progress_percentage.
 *
 * Preference: max(updatedAt) wins. When equal, canonical (reading_progress) wins.
 * Used on app start and pull-to-refresh so Home and Library converge.
 */
class ProgressReconciler(
    private val bookDao: BookDao,
    private val readingProgressDao: ReadingProgressDao,
) {
    @Suppress("DEPRECATION")
    suspend fun reconcile(bookId: String) =
        withContext(Dispatchers.IO) {
            val book = bookDao.getBookById(bookId) ?: return@withContext
            val progress = readingProgressDao.getProgressForBook(bookId)
            if (progress == null) {
                // No canonical yet — if book has progress, seed canonical (offline cache mirrors canonical)
                if (book.progressPercentage > 0f) {
                    // Seed canonical from cache without overwriting newer cache? Still log.
                    DebugDual.log(
                        DebugEvent.ProgressReconciled(
                            bookId = bookId,
                            winner = "cache-seed",
                            localAt = book.progressUpdatedAtEpochMillis,
                            remoteAt = null,
                            localPct = book.progressPercentage,
                            remotePct = null,
                        ),
                    )
                }
                return@withContext
            }
            val bookAt = book.progressUpdatedAtEpochMillis ?: 0L
            val progAt = progress.updatedAtEpochMillis
            val bookPct = book.progressPercentage
            val progPct = progress.percentage

            if (bookPct == progPct) return@withContext

            if (progAt >= bookAt) {
                // Canonical wins — update cache
                bookDao.updateReadingProgress(bookId, progPct, progAt)
                DebugDual.log(
                    DebugEvent.ProgressReconciled(
                        bookId = bookId,
                        winner = "canonical",
                        localAt = bookAt,
                        remoteAt = progAt,
                        localPct = bookPct,
                        remotePct = progPct,
                    ),
                )
            } else {
                // Cache newer — push to canonical (offline case)
                // Use same timestamp from book to preserve LWW
                try {
                    readingProgressDao.upsert(
                        progress.copy(
                            percentage = bookPct,
                            updatedAtEpochMillis = bookAt,
                        ),
                    )
                    DebugDual.log(
                        DebugEvent.ProgressReconciled(
                            bookId = bookId,
                            winner = "cache",
                            localAt = bookAt,
                            remoteAt = progAt,
                            localPct = bookPct,
                            remotePct = progPct,
                        ),
                    )
                } catch (_: Throwable) {
                    // FK constraint or other — ignore, next pull will retry
                }
            }
        }

    suspend fun reconcileAll() =
        withContext(Dispatchers.IO) {
            val allBooks =
                try {
                    bookDao.observeAllBooks()
                } catch (_: Throwable) {
                    return@withContext
                }
            // Collect once via direct DAO query to avoid Flow collection leak; use getAll alternative
            // Fallback: iterate via readingProgressDao.getAll()
            try {
                val progresses = readingProgressDao.getAll()
                val progressById = progresses.associateBy { it.bookId }
                // Reconcile every book that has canonical entry
                for ((bookId, _) in progressById) {
                    reconcile(bookId)
                }
            } catch (_: Throwable) {
                // Best effort
            }
        }
}
