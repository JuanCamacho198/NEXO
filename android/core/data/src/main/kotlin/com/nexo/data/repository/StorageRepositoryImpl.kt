package com.nexo.data.repository

import android.content.Context
import com.nexo.data.local.dao.BookDao
import com.nexo.data.storage.AccountStorageLayout
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
 * best-effort and local-only: it scans the active account's own managed
 * directories (`books/{userId}`, `covers/{userId}`, `pdfs/{userId}`,
 * `epubs/{userId}` — resolved by [AccountStorageLayout]) for files no live book
 * of that account references — a file is referenced when it matches the
 * account's `file_path` OR `cover_path`. It skips `*.part` in-flight downloads
 * and anything modified inside the grace window.
 *
 * The sweep never leaves the account's subtree (T9): a file another account
 * still needs is kept, and this account's sweep can neither delete nor be
 * steered by another account's files.
 */
class StorageRepositoryImpl(
    private val appContext: Context,
    private val bookDao: BookDao,
    private val settleGate: SyncSettleGate,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : StorageRepository {
    private val layout = AccountStorageLayout(appContext.filesDir)

    override suspend fun bookStorageUsage(userId: String): List<BookStorageItem> =
        withContext(Dispatchers.IO) {
            // Dedupe by absolute path: a cover that equals the backing file, or a
            // file shared by two rows, must not be counted twice. The first book
            // that references a path owns its bytes; later references count 0.
            val seenPaths = mutableSetOf<String>()
            bookDao.observeAllBooks(userId).first().map { entity ->
                var sizeBytes = 0L
                for (path in listOfNotNull(entity.filePath, entity.coverPath)) {
                    if (path.isBlank()) continue
                    if (!seenPaths.add(path)) continue
                    sizeBytes += File(path).length()
                }
                BookStorageItem(bookId = entity.id, title = entity.title, sizeBytes = sizeBytes)
            }
        }

    override suspend fun sweepOrphanBookFiles(userId: String): Int =
        withContext(Dispatchers.IO) {
            // Never sweep mid-sync: a download may be renaming into place.
            if (!settleGate.awaitSettled()) return@withContext 0

            // A file is referenced when it is a live book's backing file OR its
            // cover. Missing either set is how files downloaded from Drive (which
            // land in `books/{userId}/`) and cover images (`covers/{userId}/`)
            // previously grew without bound: the sweep never scanned those
            // directories and the referenced set never contained cover paths.
            //
            // Only the active user's books keep a file alive. Another account's
            // rows are not consulted, so this account's sweep can never preserve
            // (or be steered by) someone else's files.
            val referenced =
                bookDao
                    .observeAllBooks(userId)
                    .first()
                    .flatMap { listOfNotNull(it.filePath, it.coverPath) }
                    .toSet()
            val cutoff = nowMillis() - IN_FLIGHT_GRACE_MS

            var deleted = 0
            // T9: walk only this account's subtree. A shared tree would let the
            // walk delete a file another account still references.
            for (dir in layout.managedDirs(userId)) {
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

    companion object {
        /** A file modified within this window may still be an in-flight import. */
        const val IN_FLIGHT_GRACE_MS = 5 * 60 * 1000L

        private const val PART_SUFFIX = ".part"
    }
}
