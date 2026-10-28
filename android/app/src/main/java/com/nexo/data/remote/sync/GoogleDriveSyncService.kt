package com.nexo.data.remote.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.SyncFileMappingDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncFileMappingEntity
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.session.SessionManager
import com.nexo.data.sync.DriveFilename
import com.nexo.data.sync.DriveWriteGuard
import com.nexo.debug.DebugLog
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Implements [SyncService] using Google Drive REST API v3 for file storage
 * and [GoogleDriveJsonStateSync] for reading progress, highlights, and bookmark sync.
 *
 * Handles all 4 outbox types: BOOK, READING_PROGRESS, HIGHLIGHT, BOOKMARK.
 */
class GoogleDriveSyncService(
    private val outboxDao: SyncOutboxDao,
    private val bookDao: BookDao,
    private val mappingDao: SyncFileMappingDao,
    private val highlightDao: HighlightDao,
    private val bookmarkDao: BookmarkDao,
    private val sessionManager: SessionManager,
    private val remoteDataSource: StorageSyncRemoteDataSource,
    private val localBooksDir: File,
    private val isEnabled: () -> Boolean = { false },
    private val tokenRefresher: suspend () -> Result<String> = {
        Result.failure(AppError(ErrorCategory.CONFIG_ERROR, "SYNC_NO_REFRESHER", "Drive token refresher not configured.", COMPONENT))
    },
    private val diagnosticError: AppError? = null,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /**
     * Process-lifetime scope for background pushes (NEXO-ANDROID-R). Injectable
     * so tests can drive a deterministic dispatcher; production uses
     * [Dispatchers.IO]. See [DrivePushScope] for why the upload must not live on
     * a caller's scope.
     */
    private val pushScope: DrivePushScope = DrivePushScope(),
) : SyncService {
    private val state = MutableStateFlow<DriveSyncState>(if (isEnabled()) DriveSyncState.Idle else DriveSyncState.Disabled)

    /**
     * Owner of the current sync session, or `""` when none is active. Set by
     * [bootstrap]; used by diagnostics that run before a session is resolved.
     */
    @Volatile
    private var activeUserId: String = ""

    override val syncState: Flow<DriveSyncState> = state.asStateFlow()

    /**
     * The Drive facade has no ambient owner: the depth gauge is owned by
     * [SyncOrchestrator], which subscribes to the outbox already scoped to the
     * active account. Exposing the raw table count here would report every
     * account's parked rows, so the facade reports nothing.
     */
    override val pendingCount: Flow<Int> = flowOf(0)

    override suspend fun bootstrap(userId: String): Result<Unit> {
        val disabledError = ensureDriveAuthorized("bootstrap")
        if (disabledError != null) {
            return Result.failure(disabledError)
        }
        if (userId.isBlank()) {
            return Result.failure(
                AppError(
                    category = ErrorCategory.WIRING_ERROR,
                    code = "SYNC_BOOTSTRAP_INVALID_USER",
                    message = "Sync bootstrap requires a non-empty user id.",
                    component = COMPONENT,
                ),
            )
        }
        ownerBooksDir(userId).mkdirs()
        activeUserId = userId
        state.value = DriveSyncState.Idle
        return Result.success(Unit)
    }

    /**
     * The active account's own subtree of [localBooksDir] (T8). A pull writes
     * only here, so two accounts sharing a device never resolve the same file.
     */
    private fun ownerBooksDir(userId: String): File = File(localBooksDir, userId)

    private suspend fun hasLiveSession(): Boolean = sessionManager.getCurrentSession().getOrNull() != null

    /**
     * Gate every Drive entry point behind a live token, attempting a refresh
     * first.
     *
     * A stored access token expires (~1h). Before this gate existed the only
     * check was `isEnabled()` — "is a non-blank token string stored?" — so once
     * the token expired (or was never persisted, as measured on the device where
     * `nexo_drive_token_store.xml` held only its keysets) every push declined in
     * silence and the outbox parked a book for days. The refresher is the only
     * path that can restore a usable token without the user re-authorizing.
     *
     * @return null when Drive is usable, or the error to fail the caller with.
     */
    private suspend fun ensureDriveAuthorized(caller: String): AppError? {
        if (!isEnabled() && tokenRefresher().isSuccess) {
            DebugLog.info(COMPONENT, "$caller: Drive token refreshed, proceeding")
        }
        if (isEnabled()) {
            return null
        }
        val disabledError =
            diagnosticError ?: AppError(
                category = ErrorCategory.CONFIG_ERROR,
                code = "SYNC_DISABLED",
                message = "Sync service is disabled due to Google Drive configuration.",
                component = COMPONENT,
            )
        // Contract (READER-AND-SYNC-CONTRACT §"failure is logged"): an
        // unauthorized Drive is the most common reason an imported book never
        // reaches the cloud, and it used to fail with NO log at all. The pending
        // count is included so a parked outbox is traceable to its cause and to
        // how many rows it is holding back.
        val pending = runCatching { outboxDao.getPendingItems(activeUserId).size }.getOrDefault(-1)
        DebugLog.warn(
            COMPONENT,
            "$caller skipped: Drive unauthorized (${disabledError.code}); " +
                "token refresh failed, $pending outbox item(s) stay parked",
        )
        state.value = DriveSyncState.Disabled
        return disabledError
    }

    override fun schedulePushInBackground(): Job =
        // The push runs on [DrivePushScope], NOT on any caller scope, so
        // navigating away or recreating the Activity cannot cancel it. The
        // outcome is logged here because this is the only place that sees it —
        // the UI no longer awaits the result, which is the whole point: awaiting
        // it from the UI is what tied the upload's lifetime to the UI.
        pushScope.launch {
            val result = runCatching { schedulePush() }
            result
                .onSuccess { push ->
                    if (push.isFailure) {
                        val mapped = mapError(push.exceptionOrNull(), defaultCode = "SYNC_BOOK_PUSH_FAILED")
                        DebugLog.warn(COMPONENT, "background push declined: ${mapped.code} — ${mapped.message}")
                    } else {
                        DebugLog.info(COMPONENT, "background push completed")
                    }
                }.onFailure { thrown ->
                    DebugLog.error(COMPONENT, "background push threw: ${thrown.message}")
                }
        }

    override suspend fun schedulePush(): Result<Unit> {
        val disabledError = ensureDriveAuthorized("schedulePush")
        if (disabledError != null) {
            return Result.failure(disabledError)
        }
        val session =
            sessionManager.ensureFreshSession().getOrElse { error ->
                val mapped = mapError(error, defaultCode = "SYNC_SESSION_REQUIRED")
                DebugLog.warn(COMPONENT, "schedulePush skipped: no live session (${mapped.code})")
                state.value = DriveSyncState.Error(mapped.message)
                return Result.failure(mapped)
            }

        state.value = DriveSyncState.Running
        // Drain only this session's queue: an unscoped drain would consume
        // another account's parked operations under this user.
        val pendingItems = outboxDao.getPendingItems(session.userId)
        DebugLog.info(COMPONENT, "schedulePush: ${pendingItems.size} pending outbox item(s) for user ${session.userId}")

        for (item in pendingItems) {
            when (item.entityType) {
                SyncEntityType.BOOK.name -> {
                    val pushResult = pushBook(item, session.userId)
                    if (pushResult.isFailure) {
                        val mapped = mapError(pushResult.exceptionOrNull(), defaultCode = "SYNC_BOOK_PUSH_FAILED")
                        DebugLog.error(COMPONENT, "pushBook failed for ${item.entityId}: ${mapped.code} — ${mapped.message}")
                        outboxDao.incrementRetryCount(item.id, session.userId, mapped.message)
                        outboxDao.pruneFailedItems(session.userId, maxRetries)
                        state.value = DriveSyncState.Error(mapped.message)
                        return Result.failure(mapped)
                    }
                    outboxDao.deleteById(item.id, session.userId)
                }
                SyncEntityType.READING_PROGRESS.name,
                SyncEntityType.HIGHLIGHT.name,
                SyncEntityType.BOOKMARK.name,
                SyncEntityType.READING_SESSION.name,
                -> {
                    // PR4: Drive is cold only — Supabase owns all hot state
                    // (progress/highlights/bookmarks/sessions via PostgREST onConflict
                    // + single Realtime supervisor). Leave these outbox rows for
                    // SupabaseProgressSync; never consume them here (prevents empty
                    // payload replay and silent session loss).
                    continue
                }
                else -> {
                    // Unknown types are silently skipped and removed
                    outboxDao.deleteById(item.id, session.userId)
                }
            }
        }

        state.value = DriveSyncState.Idle
        return Result.success(Unit)
    }

    private suspend fun pushBook(
        item: com.nexo.data.local.entity.SyncOutboxEntity,
        userId: String,
    ): Result<Unit> {
        if (item.operation == SyncOperation.DELETE.name) {
            // DELETE ops for books are handled by removing the outbox entry;
            // the actual Drive file can be cleaned up lazily.
            DebugLog.info(COMPONENT, "pushBook skipped for ${item.entityId}: DELETE op acked, no binary push")
            return Result.success(Unit)
        }

        // entityId is nullable: FK is ON DELETE SET NULL, so a deleted book leaves the outbox
        // row with a null entity_id. Treat that as "nothing to push" and ack the row.
        val entityId = item.entityId
        if (entityId == null) {
            DebugLog.info(COMPONENT, "pushBook skipped: outbox item ${item.id} has no entity id (book deleted)")
            return Result.success(Unit)
        }

        val book =
            bookDao.getBookById(entityId, userId)
                ?: run {
                    DebugLog.info(COMPONENT, "pushBook skipped for $entityId: book no longer exists locally")
                    return Result.success(Unit)
                } // Book deleted locally, skip

        if (book.deletedAtEpochMillis != null) {
            DebugLog.info(COMPONENT, "pushBook skipped for ${book.id}: book is tombstoned locally")
            return Result.success(Unit)
        }

        val localFile = File(book.filePath)
        if (!localFile.exists()) {
            return Result.failure(
                AppError(
                    category = ErrorCategory.WIRING_ERROR,
                    code = "SYNC_LOCAL_FILE_MISSING",
                    message = "Local file is missing for book ${book.id}.",
                    component = COMPONENT,
                ),
            )
        }

        val extension = extensionFor(book)
        val drivePath = drivePathFor(userId, book.id, extension)
        val canonical = DriveFilename.canonical(book.id)
        val objectName = DriveWriteGuard.objectName(canonical, extension)
        // New uploads use the readable `Title -- id.ext` physical Drive name so
        // the user can identify the file. The logical `drivePath` (identity used
        // for remotePath + file mappings) stays canonical, so nothing that keys
        // off it changes. A pre-existing id-only object is NEVER duplicated: when
        // the canonical name already has a remote object (a legacy upload from
        // another device), the write targets that canonical name instead of
        // creating a second readable file.
        val canonicalAlreadyRemote = runCatching { remoteDataSource.readMarker(objectName) }.getOrNull() != null
        val readableName =
            if (canonicalAlreadyRemote) null else DriveFilename.readableObjectName(book.title, book.id, extension)
        if (canonicalAlreadyRemote) {
            // A legacy id-only object already exists for this book, so the push
            // targets that canonical name instead of uploading a readable twin
            // (which would duplicate the book in Drive). Record the decision: the
            // resulting file keeps the old name on purpose.
            DebugLog.info(
                COMPONENT,
                "pushBook ${book.id}: canonical object $objectName already remote — pushing under the canonical name, not a readable twin",
            )
        } else {
            DebugLog.info(COMPONENT, "pushBook ${book.id}: uploading readable object $readableName")
        }
        val uploadResult =
            retryable {
                DriveWriteGuard.uploadWithRetry(
                    port = remoteDataSource,
                    book = canonical,
                    extension = extension,
                    bytes = localFile.readBytes(),
                    // `rememberBase` stores under the RESOLVED (readable) name, so
                    // the recall must use the same resolved key or the in-session
                    // base cache never hits on a re-push. The real version is still
                    // read from Drive either way; this only restores the shortcut.
                    loadBase = {
                        DriveWriteGuard.recallBase(
                            DriveWriteGuard.resolveObjectName(canonical, extension, readableName),
                        )
                    },
                    readableName = readableName,
                )
            }

        if (uploadResult.isFailure) {
            return uploadResult.map { }
        }

        // Persist the remote path on the local book so the Supabase catalog
        // sync can register it as cloud-available (it defers rows without a
        // remote file to avoid creating ghost downloads). Without this, a
        // locally imported book would stay invisible to other devices even
        // though its file IS in Drive.
        if (book.remotePath != drivePath) {
            bookDao.upsert(book.copy(remotePath = drivePath))
        }

        // Look up existing mapping to get driveFileId, or use the path as ID
        val existingMapping = mappingDao.getByDriveFileId(drivePath)
        val driveFileId = existingMapping?.driveFileId ?: drivePath

        mappingDao.upsert(
            SyncFileMappingEntity(
                driveFileId = driveFileId,
                userId = userId,
                bookId = book.id,
                localPath = book.filePath,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        DebugLog.info(COMPONENT, "pushBook OK for ${book.id} — remotePath=$drivePath")
        return Result.success(Unit)
    }

    override suspend fun schedulePull(): Result<Unit> {
        val disabledError = ensureDriveAuthorized("schedulePull")
        if (disabledError != null) {
            return Result.failure(disabledError)
        }
        val session =
            sessionManager.ensureFreshSession().getOrElse { error ->
                val mapped = mapError(error, defaultCode = "SYNC_SESSION_REQUIRED")
                state.value = DriveSyncState.Error(mapped.message)
                return Result.failure(mapped)
            }

        state.value = DriveSyncState.Running
        val userPrefix = "books/${session.userId}/"
        val remotePaths =
            retryable { remoteDataSource.list(prefix = userPrefix) }
                .getOrElse { error ->
                    val mapped = mapError(error, defaultCode = "SYNC_LIST_FAILED")
                    state.value = DriveSyncState.Error(mapped.message)
                    return Result.failure(mapped)
                }

        for (remotePath in remotePaths.distinct()) {
            if (remotePath.endsWith("_state.json") || remotePath.endsWith("/state.json")) {
                // State JSON files are handled via pullState below
                continue
            }

            val mapping = mappingDao.getByDriveFileId(remotePath)
            val parsed =
                parseDrivePath(remotePath)
                    ?: continue
            val bookId = mapping?.bookId ?: parsed.bookId
            val extension = parsed.extension

            // D6: never resurrect a locally DELETE-marked book.
            if (isTombstoned(bookId, session.userId)) {
                continue
            }

            val existingBook = bookDao.getBookById(bookId, session.userId)

            // CRITICAL: do NOT create local books from a raw Drive pull. A remote
            // file without an existing local book or mapping belongs to the Supabase
            // catalog flow ("available from other devices" + explicit Download),
            // which carries the real title/metadata. Auto-creating here produced
            // books titled "Recovered {uuid}" and bypassed the catalog download UI.
            if (existingBook == null && mapping == null) {
                DebugLog.info(
                    COMPONENT,
                    "schedulePull: skipping unknown remote file $remotePath (no local book/mapping) — catalog owns new imports",
                )
                continue
            }

            val localPath =
                mapping?.localPath
                    ?: existingBook?.filePath
                    ?: File(ownerBooksDir(session.userId), "$bookId.$extension").absolutePath
            val localFile = File(localPath)

            if (!localFile.exists()) {
                val bytes =
                    retryable { remoteDataSource.download(remotePath) }
                        .getOrElse { error ->
                            val mapped = mapError(error, defaultCode = "SYNC_DOWNLOAD_FAILED")
                            state.value = DriveSyncState.Error(mapped.message)
                            return Result.failure(mapped)
                        }
                localFile.parentFile?.mkdirs()
                localFile.writeBytes(bytes)
            }

            // Seed the FR-08 guard base from the remote marker so a later push in
            // this session compares against the version this device just saw.
            // Best-effort: a missing/unreadable marker must not fail the pull.
            val physicalName = remotePath.substringAfterLast('/')
            runCatching { remoteDataSource.readMarker(physicalName) }
                .getOrNull()
                ?.let { DriveWriteGuard.rememberBase(physicalName, it) }

            val mergedBook =
                mergeBook(
                    existing = existingBook,
                    bookId = bookId,
                    localPath = localFile.absolutePath,
                    extension = extension,
                )
            bookDao.upsert(mergedBook)
            mappingDao.upsert(
                SyncFileMappingEntity(
                    driveFileId = remotePath,
                    userId = session.userId,
                    bookId = bookId,
                    localPath = localFile.absolutePath,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }

        // PR4: Drive hot state pull removed — Supabase is sole hot SoT.
        // No jsonStateSync.pullState here; cold import is via DriveColdBackupService only.
        state.value = DriveSyncState.Idle
        return Result.success(Unit)
    }

    private fun mergeBook(
        existing: BookEntity?,
        bookId: String,
        localPath: String,
        extension: String,
    ): BookEntity {
        // Only ever called with an existing local book (schedulePull skips unknown
        // remote files — the Supabase catalog owns new imports). Keep the original
        // title/metadata; only refresh the local path. D6: never clear deletedAt.
        require(existing != null) { "mergeBook requires an existing local book (id=$bookId)" }
        return existing.copy(
            filePath = localPath,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Skips a remote book when it is locally DELETE-marked (tombstoned), so
     * schedulePull/downloadRemoteBook never resurrect it (D6).
     */
    private suspend fun isTombstoned(
        bookId: String,
        userId: String,
    ): Boolean = bookDao.getBookById(bookId, userId)?.deletedAtEpochMillis != null

    private suspend fun <T> retryable(block: suspend () -> T): Result<T> {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < MAX_ATTEMPTS) {
            val result = runCatching { block() }
            if (result.isSuccess) {
                return result
            }
            val error = result.exceptionOrNull()
            if (isUnauthorized(error)) {
                return driveCall(block = block, lastError = error)
            }
            if (!isTransient(error)) {
                return Result.failure(error ?: IllegalStateException("Unknown sync failure"))
            }
            lastError = error
            attempt++
        }
        return Result.failure(lastError ?: IllegalStateException("Sync retries exhausted"))
    }

    /**
     * Handles Drive 401/403 (D4): refreshes the access token once, waits for the
     * caller to rebuild the source (the refreshed token is read lazily on next
     * call), and retries the operation once. On refresh failure it surfaces an
     * "authorization needed" state instead of failing silently.
     */
    private suspend fun <T> driveCall(
        block: suspend () -> T,
        lastError: Throwable?,
    ): Result<T> {
        return runCatching { tokenRefresher() }
            .fold(
                onSuccess = { refreshResult ->
                    if (refreshResult.isFailure) {
                        state.value = DriveSyncState.AuthorizationNeeded
                        return Result.failure(
                            refreshResult.exceptionOrNull()
                                ?: lastError ?: IllegalStateException("Drive authorization needed"),
                        )
                    }
                    runCatching { block() }.let { retry ->
                        if (retry.isSuccess) {
                            retry
                        } else {
                            Result.failure(retry.exceptionOrNull() ?: lastError ?: IllegalStateException("Drive retry failed"))
                        }
                    }
                },
                onFailure = { refreshThrown ->
                    state.value = DriveSyncState.AuthorizationNeeded
                    Result.failure(refreshThrown)
                },
            )
    }

    private fun isUnauthorized(error: Throwable?): Boolean {
        val authError = error as? AppError
        return authError?.category == ErrorCategory.AUTH ||
            authError?.code == "GOOGLE_DRIVE_UNAUTHORIZED"
    }

    private fun isTransient(error: Throwable?): Boolean =
        error is IOException ||
            error is AppError

    private fun mapError(
        error: Throwable?,
        defaultCode: String,
    ): AppError {
        if (error is AppError) {
            return error
        }
        val category =
            when (error) {
                null -> ErrorCategory.WIRING_ERROR
                is IllegalStateException -> ErrorCategory.WIRING_ERROR
                is IllegalArgumentException -> ErrorCategory.CONFIG_ERROR
                is IOException -> ErrorCategory.WIRING_ERROR
                else -> ErrorCategory.WIRING_ERROR
            }
        return AppError(
            category = category,
            code = defaultCode,
            message = error?.message ?: "Sync operation failed.",
            component = COMPONENT,
        )
    }

    private fun drivePathFor(
        userId: String,
        bookId: String,
        extension: String,
    ): String = DriveFilename.objectPath(userId, DriveFilename.canonical(bookId), extension)

    private fun extensionFor(book: BookEntity): String {
        val fromFormat = book.format.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
        val candidate = fromFormat.ifBlank { File(book.filePath).extension.lowercase(Locale.ROOT) }
        return DriveFilename.canonicalExtension(candidate)
    }

    private fun parseDrivePath(drivePath: String): ParsedDrivePath? {
        val parsed = DriveFilename.parseDrivePath(drivePath) ?: return null
        return ParsedDrivePath(bookId = parsed.stem, extension = parsed.extension)
    }

    private data class ParsedDrivePath(
        val bookId: String,
        val extension: String,
    )

    /**
     * Idempotent stop for [SyncOrchestrator] fan-out (PR-2 of sync-layer-split).
     *
     * Drive push/pull are call-site driven (no persistent background job), so
     * `stop()` only flips the visible state to [DriveSyncState.Disabled] and
     * logs. Any in-flight `schedulePush()` / `schedulePull()` completes on its
     * own coroutine and self-marks the appropriate state on completion.
     */
    override suspend fun stop() {
        DebugLog.info(COMPONENT, "stop: disabling Drive sync facade")
        state.value = DriveSyncState.Disabled
    }

    companion object {
        const val COMPONENT = "GoogleDriveSyncService"
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_MAX_RETRIES = 3
    }
}

// Extension functions for Entity ↔ Domain conversion used in state sync
// These mirror the conversions in ReaderRepositoryImpl

private fun com.nexo.data.local.entity.ReadingProgressEntity.toDomain(): com.nexo.domain.model.ReadingProgress =
    com.nexo.domain.model.ReadingProgress(
        id = id,
        bookId = bookId,
        cfiLocation = cfiLocation,
        percentage = percentage,
        currentPage = currentPage,
        updatedAtEpochMillis = updatedAtEpochMillis,
        locatorJson = locatorJson,
    )

private fun com.nexo.domain.model.ReadingProgress.toEntity(): com.nexo.data.local.entity.ReadingProgressEntity =
    com.nexo.data.local.entity.ReadingProgressEntity(
        id = id,
        bookId = bookId,
        cfiLocation = cfiLocation,
        percentage = percentage,
        currentPage = currentPage,
        updatedAtEpochMillis = updatedAtEpochMillis,
        locatorJson = locatorJson,
    )

private fun com.nexo.data.local.entity.HighlightEntity.toDomain(): com.nexo.domain.model.Highlight =
    com.nexo.domain.model.Highlight(
        id = id,
        bookId = bookId,
        cfiRange = cfiRange,
        textContent = textContent,
        note = note,
        color = color,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis,
        locatorJson = locatorJson,
        type = type,
        tag = tag,
    )

private fun com.nexo.domain.model.Highlight.toEntity(): com.nexo.data.local.entity.HighlightEntity =
    com.nexo.data.local.entity.HighlightEntity(
        id = id,
        bookId = bookId,
        cfiRange = cfiRange,
        textContent = textContent,
        note = note,
        color = color,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis,
        locatorJson = locatorJson,
        type = type,
        tag = tag,
    )

private fun com.nexo.data.local.entity.BookmarkEntity.toDomain(): com.nexo.domain.model.Bookmark =
    com.nexo.domain.model.Bookmark(
        id = id,
        bookId = bookId,
        cfiLocation = cfiLocation,
        titleOrSnippet = titleOrSnippet,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis,
        locatorJson = locatorJson,
    )

private fun com.nexo.domain.model.Bookmark.toEntity(): com.nexo.data.local.entity.BookmarkEntity =
    com.nexo.data.local.entity.BookmarkEntity(
        id = id,
        bookId = bookId,
        cfiLocation = cfiLocation,
        titleOrSnippet = titleOrSnippet,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis,
        locatorJson = locatorJson,
    )
