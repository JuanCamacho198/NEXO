package com.nexo.presentation.viewmodel.reader.lifecycle

import android.app.Application
import android.util.Log
import com.nexo.feature.reader.R
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.debug.DebugLog
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.event.UiEvent
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
    private val activeUser: ActiveUserProvider,
    private val state: MutableStateFlow<ReaderLifecycleState>,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val supabaseProgressSync: SupabaseProgressSync?,
    private val onErrorEvent: (UiEvent) -> Unit,
    private val onNavigateToLocator: (Locator) -> Unit,
    private val onBookLoaded: (String) -> Unit,
    private val onProgressDisplay: () -> Unit,
    /**
     * Hands the persisted position this book opened at to the progress tracker,
     * so it can distinguish the navigator's pre-restore cover emission from the
     * reader's real position. See `ReadingProgressTracker.mayPersist`.
     */
    private val onProgressContextLoaded: (ReadingProgress?) -> Unit = {},
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
            val savedProgress = readerRepository.getProgressForBook(bookId, activeUser.requireUserId())
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
                // Disarmed: the navigator's first emission arrives before the saved
                // position is applied, and persisting it would write the cover at 0%.
                progressPersistenceArmed = false,
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

                val savedProgress = readerRepository.getProgressForBook(bookId, activeUser.requireUserId())
                val initialLocator: Locator? =
                    savedProgress
                        ?.locatorJson
                        ?.let { CfiMigrator.jsonToLocator(it) }

                // Hand the tracker the SAME row the initial locator was built
                // from, so it can tell the navigator's pre-restore cover emission
                // apart from the reader's real position. Read synchronously here
                // rather than from the room-backed `readingProgress` slice, which
                // arrives on its own schedule and was still empty when the
                // navigator emitted — the gate then opened and the row was lost.
                onProgressContextLoaded(savedProgress)

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

                // Persistence is NOT armed here. The navigator fragment is committed
                // by Compose only after this coroutine yields (plus its 200 ms
                // settle delay), so its first emission — the cover — still arrives
                // after this point; arming now would let it overwrite the saved
                // row. The tracker opens its own gate from the restored position
                // instead; see `ReadingProgressTracker.mayPersist`.
                if (supabaseProgressSync != null) {
                    withContext(Dispatchers.IO) {
                        syncProgressForBook(bookId, epoch)
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

    /**
     * Applies the remote copy of this book's position (LWW). Awaited from a
     * background coroutine so the caller's control flow stays sequential.
     *
     * No arming happens here any more: the tracker opens its own persistence gate
     * from whichever position this session actually opened at, so a remote
     * locator is protected by the same rule as a local one.
     */
    private suspend fun syncProgressForBook(
        bookId: String,
        epoch: Long,
    ) {
        val sync = supabaseProgressSync ?: return
        try {
            sync.resumeForBook(bookId) { remote ->
                val locator = remote.locatorJson?.let(CfiMigrator::jsonToLocator)
                if (locator != null && epoch == loadEpoch && state.value.selectedBookId == bookId) {
                    state.update { current -> current.copy(readiumLocator = locator) }
                    scope.launch(mainDispatcher) { onNavigateToLocator(locator) }
                }
            }
        } catch (e: Exception) {
            DebugLog.warn(TAG, "resumeForBook failed bookId=$bookId epoch=$epoch cause=${e.message}")
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
        val highlights = readerRepository.getHighlightsForBook(bookId, activeUser.requireUserId())
        for (highlight in highlights) {
            if (highlight.cfiRange.startsWith("epubcfi(") && highlight.locatorJson == null) {
                val locator = resolveEpubCfi(highlight.cfiRange, readingOrderLinks)
                if (locator != null) {
                    val migrated = highlight.copy(locatorJson = CfiMigrator.locatorToJson(locator))
                    readerRepository.upsertHighlight(migrated, activeUser.requireUserId())
                }
            }
        }
        val bookmarks = readerRepository.getBookmarksForBook(bookId, activeUser.requireUserId())
        for (bookmark in bookmarks) {
            if (bookmark.cfiLocation.startsWith("epubcfi(") && bookmark.locatorJson == null) {
                val locator = resolveEpubCfi(bookmark.cfiLocation, readingOrderLinks)
                if (locator != null) {
                    val migrated = bookmark.copy(locatorJson = CfiMigrator.locatorToJson(locator))
                    readerRepository.upsertBookmark(migrated, activeUser.requireUserId())
                }
            }
        }
        val progress = readerRepository.getProgressForBook(bookId, activeUser.requireUserId())
        if (progress != null && progress.cfiLocation.startsWith("epubcfi(") && progress.locatorJson == null) {
            val locator = resolveEpubCfi(progress.cfiLocation, readingOrderLinks)
            if (locator != null) {
                val migrated = progress.copy(locatorJson = CfiMigrator.locatorToJson(locator))
                readerRepository.upsertProgress(migrated, activeUser.requireUserId())
            }
        }
        Log.d(TAG, "CFI migration complete for book $bookId")
    }

    override fun onCleared() {}
}
