package com.nexo.presentation.screen.readium

import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.nexo.debug.DebugDual
import com.nexo.debug.DebugLog
import com.nexo.debug.DebugStateHolder
import com.nexo.domain.model.Highlight
import com.nexo.presentation.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

internal const val DECORATION_GROUP = "com.nexo.highlights"

private const val POLL_MS = 300L
private const val TAP_PROBE_MS = 80L
private const val ACTION_MODE_RETRIES = 20
private const val ACTION_MODE_DELAY_MS = 50L
private const val DECOR_INITIAL_DELAY_MS = 100L
private const val DECOR_RETRY_COUNT = 10
private const val DECOR_RETRY_DELAY_MS = 200L

/**
 * Remembers the selection bridge — owns polling, decoration listener with 80ms tap probe,
 * ActionMode suppression watcher, and decoration retry/sync.
 *
 * @param navigatorFragment current Readium fragment (nullable while loading)
 * @param highlights current highlights from Room/ViewModel
 * @param publication current publication for readingOrder fallback
 * @param readingOrder reading order from host (single source; do not recompute)
 * @param viewModel ViewModel to forward selection/highlight events
 */
@Composable
fun rememberSelectionBridge(
    navigatorFragment: EpubNavigatorFragment?,
    highlights: List<Highlight>,
    publication: Publication,
    readingOrder: List<Link>,
    viewModel: ReaderViewModel,
) {
    val tapProbeScope = rememberCoroutineScope()
    val latestHighlights by rememberUpdatedState(highlights)

    // ── currentSelection (poll + diff + debug) → ViewModel ──────
    LaunchedEffect(navigatorFragment) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        Log.d("SelectionDebug", "navigatorFragment acquired, casting to SelectableNavigator...")
        val selectable = frag as? SelectableNavigator
        if (selectable == null) {
            Log.e("SelectionDebug", "CAST FAILED: EpubNavigatorFragment is NOT a SelectableNavigator")
            DebugLog.error("Readium", "CAST FAILED: EpubNavigatorFragment is NOT a SelectableNavigator")
            return@LaunchedEffect
        }
        Log.d("SelectionDebug", "CAST OK: SelectableNavigator=${selectable.hashCode()}")
        DebugLog.info("Readium", "SelectableNavigator acquired (hash=${selectable.hashCode()})")
        var lastSelection: Boolean = false
        var pollCount = 0
        while (isActive) {
            delay(POLL_MS)
            pollCount++
            Log.d("SelectionDebug", "Poll #$pollCount — calling currentSelection()...")
            val sel: org.readium.r2.navigator.Selection? =
                try {
                    selectable.currentSelection()
                } catch (e: Throwable) {
                    Log.e("SelectionDebug", "currentSelection() THREW: ${e::class.simpleName}: ${e.message}", e)
                    DebugLog.error("Readium", "currentSelection() threw: ${e::class.simpleName}: ${e.message}")
                    null
                }
            if (pollCount % 10 == 0) {
                DebugLog.info(
                    "Readium",
                    "Poll #$pollCount: ${if (sel != null) "selection present" else "no selection"}",
                )
            }
            Log.d(
                "SelectionDebug",
                "Poll #$pollCount — currentSelection() returned: ${if (sel != null) "non-null (locator=${sel.locator.href})" else "null"}",
            )
            if (sel != null) {
                Log.d(
                    "SelectionDebug",
                    "sel.rect=${sel.rect}, sel.locator.locations.totalProgression=${sel.locator.locations.totalProgression}",
                )
                val selRect = sel.rect
                if (selRect == null) {
                    Log.w("SelectionDebug", "sel.rect is null — skipping")
                    if (lastSelection) {
                        Log.d("SelectionDebug", "Clearing previous selection (rect was null)")
                        viewModel.interactionHolder.onSelectionCleared()
                    }
                    lastSelection = false
                    continue
                }
                val text: String =
                    try {
                        val jsResult =
                            frag.evaluateJavascript(
                                "(function(){var s=window.getSelection();return s?s.toString():'';})()",
                            )
                        Log.d("SelectionDebug", "evaluateJavascript result: '$jsResult'")
                        if (jsResult.isNullOrBlank()) {
                            val fallback = sel.locator.text?.let { "${it.before ?: ""}${it.after ?: ""}" } ?: ""
                            Log.d("SelectionDebug", "JS result empty, using locator.text fallback: '$fallback'")
                            fallback
                        } else {
                            jsResult
                        }
                    } catch (e: Throwable) {
                        Log.e("SelectionDebug", "evaluateJavascript THREW: ${e::class.simpleName}: ${e.message}", e)
                        val fallback = sel.locator.text?.let { "${it.before ?: ""}${it.after ?: ""}" } ?: ""
                        Log.d("SelectionDebug", "Using locator.text fallback after exception: '$fallback'")
                        fallback
                    }
                Log.d("SelectionDebug", "Calling interactionHolder.onReadiumSelection(text='${text.take(50)}', rect=$selRect)")
                try {
                    viewModel.interactionHolder.onReadiumSelection(
                        locator = sel.locator,
                        rect = selRect,
                        text = text,
                        existingHighlights = latestHighlights,
                    )
                    Log.d("SelectionDebug", "interactionHolder.onReadiumSelection OK")
                } catch (e: Throwable) {
                    Log.e("SelectionDebug", "interactionHolder.onReadiumSelection THREW: ${e::class.simpleName}: ${e.message}", e)
                }
                lastSelection = true
            } else {
                if (lastSelection) {
                    Log.d("SelectionDebug", "Selection cleared (user tapped away)")
                    viewModel.interactionHolder.onSelectionCleared()
                }
                lastSelection = false
            }
        }
    }

    // ── Highlight tap → custom menu (DecorableNavigator.Listener) ─
    DisposableEffect(navigatorFragment) {
        val frag = navigatorFragment
        val decorable = frag as? DecorableNavigator
        val selectable = frag as? SelectableNavigator
        val listener =
            if (decorable != null) {
                object : DecorableNavigator.Listener {
                    override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                        val rectString = event.rect?.toString() ?: "null"
                        DebugLog.info(
                            "Readium",
                            "onDecorationActivated: group=${event.group}, id=${event.decoration.id}, rect=$rectString",
                        )
                        DebugStateHolder.recordDecorationEvent(
                            event.decoration.id,
                            event.group,
                            event.rect,
                        )
                        if (event.group != DECORATION_GROUP) {
                            DebugLog.warn("Readium", "Decoration group mismatch (got ${event.group})")
                            return false
                        }
                        val rect: RectF = event.rect ?: return false
                        val highlight = latestHighlights.firstOrNull { it.id == event.decoration.id }
                        if (highlight == null) {
                            val knownIds = latestHighlights.map { it.id }
                            DebugLog.warn(
                                "Readium",
                                "onDecorationActivated: no highlight found for id=${event.decoration.id} — known IDs: $knownIds",
                            )
                            return false
                        }
                        DebugStateHolder.recordHighlightActivation(event.decoration.id, rectString)
                        if (selectable != null) {
                            tapProbeScope.launch {
                                try {
                                    delay(TAP_PROBE_MS)
                                    val currentSel =
                                        try {
                                            selectable.currentSelection()
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    if (currentSel != null) {
                                        DebugLog.info(
                                            "Readium",
                                            "onDecorationActivated SKIPPED: user is creating a new selection (id=${event.decoration.id})",
                                        )
                                        return@launch
                                    }
                                } catch (_: Throwable) {
                                    // Probe failed — fall through and treat as a tap
                                }
                                viewModel.interactionHolder.onHighlightTapped(highlight, rect)
                            }
                            return true
                        }
                        viewModel.interactionHolder.onHighlightTapped(highlight, rect)
                        return true
                    }
                }
            } else {
                null
            }

        if (decorable != null && listener != null) {
            decorable.addDecorationListener(DECORATION_GROUP, listener)
            DebugStateHolder.setHighlightListenerRegistered(true)
            DebugStateHolder.setListenerRegistered(true)
            DebugLog.info("Readium", "Decoration listener registered for group=$DECORATION_GROUP")
        } else {
            DebugStateHolder.setHighlightListenerRegistered(false)
            DebugStateHolder.setListenerRegistered(false)
        }
        onDispose {
            if (decorable != null && listener != null) {
                decorable.removeDecorationListener(listener)
                DebugStateHolder.setHighlightListenerRegistered(false)
                DebugStateHolder.setListenerRegistered(false)
                DebugLog.info("Readium", "Decoration listener removed")
            }
        }
    }

    // ── Suppress native ActionMode on the WebView ─────────────────
    LaunchedEffect(navigatorFragment) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        repeat(ACTION_MODE_RETRIES) {
            val view = frag.view
            if (view != null) {
                installActionModeCallback(view)
                DebugStateHolder.setActionModeInstalled(true)
                return@LaunchedEffect
            }
            delay(ACTION_MODE_DELAY_MS)
        }
        Log.d("ReadiumReaderContent", "WebView not ready for action-mode suppression")
        DebugLog.warn("Readium", "WebView not ready for action-mode suppression after 1s")
    }

    // ── Force a re-apply of decorations after the listener is registered ─
    LaunchedEffect(navigatorFragment, publication) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        val decorable = frag as? DecorableNavigator ?: return@LaunchedEffect
        delay(DECOR_INITIAL_DELAY_MS)
        repeat(DECOR_RETRY_COUNT) {
            val currentDecorations = highlightsToDecorations(highlights, readingOrder, publication)
            if (currentDecorations.isNotEmpty() || highlights.isNotEmpty()) {
                decorable.applyDecorations(currentDecorations, DECORATION_GROUP)
                DebugLog.info(
                    "Readium",
                    "Post-listener re-apply: pushed ${currentDecorations.size} decorations",
                )
                DebugDual.logHighlightApplied(currentDecorations.size)
                DebugDual.d(DebugDual.TAG_SYNC, "Post-listener re-apply dual log count=${currentDecorations.size}")
                DebugStateHolder.recordApplied(currentDecorations.size)
                return@LaunchedEffect
            }
            delay(DECOR_RETRY_DELAY_MS)
        }
        DebugLog.info("Readium", "Post-listener re-apply: gave up waiting for highlights (still empty)")
    }

    // ── Decoration sync (highlights → decorations) ────────────────
    LaunchedEffect(highlights, navigatorFragment, publication) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        val decorable = frag as? DecorableNavigator ?: return@LaunchedEffect
        val decorations = highlightsToDecorations(highlights, readingOrder, publication)
        if (highlights.isEmpty()) {
            DebugLog.info("Readium", "Decoration sync: clearing decorations (highlights empty)")
            decorable.applyDecorations(emptyList(), DECORATION_GROUP)
            DebugDual.logHighlightApplied(0)
            DebugStateHolder.recordApplied(0)
            return@LaunchedEffect
        }
        DebugLog.info("Readium", "Decoration sync: pushing ${decorations.size} decorations")
        DebugDual.d(
            DebugDual.TAG_SYNC,
            "Decoration sync: pushing ${decorations.size} decorations via ${if (readingOrder.isNotEmpty()) "readingOrder(${readingOrder.size})" else "no-order"}",
        )
        decorable.applyDecorations(decorations, DECORATION_GROUP)
        DebugDual.logHighlightApplied(decorations.size)
        DebugStateHolder.recordApplied(decorations.size)
    }
}
