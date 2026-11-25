package com.nexo.presentation.screen.pdfjs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nexo.debug.DebugLog
import com.nexo.presentation.screen.ChromeEdgeTapZones
import com.nexo.presentation.viewmodel.ReaderViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.SequenceInputStream

object PdfJsSpike {
    const val ENABLED = true
}

private const val TAG = "PdfJs"
private const val ORIGIN = "https://appassets.androidplatform.net"
private const val WORKER_URL_PARSE_POLYFILL =
    "if(typeof URL.parse!==\"function\"){URL.parse=(u,b)=>{try{return new URL(u,b)}catch{return null}}};\n"

private class EmptyToolbarCallback(
    private val original: ActionMode.Callback,
) : ActionMode.Callback2() {
    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        val result = original.onCreateActionMode(mode, menu)
        menu.clear()
        if (!toolbarSuppressionLogged) {
            toolbarSuppressionLogged = true
            DebugLog.info(TAG, "Native selection toolbar suppressed")
        }
        return result
    }

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean = original.onPrepareActionMode(mode, menu).also { menu.clear() }

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean = original.onActionItemClicked(mode, item)

    override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)

    override fun onGetContentRect(
        mode: ActionMode,
        view: View,
        outRect: Rect,
    ) {
        (original as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect)
            ?: super.onGetContentRect(mode, view, outRect)
    }
}

private var toolbarSuppressionLogged = false

private class NoToolbarWebView(
    context: Context,
) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = super.startActionMode(EmptyToolbarCallback(callback))

    override fun startActionMode(
        callback: ActionMode.Callback,
        type: Int,
    ): ActionMode? = super.startActionMode(EmptyToolbarCallback(callback), type)
}

private class BookPathHandler(
    private val context: Context,
    private val bookPath: String,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val stream: InputStream =
            try {
                if (bookPath.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(bookPath))
                } else {
                    FileInputStream(File(bookPath.removePrefix("file://")))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open book: ${e::class.java.simpleName}")
                DebugLog.warn(TAG, "Cannot open book: ${e::class.java.simpleName}")
                null
            } ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        return WebResourceResponse("application/pdf", null, stream)
    }
}

private class MjsAwareHandler(
    private val delegate: WebViewAssetLoader.PathHandler,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val response = delegate.handle(path) ?: return null
        val mime =
            when {
                path.endsWith(".mjs") || path.endsWith(".js") -> "text/javascript"
                path.endsWith(".wasm") -> "application/wasm"
                else -> return response
            }
        val data =
            if (path.endsWith("pdf.worker.min.mjs")) {
                SequenceInputStream(ByteArrayInputStream(WORKER_URL_PARSE_POLYFILL.toByteArray()), response.data)
            } else {
                response.data
            }
        return WebResourceResponse(mime, "utf-8", data)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PdfJsReaderContent(
    bookPath: String,
    viewModel: ReaderViewModel,
    onShowChrome: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val currentViewModel by rememberUpdatedState(viewModel)
    val startPage = remember { viewModel.sessionUiState.value.currentPdfPage + 1 }
    var webView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(webView) {
        val target = webView ?: return@LaunchedEffect
        viewModel.navigateToLocator.collect { locator ->
            val page = pdfPageFromLocator(locator) ?: return@collect
            target.evaluateJavascript("window.nexoGoToPage($page)", null)
        }
    }
    LaunchedEffect(webView) {
        val target = webView ?: return@LaunchedEffect
        viewModel.navigateToPdfPage.collect { pageIndex ->
            target.evaluateJavascript("window.nexoGoToPage(${pageIndex + 1})", null)
        }
    }
    Box(modifier = modifier) {
        AndroidView(
            modifier =
                Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                    viewModel.lifecycleHolder.onReadiumViewportChanged(coordinates.size.height)
                },
            factory = { context ->
                val loader =
                    WebViewAssetLoader
                        .Builder()
                        .setDomain("appassets.androidplatform.net")
                        .addPathHandler("/assets/", MjsAwareHandler(WebViewAssetLoader.AssetsPathHandler(context)))
                        .addPathHandler("/book/", BookPathHandler(context, bookPath))
                        .build()
                NoToolbarWebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.domStorageEnabled = false
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    WebView.setWebContentsDebuggingEnabled(true)
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                Log.d(TAG, "console ${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                                return true
                            }
                        }
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = !request.url.toString().startsWith(ORIGIN)

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                Log.e(TAG, "Render process gone, crashed=${detail.didCrash()}")
                                DebugLog.error(TAG, "Render process gone, crashed=${detail.didCrash()}")
                                return true
                            }
                        }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(
                            this,
                            "nexoPdf",
                            setOf(ORIGIN),
                        ) { _, message: WebMessageCompat, _, _, _ ->
                            handleMessage(message.data) { currentViewModel }
                        }
                    } else {
                        DebugLog.error(TAG, "WEB_MESSAGE_LISTENER not supported")
                    }
                    DebugLog.info(TAG, "open at page $startPage")
                    loadUrl("$ORIGIN/assets/pdfjs/viewer.html?page=$startPage")
                    webView = this
                }
            },
            onRelease = {
                webView = null
                it.destroy()
            },
        )
        if (onShowChrome != null) {
            ChromeEdgeTapZones(onShowChrome = { onShowChrome() })
        }
    }
}

private fun handleMessage(
    raw: String?,
    currentReader: () -> ReaderViewModel,
) {
    val message = parsePdfJsMessage(raw)
    val summary =
        when (message) {
            is PdfJsMessage.Loaded -> {
                currentReader().lifecycleHolder.onPdfDocumentLoaded(message.numPages)
                "loaded numPages=${message.numPages}"
            }
            is PdfJsMessage.Page -> {
                currentReader().lifecycleHolder.onPdfPageReported(message.page - 1, message.total)
                "page ${message.page}/${message.total}"
            }
            is PdfJsMessage.Selection ->
                if (message.empty) "selection empty" else "selection page=${message.pageNumber} textLen=${message.textLength}"
            is PdfJsMessage.Error -> "error ${message.message}"
            null -> "unparseable len=${raw?.length ?: 0}"
        }
    Log.d(TAG, summary)
    DebugLog.info(TAG, summary)
}
