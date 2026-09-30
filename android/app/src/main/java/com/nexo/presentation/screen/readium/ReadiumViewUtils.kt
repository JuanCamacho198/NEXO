package com.nexo.presentation.screen.readium

import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.nexo.debug.DebugLog

/**
 * [ActionMode.Callback] that suppresses Readium's default selection toolbar
 * (the native Copy / Share / Translate / overflow floating bar).
 *
 * Returning `false` from [onCreateActionMode] prevents the ActionMode from
 * being created at all, so only our custom [com.nexo.presentation.screen.SelectionOverlay] is shown.
 */
private object SuppressSelectionActionMode : ActionMode.Callback {
    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean = false

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean = false

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean = false

    override fun onDestroyActionMode(mode: ActionMode) = Unit
}

/**
 * Recursively searches [root] for a [WebView].
 */
internal fun View.findWebView(): WebView? {
    if (this is WebView) return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i)?.findWebView()?.let { return it }
        }
    }
    return null
}

/**
 * Installs [SuppressSelectionActionMode] on the first [WebView] found under
 * [root]. Returning `false` from [ActionMode.Callback.onCreateActionMode]
 * prevents the native Copy/Share/Select All floating bar from appearing,
 * so only our custom [com.nexo.presentation.screen.SelectionOverlay] is shown.
 *
 * Also registers attach/layout listeners to re-install the callback if
 * Readium swaps the WebView instance under us (it has been observed to do
 * so on configuration changes / chapter changes). The native menu is hidden
 * solely via [SuppressSelectionActionMode]; long-press itself is NOT
 * consumed so the WebView can still create a text selection for
 * [org.readium.r2.navigator.SelectableNavigator.currentSelection()].
 */
internal fun installActionModeCallback(root: View) {
    val webView =
        root.findWebView() ?: run {
            Log.d("ReadiumReaderContent", "No WebView found in navigator fragment")
            DebugLog.warn("ActionMode", "No WebView found in EPUB navigator")
            return
        }
    installEpubCallbackOnWebView(webView)

    // Re-install the callback whenever the view is attached or the layout
    // changes — defensive against Readium recreating or swapping the WebView.
    val attachListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                DebugLog.info("Readium", "WebView attached — re-installing callback")
                installEpubCallbackOnWebView(v as? WebView ?: return)
            }

            override fun onViewDetachedFromWindow(v: View) {
                // no-op
            }
        }
    val layoutListener =
        android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (webView.parent == null) return@OnGlobalLayoutListener
            val current =
                try {
                    webView::class.java
                        .getMethod("getCustomSelectionActionModeCallback")
                        .invoke(webView)
                } catch (_: Throwable) {
                    null
                }
            if (current !== SuppressSelectionActionMode) {
                DebugLog.warn("Readium", "WebView callback lost — re-installing")
                installEpubCallbackOnWebView(webView)
            }
        }
    webView.addOnAttachStateChangeListener(attachListener)
    webView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
}

/**
 * Installs [SuppressSelectionActionMode] on the first [WebView] found under
 * [root]. For the current Pdfium-based PDF navigator this is normally a no-op
 * because no WebView exists in the hierarchy, but it protects against future
 * engine changes.
 *
 * PDF divergence: `isLongClickable=false` + `OnLongClickListener{true}` consumer
 * to fully disable long-press handling, unlike EPUB which keeps `isLongClickable=true`.
 */
internal fun installPdfActionModeCallback(root: View) {
    val webView =
        root.findWebView() ?: run {
            Log.d("ReadiumPdfReaderContent", "No WebView in PDF navigator — ActionMode not applicable")
            DebugLog.info("ActionMode", "WebView not found in ${root.javaClass.simpleName}")
            return
        }
    installPdfCallbackOnWebView(webView)
}

private fun installEpubCallbackOnWebView(webView: WebView) {
    try {
        // Use getDeclaredMethod so that Readium's R2WebView subclass
        // (which may hide or override the parent) does not block access.
        // getMethod only finds public methods; getDeclaredMethod finds
        // all methods including package-private overrides.
        val method =
            try {
                webView.javaClass.getDeclaredMethod(
                    "setCustomSelectionActionModeCallback",
                    ActionMode.Callback::class.java,
                )
            } catch (e: NoSuchMethodException) {
                WebView::class.java.getDeclaredMethod(
                    "setCustomSelectionActionModeCallback",
                    ActionMode.Callback::class.java,
                )
            }
        method.isAccessible = true
        method.invoke(webView, SuppressSelectionActionMode)
        // Do NOT consume long-press: isLongClickable must stay true and no
        // OnLongClickListener should return true, otherwise the WebView never
        // creates a text selection and SelectableNavigator.currentSelection()
        // (polled every 300ms) stays null forever. SuppressSelectionActionMode
        // alone is enough to hide the native Copy/Share bar without blocking
        // the gesture.
        runCatching { webView.isLongClickable = true }
        runCatching { webView.setOnLongClickListener(null) }
        Log.d("ReadiumReaderContent", "Installed custom selection ActionMode callback on ${webView.javaClass.simpleName}")
        DebugLog.success("ActionMode", "Callback installed on ${webView.javaClass.simpleName}")
    } catch (e: Throwable) {
        // The ActionMode suppression is a nice-to-have — if it fails the
        // native toolbar may appear alongside our custom overlay, but core
        // functionality is not affected. Downgrade from ERROR to WARN.
        Log.w("ReadiumReaderContent", "Failed to install ActionMode callback (non-critical)", e)
        DebugLog.warn("ActionMode", "Callback not installed (non-critical): ${e::class.java.simpleName}: ${e.message}")
    }
}

private fun installPdfCallbackOnWebView(webView: WebView) {
    try {
        val method =
            try {
                // First try the actual runtime class (handles R2WebView overrides)
                webView.javaClass.getDeclaredMethod(
                    "setCustomSelectionActionModeCallback",
                    ActionMode.Callback::class.java,
                )
            } catch (e: NoSuchMethodException) {
                // Fallback to the public WebView class
                WebView::class.java.getDeclaredMethod(
                    "setCustomSelectionActionModeCallback",
                    ActionMode.Callback::class.java,
                )
            }
        method.isAccessible = true
        method.invoke(webView, SuppressSelectionActionMode)
        runCatching { webView.isLongClickable = false }
        runCatching { webView.setOnLongClickListener { true } }
        Log.d("ReadiumReaderContent", "Installed custom selection ActionMode callback on ${webView.javaClass.simpleName}")
        DebugLog.success("ActionMode", "Callback installed on ${webView.javaClass.simpleName}")
    } catch (e: Throwable) {
        Log.e("ReadiumReaderContent", "Failed to install ActionMode callback", e)
        DebugLog.error("ActionMode", "Failed to install callback: ${e::class.java.simpleName}: ${e.message}")
    }
}

/**
 * WebView extension variant for direct calls — installs the EPUB-style callback.
 * Preserves original `WebView.installActionModeCallbackOnWebView()` entry point.
 */
internal fun WebView.installActionModeCallbackOnWebView() {
    installEpubCallbackOnWebView(this)
}
