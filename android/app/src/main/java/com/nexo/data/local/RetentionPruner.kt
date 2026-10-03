package com.nexo.data.local

import androidx.room.withTransaction

/**
 * FR-14 retention: prunes soft-deleted rows older than [thresholdMillis] and
 * then vacuums the database. Deletes run in one transaction; `VACUUM` runs
 * outside it (SQLite forbids vacuuming inside a transaction) and must be called
 * off the main thread — the WorkManager worker does this.
 */
class RetentionPruner(
    private val database: AppDatabase,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val thresholdMillis: Long = DEFAULT_THRESHOLD_MILLIS,
) {
    data class Report(
        val books: Int,
        val highlights: Int,
        val bookmarks: Int,
    ) {
        val total: Int get() = books + highlights + bookmarks
    }

    suspend fun pruneAndVacuum(): Report {
        val cutoff = clockMillis() - thresholdMillis
        val report =
            database.withTransaction {
                val dao = database.retentionDao()
                Report(
                    books = dao.pruneBooks(cutoff),
                    highlights = dao.pruneHighlights(cutoff),
                    bookmarks = dao.pruneBookmarks(cutoff),
                )
            }
        database.openHelper.writableDatabase.execSQL("VACUUM")
        return report
    }

    companion object {
        /** Soft-deleted rows older than 30 days are pruned. */
        const val DEFAULT_THRESHOLD_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}
