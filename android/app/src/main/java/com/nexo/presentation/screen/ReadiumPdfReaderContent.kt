package com.nexo.presentation.screen

import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import com.nexo.debug.DebugLog
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReaderSettings
import com.nexo.presentation.screen.readium.findWebView
import com.nexo.presentation.screen.readium.highlightsToPdfDecorations
import com.nexo.presentation.screen.readium.installPdfActionModeCallback
import com.nexo.presentation.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import kotlin.coroutines.resume

/** Group identifier for all highlight decorations. */
private const val DECORATION_GROUP = "com.nexo.highlights"

private const val TAG = "ReadiumPdfReaderContent"

/**
 * [ActionMode.Callback] that suppresses the native text-selection toolbar.
 * Returning `false` from [onCreateActionMode] prevents the ActionMode from
 * being created at all, so only our custom [SelectionOverlay] is shown.
 */
// SuppressPdfSelectionActionMode moved to readium/ReadiumViewUtils.kt

/**
 * Readium-powered PDF reader content.
 *
 * Hosts Readium's [PdfNavigatorFragment] inside a [FragmentContainerView]
 * via [AndroidView].  Creates a [PdfNavigatorFactory] from the [Publication]
 * and a [PdfiumEngineProvider], then uses [PdfNavigatorFactory.createFragmentFactory]
 * to set up the fragment for native PDF rendering.
 */
@Composable
@OptIn(ExperimentalReadiumApi::class)
fun ReadiumPdfReaderContent(
    publication: Publication,
    highlights: List<Highlight>,
    readerSettings: ReaderSettings,
    viewModel: ReaderViewModel,
    onShowChrome: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current as FragmentActivity
    val fragmentManager = remember { context.supportFragmentManager }
    val containerId = remember { View.generateViewId() }

    // Create PdfiumEngineProvider — the adapter engine for PdfNavigatorFragment
    val pdfEngineProvider =
        remember {
            PdfiumEngineProvider()
        }

    // Create the PdfNavigatorFactory — does NOT use a Configuration class
    val navigatorFactory =
        remember(publication, pdfEngineProvider) {
            PdfNavigatorFactory(publication, pdfEngineProvider)
        }

    // S7: live highlights for the holder call below (the deleted VM delegate
    // read them live from the aggregate; rememberUpdatedState preserves that).
    val latestHighlights by rememberUpdatedState(highlights)

    var navigatorFragment by remember { mutableStateOf<PdfNavigatorFragment<*, *>?>(null) }
    var containerReady by remember { mutableStateOf(false) }

    // ── Commit PdfNavigatorFragment ───────────────────────────────
    LaunchedEffect(containerReady) {
        if (!containerReady) return@LaunchedEffect
        val tag = "ReadiumPdfNavigator"
        val existing = fragmentManager.findFragmentByTag(tag)
        if (existing == null) {
            val initialLocator =
                publication.readingOrder.firstOrNull()?.let {
                    publication.locatorFromLink(it)
                }
            val factory =
                navigatorFactory.createFragmentFactory(
                    initialLocator = initialLocator,
                )
            fragmentManager.fragmentFactory = factory
            fragmentManager.commit {
                add(containerId, PdfNavigatorFragment::class.java, Bundle(), tag)
            }
        }
        delay(200)
        @Suppress("UNCHECKED_CAST")
        navigatorFragment = fragmentManager.findFragmentByTag(tag) as? PdfNavigatorFragment<*, *>
    }

    // ── Suppress native ActionMode if the PDF navigator hosts a WebView ─
    // PdfNavigatorFragment with PdfiumEngineProvider renders via a native PDF
    // view, so a WebView is normally not present and no ActionMode appears.
    // We still install the guard defensively in case the engine changes.
    LaunchedEffect(navigatorFragment) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        repeat(20) {
            val view = frag.view
            if (view != null) {
                installPdfActionModeCallback(view)
                DebugLog.success("PdfReadium", "Installed ActionMode callback (defensive)")
                return@LaunchedEffect
            }
            delay(50)
        }
        Log.d("ReadiumPdfReaderContent", "No selectable view ready for action-mode suppression")
        DebugLog.warn("PdfReadium", "No selectable view ready for action-mode suppression")
    }

    // ── currentLocator → ViewModel ────────────────────────────────
    LaunchedEffect(navigatorFragment) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        try {
            @Suppress("UNCHECKED_CAST")
            val locatorFlow = frag.currentLocator as StateFlow<Locator>
            locatorFlow.collect { locator ->
                viewModel.lifecycleHolder.onReadiumLocatorChanged(locator)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect currentLocator from PdfNavigatorFragment", e)
        }
    }

    // ── currentSelection (poll + diff) → ViewModel ────────────────
    // Note: PdfNavigatorFragment does NOT implement evaluateJavascript,
    // so we fall back to extracting text from the locator's text context.
    LaunchedEffect(navigatorFragment) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        val selectable =
            frag as? SelectableNavigator ?: run {
                DebugLog.warn("PdfReadium", "PdfNavigatorFragment is NOT a SelectableNavigator")
                return@LaunchedEffect
            }
        DebugLog.info("PdfReadium", "SelectableNavigator acquired (hash=${selectable.hashCode()})")
        var lastSelection: Boolean = false
        var pollCount = 0
        while (isActive) {
            delay(300)
            pollCount++
            val sel =
                runCatching { selectable.currentSelection() }
                    .onFailure { DebugLog.error("PdfReadium", "currentSelection() threw: ${it.message}") }
                    .getOrNull()
            if (pollCount % 10 == 0) {
                DebugLog.info(
                    "PdfReadium",
                    "Poll #$pollCount: ${if (sel != null) "selection present" else "no selection"}",
                )
            }
            if (sel != null) {
                val selRect =
                    sel.rect ?: run {
                        if (lastSelection) viewModel.interactionHolder.onSelectionCleared()
                        lastSelection = false
                        continue
                    }
                // Always try JS selection first (even for PDF) — locator.text window is ~150 chars and truncates long selections.
                // Pdfium has no fragment-level evaluateJavascript, so probe the underlying WebView if present.
                val fallbackText = sel.locator.text?.let { "${it.before ?: ""}${it.after ?: ""}" } ?: ""
                val text: String =
                    try {
                        val webView = frag.view?.findWebView()
                        if (webView != null) {
                            val jsResult = webView.evalSelectionJs()
                            if (jsResult.isNullOrBlank()) fallbackText else jsResult
                        } else {
                            fallbackText
                        }
                    } catch (_: Throwable) {
                        fallbackText
                    }
                viewModel.interactionHolder.onReadiumSelection(
                    locator = sel.locator,
                    rect = selRect,
                    text = text,
                    existingHighlights = latestHighlights,
                )
                lastSelection = true
            } else {
                if (lastSelection) {
                    viewModel.interactionHolder.onSelectionCleared()
                }
                lastSelection = false
            }
        }
    }

    // ── Decoration sync (highlights → decorations) ────────────────
    LaunchedEffect(highlights, navigatorFragment) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        val decorable = frag as? DecorableNavigator ?: return@LaunchedEffect
        if (highlights.isEmpty()) {
            decorable.applyDecorations(emptyList(), DECORATION_GROUP)
            return@LaunchedEffect
        }
        val decorations = highlightsToPdfDecorations(highlights)
        decorable.applyDecorations(decorations, DECORATION_GROUP)
    }

    // ── navigateToLocator event flow → navigator.go() ────────────
    LaunchedEffect(navigatorFragment) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        viewModel.navigateToLocator.collect { locator ->
            try {
                frag.go(locator, animated = false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to navigate PDF navigator to locator", e)
            }
        }
    }

    // ── Clean up ──────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            val frag = navigatorFragment
            if (frag != null && frag.isAdded) {
                fragmentManager.commit { remove(frag) }
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                FragmentContainerView(ctx).also { view ->
                    view.id = containerId
                    view.addOnAttachStateChangeListener(
                        object : android.view.View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                containerReady = true
                            }

                            override fun onViewDetachedFromWindow(v: android.view.View) {}
                        },
                    )
                }
            },
            modifier =
                Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                    viewModel.lifecycleHolder.onReadiumViewportChanged(coordinates.size.height)
                },
        )

        // Edge-tap zones (top + bottom 5%) for re-showing the chrome. See
        // [ChromeEdgeTapZones] in ReadiumReaderContent for the full rationale —
        // the middle 90% has no overlay so the PDF fragment's own gestures
        // (swipe to change page, pinch to zoom) work without interference.
        if (onShowChrome != null) {
            ChromeEdgeTapZones(onShowChrome = { onShowChrome() })
        }
    }
}

private suspend fun WebView.evalSelectionJs(): String? =
    suspendCancellableCoroutine { cont ->
        try {
            evaluateJavascript("(function(){var s=window.getSelection();return s?s.toString():'';})()") { result ->
                // WebView wraps result in JSON quotes; strip them
                val cleaned =
                    result
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                if (cont.isActive) cont.resume(cleaned)
            }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }
