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
                // Retention is a device-wide background job, but each delete must
                // stay inside one account: `prune*(userId, ...)` scopes every
                // statement, and the owners are enumerated explicitly so a bug in
                // one account's rows can never remove another's.
                var books = 0
                var highlights = 0
                var bookmarks = 0
                for (owner in dao.distinctPrunableOwners()) {
                    books += dao.pruneBooks(owner, cutoff)
                    highlights += dao.pruneHighlights(owner, cutoff)
                    bookmarks += dao.pruneBookmarks(owner, cutoff)
                }
                Report(books = books, highlights = highlights, bookmarks = bookmarks)
            }
        database.openHelper.writableDatabase.execSQL("VACUUM")
        return report
    }

    companion object {
        /** Soft-deleted rows older than 30 days are pruned. */
        const val DEFAULT_THRESHOLD_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}
