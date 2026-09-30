package com.nexo.presentation.viewmodel.reader.lifecycle

import android.app.Application
import android.util.Log
import com.nexo.presentation.UiEvent
import com.nexo.presentation.viewmodel.reader.ReaderLifecycleState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * PDF loader via Readium PublicationOpener with PdfiumDocumentFactory.
 * Preserves verbatim: onPdfDocumentLoaded, loadEpoch handling.
 */
class PdfBookLoader(
    private val application: Application,
    private val state: MutableStateFlow<ReaderLifecycleState>,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val onErrorEvent: (UiEvent) -> Unit,
    private val onBookLoaded: (String) -> Unit,
    private val onProgressDisplay: () -> Unit,
) : BookLoader,
    Clearable {
    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _loadTimeMs = MutableStateFlow<Long?>(null)
    override val loadTimeMs: StateFlow<Long?> = _loadTimeMs.asStateFlow()

    @Volatile
    override var loadEpoch: Long = 0L
        private set

    companion object {
        private const val TAG = "PdfBookLoader"
    }

    override suspend fun open(
        bookId: String,
        filePath: String,
        format: String,
    ): Result<Pair<Publication, Locator?>> =
        try {
            val file = File(filePath)
            if (!file.exists()) throw Exception("File not found. Try importing the book again.")
            val fileUri =
                android.net.Uri
                    .fromFile(file)
                    .toString()
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(application.contentResolver, httpClient)
            val url = AbsoluteUrl(fileUri) ?: throw Exception("Invalid file URI: $fileUri")
            val retrieveResult = withContext(Dispatchers.IO) { assetRetriever.retrieve(url) }
            val asset = retrieveResult.getOrNull() ?: throw Exception("Failed to retrieve PDF asset")
            val parser =
                DefaultPublicationParser(
                    context = application,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = PdfiumDocumentFactory(application),
                )
            val opener = PublicationOpener(parser)
            val openResult = withContext(Dispatchers.IO) { opener.open(asset, allowUserInteraction = false) }
            val publication: Publication =
                openResult.fold(
                    onSuccess = { it },
                    onFailure = { throw Exception("Readium PDF open failed: ${it.message}") },
                )
            Result.success(publication to null)
        } catch (e: Exception) {
            Result.failure(e)
        }

    fun loadPdfBook(
        bookId: String,
        filePath: String,
        startTime: Long,
    ) {
        scope.launch(mainDispatcher) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    val message = "File not found. Try importing the book again."
                    state.update { it.copy(isLoading = false, error = message) }
                    _isLoading.value = false
                    onErrorEvent(UiEvent.ShowSnackbar(message))
                    return@launch
                }
                val fileUri =
                    android.net.Uri
                        .fromFile(file)
                        .toString()
                val httpClient = DefaultHttpClient()
                val assetRetriever = AssetRetriever(application.contentResolver, httpClient)
                val url =
                    AbsoluteUrl(fileUri)
                        ?: throw Exception("Invalid file URI: $fileUri")
                val retrieveResult = withContext(Dispatchers.IO) { assetRetriever.retrieve(url) }
                val asset = retrieveResult.getOrNull() ?: throw Exception("Failed to retrieve PDF asset")
                val parser =
                    DefaultPublicationParser(
                        context = application,
                        httpClient = httpClient,
                        assetRetriever = assetRetriever,
                        pdfFactory = PdfiumDocumentFactory(application),
                    )
                val opener = PublicationOpener(parser)
                val openResult =
                    withContext(Dispatchers.IO) {
                        opener.open(asset, allowUserInteraction = false)
                    }
                val publication: Publication =
                    openResult.fold(
                        onSuccess = { it },
                        onFailure = { error -> throw Exception("Readium PDF open failed: ${error.message}") },
                    )
                val loadTime = System.currentTimeMillis() - startTime

                // A2/A3 - reader open + native TTFP (loadBook -> publication-ready).
                val bucketedLoad =
                    com.nexo.debug.SentryMetrics
                        .bucketDurationMs(loadTime)
                val readerTags =
                    mapOf(
                        "source" to "reader",
                        "engine" to "readium",
                        "format" to "pdf",
                        "platform" to "android",
                    )
                com.nexo.debug.SentryMetrics
                    .distribution("reader_open", bucketedLoad, readerTags)
                com.nexo.debug.SentryMetrics
                    .distribution("reader_ttfp_native", bucketedLoad, readerTags)
                Log.d(TAG, "Readium loaded PDF in ${loadTime}ms")
                val chapters = TocBuilder.buildChaptersFromPublication(publication)
                state.update {
                    it.copy(
                        readiumPublication = publication,
                        chapters = chapters,
                        currentChapterIndex = 0,
                        isLoading = false,
                        loadTimeMs = loadTime,
                    )
                }
                _isLoading.value = false
                _loadTimeMs.value = loadTime
                onProgressDisplay()
                onBookLoaded(bookId)
            } catch (e: Exception) {
                Log.e(TAG, "Readium failed to open PDF", e)
                val userMessage = e.message ?: "Failed to open PDF with Readium"
                state.update { it.copy(isLoading = false, error = userMessage) }
                _isLoading.value = false
                onErrorEvent(UiEvent.ShowSnackbar(userMessage))
            }
        }
    }

    fun onPdfDocumentLoaded(pages: Int) {
        val currentPages = state.value.totalPdfPages
        if (currentPages != pages) {
            state.update { it.copy(totalPdfPages = pages) }
            onProgressDisplay()
        }
    }

    override fun onCleared() {}
}
