package com.nexo.data.remote.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object DriveCatalogContract {
    const val PROVIDER = "google_drive"
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    // Cross-platform Drive protocol folder shared with desktop/web. Deliberately NOT
    // rebranded: existing users' Drive backups live under this exact name, so renaming
    // it would orphan them.
    const val BOOKS_PATH = "NextPage/Books"
    const val PROTOCOL_VERSION = 1

    fun canonicalBookName(
        bookId: String,
        format: String,
    ) = "$bookId.${format.removePrefix(".").lowercase()}"

    fun canonicalBookPath(
        bookId: String,
        format: String,
    ) = "$BOOKS_PATH/${canonicalBookName(bookId, format)}"

    suspend fun reconcileLegacyReference(
        id: String?,
        files: List<LegacyRemoteCandidate>,
        bookId: String,
        format: String,
        expectedHash: String?,
    ): RemoteReference? =
        withContext(Dispatchers.Default) {
            val name = canonicalBookName(bookId, format)
            val expected = expectedHash?.removePrefix("sha256:")
            val match = files.firstOrNull { it.fileId == id } ?: files.firstOrNull { it.name == name }

            suspend fun hash(file: LegacyRemoteCandidate) =
                runCatching {
                    MessageDigest.getInstance("SHA-256").digest(file.download()).joinToString("") { "%02x".format(it) }
                }.getOrNull()
            val resolved =
                when {
                    match != null && (expected == null || hash(match) == expected) -> match
                    match == null && expected != null -> files.firstOrNull { hash(it) == expected }
                    else -> null
                }
            resolved?.let { RemoteReference(PROVIDER, it.fileId, canonicalBookPath(bookId, format), name, PROTOCOL_VERSION) }
        }
}

enum class Lifecycle { AVAILABLE, IMPORTED, UNAVAILABLE, DELETED }

data class LegacyRemoteCandidate(
    val fileId: String,
    val name: String,
    val download: suspend () -> ByteArray,
)

data class RemoteReference(
    val provider: String,
    val fileId: String?,
    val canonicalPath: String,
    val fileName: String,
    val protocolVersion: Int,
)

data class CatalogRow(
    val userId: String,
    val bookId: String,
    val format: String,
    val contentHash: String?,
    val lifecycle: Lifecycle,
    val remoteRef: RemoteReference?,
    val catalogVersion: Long,
)

data class SyncError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val correlationId: String,
    val bookId: String? = null,
)

/** Stable cross-platform error codes (spec REQ-07). Shared verbatim with the TS contract. */
object SyncErrorCodes {
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val AUTH_EXPIRED = "AUTH_EXPIRED"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val REMOTE_NOT_FOUND = "REMOTE_NOT_FOUND"
    const val HASH_MISMATCH = "HASH_MISMATCH"
    const val CONFLICT = "CONFLICT"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val COVER_FAILED = "COVER_FAILED"
}

/**
 * Maps a cover save/upload/render failure to the stable COVER_FAILED error.
 * Cover failures are retryable and must never block book import.
 */
fun coverFailureError(
    correlationId: String,
    bookId: String? = null,
): SyncError = SyncError(SyncErrorCodes.COVER_FAILED, "Cover save/upload failed", retryable = true, correlationId = correlationId, bookId = bookId)

/** Deterministic catalog conflict winner: (catalog_version, updated_at, id) lexicographic. */
fun catalogRowWinner(
    current: CatalogRow,
    incoming: CatalogRow,
    updatedAt: (CatalogRow) -> String,
): CatalogRow {
    if (incoming.catalogVersion != current.catalogVersion) {
        return if (incoming.catalogVersion > current.catalogVersion) incoming else current
    }
    val currentUpdated = updatedAt(current)
    val incomingUpdated = updatedAt(incoming)
    if (incomingUpdated != currentUpdated) {
        return if (incomingUpdated > currentUpdated) incoming else current
    }
    return if (incoming.bookId > current.bookId) incoming else current
}

/** Redacted observability: structured code/correlation/latency only; never tokens, binaries, or user paths. */
fun redactLogLine(line: String): String {
    val tokenPattern = Regex("(?i)(token|authorization|bearer|password|refresh_token)=([^&\\s]+)")
    val jwtPattern = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    val hashPattern = Regex("[0-9a-fA-F]{64}")
    return tokenPattern
        .replace(line, "$1=[REDACTED]")
        .let { jwtPattern.replace(it, "[JWT_REDACTED]") }
        .let { hashPattern.replace(it, "[HASH_REDACTED]") }
}
