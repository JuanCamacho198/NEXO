package com.nexo.presentation.viewmodel.reader

import android.util.Log
import com.nexo.domain.model.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * UI-level state for the search feature in the reader.
 *
 * Re-exported by the VM as [SearchUiState] — the Search slice of the
 * reader facade split (SDD reader-facade-split, slice 1). Single owner:
 * only this holder holds the search MutableStateFlow and mutating funs.
 */
typealias SearchUiState = SearchState

/**
 * UI-level state for the search feature in the reader.
 */
data class SearchState(
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
)

/**
 * Manages all search-related state for [ReaderViewModel].
 *
 * Handles debounced EPUB search (via Readium [Publication] resources),
 * PDF search results (from native PDF renderer callback), and locator
 * resolution for search-result navigation.
 *
 * @param scope           CoroutineScope for async search jobs (e.g. viewModelScope)
 * @param onNavigateToLocator  Emits a [Locator] for the Readium navigator to follow
 * @param onGoToChapter       Navigate to an EPUB chapter index (legacy fallback)
 * @param onGoToPdfPage       Navigate to a PDF page index
 * @param mainDispatcher      Dispatcher for UI-side work (default: [Dispatchers.Main])
 */
class SearchStateHolder(
    private val scope: CoroutineScope,
    private val onNavigateToLocator: (Locator) -> Unit,
    private val onGoToChapter: (Int) -> Unit,
    private val onGoToPdfPage: (Int) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val TAG = "SearchStateHolder"
        private const val LOCATOR_HIGHLIGHT_LIMIT = 50
    }

    /**
     * Toggle the search bar visibility. Resets all search state.
     */
    fun onToggleSearch() {
        _state.update { SearchState(isSearchActive = !it.isSearchActive) }
    }

    /**
     * Update the search query with debounce.
     * For EPUB publications, triggers a full-text search through reading-order resources.
     * For PDF, the query is forwarded to the native renderer via the UI layer.
     *
     * @param query       The raw search text from the UI
     * @param publication The current Readium [Publication] (nullable — may not be loaded yet)
     * @param bookFormat  "epub" or "pdf"
     */
    fun onSearchQuery(
        query: String,
        publication: Publication?,
        bookFormat: String?,
    ) {
        if (query.isBlank()) {
            searchJob?.cancel()
            _state.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
            return
        }

        _state.update { it.copy(searchQuery = query, isSearching = true) }

        searchJob?.cancel()
        searchJob =
            scope.launch(mainDispatcher) {
                delay(SEARCH_DEBOUNCE_MS)
                val results = searchReadiumPublication(query, publication, bookFormat)
                _state.update { it.copy(searchResults = results, isSearching = false) }
            }
    }

    /**
     * Clear the current search query and results without closing the search bar.
     */
    fun onClearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
    }

    /**
     * Dismiss search entirely — reset state to defaults.
     */
    fun onDismissSearch() {
        searchJob?.cancel()
        _state.update { SearchState() }
    }

    /**
     * Process PDF search results received from the native PDF renderer.
     * The JSON is expected to be an array of objects with:
     *   pageIndex, pageLabel, snippet
     *
     * @param json Raw JSON string from the PDF renderer callback
     */
    fun onPdfSearchResults(json: String) {
        val results =
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    SearchResult(
                        text = obj.optString("snippet", ""),
                        offset = 0,
                        page = obj.optInt("pageIndex", 0).toFloat(),
                        chapterIndex = 0,
                        chapterTitle = obj.optString("pageLabel", ""),
                        cfi = "pdfpage:${obj.optInt("pageIndex", 0)}",
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse PDF search results", e)
                emptyList()
            }
        _state.update { it.copy(searchResults = results, isSearching = false) }
    }

    /**
     * Navigate to the matched location when a search result is tapped.
     *
     * - PDF results navigate via [onGoToPdfPage] using the page index stored in [cfi].
     * - EPUB results resolve a [Locator] and emit it via [onNavigateToLocator].
     *
     * In both cases, the search UI is dismissed after navigation.
     */
    fun onSearchResultSelected(
        result: SearchResult,
        publication: Publication?,
        bookFormat: String?,
        currentChapterIndex: Int,
    ) {
        if (bookFormat == "pdf") {
            val pageIndex = result.cfi.removePrefix("pdfpage:").toIntOrNull() ?: return
            onGoToPdfPage(pageIndex)
        } else if (publication != null) {
            val locator = resolveLocatorForSearchResult(result, publication, currentChapterIndex)
            if (locator != null) {
                onNavigateToLocator(locator)
            }
        }
        onDismissSearch()
    }

    // ── Private helpers ─────────────────────────────────────────────

    /**
     * Full-text search through all reading-order resources of a Readium [Publication].
     *
     * Iterates each resource, extracts plain text (stripping HTML tags), and
     * returns up to 200 results with snippet context.
     */
    private suspend fun searchReadiumPublication(
        query: String,
        publication: Publication?,
        bookFormat: String?,
    ): List<SearchResult> {
        if (publication == null || bookFormat != "epub") return emptyList()

        return withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            for ((index, link) in publication.readingOrder.withIndex()) {
                try {
                    val resource = publication.get(link) ?: continue
                    val readResult = resource.read()
                    val bytes = readResult.getOrNull() ?: continue
                    val html = bytes.decodeToString()

                    if (html.contains(query, ignoreCase = true)) {
                        val lowerHtml = html.lowercase()
                        val lowerQuery = query.lowercase()
                        val snippetStart = lowerHtml.indexOf(lowerQuery)
                        val snippetEnd = minOf(snippetStart + query.length + 80, html.length)
                        val snippet =
                            html
                                .substring(maxOf(0, snippetStart - 40), snippetEnd)
                                .replace(Regex("<[^>]*>"), "")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                        results.add(
                            SearchResult(
                                text = snippet,
                                offset = snippetStart,
                                chapterIndex = index,
                                chapterTitle = link.title ?: "Chapter $index",
                                cfi = "/$index/4/$snippetStart",
                            ),
                        )
                    }
                } catch (_: Exception) {
                    // Skip resources that can't be read
                }
            }
            results
        }
    }

    /**
     * Build a [Locator] for the given search result so the Readium navigator
     * can jump to the correct chapter and approximate position.
     */
    private fun resolveLocatorForSearchResult(
        result: SearchResult,
        publication: Publication,
        currentChapterIndex: Int,
    ): Locator? {
        val link = publication.readingOrder.getOrNull(result.chapterIndex) ?: return null
        // Build via JSON to avoid depending on Readium's internal
        // [Url] and [MediaType] constructor types directly.
        val json =
            JSONObject().apply {
                put("href", link.href.toString())
                put("mediaType", link.mediaType?.toString() ?: "application/xhtml+xml")
                put("title", result.chapterTitle)
                put(
                    "locations",
                    JSONObject().apply {
                        put("progression", 0.0)
                        put("totalProgression", result.chapterIndex.toDouble() / publication.readingOrder.size)
                    },
                )
                put(
                    "text",
                    JSONObject().apply {
                        put("before", "")
                        put("highlight", result.text.take(LOCATOR_HIGHLIGHT_LIMIT))
                        put("after", "")
                    },
                )
            }
        return Locator.fromJSON(json)
    }
}
