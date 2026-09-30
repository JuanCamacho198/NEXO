package com.nexo.data.remote.sync

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.remote.supabase.BookmarkRow
import com.nexo.data.remote.supabase.HighlightRow
import com.nexo.data.remote.supabase.ReadingProgressRow
import com.nexo.data.remote.supabase.ReadingSessionRow
import com.nexo.data.remote.supabase.SupabaseBookCatalogDataSource
import com.nexo.data.remote.supabase.SupabaseProgressDataSource
import com.nexo.data.remote.supabase.UserBookRow
import com.nexo.data.session.SessionManager
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DriveColdBackupService — cold backup on demand only (PR3).
 *
 * Reuses [StorageSyncRemoteDataSource] primitives (parents only on create,
 * PATCH without parents keeps 403 parents fix). Export/import are Settings-only;
 * hot save/open never touches Drive.
 *
 * Cold backup file lives as `books/{userId}/nextpage_cold_backup.json`
 * (physical `nextpage_cold_backup.json` inside `NextPage/Books`). JSON+bin shape
 * mirrors desktop parity; bins are book files already in Drive — JSON carries
 * metadata for FK-order restore.
 *
 * Import is FK-ordered `books→progress→highlights→bookmarks→sessions`
 * chunk 100 idempotent (onConflict). Backfill reuses same path.
 */
class DriveColdBackupService(
    private val remoteDataSource: StorageSyncRemoteDataSource,
    private val bookDao: BookDao,
    private val readingProgressDao: ReadingProgressDao,
    private val highlightDao: HighlightDao,
    private val bookmarkDao: BookmarkDao,
    private val readingSessionDao: ReadingSessionDao,
    private val bookCatalogDataSource: SupabaseBookCatalogDataSource = SupabaseBookCatalogDataSource(),
    private val progressDataSource: SupabaseProgressDataSource = SupabaseProgressDataSource(),
    private val sessionManager: SessionManager,
    private val gson: Gson = Gson(),
) {
    companion object {
        // Drive-level filename shared with desktop: deliberately NOT rebranded, so existing
        // Drive backups remain findable.
        const val COLD_BACKUP_FILE = "nextpage_cold_backup.json"
        const val CHUNK_SIZE = 100

        fun coldBackupPath(userId: String) = "books/$userId/$COLD_BACKUP_FILE"

        private fun nowIso(): String =
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.US,
            ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

        private fun millisToIso(millis: Long): String =
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.US,
            ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
    }

    data class ColdBackupJson(
        val version: Int = 1,
        @SerializedName("exported_at") val exportedAt: Long,
        val books: List<UserBookRow> = emptyList(),
        val progress: List<ReadingProgressRow> = emptyList(),
        val highlights: List<HighlightRow> = emptyList(),
        val bookmarks: List<BookmarkRow> = emptyList(),
        val sessions: List<ReadingSessionRow> = emptyList(),
    )

    private suspend fun hasLiveSession(): Boolean = sessionManager.getCurrentSession().getOrNull() != null

    /**
     * Export local Room state to Drive as single JSON.
     * Gated only by Drive auth (remoteDataSource will throw on failure);
     * callers (Settings) surface the error. Never called from hot save.
     */
    suspend fun exportColdBackup(userId: String): Result<Unit> =
        runCatching {
            val books = bookDao.observeAllBooks().first()
            val highlights = highlightDao.observeAllHighlights().first()
            val bookmarks = bookmarkDao.observeAllBookmarks().first()
            val sessions = readingSessionDao.getAll()
            // Progress: one row per book via DAO list or per-book lookup
            val progressEntities = readingProgressDao.getAll()

            val bookRows =
                books.map { e ->
                    UserBookRow(
                        id = e.id,
                        userId = userId,
                        title = e.title,
                        author = e.author,
                        format = e.format,
                        contentHash = e.contentHash,
                        filePath = null,
                        coverUrl = null,
                        description = e.description,
                        totalPages = e.totalPages,
                        importedAt = millisToIso(e.updatedAtEpochMillis),
                        updatedAt = millisToIso(e.updatedAtEpochMillis),
                        lifecycle = e.remoteLifecycle.ifEmpty { "available" },
                        catalogVersion = maxOf(1L, e.remoteCatalogVersion),
                        remoteProvider = e.remoteProvider,
                        remoteFileId = e.remoteFileId,
                        remotePath = e.remotePath,
                    )
                }

            val progressRows =
                progressEntities.map { e ->
                    ReadingProgressRow(
                        userId = userId,
                        bookId = e.bookId,
                        cfiLocation = e.cfiLocation,
                        percentage = e.percentage.toDouble(),
                        locatorJson = e.locatorJson,
                        updatedAt = millisToIso(e.updatedAtEpochMillis),
                        version = 1,
                    )
                }

            val highlightRows =
                highlights.map { e ->
                    HighlightRow(
                        userId = userId,
                        bookId = e.bookId,
                        cfiRange = e.cfiRange,
                        textContent = e.textContent,
                        note = e.note,
                        color = e.color,
                        type = e.type,
                        locatorJson = e.locatorJson,
                        deletedAt = e.deletedAtEpochMillis?.let { millisToIso(it) },
                        updatedAt = millisToIso(e.updatedAtEpochMillis),
                        id = e.id,
                    )
                }

            val bookmarkRows =
                bookmarks.map { e ->
                    BookmarkRow(
                        userId = userId,
                        bookId = e.bookId,
                        cfiLocation = e.cfiLocation,
                        titleSnippet = e.titleOrSnippet,
                        locatorJson = e.locatorJson,
                        deletedAt = e.deletedAtEpochMillis?.let { millisToIso(it) },
                        updatedAt = millisToIso(e.updatedAtEpochMillis),
                        id = e.id,
                    )
                }

            val sessionRows =
                sessions.map { e ->
                    ReadingSessionRow(
                        id = e.id,
                        userId = e.userId.ifEmpty { userId },
                        bookId = e.bookId,
                        startedAt = millisToIso(e.startTimeEpochMillis),
                        durationMinutes = e.durationMinutes,
                        date = millisToIso(e.date),
                        device = "android",
                        updatedAt = millisToIso(if (e.updatedAtEpochMillis == 0L) System.currentTimeMillis() else e.updatedAtEpochMillis),
                        startPercentage = null,
                        endPercentage = null,
                    )
                }

            val backup =
                ColdBackupJson(
                    exportedAt = System.currentTimeMillis(),
                    books = bookRows,
                    progress = progressRows,
                    highlights = highlightRows,
                    bookmarks = bookmarkRows,
                    sessions = sessionRows,
                )
            val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
            // Reuses GoogleDriveStorageRemoteDataSource upload which sends parents only on create,
            // PATCH without parents (403 fix) — drive.file scope.
            remoteDataSource.upload(coldBackupPath(userId), jsonBytes)
        }

    /**
     * Import cold backup JSON from Drive, FK-order chunk 100 idempotent.
     * Gated by hasLiveSession — no request fires without live session.
     */
    suspend fun importColdBackup(userId: String): Result<ImportResult> {
        return runCatching {
            if (!hasLiveSession()) return@runCatching ImportResult(0, 0, 0, 0, 0)
            val bytes = remoteDataSource.download(coldBackupPath(userId))
            val json = String(bytes, Charsets.UTF_8)
            val backup = gson.fromJson(json, ColdBackupJson::class.java)
            importInFkOrder(backup, userId)
        }
    }

    /**
     * One-shot Drive→Supabase backfill, FK-order chunk 100 idempotent, gated on first login.
     * Reuses Drive catalog read: delegates to importColdBackup when cold file exists,
     * otherwise falls back to legacy BookStateJson files listing (no FK errors).
     */
    suspend fun backfillFromDrive(userId: String): Result<ImportResult> {
        return runCatching {
            if (!hasLiveSession()) return@runCatching ImportResult(0, 0, 0, 0, 0)
            // Prefer cold backup; fallback to legacy per-book state.json scan for migrated users
            val coldResult = importColdBackup(userId).getOrNull()
            if (coldResult != null && coldResult.totalImported > 0) return@runCatching coldResult
            // Legacy fallback: list Drive state files prefix and aggregate BookStateJson
            val legacy = collectLegacyDriveState(userId)
            if (legacy != null) importInFkOrder(legacy, userId) else ImportResult(0, 0, 0, 0, 0)
        }
    }

    private suspend fun collectLegacyDriveState(userId: String): ColdBackupJson? {
        return try {
            val prefix = "books/$userId/"
            val paths = remoteDataSource.list(prefix)
            val statePaths = paths.filter { it.endsWith("/state.json") || it.endsWith("_state.json") }
            if (statePaths.isEmpty()) return null
            val gsonLocal = gson
            val bookRows = mutableListOf<UserBookRow>()
            val progressRows = mutableListOf<ReadingProgressRow>()
            val highlightRows = mutableListOf<HighlightRow>()
            val bookmarkRows = mutableListOf<BookmarkRow>()
            // BookStateJson parsing is best-effort; delegate to GoogleDriveJsonStateSync schema
            for (p in statePaths) {
                try {
                    val bytes = remoteDataSource.download(p)
                    val json = String(bytes, Charsets.UTF_8)
                    val state = gsonLocal.fromJson(json, com.nexo.data.remote.sync.BookStateJson::class.java)
                    state.progress?.let { pr ->
                        progressRows.add(
                            ReadingProgressRow(
                                userId = userId,
                                bookId = pr.bookId,
                                cfiLocation = pr.cfiLocation,
                                percentage = pr.percentage.toDouble(),
                                updatedAt = millisToIso(pr.updatedAtEpochMillis),
                                version = 1,
                            ),
                        )
                    }
                    state.highlights.forEach { h ->
                        highlightRows.add(
                            HighlightRow(
                                userId = userId,
                                bookId = h.bookId,
                                cfiRange = h.cfiRange,
                                textContent = h.textContent,
                                note = h.note,
                                color = h.color,
                                updatedAt = millisToIso(h.updatedAtEpochMillis),
                                deletedAt = h.deletedAtEpochMillis?.let { millisToIso(it) },
                                id = h.id,
                            ),
                        )
                    }
                    state.bookmarks.forEach { b ->
                        bookmarkRows.add(
                            BookmarkRow(
                                userId = userId,
                                bookId = b.bookId,
                                cfiLocation = b.cfiLocation,
                                titleSnippet = b.titleOrSnippet,
                                updatedAt = millisToIso(b.updatedAtEpochMillis),
                                deletedAt = b.deletedAtEpochMillis?.let { millisToIso(it) },
                                id = b.id,
                            ),
                        )
                    }
                    // Derive stub book rows from state file names when catalog missing
                    val bookId = p.substringAfterLast('/').removeSuffix("_state.json").removeSuffix("/state.json")
                    if (bookId.isNotBlank() && bookRows.none { it.id == bookId }) {
                        bookRows.add(
                            UserBookRow(
                                id = bookId,
                                userId = userId,
                                title = bookId,
                                format = "epub",
                                importedAt = nowIso(),
                                updatedAt = nowIso(),
                            ),
                        )
                    }
                } catch (_: Exception) {
                    // skip corrupt file
                }
            }
            if (progressRows.isEmpty() && highlightRows.isEmpty() && bookmarkRows.isEmpty()) {
                null
            } else {
                ColdBackupJson(
                    exportedAt = System.currentTimeMillis(),
                    books = bookRows,
                    progress = progressRows,
                    highlights = highlightRows,
                    bookmarks = bookmarkRows,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun importInFkOrder(
        backup: ColdBackupJson,
        userId: String,
    ): ImportResult {
        var b = 0
        var p = 0
        var h = 0
        var bm = 0
        var s = 0
        // 1. books first (FK parent)
        for (chunk in backup.books.chunked(CHUNK_SIZE)) {
            for (row in chunk) {
                try {
                    bookCatalogDataSource.upsertBook(row.copy(userId = userId))
                    b++
                } catch (_: Exception) {
                }
            }
        }
        // 2. progress (depends on books)
        for (chunk in backup.progress.chunked(CHUNK_SIZE)) {
            for (row in chunk) {
                try {
                    progressDataSource.upsertProgress(row.copy(userId = userId))
                    p++
                } catch (_: Exception) {
                }
            }
        }
        // 3. highlights
        for (chunk in backup.highlights.chunked(CHUNK_SIZE)) {
            for (row in chunk) {
                try {
                    progressDataSource.upsertHighlight(row.copy(userId = userId))
                    h++
                } catch (_: Exception) {
                }
            }
        }
        // 4. bookmarks (unique cfi_location)
        for (chunk in backup.bookmarks.chunked(CHUNK_SIZE)) {
            for (row in chunk) {
                try {
                    progressDataSource.upsertBookmark(row.copy(userId = userId))
                    bm++
                } catch (_: Exception) {
                }
            }
        }
        // 5. sessions last
        for (chunk in backup.sessions.chunked(CHUNK_SIZE)) {
            for (row in chunk) {
                try {
                    progressDataSource.upsertReadingSession(row.copy(userId = userId))
                    s++
                } catch (_: Exception) {
                }
            }
        }
        return ImportResult(b, p, h, bm, s)
    }

    data class ImportResult(
        val books: Int,
        val progress: Int,
        val highlights: Int,
        val bookmarks: Int,
        val sessions: Int,
    ) {
        val totalImported: Int get() = books + progress + highlights + bookmarks + sessions
    }
}
