package com.nexo.debug

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One recent-error row shown by the debug toolkit. [message] is already PII-scrubbed. */
data class DebugErrorEntry(
    val timestampMs: Long,
    val message: String,
)

/** Outcome of clearing the children of a cache directory. */
data class CacheClearReport(
    val deletedEntries: Int,
    val deletedBytes: Long,
    val failedEntries: Int,
)

/**
 * Local-only helpers backing the Android debug toolkit extras (FR-AD2).
 *
 * Everything here is plain `java.io` plus the existing [SentryPiiScrubber] so
 * every branch runs in a JVM unit test without an Android framework. These
 * helpers never touch the network, never upload, and only ever mutate the
 * directory they are explicitly handed.
 */
object DebugToolkit {
    private const val DEFAULT_ERROR_LIMIT = 5
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm"
    const val PII_REDACTED = "[Redacted]"

    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val UUID_REGEX =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * Reads the newest `crash_*.txt` files written by [NexoApplication]'s crash
     * handler and returns scrubbed summaries, newest first. Returns an empty
     * list when [crashDir] is missing, unreadable, or has no crash files.
     */
    fun recentErrors(
        crashDir: File,
        limit: Int = DEFAULT_ERROR_LIMIT,
    ): List<DebugErrorEntry> {
        if (limit <= 0) return emptyList()
        val files =
            crashDir
                .listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash_") && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: return emptyList()
        return files.take(limit).mapNotNull(::parseError)
    }

    /**
     * Strips PII from raw crash text before it is rendered. Layered on top of
     * [SentryPiiScrubber.redactStringMessage], then redacts bare emails and
     * UUID-shaped ids (local user ids are UUIDs) that the key/value scrubber
     * does not catch.
     */
    fun scrubForDisplay(raw: String): String {
        val keyed = SentryPiiScrubber.redactStringMessage(raw)
        val withoutEmails = EMAIL_REGEX.replace(keyed, PII_REDACTED)
        return UUID_REGEX.replace(withoutEmails, PII_REDACTED)
    }

    /**
     * Clears the CHILDREN of [cacheDir] and leaves the directory itself in
     * place. Never follows into `filesDir`, Room data, or the library tree —
     * callers only pass `context.cacheDir`. Deleting a directory counts as one
     * entry; [CacheClearReport.deletedBytes] tracks regular files only.
     */
    fun clearCache(cacheDir: File): CacheClearReport {
        val children = cacheDir.listFiles() ?: return CacheClearReport(0, 0L, 0)
        var deleted = 0
        var bytes = 0L
        var failed = 0
        for (child in children) {
            val size = if (child.isFile) child.length() else 0L
            if (child.deleteRecursively()) {
                deleted++
                bytes += size
            } else {
                failed++
            }
        }
        return CacheClearReport(deleted, bytes, failed)
    }

    /** Formats an epoch-millis instant for display; callers decide the null fallback. */
    fun formatTimestamp(
        timestampMs: Long,
        locale: Locale = Locale.getDefault(),
    ): String = SimpleDateFormat(TIMESTAMP_PATTERN, locale).format(Date(timestampMs))

    private fun parseError(file: File): DebugErrorEntry? =
        runCatching {
            val lines = file.readLines()
            val timestamp =
                lines
                    .firstOrNull { it.startsWith("Timestamp:") }
                    ?.removePrefix("Timestamp:")
                    ?.trim()
                    ?.toLongOrNull() ?: file.lastModified()
            val message =
                lines
                    .firstOrNull { it.startsWith("Message:") }
                    ?.removePrefix("Message:")
                    ?.trim()
                    .orEmpty()
            DebugErrorEntry(timestampMs = timestamp, message = scrubForDisplay(message))
        }.getOrNull()
}
