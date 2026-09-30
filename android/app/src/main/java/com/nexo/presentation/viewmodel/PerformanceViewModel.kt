package com.nexo.presentation.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val MEASURE_DELAY_MS = 650L
private const val SPARKLINE_SAMPLE_COUNT = 16
private const val SPARKLINE_BASE_FACTOR = 0.7f
private const val SPARKLINE_VARIATION_RANGE = 0.7f
private const val P95_FACTOR = 0.95
private const val COLD_START_BASE_MS = 820L
private const val OPEN_READER_BASE_MS = 420L
private const val SYNC_PULL_BASE_MS = 610L
private const val SAVE_HIGHLIGHT_BASE_MS = 95L
private const val FAKE_DB_BYTES = 4_820_000L
private const val FAKE_CACHE_BYTES = 2_340_000L
private const val FAKE_HIGHLIGHTS_COUNT = 37
private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L
private const val BYTES_PER_KB_FLOAT = 1024f
private const val BYTES_PER_MB_FLOAT = 1024f * 1024f
private const val FPS_BASE = 56f
private const val FPS_VARIATION = 4f
private const val FPS_MAX = 120f

/**
 * Single metric sample for the "Tiempos clave" card.
 * Holds computed aggregates and raw samples for sparkline rendering.
 * TODO: replace fake generators with real telemetry (e.g. App Startup, Room query timers, Supabase sync).
 */
data class PerformanceTiming(
    val key: String,
    val labelRes: Int? = null,
    val labelFallback: String,
    val avgMs: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val samples: List<Float>, // normalized sparkline values
)

data class PerformanceResources(
    val dbSizeBytes: Long,
    val dbSizeLabel: String,
    val highlightsCount: Int,
    val cacheSizeBytes: Long,
    val cacheSizeLabel: String,
    val memoryUsageMb: Float,
    val memoryTotalMb: Float,
)

data class PerformanceSyncStatus(
    val realtimeConnected: Boolean,
    val lastSyncLabel: String,
    val outboxPending: Int,
)

data class PerformanceCrashEntry(
    val timestamp: String,
    val name: String,
    val stackSnippet: String,
)

data class PerformanceDiagnostics(
    val fpsScroll: Float,
    val fpsLabel: String,
    val anrCount: Int,
    val crashes: List<PerformanceCrashEntry>,
)

data class PerformanceUiState(
    val isMeasuring: Boolean = false,
    val isClearingCache: Boolean = false,
    val isGeneratingReport: Boolean = false,
    val reportPath: String? = null,
    val timings: List<PerformanceTiming> = emptyList(),
    val resources: PerformanceResources? = null,
    val syncStatus: PerformanceSyncStatus? = null,
    val diagnostics: PerformanceDiagnostics? = null,
    val lastMeasuredAt: String? = null,
)

class PerformanceViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val dataSource: PerformanceDataSource =
        RealPerformanceDataSource(
            appContext = application.applicationContext,
        )

    private val appContext: Context = application.applicationContext

    private val _uiState = MutableStateFlow(PerformanceUiState())
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            val timings = generateTimings()
            val resources = loadResources()
            val syncStatus = loadSyncStatus()
            val diagnostics = loadDiagnostics()
            _uiState.update {
                it.copy(
                    timings = timings,
                    resources = resources,
                    syncStatus = syncStatus,
                    diagnostics = diagnostics,
                    lastMeasuredAt = nowLabel(),
                )
            }
        }
    }

    fun measureNow() {
        if (_uiState.value.isMeasuring) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMeasuring = true) }
            // Simulate measurement delay so the button shows loading state
            kotlinx.coroutines.delay(MEASURE_DELAY_MS)
            val timings = generateTimings()
            val resources = loadResources()
            _uiState.update {
                it.copy(
                    isMeasuring = false,
                    timings = timings,
                    resources = resources,
                    lastMeasuredAt = nowLabel(),
                )
            }
        }
    }

    fun clearCache(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_uiState.value.isClearingCache) return
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingCache = true) }
            val success = withContext(Dispatchers.IO) { clearEpubCacheInternal() }
            val resources = loadResources()
            _uiState.update { it.copy(isClearingCache = false, resources = resources) }
            onResult(success, if (success) "Cache cleared" else "Failed to clear cache")
        }
    }

    fun generateReport(onResult: (File?) -> Unit = {}) {
        if (_uiState.value.isGeneratingReport) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingReport = true, reportPath = null) }
            val file = withContext(Dispatchers.IO) { buildReportZip() }
            _uiState.update { it.copy(isGeneratingReport = false, reportPath = file?.absolutePath) }
            onResult(file)
        }
    }

    // ── Delegated to the real data source (measured in-session aggregates) ──

    private fun generateTimings(): List<PerformanceTiming> = dataSource.generateTimings()

    private suspend fun loadResources(): PerformanceResources = dataSource.loadResources()

    private fun loadSyncStatus(): PerformanceSyncStatus = dataSource.loadSyncStatus()

    private fun loadDiagnostics(): PerformanceDiagnostics = dataSource.loadDiagnostics()

    private fun clearEpubCacheInternal(): Boolean =
        runCatching {
            val dirs =
                listOf(
                    File(appContext.filesDir, "epub_cache"),
                    File(appContext.cacheDir, "epub_cache"),
                )
            var cleared = false
            dirs.forEach { dir ->
                if (dir.exists()) {
                    dir.listFiles()?.forEach { it.deleteRecursively() }
                    cleared = true
                }
            }
            cleared
        }.getOrDefault(false)

    private fun buildReportZip(): File? =
        runCatching {
            val state = _uiState.value
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(appContext.cacheDir, "nexo_performance_$timestamp.zip")
            ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
                fun entry(
                    name: String,
                    content: String,
                ) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
                entry(
                    "device.txt",
                    buildString {
                        appendLine("model=${android.os.Build.MODEL}")
                        appendLine("manufacturer=${android.os.Build.MANUFACTURER}")
                        appendLine("sdk=${android.os.Build.VERSION.SDK_INT}")
                        appendLine("generated=$timestamp")
                    },
                )
                entry(
                    "timings.json",
                    buildString {
                        appendLine("{")
                        state.timings.forEachIndexed { i, t ->
                            appendLine("  \"${t.key}\": { \"avg\": ${t.avgMs}, \"p95\": ${t.p95Ms}, \"max\": ${t.maxMs} }${if (i < state.timings.lastIndex) "," else ""}")
                        }
                        appendLine("}")
                    },
                )
                entry(
                    "resources.json",
                    buildString {
                        val r = state.resources
                        appendLine("{ \"db\": \"${r?.dbSizeLabel}\", \"highlights\": ${r?.highlightsCount}, \"cache\": \"${r?.cacheSizeLabel}\", \"memUsedMb\": ${r?.memoryUsageMb} }")
                    },
                )
                entry(
                    "sync.json",
                    buildString {
                        val s = state.syncStatus
                        appendLine("{ \"realtime\": ${s?.realtimeConnected}, \"lastSync\": \"${s?.lastSyncLabel}\", \"outbox\": ${s?.outboxPending} }")
                    },
                )
                entry(
                    "diagnostics.json",
                    buildString {
                        val d = state.diagnostics
                        appendLine("{ \"fps\": ${d?.fpsScroll}, \"anrs\": ${d?.anrCount}, \"crashes\": ${d?.crashes?.size} }")
                    },
                )
            }
            outFile
        }.getOrNull()

    private fun nowLabel(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

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

    /**
     * Explicit factory for [PerformanceViewModel].
     *
     * The ViewModel keeps a SINGLE `(Application)` constructor so
     * [ViewModelProvider.AndroidViewModelFactory] can instantiate it by
     * reflection. A defaulted second constructor parameter would generate a
     * synthetic constructor instead and break that path with
     * `NoSuchMethodException: PerformanceViewModel.<init> [Application]`
     * (NEXTPAGE-ANDROID-1). This factory is the non-reflective call site used
     * by the settings screen; both paths are covered by
     * `PerformanceViewModelConstructionTest`.
     */
    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PerformanceViewModel(application) as T
    }
}
