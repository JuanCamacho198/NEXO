package com.nexo.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.ui.icons.NexoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A parsed crash file entry.
 *
 * Each crash file written by [NexoApplication]'s crash handler has the
 * format:
 * ```
 * Timestamp: <epochMs>
 * Thread: <threadName>
 * Message: <errorMessage>
 * --- Stack Trace ---
 * <stack trace lines>
 * --- Logs ---
 * <log lines>
 * ```
 * The `--- Logs ---` section is optional (only present when the snapshot was
 * non-empty).
 */
data class CrashEntry(
    val fileName: String,
    val timestamp: Long,
    val threadName: String,
    val message: String,
    val stackTrace: String,
    val logs: List<String>,
    val fullText: String,
)

/**
 * Parses a crash file created by [NexoApplication]'s crash handler.
 * Returns `null` if the file cannot be read or parsed.
 */
private fun parseCrashFile(file: File): CrashEntry? =
    runCatching {
        val lines = file.readLines()
        val timestamp =
            lines
                .firstOrNull { it.startsWith("Timestamp:") }
                ?.removePrefix("Timestamp:")
                ?.trim()
                ?.toLongOrNull() ?: 0L
        val thread =
            lines
                .firstOrNull { it.startsWith("Thread:") }
                ?.removePrefix("Thread:")
                ?.trim() ?: ""
        val msg =
            lines
                .firstOrNull { it.startsWith("Message:") }
                ?.removePrefix("Message:")
                ?.trim() ?: ""
        val stackStart = lines.indexOfFirst { it == "--- Stack Trace ---" }
        val logsStart = lines.indexOfFirst { it == "--- Logs ---" }

        val stackTrace =
            if (stackStart >= 0) {
                val end = if (logsStart > stackStart) logsStart else lines.size
                lines.subList(stackStart + 1, end).joinToString("\n")
            } else {
                ""
            }

        val crashLogs =
            if (logsStart >= 0) {
                lines.subList(logsStart + 1, lines.size)
            } else {
                emptyList()
            }

        CrashEntry(
            fileName = file.name,
            timestamp = timestamp,
            threadName = thread,
            message = msg,
            stackTrace = stackTrace,
            logs = crashLogs,
            fullText = lines.joinToString("\n"),
        )
    }.getOrNull()

/**
 * Loads and parses all crash files from [crashDir], sorted newest-first.
 * Runs on [Dispatchers.IO].
 */
private suspend fun loadCrashes(crashDir: File): List<CrashEntry> =
    withContext(Dispatchers.IO) {
        runCatching {
            crashDir
                .listFiles()
                ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.mapNotNull { parseCrashFile(it) }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

/**
 * Builds the export text for the currently selected tab.
 */
private fun buildCopyText(
    tab: Int,
    crashes: List<CrashEntry>,
    liveLogs: List<DebugLog.DebugEvent>,
    levelFilter: Set<DebugLog.Level>,
): String =
    when (tab) {
        0 -> crashes.joinToString("\n\n---\n\n") { it.fullText }
        1 -> {
            val filtered =
                if (levelFilter.isEmpty()) {
                    liveLogs
                } else {
                    liveLogs.filter { it.level in levelFilter }
                }
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            filtered.joinToString("\n") { e ->
                "[${e.level.name}] ${sdf.format(Date(e.timestamp))} ${e.tag}: ${e.message}"
            }
        }
        else -> ""
    }

/**
 * Full-screen debug log viewer with two tabs:
 *
 * 1. **Crashes**: reads crash files from `cacheDir/crashes/`. Each crash
 *    is shown as a card with timestamp; expanding reveals full stack trace
 *    and captured logs.
 * 2. **Live Logs**: collects from [DebugLog.events] StateFlow in real-time,
 *    with level filter chips (INFO / WARN / ERROR).
 *
 * Both tabs have **Copy All** (clipboard + snackbar) and **Share**
 * (ACTION_SEND intent) action buttons, plus an empty state when there is
 * no data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs =
        listOf(
            stringResource(R.string.debug_log_viewer_tab_crashes),
            stringResource(R.string.debug_log_viewer_tab_live),
        )

    // ── Crash state ───────────────────────────────────────────────
    var crashes by remember { mutableStateOf<List<CrashEntry>>(emptyList()) }
    var isLoadingCrashes by remember { mutableStateOf(true) }
    var expandedCrashFiles by remember { mutableStateOf(setOf<String>()) }

    // ── Live log state ────────────────────────────────────────────
    val liveLogs by DebugLog.events.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf(setOf<DebugLog.Level>()) }

    // ── Snackbar ──────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Load crashes on first composition
    LaunchedEffect(Unit) {
        val crashDir = File(context.cacheDir, "crashes")
        crashes = loadCrashes(crashDir)
        isLoadingCrashes = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // ── Top bar ───────────────────────────────────────────
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = NexoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                    Text(
                        text = stringResource(R.string.debug_log_viewer_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            // ── Tab row ───────────────────────────────────────────
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            // ── Action row (Copy All / Share) ──────────────────────
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val text = buildCopyText(selectedTab, crashes, liveLogs, levelFilter)
                        if (text.isNotEmpty()) {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("log", text),
                            )
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.debug_log_copied),
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.debug_log_viewer_copy_all))
                }

                OutlinedButton(
                    onClick = {
                        val text = buildCopyText(selectedTab, crashes, liveLogs, levelFilter)
                        if (text.isNotEmpty()) {
                            val intent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.debug_log_viewer_share),
                                ),
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.debug_log_viewer_share))
                }
            }

            // ── Tab content ───────────────────────────────────────
            when (selectedTab) {
                0 ->
                    CrashesTab(
                        crashes = crashes,
                        isLoading = isLoadingCrashes,
                        expandedFiles = expandedCrashFiles,
                        onToggleExpand = { file ->
                            expandedCrashFiles =
                                if (file in expandedCrashFiles) {
                                    expandedCrashFiles - file
                                } else {
                                    expandedCrashFiles + file
                                }
                        },
                    )

                1 ->
                    LiveLogsTab(
                        logs = liveLogs,
                        levelFilter = levelFilter,
                        onLevelFilterChange = { level ->
                            levelFilter =
                                if (level in levelFilter) {
                                    levelFilter - level
                                } else {
                                    levelFilter + level
                                }
                        },
                    )
            }
        }
    }
}

// ── Crashes Tab ─────────────────────────────────────────────────────

@Composable
private fun CrashesTab(
    crashes: List<CrashEntry>,
    isLoading: Boolean,
    expandedFiles: Set<String>,
    onToggleExpand: (String) -> Unit,
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else if (crashes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.debug_log_viewer_no_crashes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(crashes, key = { it.fileName }) { crash ->
                CrashCard(
                    crash = crash,
                    isExpanded = crash.fileName in expandedFiles,
                    onToggleExpand = { onToggleExpand(crash.fileName) },
                )
            }
        }
    }
}

@Composable
private fun CrashCard(
    crash: CrashEntry,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val dateFormat =
        remember {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .clickable(onClick = onToggleExpand),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: timestamp + expand icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateFormat.format(Date(crash.timestamp)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = crash.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                        softWrap = true,
                    )
                }
                Icon(
                    imageVector =
                        if (isExpanded) {
                            NexoIcons.ChevronUp
                        } else {
                            NexoIcons.ChevronDown
                        },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Expanded body
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Thread
                Text(
                    text = stringResource(R.string.debug_log_viewer_crash_thread, crash.threadName),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Stack trace
                Text(
                    text = stringResource(R.string.debug_log_viewer_crash_stack),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = crash.stackTrace,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                // Logs at crash
                if (crash.logs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.debug_log_viewer_crash_logs),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val displayLogs = crash.logs.take(50)
                        Column(modifier = Modifier.padding(8.dp)) {
                            displayLogs.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                )
                            }
                            if (crash.logs.size > 50) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.debug_log_viewer_crash_logs_more, crash.logs.size - 50),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Live Logs Tab ──────────────────────────────────────────────────

@Composable
private fun LiveLogsTab(
    logs: List<DebugLog.DebugEvent>,
    levelFilter: Set<DebugLog.Level>,
    onLevelFilterChange: (DebugLog.Level) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(DebugLog.Level.INFO, DebugLog.Level.WARN, DebugLog.Level.ERROR).forEach { level ->
                FilterChip(
                    selected = level in levelFilter,
                    onClick = { onLevelFilterChange(level) },
                    label = { Text(level.name) },
                )
            }
        }

        // Filtered logs
        val filteredLogs =
            if (levelFilter.isEmpty()) {
                logs
            } else {
                logs.filter { it.level in levelFilter }
            }

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.debug_log_viewer_no_logs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(
                    items = filteredLogs,
                    key = { "${it.timestamp}_${it.level.name}_${it.tag}_${it.message.hashCode()}" },
                ) { event ->
                    LogEntryRow(event = event)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(event: DebugLog.DebugEvent) {
    val color =
        when (event.level) {
            DebugLog.Level.ERROR -> MaterialTheme.colorScheme.error
            DebugLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
            DebugLog.Level.INFO -> MaterialTheme.colorScheme.primary
            DebugLog.Level.SUCCESS -> MaterialTheme.colorScheme.primary
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Level color indicator
        Box(
            modifier =
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(color, RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = event.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = event.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
