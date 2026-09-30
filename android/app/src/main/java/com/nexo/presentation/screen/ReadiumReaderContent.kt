@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.nexo.presentation.screen

import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.nexo.debug.DebugLog
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReaderSettings
import com.nexo.presentation.screen.readium.FragmentHostView
import com.nexo.presentation.screen.readium.rememberFragmentHost
import com.nexo.presentation.screen.readium.rememberSelectionBridge
import com.nexo.presentation.screen.readium.toEpubPreferences
import com.nexo.presentation.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

private const val TAG = "ReadiumReaderContent"

/**
 * Readium-powered EPUB reader content — thinned host.
 *
 * Delegates fragment lifecycle to [rememberFragmentHost] and selection/highlight
 * plumbing to [rememberSelectionBridge]. Keeps only 3 residual effects:
 * settings debounce, diagnostics, and navigation.
 */
@Composable
fun ReadiumReaderContent(
    publication: Publication,
    navigatorConfig: EpubNavigatorFactory.Configuration,
    highlights: List<Highlight>,
    readerSettings: ReaderSettings,
    viewModel: ReaderViewModel,
    initialLocator: Locator? = null,
    inspectHighlightsHtmlTrigger: SharedFlow<Unit> = MutableSharedFlow(),
    logWebViewTreeTrigger: SharedFlow<Unit> = MutableSharedFlow(),
    onShowChrome: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current as FragmentActivity
    val fragmentManager = remember { context.supportFragmentManager }
    val containerId = remember { View.generateViewId() }

    val hostState =
        rememberFragmentHost(
            publication = publication,
            navigatorConfig = navigatorConfig,
            fragmentManager = fragmentManager,
            containerId = containerId,
            initialLocator = initialLocator,
            viewModel = viewModel,
        )

    rememberSelectionBridge(
        navigatorFragment = hostState.navigatorFragment.value,
        highlights = highlights,
        publication = publication,
        readingOrder = hostState.readingOrder,
        viewModel = viewModel,
    )

    // ── Settings sync (readerSettings → EpubPreferences) ──────────
    LaunchedEffect(readerSettings) {
        delay(300)
        val frag = hostState.navigatorFragment.value ?: return@LaunchedEffect
        try {
            val prefs = readerSettings.toEpubPreferences()
            frag.submitPreferences(prefs)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to submit Readium reader preferences", e)
        }
    }

    // ── Diagnostics: inspect HTML + log WebView tree ──────────────
    LaunchedEffect(hostState.navigatorFragment.value) {
        val frag = hostState.navigatorFragment.value
        // Collect both diagnostic triggers in one effect to keep residual count =3
        launch {
            inspectHighlightsHtmlTrigger.collect {
                val currentFrag = hostState.navigatorFragment.value
                if (currentFrag == null) {
                    DebugLog.warn("InspectHL", "No navigator fragment available")
                    return@collect
                }
                val js =
                    """
                    (function(){
                        var results = [];
                        var spans = document.querySelectorAll('span, a, mark');
                        for (var i = 0; i < spans.length; i++) {
                            var el = spans[i];
                            var style = window.getComputedStyle(el);
                            var bg = style.backgroundColor;
                            if (bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent') {
                                var attrs = {};
                                for (var j = 0; j < el.attributes.length; j++) {
                                    var a = el.attributes[j];
                                    attrs[a.name] = a.value;
                                }
                                results.push({tag: el.tagName, bg: bg, attrs: attrs, text: el.textContent.substring(0, 50)});
                            }
                        }
                        return JSON.stringify(results);
                    })()
                    """.trimIndent()
                try {
                    val result = currentFrag.evaluateJavascript(js)
                    if (result.isNullOrBlank()) {
                        DebugLog.info("InspectHL", "Found 0 highlighted elements (empty result)")
                        return@collect
                    }
                    val trimmed =
                        result
                            .trim()
                            .removeSurrounding("\"")
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                    val arr = JSONArray(trimmed)
                    val count = arr.length()
                    DebugLog.info("InspectHL", "Found $count highlighted elements")
                    for (i in 0 until count) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val tag = obj.optString("tag")
                        val bg = obj.optString("bg")
                        val text = obj.optString("text")
                        val attrs = obj.optJSONObject("attrs")
                        val attrsStr = attrs?.toString() ?: "{}"
                        DebugLog.info("InspectHL", "tag=$tag, bg=$bg, attrs=$attrsStr, text=$text")
                    }
                } catch (t: Throwable) {
                    DebugLog.error("InspectHL", "Failed: ${t::class.simpleName}: ${t.message}")
                }
            }
        }
        launch {
            logWebViewTreeTrigger.collect {
                val currentFrag = hostState.navigatorFragment.value
                val rootView = currentFrag?.view
                if (rootView == null) {
                    DebugLog.warn("WebViewTree", "No fragment view available")
                    return@collect
                }
                val webView = rootView.findWebView()
                if (webView == null) {
                    DebugLog.warn("WebViewTree", "No WebView found in fragment hierarchy")
                    return@collect
                }
                val sb = StringBuilder()

                fun dumpView(
                    v: View,
                    depth: Int,
                ) {
                    sb
                        .append("  ".repeat(depth))
                        .append(v::class.java.simpleName)
                        .append(" id=")
                        .append(v.id)
                        .append(" visible=")
                        .append(v.visibility)
                        .append(" clickable=")
                        .append(v.isClickable)
                        .append('\n')
                    if (v is android.view.ViewGroup) {
                        for (i in 0 until v.childCount) {
                            val child = v.getChildAt(i) ?: continue
                            dumpView(child, depth + 1)
                        }
                    }
                }
                dumpView(webView, 0)
                val full = sb.toString()
                val lines = full.lines()
                DebugLog.info("WebViewTree", "Dumped ${lines.size} lines from WebView root")
                val chunkSize = 30
                if (lines.size <= chunkSize) {
                    DebugLog.info("WebViewTree", full.trimEnd())
                } else {
                    var chunkIndex = 0
                    var i = 0
                    while (i < lines.size) {
                        val end = (i + chunkSize).coerceAtMost(lines.size)
                        val chunk = lines.subList(i, end).joinToString("\n")
                        DebugLog.info("WebViewTree", "[chunk $chunkIndex]\n$chunk")
                        chunkIndex++
                        i = end
                    }
                }
            }
        }
    }

    // ── Navigation: navigateToLocator + clearSelection ────────────
    LaunchedEffect(hostState.navigatorFragment.value) {
        val frag = hostState.navigatorFragment.value ?: return@LaunchedEffect
        launch {
            viewModel.navigateToLocator.collect { locator ->
                try {
                    frag.go(locator, animated = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to navigate Readium navigator to locator", e)
                }
            }
        }
        launch {
            viewModel.clearSelectionEvent.collect {
                try {
                    frag.evaluateJavascript(
                        "(function(){var s=window.getSelection();if(s)s.removeAllRanges();})()",
                    )
                    Log.d("SelectionDebug", "WebView selection cleared via JS")
                } catch (e: Throwable) {
                    Log.e("SelectionDebug", "Failed to clear WebView selection", e)
                }
            }
        }
    }

    val currentOnShowChrome by rememberUpdatedState(onShowChrome)

    Box(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            hostState.FragmentHostView(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        if (currentOnShowChrome != null) {
            ChromeEdgeTapZones(onShowChrome = { currentOnShowChrome?.invoke() })
        }
    }
}

// Private extension to find WebView — keeps import local without exposing internals
private fun View.findWebView(): android.webkit.WebView? {
    if (this is android.webkit.WebView) return this
    if (this is android.view.ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i)?.findWebView()?.let { return it }
        }
    }
    return null
}
