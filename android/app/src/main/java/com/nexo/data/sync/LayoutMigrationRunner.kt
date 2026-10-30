package com.nexo.data.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.StoredBookPath
import java.io.File

/**
 * Wires [LayoutMigration] to the Room `books.file_path` column: reads the raw
 * stored paths, runs the migration, persists the rewrite in one transaction,
 * and returns the typed outcome.
 *
 * The migration is device-wide (it moves files on disk), so it walks every
 * account that owns a stored path. Each account gets its own run, scoped to its
 * own subtree, so a migration can never move a file into or out of another
 * account's directory. Each rewrite is still scoped to its owner so the
 * statement can never touch another account's row.
 */
object LayoutMigrationRunner {
    /**
     * Reserved segment for the no-owner pass, which only creates directories and
     * the version marker. It keeps that pass out of any real account's subtree.
     */
    private const val NO_OWNER_SEGMENT: String = "_no_owner"

    suspend fun run(
        bookDao: BookDao,
        filesDir: File,
        cacheDir: File,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): LayoutMigrationResult {
        val owners = bookDao.distinctStoredPathOwners()
        if (owners.isEmpty()) {
            // Fresh install with no rows: still create the marker and staging dir.
            return LayoutMigration(filesDir, cacheDir, nowMillis)
                .migrate(NO_OWNER_SEGMENT, emptyList()) {}
        }
        var lastResult: LayoutMigrationResult? = null
        for (owner in owners) {
            val rows = bookDao.allStoredPaths(owner)
            val migration = LayoutMigration(filesDir, cacheDir, nowMillis)
            lastResult =
                migration.migrate(owner, rows.map { it.filePath }) { mapping ->
                    val rewritten =
                        rows
                            .filter { mapping.containsKey(it.filePath) }
                            .map { StoredBookPath(it.id, mapping.getValue(it.filePath)) }
                    if (rewritten.isNotEmpty()) bookDao.rewriteFilePaths(owner, rewritten)
                }
        }
        return lastResult ?: LayoutMigration(filesDir, cacheDir, nowMillis)
            .migrate(NO_OWNER_SEGMENT, emptyList()) {}
    }
}
