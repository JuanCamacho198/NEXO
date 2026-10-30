package com.nexo.data.remote.catalog

/**
 * Built-in CatalogProvider adapters over the raw datasources.
 * Gutendex is metadata/download authority; Open Library enriches + cover
 * fallback at the composite level. Book-id formats stay byte-for-byte:
 * `gutendex:<numericId>` / `openlibrary:<key>` / `standard-ebooks:<slug>`.
 * Mirrors desktop `BuiltInCatalogProviders.ts`.
 */
open class GutendexCatalogProvider(
    private val ds: GutendexDataSource,
) : CatalogProvider {
    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult {
        val result = ds.search(query, page)
        return toPagedResult(result.books, page, result.totalCount)
    }

    /** Featured rails are served by Gutendex alone (POPULAR / NEWEST). */
    override suspend fun featured(
        sort: CatalogFeaturedSort,
        page: Int,
    ): PagedResult {
        val result = ds.featured(sort, page)
        return toPagedResult(result.books, page, result.totalCount)
    }

    override fun supportsFeatured(): Boolean = true

    /** Topic rails are served by the Gutendex `?topic=` selector. */
    override suspend fun topic(
        term: String,
        page: Int,
    ): PagedResult {
        val result = ds.topic(term, page)
        return toPagedResult(result.books, page, result.totalCount)
    }

    override fun supportsTopic(): Boolean = true

    /** Gutendex detail by numeric id; malformed ids reject NOT_FOUND. */
    override suspend fun getDetails(id: String): CatalogBook {
        if (!id.startsWith("gutendex:")) {
            throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
        }
        val numericId = id.removePrefix("gutendex:").toIntOrNull()
        if (numericId == null || numericId < 1) {
            throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
        }
        return ds.getById(numericId)
    }

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String =
        com.nexo.data.remote.catalog
            .resolveDownloadUrl(formats, preferEpub)

    override fun listSources(): List<CatalogSourceInfo> =
        listOf(
            CatalogSourceInfo(BUILTIN_GUTENDEX, PROVIDER_LABEL_GUTENDEX, CatalogSourceKind.BUILTIN),
        )
}

open class OpenLibraryCatalogProvider(
    private val ds: OpenLibraryDataSource,
) : CatalogProvider {
    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult {
        val result = ds.search(query, page)
        return toPagedResult(result.books, page, result.totalCount)
    }

    // NOTE: `featured` / `supportsFeatured` are deliberately left at the
    // fail-closed defaults. `/trending/*.json` returns 100 works but is dominated
    // by borrow-restricted in-copyright titles that mapOpenLibraryDoc drops, its
    // payloads are 274-319 KB, and the endpoint proved flaky - so no OL rail,
    // no new OL endpoint, and the rail simply auto-hides.

    /** OL ids are not detail-resolvable (NOT_FOUND — preserved contract). */
    override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "openlibrary ids are not detail-resolvable: $id")

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String =
        com.nexo.data.remote.catalog
            .resolveDownloadUrl(formats, preferEpub)

    override fun listSources(): List<CatalogSourceInfo> =
        listOf(
            CatalogSourceInfo(BUILTIN_OPENLIBRARY, PROVIDER_LABEL_OPENLIBRARY, CatalogSourceKind.BUILTIN),
        )
}

/**
 * Standard Ebooks: a first-party catalog over the public Atom acquisition feed.
 *
 * The two search doors stay distinct on purpose. `search` fails closed for a
 * keyword query (the feed has no query parameter, so it cannot honor one) and
 * answers the published shelf only for the blank term — which is exactly what
 * the composite's [CompositeCatalogProvider.searchSource] passes, because it
 * routes a per-source request through the owner's `search`. `supportsSearch`
 * is false, so no user search ever reaches this provider.
 *
 * `featured` stays at the fail-closed default: this source has no
 * [CatalogFeaturedSort] ordering, and the popular rail must stay Gutendex-only.
 */
open class StandardEbooksCatalogProvider(
    private val ds: StandardEbooksDataSource,
) : CatalogProvider {
    /** A keyword search cannot be honored (empty page, no I/O); blank = the shelf. */
    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult =
        if (query.isBlank()) {
            val result = ds.newest(page)
            toPagedResult(result.books, page, result.totalCount)
        } else {
            PagedResult(emptyList(), null, 0)
        }

    /** Detail by namespaced id; malformed or unknown ids reject NOT_FOUND. */
    override suspend fun getDetails(id: String): CatalogBook {
        if (!id.startsWith(STANDARD_EBOOKS_ID_PREFIX)) {
            throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
        }
        return ds.getById(id)
    }

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String =
        com.nexo.data.remote.catalog
            .resolveDownloadUrl(formats, preferEpub)

    override fun supportsSearch(): Boolean = false

    override fun listSources(): List<CatalogSourceInfo> =
        listOf(
            CatalogSourceInfo(BUILTIN_STANDARD_EBOOKS, PROVIDER_LABEL_STANDARD_EBOOKS, CatalogSourceKind.BUILTIN),
        )
}
