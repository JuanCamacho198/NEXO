package com.nexo.data.remote.catalog

import kotlinx.serialization.json.Json
import java.net.URLEncoder

const val GUTENDEX_BASE_URL = "https://gutendex.com"

/**
 * Gutendex datasource — metadata/download authority (`copyright=false`).
 * Ktor transport + identified UA; PD filtering happens in the mapper.
 */
open class GutendexDataSource(
    private val transport: CatalogHttpTransport,
    private val baseUrl: String = GUTENDEX_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Search PD books; in-copyright records are excluded by the mapper. */
    open suspend fun search(
        query: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): CatalogSearchResult {
        val size = clampPageSize(pageSize)
        val params = "search=${query.encode()}&page=$page"
        val res = transport.getWithRetry("$baseUrl/books/?$params")
        val data = json.decodeFromString<GutendexSearchResponse>(res.body)
        val books = data.results.mapNotNull(::mapGutendexBook).take(size)
        return CatalogSearchResult(books, data.count ?: books.size)
    }

    /**
     * Featured rail page. The ordering is a closed enum because an invalid
     * upstream `sort` value hangs the call; only verified literals are emitted.
     */
    open suspend fun featured(
        sort: CatalogFeaturedSort,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): CatalogSearchResult {
        val size = clampPageSize(pageSize)
        val sortParam =
            when (sort) {
                CatalogFeaturedSort.POPULAR -> "sort=popular"
                CatalogFeaturedSort.NEWEST -> "sort=descending"
            }
        val res = transport.getWithRetry("$baseUrl/books/?$sortParam&page=$page")
        val data = json.decodeFromString<GutendexSearchResponse>(res.body)
        val books = data.results.mapNotNull(::mapGutendexBook).take(size)
        return CatalogSearchResult(books, data.count ?: books.size)
    }

    /** Fetch one book by numeric id; unknown ids surface NOT_FOUND. */
    open suspend fun getById(numericId: Int): CatalogBook {
        val res = transport.getWithRetry("$baseUrl/books/$numericId/")
        val record = json.decodeFromString<GutendexRecord>(res.body)
        return mapGutendexBook(record)
            ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "gutendex book $numericId unavailable")
    }

    private fun String.encode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}
