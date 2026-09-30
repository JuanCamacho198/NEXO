package com.nexo.data.repository

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookmarkEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.sync.LocatorCodec
import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.sync.OutboxDrainScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

// TODO: make PagingConfig config-driven (pageSize, prefetchDistance) — currently pageSize=20
// is hardcoded in RepositoryImpl. Derive from screen metrics or server page size (R13).
class ReaderRepositoryImpl(
    private val readingProgressDao: ReadingProgressDao,
    private val highlightDao: HighlightDao,
    private val bookmarkDao: BookmarkDao,
    private val bookDao: BookDao,
    private val outboxDao: SyncOutboxDao? = null,
    /**
     * S6 scheduler seam. Nullable so non-wired callers/tests keep the previous
     * local-only behavior; production injects the WorkManager-backed scheduler
     * and a drain is requested after every successful outbox enqueue.
     */
    private val drainScheduler: OutboxDrainScheduler? = null,
) : ReaderRepository {
    override fun observeProgress(bookId: String): Flow<ReadingProgress?> =
        readingProgressDao
            .observeProgressForBook(bookId)
            .map { progress -> progress?.toDomain() }

    override suspend fun upsertProgress(progress: ReadingProgress) {
        // Guard: reading_progress.book_id has a FK to books.id. After a cloud
        // download (or a stale sync), progress may reference a book that is not
        // present locally — upserting would throw SQLiteConstraintException and
        // crash the app (seen when opening a freshly downloaded book).
        if (bookDao.getBookById(progress.bookId) == null) return
        readingProgressDao.upsert(progress.toEntity())
        val payload = buildProgressPayload(progress)
        ensureValidJson(payload)
        val dao = outboxDao ?: return
        val entity =
            SyncOutboxEntity(
                id = "outbox-${UUID.randomUUID()}",
                entityType = SyncEntityType.READING_PROGRESS.name,
                entityId = progress.bookId,
                operation = SyncOperation.UPDATE.name,
                payloadJson = payload,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        // READING_PROGRESS coalesced by bookId — keep latest per (type, bookId)
        // Mirrors desktop addCoalesced: flood of location events collapses to single row
        val existing = dao.getByTypeAndEntityId(SyncEntityType.READING_PROGRESS.name, progress.bookId)
        if (existing != null) {
            dao.updatePayload(existing.id, payload)
        } else {
            dao.insert(entity)
        }
        // S6: outbox now actually drains — request a run after a successful enqueue.
        drainScheduler?.scheduleDrain()
    }

    private fun buildProgressPayload(progress: ReadingProgress): String {
        val obj = org.json.JSONObject()
        obj.put("id", progress.id)
        obj.put("bookId", progress.bookId)
        obj.put("cfiLocation", progress.cfiLocation)
        obj.put("percentage", progress.percentage.toDouble())
        if (progress.locatorJson != null) obj.put("locatorJson", progress.locatorJson) else obj.put("locatorJson", org.json.JSONObject.NULL)
        obj.put("updatedAtEpochMillis", progress.updatedAtEpochMillis)
        if (progress.currentPage != null) obj.put("currentPage", progress.currentPage) else obj.put("currentPage", org.json.JSONObject.NULL)
        return obj.toString()
    }

    private fun ensureValidJson(json: String) {
        require(json.isNotEmpty()) { "payloadJson must be non-empty valid JSON" }
        val parsed = org.json.JSONObject(json)

        @Suppress("UNUSED_VARIABLE")
        val check = parsed
    }

    override suspend fun updateBookReadingState(
        bookId: String,
        progressPercent: Float,
        updatedAt: Long,
    ) {
        bookDao.updateReadingProgress(
            bookId = bookId,
            progress = progressPercent.coerceIn(0f, 100f),
            updatedAt = updatedAt,
        )
    }

    override suspend fun getProgressForBook(bookId: String): ReadingProgress? = readingProgressDao.getProgressForBook(bookId)?.toDomain()

    override fun observeAllHighlights(): Flow<List<Highlight>> =
        highlightDao
            .observeAllHighlights()
            .map { list -> list.map { it.toDomain() } }

    override fun observeHighlights(bookId: String): Flow<List<Highlight>> =
        highlightDao
            .observeHighlightsForBook(bookId)
            .map { list -> list.map { it.toDomain() } }

    override fun observeAllTags(): Flow<List<String>> = highlightDao.observeAllTags()

    override suspend fun upsertHighlight(highlight: Highlight) {
        // Guard: highlights.book_id has a FK to books.id (see upsertProgress).
        if (bookDao.getBookById(highlight.bookId) == null) return
        highlightDao.upsert(highlight.toEntity())
        val payload = buildHighlightPayload(highlight)
        ensureValidJson(payload)
        // HIGHLIGHT per id — atomic enqueue, never coalesced across ids (desktop parity)
        // Distinguish DELETE: when deletedAt != null, outbox must be DELETE so Supabase softDelete path runs
        val operation = if (highlight.deletedAtEpochMillis != null) SyncOperation.DELETE else SyncOperation.UPDATE
        val dao = outboxDao ?: return
        dao.insert(
            SyncOutboxEntity(
                id = "outbox-${UUID.randomUUID()}",
                entityType = SyncEntityType.HIGHLIGHT.name,
                entityId = highlight.id,
                operation = operation.name,
                payloadJson = payload,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        // S6: request a drain after a successful enqueue.
        drainScheduler?.scheduleDrain()
    }

    private fun buildHighlightPayload(highlight: Highlight): String {
        val obj = org.json.JSONObject()
        obj.put("id", highlight.id)
        obj.put("bookId", highlight.bookId)
        obj.put("cfiRange", highlight.cfiRange)
        obj.put("textContent", highlight.textContent)
        if (highlight.note != null) obj.put("note", highlight.note) else obj.put("note", org.json.JSONObject.NULL)
        obj.put("color", highlight.color)
        obj.put("updatedAtEpochMillis", highlight.updatedAtEpochMillis)
        if (highlight.deletedAtEpochMillis !=
            null
        ) {
            obj.put("deletedAtEpochMillis", highlight.deletedAtEpochMillis)
        } else {
            obj.put("deletedAtEpochMillis", org.json.JSONObject.NULL)
        }
        if (highlight.locatorJson !=
            null
        ) {
            obj.put("locatorJson", highlight.locatorJson)
        } else {
            obj.put("locatorJson", org.json.JSONObject.NULL)
        }
        if (highlight.type != null) obj.put("type", highlight.type) else obj.put("type", org.json.JSONObject.NULL)
        if (highlight.tag != null) obj.put("tag", highlight.tag) else obj.put("tag", org.json.JSONObject.NULL)
        return obj.toString()
    }

    override suspend fun getHighlightsForBook(bookId: String): List<Highlight> = highlightDao.getHighlightsForBook(bookId).map { it.toDomain() }

    override fun observeAllBookmarks(): Flow<List<Bookmark>> =
        bookmarkDao
            .observeAllBookmarks()
            .map { list -> list.map { it.toDomain() } }

    override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> =
        bookmarkDao
            .observeBookmarksForBook(bookId)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun upsertBookmark(bookmark: Bookmark) {
        // Guard: bookmarks.book_id has a FK to books.id (see upsertProgress).
        if (bookDao.getBookById(bookmark.bookId) == null) return
        bookmarkDao.upsert(bookmark.toEntity())
        val payload = buildBookmarkPayload(bookmark)
        ensureValidJson(payload)
        // BOOKMARK per id — atomic enqueue, never coalesced across ids
        val dao = outboxDao ?: return
        dao.insert(
            SyncOutboxEntity(
                id = "outbox-${UUID.randomUUID()}",
                entityType = SyncEntityType.BOOKMARK.name,
                entityId = bookmark.id,
                operation = SyncOperation.UPDATE.name,
                payloadJson = payload,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        // S6: request a drain after a successful enqueue.
        drainScheduler?.scheduleDrain()
    }

    private fun buildBookmarkPayload(bookmark: Bookmark): String {
        val obj = org.json.JSONObject()
        obj.put("id", bookmark.id)
        obj.put("bookId", bookmark.bookId)
        obj.put("cfiLocation", bookmark.cfiLocation)
        obj.put("titleOrSnippet", bookmark.titleOrSnippet)
        obj.put("updatedAtEpochMillis", bookmark.updatedAtEpochMillis)
        if (bookmark.deletedAtEpochMillis !=
            null
        ) {
            obj.put("deletedAtEpochMillis", bookmark.deletedAtEpochMillis)
        } else {
            obj.put("deletedAtEpochMillis", org.json.JSONObject.NULL)
        }
        if (bookmark.locatorJson != null) obj.put("locatorJson", bookmark.locatorJson) else obj.put("locatorJson", org.json.JSONObject.NULL)
        return obj.toString()
    }

    override suspend fun getBookmarksForBook(bookId: String): List<Bookmark> = bookmarkDao.getBookmarksForBook(bookId).map { it.toDomain() }

    private fun ReadingProgressEntity.toDomain(): ReadingProgress {
        // Backfill: normalize on read (persist corrected similar to pageNumber fix)
        val normalizedJson = LocatorCodec.normalizeLocatorJson(locatorJson)
        if (normalizedJson != locatorJson && normalizedJson != null) {
            // Fire-and-forget backfill correction — will be persisted on next upsert
            // Direct DAO update is avoided here to keep mapping pure; Supabase sync path also normalizes
        }
        return ReadingProgress(
            id = id,
            bookId = bookId,
            cfiLocation = cfiLocation,
            percentage = percentage,
            currentPage = currentPage,
            updatedAtEpochMillis = updatedAtEpochMillis,
            locatorJson = normalizedJson,
        )
    }

    private fun ReadingProgress.toEntity(): ReadingProgressEntity =
        ReadingProgressEntity(
            id = id,
            bookId = bookId,
            cfiLocation = cfiLocation,
            percentage = percentage,
            currentPage = currentPage,
            updatedAtEpochMillis = updatedAtEpochMillis,
            locatorJson = LocatorCodec.normalizeLocatorJson(locatorJson),
        )

    private fun HighlightEntity.toDomain(): Highlight {
        val normalizedJson = LocatorCodec.normalizeLocatorJson(locatorJson)
        return Highlight(
            id = id,
            bookId = bookId,
            cfiRange = cfiRange,
            textContent = textContent,
            note = note,
            color = color,
            updatedAtEpochMillis = updatedAtEpochMillis,
            deletedAtEpochMillis = deletedAtEpochMillis,
            locatorJson = normalizedJson,
            type = type,
            tag = tag,
        )
    }

    private fun Highlight.toEntity(): HighlightEntity =
        HighlightEntity(
            id = id,
            bookId = bookId,
            cfiRange = cfiRange,
            textContent = textContent,
            note = note,
            color = color,
            updatedAtEpochMillis = updatedAtEpochMillis,
            deletedAtEpochMillis = deletedAtEpochMillis,
            locatorJson = LocatorCodec.normalizeLocatorJson(locatorJson),
            type = type,
            tag = tag,
        )

    private fun BookmarkEntity.toDomain(): Bookmark {
        val normalizedJson = LocatorCodec.normalizeLocatorJson(locatorJson)
        return Bookmark(
            id = id,
            bookId = bookId,
            cfiLocation = cfiLocation,
            titleOrSnippet = titleOrSnippet,
            updatedAtEpochMillis = updatedAtEpochMillis,
            deletedAtEpochMillis = deletedAtEpochMillis,
            locatorJson = normalizedJson,
        )
    }

    private fun Bookmark.toEntity(): BookmarkEntity =
        BookmarkEntity(
            id = id,
            bookId = bookId,
            cfiLocation = cfiLocation,
            titleOrSnippet = titleOrSnippet,
            updatedAtEpochMillis = updatedAtEpochMillis,
            deletedAtEpochMillis = deletedAtEpochMillis,
            locatorJson = LocatorCodec.normalizeLocatorJson(locatorJson),
        )
}
