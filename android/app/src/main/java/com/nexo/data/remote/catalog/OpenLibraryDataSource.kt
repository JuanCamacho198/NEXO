package com.nexo.data.remote.catalog

import kotlinx.serialization.json.Json
import java.net.URLEncoder

const val OPEN_LIBRARY_BASE_URL = "https://openlibrary.org"

/**
 * Open Library datasource — relevance enrichment + cover fallback.
 * Enforces the 1s anonymous courtesy gap between calls via a rate limiter.
 */
open class OpenLibraryDataSource(
    private val transport: CatalogHttpTransport,
    private val limiter: RateLimiter = RateLimiter(OL_MIN_GAP_MS),
    private val baseUrl: String = OPEN_LIBRARY_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Search usable-public docs only; borrow-restricted docs are dropped. */
    open suspend fun search(
        query: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): CatalogSearchResult {
        val size = clampPageSize(pageSize)
        limiter.waitForSlot()
        try {
            val params = "q=${query.encode()}&page=$page&limit=$size"
            val res = transport.getWithRetry("$baseUrl/search.json?$params")
            val data = json.decodeFromString<OpenLibrarySearchResponse>(res.body)
            val books = data.docs.mapNotNull(::mapOpenLibraryDoc).take(size)
            return CatalogSearchResult(books, data.numFound ?: books.size)
        } catch (err: Throwable) {
            if (isCatalogError(err)) throw err
            throw catalogError(CatalogErrorCode.NETWORK_ERROR, "openlibrary request failed")
        }
    }

    private fun String.encode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}
