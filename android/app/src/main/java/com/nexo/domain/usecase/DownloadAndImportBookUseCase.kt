package com.nexo.domain.usecase

import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogFileDownloader
import com.nexo.domain.model.Book
import com.nexo.domain.model.BookImportRequest
import com.nexo.domain.model.DuplicateBookException
import com.nexo.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Observable lifecycle of a catalog "download → import" run.
 *
 * [Duplicate] is deliberately NOT part of [Failure]: "already in your library"
 * is a successful, idempotent outcome the UI renders as a neutral message.
 */
sealed interface DownloadImportState {
    data object Idle : DownloadImportState

    data class Downloading(
        val bytesSoFar: Long,
        val totalBytes: Long?,
    ) : DownloadImportState

    data object Importing : DownloadImportState

    data class Success(
        val book: Book,
    ) : DownloadImportState

    /** The book already exists in the library; no download was performed. */
    data class Duplicate(
        val message: String,
    ) : DownloadImportState

    /** [error] is the downloader's typed code, or `null` for import failures. */
    data class Failure(
        val error: CatalogErrorCode?,
    ) : DownloadImportState
}

/**
 * Downloads a catalog EPUB into internal storage, atomically renames it into
 * place and imports it into the library, exposing progress as a cold [Flow].
 *
 * Invariants:
 * - The deterministic `<sanitized-id>.part` download file is always deleted,
 *   on every exit path (success, failure, cancellation).
 * - The renamed `<sanitized-id>.epub` file is kept only when the import
 *   committed (the library references it); otherwise it is removed so a failed
 *   run never leaves an orphan.
 * - [CancellationException] is never mapped to [DownloadImportState.Failure].
 * - A duplicate detected up-front (metadata lookup) skips the network entirely;
 *   a duplicate detected at import time is still surfaced as
 *   [DownloadImportState.Duplicate], never as a failure.
 */
class DownloadAndImportBookUseCase(
    private val downloader: CatalogFileDownloader,
    private val importEpubBookUseCase: ImportEpubBookUseCase,
    private val libraryRepository: LibraryRepository,
    private val tempDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(book: CatalogBook): Flow<DownloadImportState> =
        channelFlow {
            send(DownloadImportState.Idle)

            val url = book.downloadUrl
            if (url.isNullOrBlank()) {
                send(DownloadImportState.Failure(CatalogErrorCode.UNAVAILABLE_DOWNLOAD))
                return@channelFlow
            }

            // Cheap pre-check: never touch the network for a book already owned.
            val existing =
                withContext(ioDispatcher) {
                    libraryRepository.findBookByTitleAndAuthor(book.title, book.authors.firstOrNull())
                }
            if (existing != null) {
                send(DownloadImportState.Duplicate(DUPLICATE_MESSAGE))
                return@channelFlow
            }

            tempDir.mkdirs()
            val key = sanitize(book.id)
            val partFile = File(tempDir, "$key.part")
            val finalFile = File(tempDir, "$key.epub")
            var committed = false

            try {
                // Deterministic name: a leftover partial from a previous attempt is
                // overwritten, never appended to.
                partFile.delete()
                send(DownloadImportState.Downloading(0L, null))
                downloader.download(url, partFile) { bytesSoFar, totalBytes ->
                    trySend(DownloadImportState.Downloading(bytesSoFar, totalBytes))
                }

                // Atomic same-volume move (`<id>.part` → `<id>.epub`).
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    throw CatalogException(CatalogErrorCode.UPSTREAM_ERROR, "atomic rename failed")
                }

                send(DownloadImportState.Importing)
                val result =
                    importEpubBookUseCase(
                        request = BookImportRequest(sourcePath = finalFile.path, fallbackTitle = book.title),
                        inputStreamProvider = { finalFile.inputStream() },
                    )
                result.fold(
                    onSuccess = { imported ->
                        committed = true
                        send(DownloadImportState.Success(imported))
                    },
                    onFailure = { error ->
                        if (error is DuplicateBookException) {
                            send(DownloadImportState.Duplicate(DUPLICATE_MESSAGE))
                        } else {
                            send(DownloadImportState.Failure(null))
                        }
                    },
                )
            } catch (err: CancellationException) {
                throw err
            } catch (err: CatalogException) {
                send(DownloadImportState.Failure(err.code))
            } catch (err: Throwable) {
                send(DownloadImportState.Failure(null))
            } finally {
                partFile.delete()
                if (!committed) finalFile.delete()
            }
        }

    private companion object {
        const val DUPLICATE_MESSAGE = "Book already in the library"

        /** Filesystem-safe, deterministic per-book id used for the temp names. */
        fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
