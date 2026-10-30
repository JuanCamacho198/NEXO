package com.nexo.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.nexo.data.epub.EpubParserService
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.pdf.PdfParserService
import com.nexo.data.remote.drive.coverFailureError
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.model.Book
import com.nexo.domain.model.BookImportRequest
import com.nexo.domain.model.DuplicateBookException
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.sync.OutboxDrainScheduler
import com.nexo.domain.sync.SyncSettleGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

// TODO: make PagingConfig config-driven (pageSize, prefetchDistance) — currently pageSize=20
// is hardcoded in RepositoryImpl. Derive from screen metrics or server page size (R13).
class LibraryRepositoryImpl(
    private val appContext: Context,
    private val bookDao: BookDao,
    private val readingStatsDao: com.nexo.data.local.dao.ReadingStatsDao,
    private val epubParserService: EpubParserService,
    private val pdfParserService: PdfParserService,
    private val coverStorage: CoverStorage,
    private val readingProgressDao: ReadingProgressDao,
    private val outboxDao: SyncOutboxDao,
    /**
     * Gates local backing-file deletion on sync having settled. Defaults to an
     * already-settled gate so non-wired callers (and tests) keep the previous
     * behavior; production wires the orchestrator-backed gate.
     */
    private val settleGate: SyncSettleGate = SyncSettleGate { true },
    /**
     * S6 scheduler seam. Nullable so tests keep the previous local-only
     * behavior; production injects the WorkManager-backed scheduler and a drain
     * is requested after a successful book outbox enqueue.
     */
    private val drainScheduler: OutboxDrainScheduler? = null,
) : LibraryRepository {
    override fun observeLibrary(userId: String): Flow<List<Book>> =
        combine(bookDao.observeAllBooks(userId), readingProgressDao.observeAll(userId)) { books, progresses ->
            val progressById = progresses.associateBy { it.bookId }
            books.map { entity ->
                val canonical = progressById[entity.id]
                if (canonical != null) entity.toDomainWithCanonical(canonical) else entity.toDomain()
            }
        }

    override fun observeBookById(
        bookId: String,
        userId: String,
    ): Flow<Book?> =
        bookDao.observeBookById(bookId, userId).combine(readingProgressDao.observeProgressForBook(bookId, userId)) { entity, canonical ->
            entity?.let { if (canonical != null) it.toDomainWithCanonical(canonical) else it.toDomain() }
        }

    override fun observeProgressForBook(
        bookId: String,
        userId: String,
    ): Flow<ReadingProgress?> = readingProgressDao.observeProgressForBook(bookId, userId).map { it?.toDomain() }

    override fun observeTotalReadingTime(userId: String): Flow<Long> = readingStatsDao.observeTotalMinutesReadForUser(userId).map { it ?: 0L }

    override fun observeReadingTimeByBook(userId: String): Flow<Map<String, Long>> =
        readingStatsDao.observeAllStats(userId).map { stats ->
            stats.associate { it.bookId to it.totalMinutesRead }
        }

    override suspend fun importBookFromEpub(
        userId: String,
        request: BookImportRequest,
        inputStreamProvider: suspend () -> InputStream?,
    ): Result<Book> =
        runCatching {
            val inputStream =
                inputStreamProvider()
                    ?: throw IllegalArgumentException("Unable to open EPUB stream")
            val metadata = epubParserService.extractMetadata(inputStream).getOrThrow()
            val now = System.currentTimeMillis()
            val bookId = UUID.randomUUID().toString()

            // Primary: extract cover via Readium Publication.cover()
            // Fallback: use existing OPF-based cover bytes from metadata
            val coverBytes =
                extractReadiumCover(request.sourcePath)
                    ?: metadata.coverImageBytes

            val coverPath = coverBytes?.let { persistCoverNonBlocking(bookId, it) }

            val book =
                Book(
                    id = bookId,
                    title = metadata.title.ifBlank { request.fallbackTitle ?: "Untitled" },
                    author = metadata.author,
                    description = metadata.description,
                    coverPath = coverPath,
                    filePath = request.sourcePath,
                    format = EPUB_FORMAT,
                    totalPages = metadata.estimatedPageCount,
                    chapterCount = metadata.chapterCount.takeIf { it > 0 },
                    // EPUB metadata does not expose a genre — it stays null until the
                    // user assigns one in the editor.
                    genre = null,
                    language = metadata.language,
                    publisher = metadata.publisher,
                    tags = metadata.tags.joinToString(", ").takeIf { it.isNotBlank() },
                    publishedDate = metadata.publishedDate,
                    updatedAtEpochMillis = now,
                )

            val contentHash = computeSha256(request.sourcePath)
            // Duplicate rule: identical bytes already imported are not imported
            // twice. Surfaced as DuplicateBookException so the caller can render a
            // neutral "already in library" outcome instead of a failure.
            if (contentHash != null && bookDao.observeAllBooks(userId).first().any { it.contentHash == contentHash }) {
                throw DuplicateBookException()
            }
            bookDao.upsert(book.toEntity(userId).copy(contentHash = contentHash))
            queueBookOutboxEntry(bookId, userId)
            book
        }

    override suspend fun importBookFromPdf(
        userId: String,
        request: BookImportRequest,
        file: java.io.File,
    ): Result<Book> =
        runCatching {
            val metadata = pdfParserService.extractMetadata(file).getOrThrow()
            val now = System.currentTimeMillis()
            val bookId = UUID.randomUUID().toString()

            val coverPath =
                metadata.coverBytes
                    ?.let { persistCoverNonBlocking(bookId, it) }

            val book =
                Book(
                    id = bookId,
                    title =
                        metadata.title?.ifBlank { request.fallbackTitle ?: "Untitled" }
                            ?: request.fallbackTitle ?: "Untitled",
                    author = metadata.author,
                    coverPath = coverPath,
                    filePath = request.sourcePath,
                    format = PDF_FORMAT,
                    totalPages = metadata.pageCount,
                    updatedAtEpochMillis = now,
                )

            val contentHash = computeSha256(file.absolutePath)
            bookDao.upsert(book.toEntity(userId).copy(contentHash = contentHash))
            queueBookOutboxEntry(bookId, userId)
            book
        }

    override suspend fun getBookById(
        bookId: String,
        userId: String,
    ): Book? = bookDao.getBookById(bookId, userId)?.toDomain()

    override suspend fun findBookByTitleAndAuthor(
        title: String,
        author: String?,
        userId: String,
    ): Book? {
        val requestedAuthor = author?.trim().orEmpty()
        return bookDao
            .observeAllBooks(userId)
            .first()
            .firstOrNull { candidate ->
                candidate.title.equals(title, ignoreCase = true) &&
                    (
                        requestedAuthor.isEmpty() ||
                            candidate.author
                                ?.trim()
                                .orEmpty()
                                .equals(requestedAuthor, ignoreCase = true)
                    )
            }?.toDomain()
    }

    override suspend fun startReading(
        bookId: String,
        userId: String,
    ): Result<Unit> =
        runCatching {
            bookDao.startReading(bookId, userId, System.currentTimeMillis())
        }

    override suspend fun updateReadingProgress(
        bookId: String,
        userId: String,
        progress: Float,
    ): Result<Unit> =
        runCatching {
            val bounded = progress.coerceIn(0f, 100f)
            bookDao.updateReadingProgress(bookId, userId, bounded, System.currentTimeMillis())
        }

    override suspend fun completeReading(
        bookId: String,
        userId: String,
    ): Result<Unit> =
        runCatching {
            bookDao.completeReading(bookId, userId, System.currentTimeMillis())
        }

    override suspend fun deleteBook(
        bookId: String,
        userId: String,
    ): Result<Unit> =
        runCatching {
            // Capture the local backing path BEFORE the soft-delete — the row is
            // excluded from observeAllBooks() once deleted_at is set.
            val backingPath = bookDao.getBookById(bookId, userId)?.filePath
            // Clean up cover file first (idempotent — no-op if missing)
            coverStorage.deleteCover(bookId).getOrNull()
            val now = System.currentTimeMillis()
            bookDao.deleteBook(bookId, userId, now)
            readingStatsDao.deleteForBook(bookId, userId)
            queueBookOutboxEntry(bookId, userId, SyncOperation.DELETE)
            deleteBackingFileIfSettled(backingPath, bookId, userId)
        }

    override suspend fun deleteBookLocalOnly(
        bookId: String,
        userId: String,
    ): Result<Unit> =
        runCatching {
            val backingPath = bookDao.getBookById(bookId, userId)?.filePath
            coverStorage.deleteCover(bookId).getOrNull()
            val now = System.currentTimeMillis()
            bookDao.deleteBook(bookId, userId, now)
            readingStatsDao.deleteForBook(bookId, userId)
            // Local only — do NOT queue a DELETE tombstone so the Drive file is kept
            // (mirrors desktop handleHideBook). Also drop any pending DELETE for this
            // book so a previously-queued cloud delete does not resurrect.
            try {
                val pending =
                    outboxDao.getPendingItems(userId).filter {
                        it.entityType == SyncEntityType.BOOK.name && it.entityId == bookId && it.operation == SyncOperation.DELETE.name
                    }
                pending.forEach { outboxDao.deleteById(it.id, userId) }
            } catch (_: Exception) {
            }
            deleteBackingFileIfSettled(backingPath, bookId, userId)
        }

    /**
     * Deletes a book's local backing file once sync has settled. Rules:
     *  - Only regular files inside the app's internal `filesDir` are removed;
     *    SAF/`content://` imports and any external original are left untouched,
     *    and remote/Drive bytes are never touched (this is a local-only clean).
     *  - A path still referenced by another live book row is kept (dedup by path).
     *  - When the settle gate reports `false` (timeout/active sync) the file is
     *    left for the orphan sweep instead of blocking the delete.
     */
    private suspend fun deleteBackingFileIfSettled(
        filePath: String?,
        bookId: String,
        userId: String,
    ) {
        if (filePath.isNullOrBlank()) return
        val file = File(filePath)
        if (!file.isFile) return
        if (!isInsideAppFilesDir(file)) return
        if (!settleGate.awaitSettled()) return
        val referencedElsewhere =
            bookDao.observeAllBooks(userId).first().any { other ->
                other.id != bookId && other.filePath == filePath
            }
        if (referencedElsewhere) return
        file.delete()
    }

    /** True when [file] resolves under this app's internal `filesDir`. */
    private fun isInsideAppFilesDir(file: File): Boolean =
        runCatching {
            val root = appContext.filesDir.canonicalPath
            file.canonicalPath.startsWith("$root${File.separator}")
        }.getOrDefault(false)

    override suspend fun updateBookRating(
        bookId: String,
        userId: String,
        rating: Int?,
    ) {
        bookDao.updateRating(bookId, userId, rating)
    }

    override suspend fun updateBookStatus(
        bookId: String,
        userId: String,
        status: String?,
    ): Result<Unit> =
        runCatching {
            bookDao.updateStatus(bookId, userId, status, System.currentTimeMillis())
        }

    override suspend fun updateBookMetadata(
        bookId: String,
        userId: String,
        title: String,
        author: String?,
        description: String?,
        coverPath: String?,
        genre: String?,
        language: String?,
        publisher: String?,
        tags: String?,
        publishedDate: String?,
    ): Result<Unit> =
        runCatching {
            bookDao.updateMetadata(
                bookId = bookId,
                userId = userId,
                title = title,
                author = author,
                description = description,
                coverPath = coverPath,
                genre = genre,
                language = language,
                publisher = publisher,
                tags = tags,
                publishedDate = publishedDate,
                updatedAt = System.currentTimeMillis(),
            )
            // Queue a BOOK UPDATE so the edited metadata reaches the cloud. The
            // remotePath guard in SupabaseBookCatalogSync.processBookItem still
            // defers books without a remote file to the Drive push.
            queueBookOutboxEntry(bookId, userId, SyncOperation.UPDATE)
        }

    /**
     * Attempts to extract cover image bytes using Readium's [Publication.cover]
     * extension (from [CoverService]).  Falls back to returning null, letting
     * the caller use the OPF-based [EpubMetadata.coverImageBytes].
     *
     * Opens the EPUB via [PublicationOpener] (same pattern as
     * [com.nexo.presentation.viewmodel.ReaderViewModel.loadEpubBook]),
     * calls [publication.cover] which returns a [Bitmap?], then compresses
     * it to JPEG bytes.
     */
    private suspend fun extractReadiumCover(filePath: String): ByteArray? =
        runCatching {
            val file = File(filePath)
            if (!file.exists()) return@runCatching null

            val fileUri = Uri.fromFile(file).toString()
            val url = AbsoluteUrl(fileUri) ?: return@runCatching null
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(appContext.contentResolver, httpClient)

            val asset =
                withContext(Dispatchers.IO) {
                    assetRetriever.retrieve(url)
                }.getOrNull() ?: return@runCatching null

            val parser =
                DefaultPublicationParser(
                    context = appContext,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                )
            val opener = PublicationOpener(parser)

            val publication =
                withContext(Dispatchers.IO) {
                    opener.open(asset, allowUserInteraction = false)
                }.getOrNull() ?: return@runCatching null

            // publication.cover() returns a Bitmap? via CoverService
            publication
                .cover()
                ?.let { bitmap ->
                    withContext(Dispatchers.IO) {
                        ByteArrayOutputStream().use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                            stream.toByteArray()
                        }
                    }
                }
        }.getOrNull()

    /**
     * Persist a cover without ever blocking book import. A cover save
     * failure is mapped to the stable COVER_FAILED error code (REQ-07)
     * and yields a null cover path so the import proceeds with a fallback.
     */
    private suspend fun persistCoverNonBlocking(
        bookId: String,
        coverBytes: ByteArray,
    ): String? =
        coverStorage.saveCover(bookId = bookId, coverBytes = coverBytes).getOrElse {
            coverFailureError(correlationId = bookId, bookId = bookId)
            null
        }

    private fun BookEntity.toDomain(): Book =
        Book(
            id = id,
            title = title,
            author = author,
            description = description,
            coverPath = coverPath,
            filePath = filePath,
            format = format,
            totalPages = totalPages,
            userRating = userRating,
            updatedAtEpochMillis = updatedAtEpochMillis,
            status = status,
            readingState = readingState,
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = completedAtEpochMillis,
            progressUpdatedAtEpochMillis = progressUpdatedAtEpochMillis,
            stateVersion = stateVersion,
            genre = genre,
            language = language,
            publisher = publisher,
            tags = tags,
            publishedDate = publishedDate,
        )

    private fun BookEntity.toDomainWithCanonical(canonical: ReadingProgressEntity): Book =
        Book(
            id = id,
            title = title,
            author = author,
            description = description,
            coverPath = coverPath,
            filePath = filePath,
            format = format,
            totalPages = totalPages,
            userRating = userRating,
            updatedAtEpochMillis = updatedAtEpochMillis,
            status = status,
            readingState = readingState,
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = completedAtEpochMillis,
            progressPercentage = canonical.percentage,
            progressUpdatedAtEpochMillis = canonical.updatedAtEpochMillis,
            stateVersion = stateVersion,
            genre = genre,
            language = language,
            publisher = publisher,
            tags = tags,
            publishedDate = publishedDate,
        )

    private fun ReadingProgressEntity.toDomain(): ReadingProgress =
        ReadingProgress(
            id = id,
            bookId = bookId,
            cfiLocation = cfiLocation,
            percentage = percentage,
            currentPage = currentPage,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun Book.toEntity(userId: String): BookEntity =
        BookEntity(
            id = id,
            title = title,
            author = author,
            description = description,
            coverPath = coverPath,
            filePath = filePath,
            format = format,
            totalPages = totalPages,
            chapterCount = chapterCount,
            userRating = userRating,
            updatedAtEpochMillis = updatedAtEpochMillis,
            status = status,
            readingState = readingState,
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = completedAtEpochMillis,
            progressUpdatedAtEpochMillis = progressUpdatedAtEpochMillis,
            stateVersion = stateVersion,
            genre = genre,
            language = language,
            publisher = publisher,
            tags = tags,
            publishedDate = publishedDate,
            userId = userId,
        )

    /**
     * Queue a BOOK outbox entry so the catalog sync processor
     * can push its metadata to Supabase.
     *
     * Non-blocking on failure — the reconciliation pass in
     * [SupabaseBookCatalogSync.reconcileLocalBooks] covers gaps.
     */
    private suspend fun queueBookOutboxEntry(
        bookId: String,
        userId: String,
        operation: SyncOperation = SyncOperation.CREATE,
    ) {
        val enqueued =
            try {
                outboxDao.insert(
                    SyncOutboxEntity(
                        id = UUID.randomUUID().toString(),
                        entityType = SyncEntityType.BOOK.name,
                        entityId = bookId,
                        operation = operation.name,
                        payloadJson = """{}""",
                        createdAtEpochMillis = System.currentTimeMillis(),
                        userId = userId,
                    ),
                )
                true
            } catch (_: Exception) {
                // Non-blocking — reconciliation in SupabaseBookCatalogSync covers gaps
                false
            }
        // S6: request a drain after a successful enqueue (a failed insert is
        // covered by the catalog reconciliation pass).
        if (enqueued) drainScheduler?.scheduleDrain()
    }

    private companion object {
        const val EPUB_FORMAT = "epub"
        const val PDF_FORMAT = "pdf"
    }
}
