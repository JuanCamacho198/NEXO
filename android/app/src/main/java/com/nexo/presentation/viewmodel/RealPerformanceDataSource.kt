package com.nexo.presentation.viewmodel

import android.app.ActivityManager
import android.content.Context
import com.nexo.debug.DebugToolkit
import com.nexo.debug.SentryMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * PP-1 A9 — real, measured data source for the debug-only Performance screen.
 * Values come from the in-session aggregates of the same instrumentation that
 * feeds the P0 metrics. No random values, ever (perf-screen spec).
 *
 * `save_highlight` has no matching P0 instrument in this change (resolved
 * decision 1) → the key is omitted and the row renders an explicit no-data
 * state.
 */
class RealPerformanceDataSource(
    private val appContext: Context? = null,
) : PerformanceDataSource {
    override fun generateTimings(): List<PerformanceTiming> {
        val timings = mutableListOf<PerformanceTiming>()
        for (spec in TIMING_SPECS) {
            // In-session aggregate wins; otherwise fall back to the aggregate
            // carried over from the previous process (no fresh samples this run).
            val inSession = SentryMetrics.aggregate(spec.metricName)
            val aggregate = inSession ?: SentryMetrics.persistedAggregate(spec.metricName) ?: continue
            timings.add(
                PerformanceTiming(
                    key = spec.key,
                    labelFallback = spec.label,
                    avgMs = aggregate.avgMs,
                    p50Ms = aggregate.p50Ms,
                    p95Ms = aggregate.p95Ms,
                    maxMs = aggregate.maxMs,
                    count = aggregate.count,
                    samples = aggregate.samples,
                    fromPreviousSession = inSession == null,
                ),
            )
        }
        return timings
    }

    override suspend fun loadResources(): PerformanceResources =
        withContext(Dispatchers.IO) {
            val context = appContext
            if (context == null) {
                return@withContext PerformanceResources(
                    dbSizeBytes = 0L,
                    dbSizeLabel = "n/d",
                    highlightsCount = 0,
                    cacheSizeBytes = 0L,
                    cacheSizeLabel = "n/d",
                    memoryUsageMb = 0f,
                    memoryTotalMb = 0f,
                )
            }

            val dbFile = context.getDatabasePath("nexo.db")
            val dbBytes =
                runCatching {
                    var total = 0L
                    if (dbFile.exists()) total += dbFile.length()
                    for (suffix in listOf("-wal", "-shm")) {
                        val f = File(dbFile.path + suffix)
                        if (f.exists()) total += f.length()
                    }
                    total
                }.getOrDefault(0L)

            val cacheDir =
                File(context.filesDir, "epub_cache")
                    .let { if (it.exists()) it else File(context.cacheDir, "epub_cache") }
            val cacheBytes = runCatching { folderSize(cacheDir) }.getOrDefault(0L)

            val memInfo = ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / BYTES_PER_MB_FLOAT
            val usedMb = (totalMb - memInfo.availMem / BYTES_PER_MB_FLOAT).coerceAtLeast(0f)

            PerformanceResources(
                dbSizeBytes = dbBytes,
                dbSizeLabel = formatBytes(dbBytes),
                highlightsCount = 0,
                cacheSizeBytes = cacheBytes,
                cacheSizeLabel = formatBytes(cacheBytes),
                memoryUsageMb = usedMb,
                memoryTotalMb = totalMb,
            )
        }

    override fun loadSyncStatus(): PerformanceSyncStatus =
        PerformanceSyncStatus(
            realtimeConnected = false,
            lastSyncLabel = "n/d",
            outboxPending = SentryMetrics.lastKnownPendingCount,
        )

    /**
     * G3: honest diagnostics. No real FPS/ANR instrument exists in this app, so
     * those fields are reported as `null` (unknown) rather than fake zeros.
     * Crashes come from the real crash files on disk via [DebugToolkit].
     */
    override fun loadDiagnostics(): PerformanceDiagnostics {
        val context = appContext
        val crashes =
            if (context == null) {
                emptyList()
            } else {
                DebugToolkit
                    .recentErrors(File(context.cacheDir, "crashes"))
                    .map { entry ->
                        PerformanceCrashEntry(
                            timestamp = DebugToolkit.formatTimestamp(entry.timestampMs),
                            name = entry.message.take(CRASH_NAME_MAX_CHARS),
                            stackSnippet = entry.message,
                        )
                    }
            }
        return PerformanceDiagnostics(
            fpsScroll = null,
            fpsLabel = "n/d",
            anrCount = null,
            crashes = crashes,
        )
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB_FLOAT)
            bytes >= BYTES_PER_KB -> String.format(Locale.US, "%.0f KB", bytes / BYTES_PER_KB_FLOAT)
            else -> "$bytes B"
        }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir
            .walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    private data class TimingSpec(
        val key: String,
        val label: String,
        val metricName: String,
    )

    private companion object {
        const val BYTES_PER_KB_FLOAT = 1024f
        const val CRASH_NAME_MAX_CHARS = 80
        val BYTES_PER_KB = 1024L
        val BYTES_PER_MB = 1024L * 1024L
        const val BYTES_PER_MB_FLOAT = 1024f * 1024f

        val TIMING_SPECS =
            listOf(
                TimingSpec("cold_start", "Cold start", "app_cold_start"),
                TimingSpec("open_reader", "Open reader", "reader_open"),
                TimingSpec("sync_pull", "Sync pull", "sync_flush"),
                // save_highlight: no P0 instrument — ships as no-data (resolved decision 1).
            )
    }
}
