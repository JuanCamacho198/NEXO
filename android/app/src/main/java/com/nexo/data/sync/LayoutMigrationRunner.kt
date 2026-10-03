package com.nexo.data.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.StoredBookPath
import java.io.File

/**
 * Wires [LayoutMigration] to the Room `books.file_path` column: reads the raw
 * stored paths, runs the migration, persists the rewrite in one transaction,
 * and returns the typed outcome.
 */
object LayoutMigrationRunner {
    suspend fun run(
        bookDao: BookDao,
        filesDir: File,
        cacheDir: File,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): LayoutMigrationResult {
        val rows = bookDao.allStoredPaths()
        val migration = LayoutMigration(filesDir, cacheDir, nowMillis)
        return migration.migrate(rows.map { it.filePath }) { mapping ->
            val rewritten =
                rows
                    .filter { mapping.containsKey(it.filePath) }
                    .map { StoredBookPath(it.id, mapping.getValue(it.filePath)) }
            if (rewritten.isNotEmpty()) bookDao.rewriteFilePaths(rewritten)
        }
    }
}
