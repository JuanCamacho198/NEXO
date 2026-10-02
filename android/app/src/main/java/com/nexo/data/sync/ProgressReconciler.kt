package com.nexo.data.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.debug.DebugDual
import com.nexo.debug.DebugEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Reconciles divergent progress between canonical reading_progress and the retired
 * books.progress_percentage cache.
 *
 * WU2a (storage-layout-and-sync): reading_progress.percentage is the SINGLE writer
 * target. `books.progress_percentage` is write-dead; this reconciler no longer
 * writes it. Its remaining job is the FR-10 backfill: copy any position that
 * exists only in the cache column into canonical so the column can be dropped
 * (WU2b) with zero position loss.
 *
 * Preference: max(updatedAt) wins. When equal, canonical (reading_progress) wins.
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
                // Cache-only position: seed canonical (FR-10 backfill, cache-seed direction).
                if (book.progressPercentage > 0f) {
                    try {
                        readingProgressDao.upsert(
                            ReadingProgressEntity(
                                id = "progress-$bookId",
                                bookId = bookId,
                                cfiLocation = "",
                                percentage = book.progressPercentage.coerceIn(0f, 100f),
                                updatedAtEpochMillis = book.progressUpdatedAtEpochMillis ?: book.updatedAtEpochMillis,
                            ),
                        )
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
                    } catch (_: Throwable) {
                        // FK constraint or other — next pull will retry.
                    }
                }
                return@withContext
            }
            val bookAt = book.progressUpdatedAtEpochMillis
            val progAt = progress.updatedAtEpochMillis
            val bookPct = book.progressPercentage
            val progPct = progress.percentage

            if (bookPct == progPct) return@withContext

            // WU2a correction (verify W4): a NULL cache timestamp has unknown
            // provenance, so it must not be read as "canonical is newer". When the
            // cache carries a real position and disagrees, the cache wins and is
            // backfilled; when the cache carries no position (0f), canonical is
            // left untouched. Known timestamps keep the normal LWW order.
            val canonicalWins =
                if (bookAt == null) {
                    bookPct <= 0f
                } else {
                    progAt >= bookAt
                }

            if (canonicalWins) {
                // Canonical wins. WU2a: cache write is retired — log only.
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
                // Cache newer (or unknown timestamp carrying real progress) —
                // push to canonical. Use the cache timestamp when known, else the
                // book's own updatedAt, to preserve the ordering contract.
                try {
                    readingProgressDao.upsert(
                        progress.copy(
                            percentage = bookPct,
                            updatedAtEpochMillis = bookAt ?: book.updatedAtEpochMillis,
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
            // WU2a: iterate EVERY book so cache-only positions are backfilled into
            // canonical. The previous implementation only walked canonical-backed
            // books, which would silently skip the exact rows the backfill exists for.
            val allBooks =
                try {
                    bookDao.observeAllBooks().first()
                } catch (_: Throwable) {
                    return@withContext
                }
            for (book in allBooks) {
                if (book.deletedAtEpochMillis == null) {
                    reconcile(book.id)
                }
            }
        }
}
