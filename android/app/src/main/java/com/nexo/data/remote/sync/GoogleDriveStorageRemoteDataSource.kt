package com.nexo.data.remote.sync

import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpResponseException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.nexo.data.remote.drive.DriveCatalogContract
import com.nexo.data.sync.DriveBooksFolder
import com.nexo.data.sync.DriveVersionMarker
import com.nexo.data.sync.DriveWriteGuard
import com.nexo.debug.DebugLog
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Implements [StorageSyncRemoteDataSource] using Google Drive REST API v3,
 * unifying Android on the **desktop protocol**.
 *
 * Files live in the shared `Nexo/books` protocol folder and are named
 * `{bookId}.{ext}` (no per-user subfolders). Lookup is by
 * `name='{bookId}.{ext}' and trashed=false`. Uses the `drive.file` scope.
 *
 * The interface still talks in **logical paths** of the form
 * `books/{userId}/{bookId}.{ext}` so [GoogleDriveSyncService] parsing stays
 * intact. Inside [GoogleDriveStorageRemoteDataSource]:
 * - upload/download receive the logical path and map it to the physical file `{bookId}.{ext}`.
 * - [list] reconstructs logical paths `books/{userId}/{fileName}` using the user id
 *   parsed from the supplied logical [prefix], so the caller keeps working unchanged.
 */
class GoogleDriveStorageRemoteDataSource(
    private val driveService: Drive,
    private val component: String = COMPONENT,
) : StorageSyncRemoteDataSource {
    override suspend fun upload(
        path: String,
        bytes: ByteArray,
    ) {
        // Google Drive REST calls are BLOCKING (non-suspend). They must run on
        // Dispatchers.IO — calling them on Main throws NetworkOnMainThreadException,
        // which is exactly what the Log Viewer showed (GOOGLE_DRIVE_UPLOAD_FAILED,
        // status=null, NetworkOnMainThreadException).
        withContext(Dispatchers.IO) {
            runCatching {
                val physicalName = path.substringAfterLast('/')
                val folder = ensureBooksFolder()

                val existing = findFileByName(folderId = folder, name = physicalName)

                val mediaContent = ByteArrayContent("application/octet-stream", bytes)

                if (existing != null) {
                    // Drive v3 rejects `parents` in update (PATCH) with 403
                    // "The parents field is not directly writable in update requests.
                    //  Use addParents/removeParents instead." File already lives in
                    //  `folder` (found via `'$folder' in parents` query), so no move
                    //  is needed — send only `name` (desktop GDriveProvider parity).
                    val updateMetadata =
                        File().apply {
                            name = physicalName
                        }
                    driveService.files().update(existing.id, updateMetadata, mediaContent).execute()
                } else {
                    val createMetadata =
                        File().apply {
                            name = physicalName
                            parents = listOf(folder)
                        }
                    driveService
                        .files()
                        .create(createMetadata, mediaContent)
                        .setFields("name")
                        .execute()
                }
            }.getOrElse { throwable ->
                throw mapDriveError(throwable, "GOOGLE_DRIVE_UPLOAD_FAILED", "Failed to upload to Google Drive: $path")
            }
        }
    }

    /**
     * FR-08 guard port: read the `{ nexoVersion, nexoChecksum }` marker from the
     * object's Drive `appProperties`. Returns null when the file or marker is
     * absent; the guard treats that as version 0.
     */
    override suspend fun readMarker(objectName: String): DriveVersionMarker? =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = booksFolderIdOrNull() ?: return@runCatching null
                val file = findFileByNameWithProperties(folderId = folder, name = objectName) ?: return@runCatching null
                DriveWriteGuard.markerFromAppProperties(file.appProperties)
            }.getOrElse { throwable ->
                throw mapDriveError(throwable, "GOOGLE_DRIVE_MARKER_READ_FAILED", "Failed to read the Drive marker for $objectName")
            }
        }

    /**
     * FR-08 guard port: write bytes + marker in one `files.update`/create so the
     * binary and its version marker move together.
     */
    override suspend fun writeBinary(
        objectName: String,
        bytes: ByteArray,
        marker: DriveVersionMarker,
    ): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = ensureBooksFolder()
                val existing = findFileByName(folderId = folder, name = objectName)
                val mediaContent = ByteArrayContent("application/octet-stream", bytes)
                val appProperties = DriveWriteGuard.appProperties(marker)
                if (existing != null) {
                    val updateMetadata =
                        File().apply {
                            name = objectName
                            this.appProperties = appProperties
                        }
                    driveService.files().update(existing.id, updateMetadata, mediaContent).execute()
                    existing.id
                } else {
                    val createMetadata =
                        File().apply {
                            name = objectName
                            parents = listOf(folder)
                            this.appProperties = appProperties
                        }
                    driveService
                        .files()
                        .create(createMetadata, mediaContent)
                        .setFields("id")
                        .execute()
                        .id
                }
            }.getOrElse { throwable ->
                throw mapDriveError(throwable, "GOOGLE_DRIVE_UPLOAD_FAILED", "Failed to upload to Google Drive: $objectName")
            }
        }

    override suspend fun download(path: String): ByteArray =
        withContext(Dispatchers.IO) {
            runCatching {
                val physicalName = path.substringAfterLast('/')
                val folder =
                    booksFolderIdOrNull()
                        ?: throw AppError(
                            category = ErrorCategory.NOT_FOUND,
                            code = "REMOTE_NOT_FOUND",
                            message = "File not found in Drive: $path",
                            component = component,
                        )
                val file =
                    findFileByName(folderId = folder, name = physicalName)
                        ?: throw AppError(
                            category = ErrorCategory.NOT_FOUND,
                            code = "REMOTE_NOT_FOUND",
                            message = "File not found in Drive: $path",
                            component = component,
                        )

                val outputStream = ByteArrayOutputStream()
                driveService
                    .files()
                    .get(file.id)
                    .executeMediaAndDownloadTo(outputStream)
                outputStream.toByteArray()
            }.getOrElse { throwable ->
                if (throwable is AppError) throw throwable
                throw mapDriveError(throwable, "GOOGLE_DRIVE_DOWNLOAD_FAILED", "Failed to download from Google Drive: $path")
            }
        }

    override suspend fun list(prefix: String): List<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = booksFolderIdOrNull() ?: return@runCatching emptyList()

                // Every file in the shared Nexo/books folder belongs to this
                // Drive account, so map physical names back under the caller's prefix.
                val userId = prefix.trim('/').substringAfter("books/").substringBefore('/')

                val files =
                    driveService
                        .files()
                        .list()
                        .setQ("'$folder' in parents and trashed = false")
                        .setSpaces("drive")
                        .setFields("files(name)")
                        .execute()
                files.files
                    .orEmpty()
                    .mapNotNull { it.name }
                    .map { name -> logicalPath(userId, name) }
            }.getOrElse { throwable ->
                throw mapDriveError(throwable, "GOOGLE_DRIVE_LIST_FAILED", "Failed to list files in Google Drive: $prefix")
            }
        }
    }

    override suspend fun getFileSize(path: String): Long? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val physicalName = path.substringAfterLast('/')
                val folder = booksFolderIdOrNull() ?: return@runCatching null
                val file = findFileByName(folderId = folder, name = physicalName) ?: return@runCatching null
                val size: Long? =
                    driveService
                        .files()
                        .get(file.id)
                        .setFields("size")
                        .execute()
                        .size
                        ?.toLong()
                size
            }.getOrNull()
        }
    }

    /**
     * Store paths [logical] (books/{userId}/{file}) so Sync parsing stays intact.
     */
    private fun logicalPath(
        userId: String,
        physicalName: String,
    ): String = "books/$userId/$physicalName".replace("//", "/")

    /**
     * Finds the shared `Nexo/books` folder id, creating it if missing.
     *
     * Memoized at companion level so concurrent callers (e.g. push of several
     * books in parallel, or push + state sync racing on first run) resolve the
     * SAME folder instead of creating duplicate trees (DRIVE_DUP_FOLDERS,
     * desktop parity).
     */
    private suspend fun ensureBooksFolder(): String {
        booksFolderId?.let { return it }
        booksFolderDeferred?.let { return it.await() }
        return coroutineScope {
            val deferred = async { resolveBooksFolder() }
            booksFolderDeferred = deferred
            deferred.await()
        }
    }

    /**
     * Adopt-or-rename the book folder (WU6). Canonical `books` is adopted when
     * present; a legacy `Books` folder is renamed IN PLACE once the remote
     * `manifest.json` proves the WU4 canonical filenames shipped
     * (`filenameVersion >= 1`). Until then the legacy folder stays live. A fresh
     * install never consults the gate.
     */
    private fun resolveBooksFolder(): String {
        val nexoId = ensureNexoRoot()
        val (canonical, legacy) = findBooksFolderPair(nexoId)
        val filenameVersion = if (canonical == null && legacy != null) readFilenameVersion(nexoId) else 0
        val id =
            when (val plan = DriveBooksFolder.plan(canonical?.id, legacy?.id, filenameVersion)) {
                is DriveBooksFolder.Plan.Adopt -> plan.folderId
                is DriveBooksFolder.Plan.Blocked -> plan.folderId
                is DriveBooksFolder.Plan.Rename -> {
                    renameFolder(plan.folderId, DriveBooksFolder.CANONICAL)
                    plan.folderId
                }
                DriveBooksFolder.Plan.Create ->
                    driveService
                        .files()
                        .create(
                            File().apply {
                                name = DriveBooksFolder.CANONICAL
                                mimeType = FOLDER_MIME
                                parents = listOf(nexoId)
                            },
                        ).setFields("id")
                        .execute()
                        .id
            }
        booksFolderId = id
        return id
    }

    private fun ensureNexoRoot(): String =
        findFolder(NEXO_FOLDER)
            ?: driveService
                .files()
                .create(
                    File().apply {
                        name = NEXO_FOLDER
                        mimeType = FOLDER_MIME
                    },
                ).setFields("id")
                .execute()
                .id

    /**
     * One lookup for both folder names. Drive's `name='...'` query is
     * case-sensitive, so probing `books` and `Books` together is what lets us
     * detect the case-sensitive duplicate instead of creating a third tree.
     */
    private fun findBooksFolderPair(nexoId: String): Pair<File?, File?> {
        val query =
            "(name='${DriveBooksFolder.CANONICAL}' or name='${DriveBooksFolder.LEGACY}') and " +
                "mimeType='$FOLDER_MIME' and trashed = false and '$nexoId' in parents"
        val files =
            driveService
                .files()
                .list()
                .setSpaces("drive")
                .setQ(query)
                .setFields("files(id, name)")
                .execute()
                .files
                .orEmpty()
        // A match without a name (mock/legacy response shape) is treated as
        // canonical so a found folder is never shadowed by a create (desktop
        // `findBooksFolders` parity).
        return files.firstOrNull { it.name == DriveBooksFolder.CANONICAL || it.name == null } to
            files.firstOrNull { it.name == DriveBooksFolder.LEGACY }
    }

    /** Rename a folder in place (`files.update`); id and children are preserved. */
    private fun renameFolder(
        folderId: String,
        name: String,
    ) {
        driveService
            .files()
            .update(folderId, File().apply { this.name = name })
            .setFields("id")
            .execute()
    }

    /** Remote `Nexo/manifest.json` filenameVersion; 0 when absent/unreadable. */
    private fun readFilenameVersion(nexoId: String): Int {
        val manifest = findFileByName(nexoId, MANIFEST_FILE) ?: return 0
        return runCatching {
            driveService
                .files()
                .get(manifest.id)
                .executeMediaAsInputStream()
                .bufferedReader()
                .use { it.readText() }
        }.mapCatching { JSONObject(it).optInt("filenameVersion", 0) }
            .getOrDefault(0)
    }

    private fun booksFolderIdOrNull(): String? {
        val nexoId = findFolder(NEXO_FOLDER) ?: return null
        val (canonical, legacy) = findBooksFolderPair(nexoId)
        return DriveBooksFolder.pickFolder(canonical?.id, legacy?.id)
    }

    /**
     * Locates the `Nexo` root folder by name.
     */
    private fun findFolder(
        name: String,
        parentId: String? = null,
    ): String? {
        val query =
            buildString {
                append("name='$name' and mimeType='$FOLDER_MIME' and trashed = false")
                if (parentId != null) append(" and '$parentId' in parents")
            }
        val files =
            driveService
                .files()
                .list()
                .setSpaces("drive")
                .setQ(query)
                .setFields("files(id, name, parents)")
                .execute()
        return files.files?.firstOrNull()?.id
    }

    /**
     * Find a (non-trashed) file by name within the given parent folder.
     */
    private fun findFileByName(
        folderId: String,
        name: String,
    ): File? {
        val query = "name='$name' and '$folderId' in parents and trashed = false"
        val files =
            driveService
                .files()
                .list()
                .setSpaces("drive")
                .setQ(query)
                .setFields("files(id, name)")
                .execute()
        return files.files?.firstOrNull { it.name == name }
    }

    /**
     * Like [findFileByName] but requests `appProperties` so the guard marker can
     * be read without a second `files.get` round-trip.
     */
    private fun findFileByNameWithProperties(
        folderId: String,
        name: String,
    ): File? {
        val query = "name='$name' and '$folderId' in parents and trashed = false"
        val files =
            driveService
                .files()
                .list()
                .setSpaces("drive")
                .setQ(query)
                .setFields("files(id, name, appProperties)")
                .execute()
        return files.files?.firstOrNull { it.name == name }
    }

    /**
     * Maps a Drive API failure to an [AppError], tagging HTTP 401/403 as an
     * AUTH error so sync can refresh the token and retry (see D4).
     * Also surfaces the full detail to the in-app LogViewer (Ajustes → Log Viewer
     * → Live) so sync failures are debuggable without adb.
     */
    private fun mapDriveError(
        throwable: Throwable,
        code: String,
        message: String,
    ): AppError {
        val statusCode = (throwable as? HttpResponseException)?.statusCode
        val unauthorized = statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN
        val category = if (unauthorized) ErrorCategory.AUTH else ErrorCategory.WIRING_ERROR
        val errorCode =
            when {
                statusCode == HTTP_UNAUTHORIZED -> "AUTH_EXPIRED"
                statusCode == HTTP_FORBIDDEN -> "PERMISSION_DENIED"
                code == "GOOGLE_DRIVE_FILE_NOT_FOUND" -> "REMOTE_NOT_FOUND"
                else -> code
            }
        DebugLog.error(
            component,
            "$errorCode: $message | status=$statusCode | ${throwable.javaClass.simpleName}: ${throwable.message}",
        )
        return AppError(
            category = category,
            code = errorCode,
            message = throwable.message ?: message,
            component = component,
        )
    }

    companion object {
        const val COMPONENT = "GoogleDriveStorageRemoteDataSource"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val MANIFEST_FILE = "manifest.json"
        private val NEXO_FOLDER = DriveCatalogContract.BOOKS_PATH.substringBefore('/')

        /**
         * Module-level (companion) folder cache shared across ALL data-source
         * instances. Without it, concurrent sync paths race to create duplicate
         * `Nexo/books` trees on first run.
         */
        private var booksFolderId: String? = null
        private var booksFolderDeferred: Deferred<String>? = null

        /** Reset the module-level folder cache (used by tests between cases). */
        fun __resetGDriveFolderCache() {
            booksFolderId = null
            booksFolderDeferred = null
        }
    }
}
