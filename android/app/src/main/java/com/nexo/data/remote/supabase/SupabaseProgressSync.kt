package com.nexo.data.remote.supabase

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookmarkEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.data.local.entity.ReadingSessionEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.remote.sync.ApplyOutcome
import com.nexo.data.remote.sync.CommitOutcome
import com.nexo.data.remote.sync.OutboxCommit
import com.nexo.data.session.SessionManager
import com.nexo.data.sync.CanonicalLocator
import com.nexo.data.sync.LocatorCodec
import com.nexo.data.sync.LocatorLocations
import com.nexo.debug.DebugLog
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Processes the local outbox for READING_PROGRESS entries and upserts them
 * to Supabase via [SupabaseProgressDataSource].
 *
 * Also manages Realtime subscription so incoming remote progress changes
 * are applied to the local Room database.
 *
 * Only acts when a valid Supabase session is active.
 */
class SupabaseProgressSync(
    private val outboxDao: SyncOutboxDao,
    private val bookDao: BookDao,
    private val readingProgressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val readingSessionDao: ReadingSessionDao,
    private val sessionManager: SessionManager,
    private val dataSource: SupabaseProgressDataSource = SupabaseProgressDataSource(),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    /**
     * OutboxCommit helper (sync-layer-split PR-3). When non-null, the four
     * `process*Item` methods delegate ack/retry/poison persistence to it via
     * `commit(item) { applyRemote*() }`. Nullable so legacy callers (e.g. unit
     * tests using direct DAO fakes) keep working without injection.
     */
    private val outboxCommit: OutboxCommit? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var processJob: Job? = null
    private var realtimeJob: Job? = null

    /**
     * Remote progress rows whose book was not yet present locally when the pull
     * arrived (e.g. a book was just downloaded from the cloud). reading_progress
     * has a real FK to books.id, so we cannot upsert without the book; instead we
     * retain the row here and apply it as soon as the book becomes available.
     * This fixes the race where a freshly downloaded book opened before the pull
     * applied would start at 0 and then overwrite the remote progress (LWW).
     */
    private val pendingRemoteProgress = java.util.concurrent.ConcurrentHashMap<String, ReadingProgressRow>()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val pendingCount: kotlinx.coroutines.flow.Flow<Int> = outboxDao.observePendingCount()

    sealed class State {
        data object Idle : State()

        data object Running : State()

        data class Gated(
            val reason: String,
        ) : State()

        data class Error(
            val message: String,
        ) : State()
    }

    /**
     * Start periodic outbox processing. Processes all pending READING_PROGRESS
     * outbox entries and upserts them to Supabase.
     */
    fun startProcessing() {
        if (processJob?.isActive == true) return
        _state.value = State.Idle

        processJob =
            scope.launch {
                processOutbox()
            }
    }

    private fun gatedDelay(attempt: Int): Long =
        if (attempt < GATED_FLUSH_MAX_ATTEMPTS) {
            minOf(GATED_BACKOFF_BASE_MS * (1L shl attempt), GATED_BACKOFF_CAP_MS)
        } else {
            GATED_PLATEAU_MS
        }

    /**
     * Option 1 — Highlights screen silent pull (global).
     * Fetches ALL highlights for the current user and merges them
     * incrementally via [applyRemoteHighlight] (LWW, tombstone-aware).
     * No UI blocking: caller launches in its own scope and observes
     * Room Flow for incremental updates.
     * Returns number of remote rows fetched (0 when gated/offline).
     */
    suspend fun pullAllHighlights(): Int {
        val session = sessionManager.ensureFreshSession().getOrNull() ?: return 0
        return try {
            val rows = dataSource.listHighlights(session.userId, null, includeDeleted = true)
            for (row in rows) {
                applyRemoteHighlight(row)
            }
            rows.size
        } catch (_: Exception) {
            0
        }
    }

    /** Pulls one book without blocking the reader's local open path. */
    suspend fun resumeForBook(
        bookId: String,
        onProgressApplied: (ReadingProgressRow) -> Unit = {},
    ) {
        val session = sessionManager.ensureFreshSession().getOrNull() ?: return
        // A book downloaded from the cloud may not be registered in `books` yet
        // when this pull runs. reading_progress has a real FK to books.id, so we
        // must wait until the book exists before applying the remote progress —
        // otherwise the remote value is dropped and the local 0% wins (LWW),
        // wiping the "continue reading" position. Retry briefly with a small
        // delay instead of giving up on the first attempt.
        val bookReady = bookDao.getBookById(bookId) != null
        var attempts = 0
        while (!bookReady && attempts < PULL_BOOK_READY_MAX_ATTEMPTS) {
            delay(PULL_BOOK_READY_RETRY_DELAY_MS)
            attempts++
        }
        runCatching {
            val state = dataSource.fetchBookState(session.userId, bookId)
            state.progress?.let { row ->
                if (applyRemoteProgress(row)) onProgressApplied(row)
            }
            state.bookmarks.forEach { applyRemoteBookmark(it) }
            state.highlights.forEach { applyRemoteHighlight(it) }
        }
        // Apply any progress retained while the book was not yet available.
        applyPendingProgressForBook(bookId)?.let { onProgressApplied(it) }
    }

    /**
     * Apply a previously-retained remote progress row for [bookId] now that the
     * book exists locally (or has just been pulled). Returns the applied row, or
     * null if there was none pending / the book is still missing.
     */
    internal suspend fun applyPendingProgressForBook(bookId: String): ReadingProgressRow? {
        val row = pendingRemoteProgress.remove(bookId) ?: return null
        if (bookDao.getBookById(bookId) == null) {
            // Book still missing — keep it pending for a later flush.
            pendingRemoteProgress[bookId] = row
            return null
        }
        return if (applyRemoteProgress(row)) row else null
    }

    private val dateFormat: SimpleDateFormat =
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US,
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /**
     * Adapter between per-item processors and [OutboxCommit].
     *
     * When [outboxCommit] is wired (production DI), persistence is delegated to
     * the helper, which centralises the ack / increment / prune policy and
     * preserves the gated-backoff semantic (the helper returns the typed
     * outcome; this syncer continues processing remaining items — backoff is
     * handled at the outer [processOutbox] loop via [hasLiveSession] + gated
     * retry on the next pass).
     *
     * When [outboxCommit] is null (legacy callers, unit tests using a direct
     * DAO fake), we fall back to the pre-PR-3 inline behaviour: Ok → deleteById,
     * Retryable → incrementRetryCount + pruneFailedItems(3). This keeps the
     * existing syncer test suite untouched.
     */
    private suspend fun commitOutbox(
        item: com.nexo.data.local.entity.SyncOutboxEntity,
        apply: suspend () -> ApplyOutcome,
    ) {
        val helper = outboxCommit
        if (helper != null) {
            val outcome = helper.commit(item, apply)
            if (outcome is CommitOutcome.Poison) {
                // Genuine, bounded error: the item exhausted its retries and was
                // pruned. Per-attempt failures stay WARN telemetry (FIX 5); only
                // this terminal state is error-level.
                DebugLog.error(
                    TAG,
                    "sync.outboxPoisoned entityType=${item.entityType} " +
                        "entityId=${item.entityId} error=${outcome.cause.message}",
                )
            }
            return
        }
        when (val outcome = apply()) {
            is ApplyOutcome.Ok -> outboxDao.deleteById(item.id)
            is ApplyOutcome.Retryable -> {
                outboxDao.incrementRetryCount(item.id, outcome.cause.message ?: "Unknown error")
                outboxDao.pruneFailedItems(3)
            }
            is ApplyOutcome.Poison -> outboxDao.pruneFailedItems(3)
        }
    }

    /**
     * Parses a remote PostgREST timestamp into epoch millis for LWW comparison.
     *
     * PostgREST serializes timestamptz as ISO-8601 with a numeric UTC offset
     * (e.g. `2026-08-22T13:16:22.45073+00:00`), which the legacy
     * [dateFormat] pattern (literal trailing `Z`) can NOT parse. Falling back
     * to `System.currentTimeMillis()` on parse failure poisoned every LWW
     * decision: any live remote row compared as "newest possible" and
     * overwrote newer local edits/tombstones (observed as deleted highlights
     * resurrecting on device). Unparsable input now maps to 0L — treated as
     * the OLDEST possible instant — so it can never win LWW.
     */
    internal fun parseRemoteTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        try {
            return java.time.OffsetDateTime
                .parse(raw)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return dateFormat.parse(raw)?.time ?: 0L
        } catch (_: Exception) {
        }
        return 0L
    }

    /**
     * Hot-path gate: Supabase SoT must never fire without a live session (PR2).
     * Mirrors desktop hasLiveSession() — session must exist and belong to current user.
     */
    private suspend fun hasLiveSession(): Boolean = sessionManager.getCurrentSession().getOrNull() != null

    /**
     * Bounded gated-flush loop.
     *
     * Gated path (no live session OR ensureFreshSession failure) retries with
     * in-memory exponential backoff: up to [GATED_FLUSH_MAX_ATTEMPTS] attempts
     * with base [GATED_BACKOFF_BASE_MS] doubling each attempt, capped at
     * [GATED_BACKOFF_CAP_MS] (160s), then plateaus polling [GATED_PLATEAU_MS]
     * (60s) while [processJob] remains active (cancelled by [stop] on logout).
     *
     * The attempt counter is a LOCAL variable in this function and the gated
     * path NEVER calls [SyncOutboxDao.incrementRetryCount] or
     * [SyncOutboxDao.pruneFailedItems] — those are only invoked in per-item
     * catch blocks on actual push exceptions. Therefore `retry_count` stays 0
     * through any number of gated retries, and no item can be pruned before
     * 3 recorded REAL failures (pruneFailedItems(3) threshold). Recovery
     * drains remaining items in order without requiring app restart.
     */
    private suspend fun processOutbox() {
        var gateAttempts = 0
        var session: com.nexo.domain.model.AuthSession? = null

        while (true) {
            if (!hasLiveSession()) {
                _state.value = State.Gated("no_session")
                delay(gatedDelay(gateAttempts))
                gateAttempts++
                continue
            }
            val fresh = sessionManager.ensureFreshSession().getOrNull()
            if (fresh == null) {
                _state.value = State.Gated("refresh_failed")
                delay(gatedDelay(gateAttempts))
                gateAttempts++
                continue
            }
            session = fresh
            break
        }

        val authSession = session ?: return

        _state.value = State.Running
        val pendingItems = outboxDao.getPendingItems()

        // A4 - sync flush duration: timestamps around the drain loop only
        // (background path; the session-gate backoff loop above is excluded).
        // runCatching keeps JVM unit tests (unmocked SystemClock) green.
        val drainStart = runCatching { android.os.SystemClock.elapsedRealtime() }.getOrDefault(0L)

        for (item in pendingItems) {
            when (item.entityType) {
                SyncEntityType.READING_PROGRESS.name -> processProgressItem(item, authSession.userId)
                SyncEntityType.BOOKMARK.name -> processBookmarkItem(item, authSession.userId)
                SyncEntityType.HIGHLIGHT.name -> processHighlightItem(item, authSession.userId)
                SyncEntityType.READING_SESSION.name -> processSessionItem(item, authSession.userId)
            }
        }

        _state.value = State.Idle

        runCatching {
            val drainElapsed = android.os.SystemClock.elapsedRealtime() - drainStart
            com.nexo.debug.SentryMetrics.distribution(
                "sync_flush",
                com.nexo.debug.SentryMetrics
                    .bucketDurationMs(drainElapsed),
                mapOf(
                    "source" to "sync",
                    "platform" to "android",
                ),
            )
        }
    }

    private suspend fun processProgressItem(
        item: com.nexo.data.local.entity.SyncOutboxEntity,
        userId: String,
    ) {
        if (!hasLiveSession()) return
        val bookId = item.entityId ?: return
        val localProgress = readingProgressDao.getProgressForBook(bookId) ?: return

        val row =
            ReadingProgressRow(
                userId = userId,
                bookId = localProgress.bookId,
                cfiLocation = localProgress.cfiLocation,
                percentage = localProgress.percentage.toDouble(),
                locatorJson = LocatorCodec.normalizeLocatorJson(localProgress.locatorJson),
                updatedAt = dateFormat.format(Date(localProgress.updatedAtEpochMillis)),
                version = 1,
            )

        commitOutbox(item) {
            try {
                dataSource.upsertProgress(row)
                ApplyOutcome.Ok
            } catch (e: Exception) {
                ApplyOutcome.Retryable(e)
            }
        }
    }

    private suspend fun processBookmarkItem(
        item: com.nexo.data.local.entity.SyncOutboxEntity,
        userId: String,
    ) {
        if (!hasLiveSession()) return
        val bookmarkId = item.entityId ?: return
        val operation =
            try {
                SyncOperation.valueOf(item.operation)
            } catch (_: IllegalArgumentException) {
                SyncOperation.UPDATE
            }

        // Pre-resolve local row outside the apply lambda. Missing local rows
        // for UPDATE bypass `commitOutbox` entirely so the outbox entry stays
        // for the next pass (preserves pre-PR-3 semantics: no delete, no
        // retry increment — the item just waits for a future pass).
        if (operation != SyncOperation.DELETE) {
            val localBookmark = bookmarkDao.getBookmarkById(bookmarkId) ?: return
            commitOutbox(item) {
                try {
                    val row =
                        BookmarkRow(
                            id = localBookmark.id,
                            userId = userId,
                            bookId = localBookmark.bookId,
                            cfiLocation = localBookmark.cfiLocation,
                            titleSnippet = localBookmark.titleOrSnippet.ifEmpty { null },
                            locatorJson = LocatorCodec.normalizeLocatorJson(localBookmark.locatorJson),
                            deletedAt =
                                localBookmark.deletedAtEpochMillis?.let {
                                    dateFormat.format(Date(it))
                                },
                            updatedAt = dateFormat.format(Date(localBookmark.updatedAtEpochMillis)),
                        )
                    dataSource.upsertBookmark(row)
                    ApplyOutcome.Ok
                } catch (e: Exception) {
                    ApplyOutcome.Retryable(e)
                }
            }
            return
        }

        // DELETE: push to Supabase and ack via OutboxCommit.
        commitOutbox(item) {
            try {
                dataSource.softDeleteBookmark(bookmarkId, userId)
                ApplyOutcome.Ok
            } catch (e: Exception) {
                ApplyOutcome.Retryable(e)
            }
        }
    }

    private suspend fun processHighlightItem(
        item: com.nexo.data.local.entity.SyncOutboxEntity,
        userId: String,
    ) {
        if (!hasLiveSession()) return
        val entityId = item.entityId ?: return
        val operation =
            try {
                SyncOperation.valueOf(item.operation)
            } catch (_: IllegalArgumentException) {
                SyncOperation.UPDATE
            }

        // PR4: HIGHLIGHT per id — atomic enqueue, never coalesced across ids.
        // Legacy bookId rows (old outbox where entityId was bookId) are ignored:
        // they would re-send deleted rows and cause resurrection. Clean them up
        // by ack-via-OutboxCommit (Ok outcome).
        if (operation != SyncOperation.DELETE) {
            val localHighlight = highlightDao.getHighlightById(entityId)
            if (localHighlight == null) {
                commitOutbox(item) { ApplyOutcome.Ok }
                return
            }

            // Ensure locatorJson is never null for epubcfi highlights (LocatorCodec fallback)
            val resolvedLocatorJson =
                LocatorCodec.normalizeLocatorJson(localHighlight.locatorJson)
                    ?: run {
                        val cfi = localHighlight.cfiRange
                        if (cfi.startsWith("epubcfi(")) {
                            // Fallback for legacy rows where only cfiRange exists — preserve fragment
                            val spineIdx =
                                Regex("""epubcfi\(/6/(\d+)""")
                                    .find(cfi)
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()
                            val href = if (spineIdx != null && spineIdx > 0) "OEBPS/chapter$spineIdx.xhtml" else "OEBPS/text.xhtml"
                            val fallback =
                                CanonicalLocator(
                                    href = href,
                                    type = "application/xhtml+xml",
                                    locations = LocatorLocations(progression = 0.0, fragment = cfi),
                                )
                            LocatorCodec.locatorToJson(fallback)
                        } else {
                            null
                        }
                    }

            commitOutbox(item) {
                try {
                    val row =
                        HighlightRow(
                            id = localHighlight.id,
                            userId = userId,
                            bookId = localHighlight.bookId,
                            cfiRange = localHighlight.cfiRange,
                            textContent = localHighlight.textContent,
                            note = localHighlight.note,
                            color = localHighlight.color,
                            page = null,
                            type = localHighlight.type,
                            locatorJson = resolvedLocatorJson,
                            deletedAt =
                                localHighlight.deletedAtEpochMillis?.let {
                                    dateFormat.format(Date(it))
                                },
                            updatedAt = dateFormat.format(Date(localHighlight.updatedAtEpochMillis)),
                        )
                    dataSource.upsertHighlight(row)

                    if (!localHighlight.tag.isNullOrBlank()) {
                        val tagNames =
                            localHighlight.tag
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                        for (tagName in tagNames) {
                            val tag = dataSource.findOrCreateTag(userId, tagName)
                            dataSource.linkTagToHighlight(
                                localHighlight.id,
                                requireNotNull(tag.id) { "Supabase tag missing id after findOrCreateTag" },
                            )
                        }
                    }
                    ApplyOutcome.Ok
                } catch (e: Exception) {
                    ApplyOutcome.Retryable(e)
                }
            }
            return
        }

        // DELETE: push to Supabase and ack via OutboxCommit.
        commitOutbox(item) {
            try {
                dataSource.softDeleteHighlight(entityId, userId)
                ApplyOutcome.Ok
            } catch (e: Exception) {
                ApplyOutcome.Retryable(e)
            }
        }
    }

    /**
     * Single Realtime supervisor (PR2): owns 4 channels progress:uid/highlights:uid/
     * bookmarks:uid/sessions:uid, gated by hasLiveSession, torn down on stop()/logout.
     * LWW with version+1 for progress is applied on import.
     */
    fun subscribeToRealtimeChanges() {
        if (realtimeJob?.isActive == true) return

        realtimeJob =
            scope.launch {
                if (!hasLiveSession()) return@launch
                val session = sessionManager.ensureFreshSession().getOrNull() ?: return@launch

                // Progress changes
                launch {
                    dataSource.subscribeToUserChanges(session.userId).collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> {
                                val row = action.decodeRecord<ReadingProgressRow>()
                                applyRemoteProgress(row)
                            }
                            is PostgresAction.Update -> {
                                val row = action.decodeRecord<ReadingProgressRow>()
                                applyRemoteProgress(row)
                            }
                            is PostgresAction.Delete, is PostgresAction.Select -> { /* no-op */ }
                        }
                    }
                }

                // Bookmark changes
                launch {
                    dataSource.subscribeToBookmarkChanges(session.userId).collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> {
                                val row = action.decodeRecord<BookmarkRow>()
                                applyRemoteBookmark(row)
                            }
                            is PostgresAction.Update -> {
                                val row = action.decodeRecord<BookmarkRow>()
                                applyRemoteBookmark(row)
                            }
                            is PostgresAction.Delete, is PostgresAction.Select -> { /* no-op */ }
                        }
                    }
                }

                // Highlight changes
                launch {
                    dataSource.subscribeToHighlightChanges(session.userId).collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> {
                                val row = action.decodeRecord<HighlightRow>()
                                applyRemoteHighlight(row)
                            }
                            is PostgresAction.Update -> {
                                val row = action.decodeRecord<HighlightRow>()
                                applyRemoteHighlight(row)
                            }
                            is PostgresAction.Delete, is PostgresAction.Select -> { /* no-op */ }
                        }
                    }
                }

                // Reading-session changes (REQ-reading-sessions-sync-4, SCEN-sync-6/7)
                launch {
                    dataSource.subscribeToReadingSessionChanges(session.userId).collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> {
                                val row = action.decodeRecord<ReadingSessionRow>()
                                applyRemoteSession(row)
                            }
                            is PostgresAction.Update -> {
                                val row = action.decodeRecord<ReadingSessionRow>()
                                applyRemoteSession(row)
                            }
                            is PostgresAction.Delete, is PostgresAction.Select -> { /* no-op */ }
                        }
                    }
                }
            }
    }

    private suspend fun applyRemoteProgress(row: ReadingProgressRow): Boolean {
        if (bookDao.getBookById(row.bookId) == null) {
            pendingRemoteProgress[row.bookId] = row
            return false
        }

        val localProgress = readingProgressDao.getProgressForBook(row.bookId)
        val remoteTime = parseRemoteTimestamp(row.updatedAt)

        // LWW with version+1 and recordId tie (PR2): remote wins if newer; tie → recordId lexicographic.
        val shouldApply =
            when {
                localProgress == null -> true
                remoteTime > localProgress.updatedAtEpochMillis -> true
                remoteTime < localProgress.updatedAtEpochMillis -> false
                else -> (row.id ?: row.bookId) > localProgress.id
            }
        if (shouldApply) {
            val normalizedLocator = LocatorCodec.normalizeLocatorJson(row.locatorJson)
            // Backfill: persist corrected if needed (already normalized above)
            readingProgressDao.upsert(
                com.nexo.data.local.entity.ReadingProgressEntity(
                    id =
                        row.id ?: java.util.UUID
                            .randomUUID()
                            .toString(),
                    bookId = row.bookId,
                    cfiLocation = row.cfiLocation,
                    percentage = row.percentage.toFloat(),
                    currentPage = localProgress?.currentPage,
                    updatedAtEpochMillis = remoteTime,
                    locatorJson = normalizedLocator,
                ),
            )
            return true
        }
        return false
    }

    private suspend fun applyRemoteBookmark(row: BookmarkRow) {
        val isDeleted = row.deletedAt != null
        val localBookmark = bookmarkDao.getBookmarkById(row.id ?: return)

        if (!isDeleted && bookDao.getBookById(row.bookId) == null) return

        if (isDeleted) {
            if (localBookmark != null) {
                // LWW tombstone: later deletedAt wins, tie → recordId lexicographic
                val remoteDeleted = parseRemoteTimestamp(row.deletedAt)
                val localDeleted = localBookmark.deletedAtEpochMillis ?: Long.MIN_VALUE
                val tombstoneWins = remoteDeleted > localDeleted || (remoteDeleted == localDeleted && (row.id ?: "") > localBookmark.id)
                if (tombstoneWins || localBookmark.deletedAtEpochMillis == null) {
                    bookmarkDao.upsert(
                        localBookmark.copy(
                            deletedAtEpochMillis =
                                try {
                                    dateFormat.parse(row.deletedAt)?.time
                                } catch (_: Exception) {
                                    System.currentTimeMillis()
                                },
                        ),
                    )
                }
            }
        } else {
            val remoteTime = parseRemoteTimestamp(row.updatedAt)

            val shouldApply =
                when {
                    localBookmark == null -> true
                    // A live remote row must never resurrect a NEWER local tombstone.
                    localBookmark.deletedAtEpochMillis != null ->
                        remoteTime > localBookmark.deletedAtEpochMillis
                    remoteTime > localBookmark.updatedAtEpochMillis -> true
                    remoteTime < localBookmark.updatedAtEpochMillis -> false
                    else -> (row.id ?: "") > localBookmark.id
                }
            if (shouldApply) {
                bookmarkDao.upsert(
                    BookmarkEntity(
                        id = row.id ?: UUID.randomUUID().toString(),
                        bookId = row.bookId,
                        cfiLocation = row.cfiLocation,
                        titleOrSnippet = row.titleSnippet ?: "",
                        updatedAtEpochMillis = remoteTime,
                        deletedAtEpochMillis = if (isDeleted) remoteTime else null,
                        locatorJson = LocatorCodec.normalizeLocatorJson(row.locatorJson),
                    ),
                )
            }
        }
    }

    private suspend fun applyRemoteHighlight(row: HighlightRow) {
        val isDeleted = row.deletedAt != null
        val localHighlight = highlightDao.getHighlightById(row.id ?: return)

        if (!isDeleted && bookDao.getBookById(row.bookId) == null) return

        if (isDeleted) {
            if (localHighlight != null) {
                val remoteDeleted = parseRemoteTimestamp(row.deletedAt)
                val localDeleted = localHighlight.deletedAtEpochMillis ?: Long.MIN_VALUE
                val tombstoneWins = remoteDeleted > localDeleted || (remoteDeleted == localDeleted && (row.id ?: "") > localHighlight.id)
                if (tombstoneWins || localHighlight.deletedAtEpochMillis == null) {
                    highlightDao.upsert(
                        localHighlight.copy(
                            deletedAtEpochMillis = parseRemoteTimestamp(row.deletedAt),
                        ),
                    )
                }
            }
        } else {
            val remoteTime = parseRemoteTimestamp(row.updatedAt)

            val shouldApply =
                when {
                    localHighlight == null -> true
                    // A live remote row must never resurrect a NEWER local tombstone:
                    // when the local row is deleted, compare against the deletion
                    // instant instead of updatedAtEpochMillis (which equals it).
                    localHighlight.deletedAtEpochMillis != null ->
                        remoteTime > localHighlight.deletedAtEpochMillis
                    remoteTime > localHighlight.updatedAtEpochMillis -> true
                    remoteTime < localHighlight.updatedAtEpochMillis -> false
                    else -> (row.id ?: "") > localHighlight.id
                }
            if (shouldApply) {
                highlightDao.upsert(
                    HighlightEntity(
                        id = row.id ?: UUID.randomUUID().toString(),
                        bookId = row.bookId,
                        cfiRange = row.cfiRange,
                        textContent = row.textContent,
                        note = row.note,
                        color = row.color,
                        updatedAtEpochMillis = remoteTime,
                        deletedAtEpochMillis = if (isDeleted) remoteTime else null,
                        locatorJson = LocatorCodec.normalizeLocatorJson(row.locatorJson) ?: row.locatorJson,
                        type = row.type,
                    ),
                )
            }
        }
    }

    /**
     * Push a READING_SESSION outbox item (REQ-reading-sessions-sync-3).
     * PR2: gated by hasLiveSession, single supervisor channel sessions:uid, per-id upsert onConflict id.
     */
    private suspend fun processSessionItem(
        item: com.nexo.data.local.entity.SyncOutboxEntity,
        userId: String,
    ) {
        if (!hasLiveSession()) return
        val payload =
            try {
                JSONObject(item.payloadJson)
            } catch (_: Exception) {
                // Malformed payload — ack-via-OutboxCommit so the bad row is pruned
                // instead of getting stuck in a poison-loop.
                commitOutbox(item) { ApplyOutcome.Ok }
                return
            }

        val id = payload.optString("id", "")
        val bookId = payload.optString("bookId", item.entityId ?: "")
        val startTimeEpochMillis = payload.optLong("startTimeEpochMillis", 0L)
        val durationMinutes = payload.optInt("durationMinutes", 0)
        val date = payload.optLong("date", 0L)
        val updatedAtEpochMillis = payload.optLong("updatedAtEpochMillis", 0L)

        if (id.isBlank() || bookId.isBlank() || durationMinutes <= 0) {
            // Invalid payload fields — same treatment as malformed JSON above.
            commitOutbox(item) { ApplyOutcome.Ok }
            return
        }

        val row =
            ReadingSessionRow(
                id = id,
                userId = userId,
                bookId = bookId,
                startedAt = dateFormat.format(Date(startTimeEpochMillis)),
                durationMinutes = durationMinutes,
                date = dateFormat.format(Date(date)),
                device = "android",
                updatedAt = dateFormat.format(Date(updatedAtEpochMillis)),
            )

        commitOutbox(item) {
            try {
                dataSource.upsertReadingSession(row)
                ApplyOutcome.Ok
            } catch (e: Exception) {
                ApplyOutcome.Retryable(e)
            }
        }
    }

    /**
     * Apply a remote reading session (REQ-reading-sessions-sync-4).
     *
     * FK guard first: reading_sessions.book_id references books.id, so a remote
     * session for a book not present locally is skipped (SCEN-sync-6). Then LWW:
     * the remote `updated_at` beats the local `updatedAtEpochMillis`; on equal or
     * older remote clocks nothing changes. The deterministic id REPLACEs the local
     * row (insert REPLACE by PK — SCEN-sync-7).
     */
    internal suspend fun applyRemoteSession(row: ReadingSessionRow): Boolean {
        if (bookDao.getBookById(row.bookId) == null) return false

        val remoteTime =
            try {
                dateFormat.parse(row.updatedAt)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }

        val local = readingSessionDao.getById(row.id)
        val shouldApply =
            when {
                local == null -> true
                remoteTime > local.updatedAtEpochMillis -> true
                remoteTime < local.updatedAtEpochMillis -> false
                else -> row.id > local.id
            }
        if (shouldApply) {
            readingSessionDao.insert(
                ReadingSessionEntity(
                    id = row.id,
                    bookId = row.bookId,
                    startTimeEpochMillis =
                        try {
                            dateFormat.parse(row.startedAt)?.time ?: 0L
                        } catch (_: Exception) {
                            0L
                        },
                    durationMinutes = row.durationMinutes,
                    date =
                        try {
                            dateFormat.parse(row.date)?.time ?: 0L
                        } catch (_: Exception) {
                            0L
                        },
                    userId = row.userId,
                    updatedAtEpochMillis = remoteTime,
                ),
            )
            return true
        }
        return false
    }

    /**
     * Stop periodic processing and unsubscribe from Realtime.
     */
    suspend fun stop() {
        processJob?.cancel()
        processJob = null
        realtimeJob?.cancel()
        realtimeJob = null
        dataSource.unsubscribeAll()
        _state.value = State.Idle
    }

    companion object {
        private const val TAG = "SupabaseProgressSync"

        /** How many times to re-check that a freshly-downloaded book exists locally. */
        internal const val PULL_BOOK_READY_MAX_ATTEMPTS = 5

        /** Delay between book-readiness checks (5 * 400ms = up to ~2s total). */
        internal const val PULL_BOOK_READY_RETRY_DELAY_MS = 400L

        internal const val GATED_FLUSH_MAX_ATTEMPTS = 6
        internal const val GATED_BACKOFF_BASE_MS = 5_000L
        internal const val GATED_BACKOFF_CAP_MS = 160_000L
        internal const val GATED_PLATEAU_MS = 60_000L
    }
}
