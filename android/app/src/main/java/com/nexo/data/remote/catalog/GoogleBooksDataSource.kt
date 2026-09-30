package com.nexo.data.remote.catalog

import kotlinx.serialization.json.Json
import java.net.URLEncoder

const val GOOGLE_BOOKS_BASE_URL = "https://www.googleapis.com/books/v1"

/**
 * Google Books datasource (U2) — keyed volume search by ISBN / title-author.
 *
 * Fail-closed on the key: the provider is only built when
 * `GOOGLE_BOOKS_KEY` (gitignored `local.properties` → `BuildConfig`) is
 * non-blank (see `googleBooksProviderOrNull`). This datasource itself takes
 * the key as a constructor arg so it stays fully testable offline with
 * [FakeCatalogHttpTransport]; the key is appended as `&key=` only when
 * non-blank and never logged.
 */
open class GoogleBooksDataSource(
    private val transport: CatalogHttpTransport,
    private val apiKey: String = "",
    private val baseUrl: String = GOOGLE_BOOKS_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Search volumes; ISBN queries use the `isbn:` operator, all else raw. */
    open suspend fun search(
        query: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): CatalogSearchResult {
        val size = clampPageSize(pageSize)
        val startIndex = (page - 1) * size
        val q = buildGoogleBooksQuery(query)
        val keyParam = apiKey.trim().takeIf { it.isNotEmpty() }?.let { "&key=${it.encode()}" } ?: ""
        val url = "$baseUrl/volumes?q=${q.encode()}&startIndex=$startIndex&maxResults=$size$keyParam"
        try {
            val res = transport.getWithRetry(url)
            val data = json.decodeFromString<GoogleBooksSearchResponse>(res.body)
            val books = data.items.mapNotNull(::mapGoogleBooksVolume).take(size)
            return CatalogSearchResult(books, data.totalItems ?: books.size)
        } catch (err: Throwable) {
            if (isCatalogError(err)) throw err
            throw catalogError(CatalogErrorCode.NETWORK_ERROR, "googlebooks request failed")
        }
    }

    /** Fetch one volume by id; unknown ids surface NOT_FOUND. */
    open suspend fun getById(volumeId: String): CatalogBook {
        val keyParam = apiKey.trim().takeIf { it.isNotEmpty() }?.let { "&key=${it.encode()}" } ?: ""
        // `fields=` keeps the payload small; unknown fields still decode leniently.
        val url = "$baseUrl/volumes/${volumeId.encode()}?fields=id,volumeInfo(title,authors,description,language,categories,imageLinks)$keyParam"
        try {
            val res = transport.getWithRetry(url)
            val item = json.decodeFromString<GoogleBooksVolumeItem>(res.body)
            return mapGoogleBooksVolume(item)
                ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "googlebooks volume unavailable")
        } catch (err: Throwable) {
            if (isCatalogError(err)) throw err
            throw catalogError(CatalogErrorCode.NETWORK_ERROR, "googlebooks request failed")
        }
    }

    private fun String.encode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}

/** ISBN-10 total length, ISBN-10 body length (without check digit), ISBN-13 length. */
private const val ISBN10_LENGTH = 10
private const val ISBN10_BODY_LENGTH = 9
private const val ISBN13_LENGTH = 13

/**
 * Build the `q` param: bare ISBN-10/13 (digits, optional `978`/`979` prefix,
 * `X` check digit, dashes/spaces tolerated) uses the `isbn:` operator so an
 * ISBN search resolves to the exact edition; everything else passes through
 * as a title/author query the API ranks itself.
 */
fun buildGoogleBooksQuery(rawQuery: String): String {
    val trimmed = rawQuery.trim()
    val compact = trimmed.replace(Regex("[-\\s]"), "").uppercase()
    val digits = compact.let { if (it.endsWith("X")) it.dropLast(1) + "X" else it }
    val isIsbn =
        (
            digits.length == ISBN10_LENGTH &&
                digits.take(ISBN10_BODY_LENGTH).all { it.isDigit() } &&
                (digits.last().isDigit() || digits.last() == 'X')
        ) ||
            (digits.length == ISBN13_LENGTH && digits.all { it.isDigit() })
    return if (isIsbn) "isbn:$compact" else trimmed
}
