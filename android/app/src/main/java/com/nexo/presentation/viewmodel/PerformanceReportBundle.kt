package com.nexo.presentation.viewmodel

import com.nexo.debug.DebugErrorEntry
import com.nexo.debug.DebugLog
import com.nexo.debug.DebugToolkit
import com.nexo.debug.LogRedaction
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure assembly of the diagnostics ZIP entries (G3). Deliberately Android-free
 * (all inputs are values) so the bundle contract — which entries exist, that
 * logs are capped and redacted, and that diagnostics report honest nulls — is
 * verifiable in a plain JVM unit test.
 *
 * The bundle NEVER carries book content, highlight text, absolute paths, or
 * secrets: the log tail is a defense-in-depth re-application of
 * [LogRedaction.redactString] over already-redacted lines, and the crash rows
 * are pre-scrubbed by [DebugToolkit.recentErrors].
 */
object PerformanceReportBundle {
    /** Matches CrashLogStore.SNAPSHOT_LINE_LIMIT. */
    const val LOG_TAIL_LINE_LIMIT = 200

    /** Device/build identity captured at assembly time. */
    data class BuildInfo(
        val model: String,
        val manufacturer: String,
        val sdk: Int,
        val appVersion: String,
        val versionCode: Int,
        val gitSha: String,
        val buildTime: String,
    )

    fun deviceTxt(
        info: BuildInfo,
        timestamp: String,
    ): String =
        buildString {
            appendLine("model=${info.model}")
            appendLine("manufacturer=${info.manufacturer}")
            appendLine("sdk=${info.sdk}")
            appendLine("appVersion=${info.appVersion}")
            appendLine("versionCode=${info.versionCode}")
            appendLine("gitSha=${info.gitSha}")
            appendLine("buildTime=${info.buildTime}")
            // No stable per-install identifier exists on-device; report the
            // absence instead of inventing one.
            appendLine("installId=(none)")
            appendLine("generated=$timestamp")
        }

    /**
     * Aggregates-only timings. Every row is bucket-derived (in-session or
     * carried over from a previous process); exact per-request durations are
     * never present.
     */
    fun timingsJson(timings: List<PerformanceTiming>): String =
        buildString {
            appendLine("{")
            timings.forEachIndexed { i, t ->
                val source = if (t.fromPreviousSession) "previous_session" else "this_session"
                append(
                    "  \"${t.key}\": { \"avg\": ${t.avgMs}, \"p50\": ${t.p50Ms}, " +
                        "\"p95\": ${t.p95Ms}, \"max\": ${t.maxMs}, \"count\": ${t.count}, " +
                        "\"source\": \"$source\" }",
                )
                appendLine(if (i < timings.lastIndex) "," else "")
            }
            appendLine("}")
        }

    /**
     * Honest diagnostics. FPS/ANR are `null` (no instrument measures them);
     * `crashes` is the real count of scrubbed crash summaries on disk.
     */
    fun diagnosticsJson(diagnostics: PerformanceDiagnostics?): String =
        buildString {
            val fps = diagnostics?.fpsScroll
            val anrs = diagnostics?.anrCount
            append("{ \"fps\": ${fps ?: "null"}, \"anrs\": ${anrs ?: "null"}, ")
            append("\"crashes\": ${diagnostics?.crashes?.size ?: 0}, ")
            append("\"crashesMeasured\": true, ")
            append("\"fpsMeasured\": ${fps != null}, \"anrsMeasured\": ${anrs != null} }")
        }

    /**
     * Capped (≤ [LOG_TAIL_LINE_LIMIT] lines), newest-first tail of the log ring,
     * formatted like [DebugLog.toText]. The defense-in-depth
     * [LogRedaction.redactString] pass must be idempotent over already-redacted
     * text so it can never mangle a line while catching a future leak.
     */
    fun logsTxt(
        events: List<DebugLog.DebugEvent>,
        limit: Int = LOG_TAIL_LINE_LIMIT,
    ): String {
        val tail = events.take(limit)
        if (tail.isEmpty()) return "(empty)"
        val assembled =
            buildString {
                for (e in tail) {
                    append('[').append(e.level.name).append("] ")
                        .append(e.tag).append(": ")
                        .append(e.message).append('\n')
                }
            }
        return LogRedaction.redactString(assembled)
    }

    /** Recent crash summaries — already PII-scrubbed by [DebugToolkit.recentErrors]. */
    fun crashesTxt(entries: List<DebugErrorEntry>): String {
        if (entries.isEmpty()) return "(none)"
        return buildString {
            for (e in entries) {
                append(DebugToolkit.formatTimestamp(e.timestampMs))
                    .append("  ").append(e.message).append('\n')
            }
        }
    }

    /**
     * Writes the assembled entries into [outFile] as a ZIP. Ordered map so the
     * entry set is deterministic and directly assertable.
     */
    fun writeZip(
        outFile: File,
        entries: Map<String, String>,
    ) {
        ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }
}
