package com.nexo.data.repository

import android.content.Context
import com.nexo.data.local.dao.BookDao
import com.nexo.domain.model.BookStorageItem
import com.nexo.domain.repository.StorageRepository
import com.nexo.domain.sync.SyncSettleGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JVM/Android implementation of [StorageRepository].
 *
 * Measurement sums the backing file plus cover of every live book. The sweep is
 * best-effort and local-only: it scans EVERY managed book directory
 * (`books`, `covers`, `catalog`, `pdfs`, `epubs`) for files no live book
 * references — a file is referenced when it matches a live book's `file_path`
 * OR `cover_path` — skipping `*.part` in-flight downloads and anything modified
 * inside the grace window. It never touches remote/Drive bytes.
 */
class StorageRepositoryImpl(
    private val appContext: Context,
    private val bookDao: BookDao,
    private val settleGate: SyncSettleGate,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : StorageRepository {
    override suspend fun bookStorageUsage(): List<BookStorageItem> =
        withContext(Dispatchers.IO) {
            // Dedupe by absolute path: a cover that equals the backing file, or a
            // file shared by two rows, must not be counted twice. The first book
            // that references a path owns its bytes; later references count 0.
            val seenPaths = mutableSetOf<String>()
            bookDao.observeAllBooks().first().map { entity ->
                var sizeBytes = 0L
                for (path in listOfNotNull(entity.filePath, entity.coverPath)) {
                    if (path.isBlank()) continue
                    if (!seenPaths.add(path)) continue
                    sizeBytes += File(path).length()
                }
                BookStorageItem(bookId = entity.id, title = entity.title, sizeBytes = sizeBytes)
            }
        }

    override suspend fun sweepOrphanBookFiles(): Int =
        withContext(Dispatchers.IO) {
            // Never sweep mid-sync: a download may be renaming into place.
            if (!settleGate.awaitSettled()) return@withContext 0

            // A file is referenced when it is a live book's backing file OR its
            // cover. Missing either set is how files downloaded from Drive (which
            // land in `books/`) and cover images (`covers/`) previously grew
            // without bound: the sweep never scanned those directories and the
            // referenced set never contained cover paths.
            val referenced =
                bookDao
                    .observeAllBooks()
                    .first()
                    .flatMap { listOfNotNull(it.filePath, it.coverPath) }
                    .toSet()
            val cutoff = nowMillis() - IN_FLIGHT_GRACE_MS

            var deleted = 0
            for (dir in managedBookDirs()) {
                if (!dir.isDirectory) continue
                for (candidate in dir.listFiles().orEmpty()) {
                    if (!candidate.isFile) continue
                    if (candidate.name.endsWith(PART_SUFFIX)) continue
                    if (candidate.absolutePath in referenced) continue
                    if (candidate.lastModified() > cutoff) continue
                    if (candidate.delete()) deleted++
                }
            }
            deleted
        }

    private fun managedBookDirs(): List<File> =
        listOf(BOOKS_DIR, COVERS_DIR, CATALOG_DIR, PDFS_DIR, EPUBS_DIR)
            .map { File(appContext.filesDir, it) }

    companion object {
        /** A file modified within this window may still be an in-flight import. */
        const val IN_FLIGHT_GRACE_MS = 5 * 60 * 1000L

        private const val PART_SUFFIX = ".part"
        private const val BOOKS_DIR = "books"
        private const val COVERS_DIR = "covers"
        private const val CATALOG_DIR = "catalog"
        private const val PDFS_DIR = "pdfs"
        private const val EPUBS_DIR = "epubs"
    }
}
