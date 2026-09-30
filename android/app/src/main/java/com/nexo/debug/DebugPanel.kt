package com.nexo.debug

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.presentation.viewmodel.reader.AnnotationUiState
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.ui.icons.NexoIcons

/**
 * Full-screen debug panel for the reader.
 *
 * - Visible only in [com.nexo.BuildConfig.DEBUG] builds (gated by caller).
 * - Shows real-time selection / ActionMode / highlight-tap state and the
 *   full [DebugLog] history so the user can diagnose why the custom
 *   color-picker / context menu overlays fail to appear.
 * - All reads come from the annotation slice ([AnnotationUiState]); this file
 *   must not read the deleted Reader uiState aggregate (SDD reader-uiState-cleanup S6).
 *
 * Product note (S6, same live-fix precedent as the S2 tint decision): the
 * previous aggregate activeHighlightId read was dead — the aggregate field has
 * zero writers and is always null, so the panel always rendered "—". The id
 * derives live from the coordinator-owned selection
 * (`selectionState as? Existing`), which ACTIVATES the readout: tapping an
 * existing highlight now shows its id. Flag to product if bug-compatible-null
 * (always-"—") is ever preferred.
 *
 * Debounce parity note (S6): the previous aggregate highlightTapDebounceUntil
 * read was likewise dead — the aggregate field has zero writers and is always
 * 0L, so the panel always rendered "0 ms". [highlightTapDebounceUntil] keeps
 * that exact readout via its default; it is a seam for wiring the live
 * coordinator value later, not a behavior change today.
 */
@Composable
fun DebugPanel(
    visible: Boolean,
    annotation: AnnotationUiState,
    highlightTapDebounceUntil: Long = 0L,
    onClose: () -> Unit,
    onForceColorPicker: () -> Unit,
    onForceContextMenu: () -> Unit,
    onSimulateHighlightTap: () -> Unit,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
    onInspectHighlightsHtml: () -> Unit = {},
    onLogWebViewTree: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val events by DebugLog.events.collectAsStateWithLifecycle()
    val actionMode by DebugStateHolder.actionMode.collectAsStateWithLifecycle()
    val highlight by DebugStateHolder.highlight.collectAsStateWithLifecycle()
    val decoration by DebugStateHolder.decoration.collectAsStateWithLifecycle()

    // Live-fix (S6): coordinator-owned selection; the uiState mirror was always null.
    val activeHighlightId =
        (annotation.selectionState as? ReaderSelectionState.Existing)?.highlight?.id

    val debounceRemainingMs =
        run {
            val until = highlightTapDebounceUntil
            if (until <= 0L) 0L else (until - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            // ── Header ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.debug_panel_title),
                    color = Color(0xFFEF4444),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = NexoIcons.Close,
                        contentDescription = stringResource(R.string.debug_close),
                        tint = Color(0xFFDDE2F8),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Selection state ───────────────────────────────────
            DebugSectionCard(title = stringResource(R.string.debug_section_selection)) {
                DebugKeyValue(
                    stringResource(R.string.debug_kv_selected_text),
                    annotation.selectedText?.take(50) ?: "—",
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_selection_rect),
                    annotation.selectionRect?.toString() ?: "—",
                )
                DebugKeyValue(
                    "selectionState",
                    annotation.selectionState::class.simpleName ?: "—",
                )
                DebugKeyValue(
                    "showColorPickerPopover",
                    annotation.showColorPickerPopover.toString(),
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_active_highlight_id),
                    activeHighlightId ?: "—",
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_debounce_remaining_ms),
                    "$debounceRemainingMs ms",
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── ActionMode ────────────────────────────────────────
            DebugSectionCard(title = stringResource(R.string.debug_section_action_mode)) {
                DebugKeyValue(
                    stringResource(R.string.debug_kv_am_installed),
                    actionMode.installed.toString(),
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_am_last_event),
                    actionMode.lastEvent,
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_am_last_type),
                    actionMode.lastType,
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_am_suppressed),
                    actionMode.suppressedCount.toString(),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Highlight tap ─────────────────────────────────────
            DebugSectionCard(title = stringResource(R.string.debug_section_highlight)) {
                DebugKeyValue(
                    stringResource(R.string.debug_kv_hl_registered),
                    highlight.listenerRegistered.toString(),
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_hl_last_id),
                    highlight.lastEventId,
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_hl_last_rect),
                    highlight.lastEventRect,
                )
                DebugKeyValue(
                    stringResource(R.string.debug_kv_hl_activations),
                    highlight.activationCount.toString(),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Decorations ───────────────────────────────────────
            DebugSectionCard(title = stringResource(R.string.debug_section_decorations)) {
                DebugKeyValueColored(
                    key = stringResource(R.string.debug_decorations_listener_registered),
                    value = if (decoration.listenerRegistered) "yes" else "no",
                    isPositive = decoration.listenerRegistered,
                )
                DebugKeyValue(
                    stringResource(R.string.debug_decorations_activation_count),
                    decoration.activationCount.toString(),
                )
                DebugKeyValue(
                    stringResource(R.string.debug_decorations_last_id),
                    decoration.lastEventId,
                )
                DebugKeyValue(
                    stringResource(R.string.debug_decorations_last_rect),
                    decoration.lastEventRect,
                )
                DebugKeyValue(
                    stringResource(R.string.debug_decorations_last_group),
                    decoration.lastEventGroup,
                )
                DebugKeyValue(
                    stringResource(R.string.debug_decorations_last_applied),
                    decoration.lastAppliedCount.toString(),
                )
                DebugKeyValue(
                    stringResource(R.string.debug_decorations_active),
                    decoration.activeCount.toString(),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Actions ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onForceColorPicker, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_action_force_color),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                Button(onClick = onForceContextMenu, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_action_force_context),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onSimulateHighlightTap, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_action_simulate_tap),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                Button(onClick = onClearLog, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_action_clear_log),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onCopyLog, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.debug_action_copy_log),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onInspectHighlightsHtml, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_action_inspect_html),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                Button(onClick = onLogWebViewTree, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_action_log_webview),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Event log ─────────────────────────────────────────
            Text(
                text = stringResource(R.string.debug_section_log),
                color = Color(0xFFADC6FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161F33)),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                ) {
                    if (events.isEmpty()) {
                        Text(
                            text = "—",
                            style =
                                TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                            color = Color(0xFF8A8FA3),
                        )
                    } else {
                        for (e in events.take(50)) {
                            val color =
                                when (e.level) {
                                    DebugLog.Level.INFO -> Color(0xFFDDE2F8)
                                    DebugLog.Level.WARN -> Color(0xFFFFB454)
                                    DebugLog.Level.ERROR -> Color(0xFFEF4444)
                                    DebugLog.Level.SUCCESS -> Color(0xFF6EE7B7)
                                }
                            Text(
                                text = "[${e.level.name}] ${e.tag}: ${e.message}",
                                style =
                                    TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                color = color,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DebugSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161F33)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                color = Color(0xFFADC6FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            content()
        }
    }
}

@Composable
private fun DebugKeyValue(
    key: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key,
            style =
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            color = Color(0xFF8A8FA3),
            modifier = Modifier.width(140.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style =
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            color = Color(0xFFDDE2F8),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DebugKeyValueColored(
    key: String,
    value: String,
    isPositive: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key,
            style =
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            color = Color(0xFF8A8FA3),
            modifier = Modifier.width(140.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style =
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            color = if (isPositive) Color(0xFF6EE7B7) else Color(0xFFEF4444),
            modifier = Modifier.weight(1f),
        )
    }
}
