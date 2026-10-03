package com.nexo.presentation.debug

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.debug.DebugToolkit
import com.nexo.presentation.viewmodel.AuthViewModel
import com.nexo.presentation.viewmodel.reader.SessionUiState
import com.nexo.ui.icons.NexoIcons

private val DARK_BG = Color(0xFF0D1322)
private val DARK_SURFACE = Color(0xFF1A1F36)
private val SECTION_LABEL = Color(0xFF8892B0)
private val VALUE_TEXT = Color(0xFFCCD6F6)
private val ACCENT = Color(0xFF64FFDA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPanel(
    viewModel: DebugViewModel,
    authViewModel: AuthViewModel,
    // Session slice (SDD reader-uiState-cleanup S6): replaces the previous
    // `readerViewModel: ReaderViewModel?` whose only use was reading
    // `uiState.bookFormat/currentPdfPage/totalPdfPages/loadTimeMs/bookFilePath`
    // for the PDF section. Null when no reader is open — the PDF section is
    // then skipped, exactly as before.
    session: SessionUiState?,
    syncService: com.nexo.data.remote.sync.SyncService,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val debugInfo by viewModel.debugInfo.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val resources = LocalResources.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    // Load async data when sheet opens
    LaunchedEffect(Unit) {
        viewModel.loadDbCounts()
        viewModel.updateSyncInfo(syncService)
        viewModel.loadRecentErrors(context)

        // Populate session info
        val sessionInfo = authState.currentSession
        if (sessionInfo != null) {
            viewModel.updateSessionInfo(
                userId = sessionInfo.userId,
                email = sessionInfo.email,
                displayName = sessionInfo.displayName,
                isSupabaseConfigured = authState.isConfigured,
                hasWiringIssue = authState.hasWiringIssue,
            )
        }

        // Populate PDF info
        if (session != null && session.bookFormat == "pdf") {
            viewModel.updatePdfInfo(
                currentPage = session.currentPdfPage,
                totalPages = session.totalPdfPages,
                loadTimeMs = session.loadTimeMs,
                filePath = session.bookFilePath,
            )
        }
    }

    // Clear DB confirmation dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Database") },
            text = { Text("This will delete all local data and sign you out. Continue?") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearDb()
                        onDismiss()
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935),
                        ),
                ) {
                    Text("Clear & Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Clear cache confirmation dialog (cacheDir only — library data survives)
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.debug_panel_clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.debug_panel_clear_cache_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheConfirm = false
                        viewModel.clearCache(context) { report ->
                            Toast
                                .makeText(
                                    context,
                                    resources.getString(
                                        R.string.debug_panel_clear_cache_done,
                                        report.deletedEntries,
                                        report.deletedBytes,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935),
                        ),
                ) {
                    Text(stringResource(R.string.debug_panel_clear_cache_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) {
                    Text(stringResource(R.string.debug_panel_cancel))
                }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DARK_BG,
        contentColor = VALUE_TEXT,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            // ── Header ────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                Icon(
                    imageVector = NexoIcons.BugReport,
                    contentDescription = null,
                    tint = ACCENT,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Debug Panel",
                    style = MaterialTheme.typography.titleMedium,
                    color = VALUE_TEXT,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ── 1. Session Section ─────────────────────────────────────
            val sessionEmail = debugInfo.session.email
            val sessionDisplayName = debugInfo.session.displayName
            SectionHeader(title = "Session")
            DebugRow(label = "userId", value = debugInfo.session.userId.ifEmpty { "No session" })
            if (sessionEmail != null) {
                DebugRow(label = "email", value = sessionEmail)
            }
            if (sessionDisplayName != null) {
                DebugRow(label = "displayName", value = sessionDisplayName)
            }
            DebugRow(label = "authMode", value = debugInfo.session.authMode)
            DebugRow(
                label = "supabaseConfigured",
                value = debugInfo.session.isSupabaseConfigured.toString(),
            )
            DebugRow(
                label = "hasWiringIssue",
                value = debugInfo.session.hasWiringIssue.toString(),
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = DARK_SURFACE,
            )

            // ── 2. Init Timings Section ───────────────────────────────
            SectionHeader(title = "Init Timings")
            DebugRow(label = "dbInit", value = "${debugInfo.initTimings.dbInitMs}ms")
            DebugRow(label = "epubImportInit", value = "${debugInfo.initTimings.epubImportInitMs}ms")
            DebugRow(label = "readerRepoInit", value = "${debugInfo.initTimings.readerRepoInitMs}ms")
            DebugRow(
                label = "totalInit",
                value = "${debugInfo.initTimings.totalInitMs}ms",
                isBold = true,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = DARK_SURFACE,
            )

            // ── 3. DB Counts Section ──────────────────────────────────
            SectionHeader(title = "DB Counts")
            if (debugInfo.isLoadingDbCounts) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = ACCENT,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                DbCountRow(label = "books", count = debugInfo.dbCounts.books)
                DbCountRow(label = "highlights", count = debugInfo.dbCounts.highlights)
                DbCountRow(label = "bookmarks", count = debugInfo.dbCounts.bookmarks)
                DbCountRow(label = "readingSessions", count = debugInfo.dbCounts.readingSessions)
                DbCountRow(label = "readingProgress", count = debugInfo.dbCounts.readingProgress)
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = DARK_SURFACE,
            )

            // ── 4. Sync Section ──────────────────────────────────────
            SectionHeader(title = "Sync")
            DebugRow(label = "state", value = debugInfo.syncDebug.state)
            DebugRow(label = "pendingCount", value = debugInfo.syncDebug.pendingCount.toString())
            DebugRow(
                label = "lastSync",
                value =
                    debugInfo.syncDebug.lastSyncAtMs?.let { DebugToolkit.formatTimestamp(it) }
                        ?: stringResource(R.string.debug_panel_sync_never),
            )
            debugInfo.syncDebug.lastFailure?.let { failure ->
                DebugRow(label = "lastFailure", value = failure)
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = DARK_SURFACE,
            )

            // ── 4b. Supabase Sync Section ──────────────────────────────
            SectionHeader(title = "Supabase Sync")
            DebugRow(label = "state", value = debugInfo.supabaseSyncDebug.state)
            val gatedReason = debugInfo.supabaseSyncDebug.gatedReason
            if (gatedReason != null) {
                DebugRow(label = "gatedReason", value = gatedReason)
            }
            DebugRow(label = "pendingCount", value = debugInfo.supabaseSyncDebug.pendingCount.toString())

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = DARK_SURFACE,
            )

            // ── 4c. Recent Errors Section ─────────────────────────────
            SectionHeader(title = stringResource(R.string.debug_panel_recent_errors_title))
            val recentErrors = debugInfo.recentErrors
            if (recentErrors.isEmpty()) {
                Text(
                    text = stringResource(R.string.debug_panel_recent_errors_empty),
                    color = SECTION_LABEL,
                    fontSize = 13.sp,
                )
            } else {
                recentErrors.forEach { entry ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = DebugToolkit.formatTimestamp(entry.timestampMs),
                            color = SECTION_LABEL,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = entry.message,
                            color = VALUE_TEXT,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            // ── 5. PDF Debug Section (conditional) ───────────────────
            val pdfDebug = debugInfo.pdfDebug
            if (pdfDebug != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = DARK_SURFACE,
                )

                SectionHeader(title = "PDF Debug")
                DebugRow(label = "currentPage", value = (pdfDebug.currentPage + 1).toString())
                DebugRow(label = "totalPages", value = pdfDebug.totalPages.toString())
                if (pdfDebug.loadTimeMs != null) {
                    DebugRow(label = "loadTime", value = "${pdfDebug.loadTimeMs}ms")
                }
                if (pdfDebug.filePath != null) {
                    DebugRow(label = "filePath", value = pdfDebug.filePath)
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = DARK_SURFACE,
            )

            // ── Quick Actions ────────────────────────────────────────
            SectionHeader(title = "Quick Actions")
            Spacer(Modifier.height(8.dp))

            QuickActionButton(
                icon = NexoIcons.Trash,
                label = "Clear DB",
                color = Color(0xFFE53935),
                onClick = { showClearConfirm = true },
            )
            Spacer(Modifier.height(8.dp))
            QuickActionButton(
                icon = NexoIcons.Sync,
                label = "Force Sync Push",
                onClick = {
                    viewModel.forceSyncPush()
                    Toast.makeText(context, "Sync push scheduled", Toast.LENGTH_SHORT).show()
                },
            )
            Spacer(Modifier.height(8.dp))
            QuickActionButton(
                icon = NexoIcons.Sync,
                label = "Force Sync Pull",
                onClick = {
                    viewModel.forceSyncPull()
                    Toast.makeText(context, "Sync pull scheduled", Toast.LENGTH_SHORT).show()
                },
            )
            Spacer(Modifier.height(8.dp))
            QuickActionButton(
                icon = NexoIcons.Copy,
                label = "Copy Session Info",
                onClick = {
                    viewModel.copySessionInfo(context, authState.currentSession)
                    Toast.makeText(context, "Session info copied", Toast.LENGTH_SHORT).show()
                },
            )
            Spacer(Modifier.height(8.dp))
            QuickActionButton(
                icon = NexoIcons.Storage,
                label = stringResource(R.string.debug_panel_clear_cache),
                color = Color(0xFFE53935),
                onClick = { showClearCacheConfirm = true },
            )
        }
    }
}

// ── Reusable section helpers ─────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = SECTION_LABEL,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun DebugRow(
    label: String,
    value: String,
    isBold: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = SECTION_LABEL,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = value,
            color = if (isBold) ACCENT else VALUE_TEXT,
            fontSize = 13.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DbCountRow(
    label: String,
    count: Int,
) {
    val display = if (count < 0) "Error" else count.toString()
    DebugRow(label = label, value = display)
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    color: Color = ACCENT,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = DARK_SURFACE,
                contentColor = VALUE_TEXT,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label)
    }
}
