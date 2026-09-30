package com.nexo.data.remote.catalog

/**
 * Built-in Google Books provider (U2) over [GoogleBooksDataSource].
 *
 * Fail-closed on the key: [googleBooksProviderOrNull] returns null when the
 * key is absent/blank so [NetworkModule] omits the provider from the DI list
 * and the app keeps working on Open Library + Gutenberg. Book-id format is
 * `googlebooks:<volumeId>` (mirrors the `gutendex:`/`openlibrary:` scheme).
 */
open class GoogleBooksCatalogProvider(
    private val ds: GoogleBooksDataSource,
) : CatalogProvider {
    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult {
        val result = ds.search(query, page)
        return toPagedResult(result.books, page, result.totalCount)
    }

    /** Google Books detail by volume id; malformed ids reject NOT_FOUND. */
    override suspend fun getDetails(id: String): CatalogBook {
        if (!id.startsWith("googlebooks:")) {
            throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
        }
        val volumeId = id.removePrefix("googlebooks:")
        if (volumeId.isBlank() || volumeId.contains("/") || volumeId.contains(" ")) {
            throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
        }
        return ds.getById(volumeId)
    }

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String =
        com.nexo.data.remote.catalog
            .resolveDownloadUrl(formats, preferEpub)

    override fun listSources(): List<CatalogSourceInfo> =
        listOf(
            CatalogSourceInfo(BUILTIN_GOOGLEBOOKS, "Google Books", CatalogSourceKind.BUILTIN),
        )
}

/**
 * Fail-closed factory: a blank key yields null (provider omitted from the
 * composite fan-out) instead of a broken provider that would 400/403 every
 * search. Trims surrounding whitespace so `local.properties` copy-paste with
 * a trailing newline still enables the source.
 */
fun googleBooksProviderOrNull(
    transport: CatalogHttpTransport,
    apiKey: String,
): GoogleBooksCatalogProvider? {
    if (apiKey.isBlank()) return null
    return GoogleBooksCatalogProvider(GoogleBooksDataSource(transport, apiKey.trim()))
}
