package com.nexo.presentation.viewmodel.reader.lifecycle

import android.app.Application
import android.util.Log
import com.nexo.R
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.debug.DebugLog
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.UiEvent
import com.nexo.presentation.viewmodel.CfiMigrator
import com.nexo.presentation.viewmodel.reader.ReaderLifecycleState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * EPUB loader via Readium PublicationOpener + AssetRetriever.
 *
 * Preserves verbatim: resolveEpubCfi, migrateCfiDataForBook, loadEpoch++,
 * and publication + locator handling.
 */
class EpubBookLoader(
    private val application: Application,
    private val readerRepository: ReaderRepository,
    private val state: MutableStateFlow<ReaderLifecycleState>,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val supabaseProgressSync: SupabaseProgressSync?,
    private val onErrorEvent: (UiEvent) -> Unit,
    private val onNavigateToLocator: (Locator) -> Unit,
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
        private const val TAG = "EpubBookLoader"
        private const val EPUB_LOAD_TIMEOUT_MS = 30_000L
    }

    override suspend fun open(
        bookId: String,
        filePath: String,
        format: String,
    ): Result<Pair<Publication, Locator?>> =
        try {
            val file = File(filePath)
            val fileUri =
                android.net.Uri
                    .fromFile(file)
                    .toString()
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(application.contentResolver, httpClient)
            val url = AbsoluteUrl(fileUri) ?: throw Exception("Invalid file URI: $fileUri")
            val retrieveResult = withContext(Dispatchers.IO) { assetRetriever.retrieve(url) }
            val asset = retrieveResult.getOrNull() ?: throw Exception("Failed to retrieve EPUB asset")
            val parser =
                DefaultPublicationParser(
                    context = application,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                )
            val opener = PublicationOpener(parser)
            val openResult = withContext(Dispatchers.IO) { opener.open(asset, allowUserInteraction = false) }
            val publication: Publication =
                openResult.fold(
                    onSuccess = { it },
                    onFailure = { throw Exception("Readium open failed: ${it.message}") },
                )
            val savedProgress = readerRepository.getProgressForBook(bookId)
            val initialLocator: Locator? =
                savedProgress
                    ?.locatorJson
                    ?.let { CfiMigrator.jsonToLocator(it) }
            Result.success(publication to initialLocator)
        } catch (e: Exception) {
            Result.failure(e)
        }

    fun loadEpubBook(
        bookId: String,
        filePath: String,
    ) {
        val epoch = ++loadEpoch
        val startTime = System.currentTimeMillis()
        DebugLog.info(TAG, "loadEpubBook start bookId=$bookId epoch=$epoch")
        state.update {
            it.copy(
                selectedBookId = bookId,
                bookFilePath = filePath,
                bookFormat = "epub",
                isLoading = true,
                error = null,
                chapters = emptyList(),
                currentChapterIndex = 0,
                readiumPublication = null,
                readiumLocator = null,
                progressPercent = 0f,
                progressLabel = "",
            )
        }
        _isLoading.value = true

        scope.launch(mainDispatcher) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    val message = application.getString(R.string.book_not_found)
                    DebugLog.warn(TAG, "loadEpubBook missing file bookId=$bookId epoch=$epoch")
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
                val publication: Publication =
                    withTimeout(EPUB_LOAD_TIMEOUT_MS) {
                        val retrieveResult = withContext(Dispatchers.IO) { assetRetriever.retrieve(url) }
                        val asset =
                            retrieveResult.getOrNull()
                                ?: throw Exception("Failed to retrieve EPUB asset")
                        val parser =
                            DefaultPublicationParser(
                                context = application,
                                httpClient = httpClient,
                                assetRetriever = assetRetriever,
                                pdfFactory = null,
                            )
                        val opener = PublicationOpener(parser)
                        val openResult =
                            withContext(Dispatchers.IO) {
                                opener.open(asset, allowUserInteraction = false)
                            }
                        openResult.fold(
                            onSuccess = { it },
                            onFailure = { error -> throw Exception("Readium open failed: ${error.message}") },
                        )
                    }
                if (epoch != loadEpoch) {
                    DebugLog.warn(TAG, "loadEpubBook stale completion ignored bookId=$bookId epoch=$epoch")
                    return@launch
                }

                val loadTime = System.currentTimeMillis() - startTime

                // A2/A3 - reader open + native TTFP (loadBook -> publication-ready).
                val bucketedLoad =
                    com.nexo.debug.SentryMetrics
                        .bucketDurationMs(loadTime)
                val readerTags =
                    mapOf(
                        "source" to "reader",
                        "engine" to "readium",
                        "format" to "epub",
                        "platform" to "android",
                    )
                com.nexo.debug.SentryMetrics
                    .distribution("reader_open", bucketedLoad, readerTags)
                com.nexo.debug.SentryMetrics
                    .distribution("reader_ttfp_native", bucketedLoad, readerTags)
                Log.d(TAG, "Readium loaded EPUB in ${loadTime}ms")
                DebugLog.info(TAG, "loadEpubBook success bookId=$bookId epoch=$epoch loadTimeMs=$loadTime")

                val chapters = TocBuilder.buildChaptersFromPublication(publication)

                val savedProgress = readerRepository.getProgressForBook(bookId)
                val initialLocator: Locator? =
                    savedProgress
                        ?.locatorJson
                        ?.let { CfiMigrator.jsonToLocator(it) }

                state.update {
                    it.copy(
                        readiumPublication = publication,
                        chapters = chapters,
                        readiumLocator = initialLocator,
                        isLoading = false,
                        loadTimeMs = loadTime,
                    )
                }
                _isLoading.value = false
                _loadTimeMs.value = loadTime

                supabaseProgressSync?.let { sync ->
                    scope.launch(Dispatchers.IO) {
                        sync.resumeForBook(bookId) { remote ->
                            val locator = remote.locatorJson?.let(CfiMigrator::jsonToLocator)
                            if (locator != null && epoch == loadEpoch && state.value.selectedBookId == bookId) {
                                state.update { current -> current.copy(readiumLocator = locator) }
                                scope.launch(mainDispatcher) { onNavigateToLocator(locator) }
                            }
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    migrateCfiDataForBook(bookId, publication.readingOrder)
                }
                onProgressDisplay()
                onBookLoaded(bookId)
            } catch (e: TimeoutCancellationException) {
                if (epoch != loadEpoch) {
                    DebugLog.warn(TAG, "loadEpubBook stale timeout ignored bookId=$bookId epoch=$epoch")
                    return@launch
                }
                Log.e(TAG, "Readium EPUB open timed out", e)
                DebugLog.error(TAG, "loadEpubBook timeout bookId=$bookId epoch=$epoch")
                val timeoutMessage = application.getString(R.string.error_unknown)
                state.update { it.copy(isLoading = false, error = timeoutMessage) }
                _isLoading.value = false
                onErrorEvent(UiEvent.ShowSnackbar(timeoutMessage))
            } catch (e: Exception) {
                if (epoch != loadEpoch) {
                    DebugLog.warn(TAG, "loadEpubBook stale failure ignored bookId=$bookId epoch=$epoch")
                    return@launch
                }
                Log.e(TAG, "Readium failed to open EPUB", e)
                DebugLog.error(TAG, "loadEpubBook failure bookId=$bookId epoch=$epoch cause=${e.message}")
                val message = e.message ?: "Failed to open EPUB with Readium"
                state.update { it.copy(isLoading = false, error = message) }
                _isLoading.value = false
                onErrorEvent(UiEvent.ShowSnackbar(message))
            }
        }
    }

    private fun resolveEpubCfi(
        cfi: String,
        readingOrderLinks: List<Link>,
    ): Locator? {
        val parsed = CfiMigrator.parsePreciseCfi(cfi)
        if (parsed != null) {
            val link = readingOrderLinks.getOrNull(parsed.spineIndex - 1)
            if (link != null) {
                val metric = CfiMigrator.TextMetric(charOffset = parsed.textOffset, chapterChars = 10000)
                val prog = CfiMigrator.progressionFor(metric) ?: 0.0
                val json =
                    JSONObject().apply {
                        put("href", link.href.toString())
                        put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                        put(
                            "locations",
                            JSONObject().apply {
                                put("progression", prog)
                                put("fragment", cfi)
                            },
                        )
                    }
                Locator.fromJSON(json)?.let { return it }
            }
        }
        CfiMigrator.migrateCfiToLocator(cfi, readingOrderLinks)?.let { return it }
        val spineIdx =
            Regex("epubcfi\\(/6/(\\d+)")
                .find(cfi)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.minus(1)
        if (spineIdx != null && spineIdx >= 0) {
            val link = readingOrderLinks.getOrNull(spineIdx) ?: return null
            val json =
                JSONObject().apply {
                    put("href", link.href.toString())
                    put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                    put(
                        "locations",
                        JSONObject().apply {
                            put("progression", 0.0)
                            put("fragment", cfi)
                        },
                    )
                }
            return Locator.fromJSON(json)
        }
        return null
    }

    private suspend fun migrateCfiDataForBook(
        bookId: String,
        readingOrder: List<Link>,
    ) {
        val readingOrderLinks = readingOrder
        if (readingOrderLinks.isEmpty()) return
        val highlights = readerRepository.getHighlightsForBook(bookId)
        for (highlight in highlights) {
            if (highlight.cfiRange.startsWith("epubcfi(") && highlight.locatorJson == null) {
                val locator = resolveEpubCfi(highlight.cfiRange, readingOrderLinks)
                if (locator != null) {
                    val migrated = highlight.copy(locatorJson = CfiMigrator.locatorToJson(locator))
                    readerRepository.upsertHighlight(migrated)
                }
            }
        }
        val bookmarks = readerRepository.getBookmarksForBook(bookId)
        for (bookmark in bookmarks) {
            if (bookmark.cfiLocation.startsWith("epubcfi(") && bookmark.locatorJson == null) {
                val locator = resolveEpubCfi(bookmark.cfiLocation, readingOrderLinks)
                if (locator != null) {
                    val migrated = bookmark.copy(locatorJson = CfiMigrator.locatorToJson(locator))
                    readerRepository.upsertBookmark(migrated)
                }
            }
        }
        val progress = readerRepository.getProgressForBook(bookId)
        if (progress != null && progress.cfiLocation.startsWith("epubcfi(") && progress.locatorJson == null) {
            val locator = resolveEpubCfi(progress.cfiLocation, readingOrderLinks)
            if (locator != null) {
                val migrated = progress.copy(locatorJson = CfiMigrator.locatorToJson(locator))
                readerRepository.upsertProgress(migrated)
            }
        }
        Log.d(TAG, "CFI migration complete for book $bookId")
    }

    override fun onCleared() {}
}
