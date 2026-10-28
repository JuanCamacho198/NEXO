package com.nexo.data.remote.catalog

const val STANDARD_EBOOKS_BASE_URL = "https://standardebooks.org"

/**
 * The one public Standard Ebooks feed. The `/feeds/opds*` family answers 401
 * for every Accept variant (Patrons Circle only), so it is never requested;
 * `/feeds/atom/new-releases` answers 200 `application/atom+xml` with ~15
 * entries, each carrying a title, author, cover thumbnail and EPUB enclosure.
 */
const val STANDARD_EBOOKS_ATOM_FEED_URL = "$STANDARD_EBOOKS_BASE_URL/feeds/atom/new-releases"

/**
 * The feed is a single unpaged, date-ordered page: there is no query parameter
 * and no page cursor. `page > 1` is an empty page rather than an invented slice
 * of a catalog that does not exist.
 */
private const val STANDARD_EBOOKS_ONLY_PAGE = 1

/** The Atom media type the endpoint was measured to answer 200 with. */
private const val ATOM_ACCEPT = "application/atom+xml"

/**
 * Standard Ebooks datasource over the public Atom acquisition feed.
 *
 * Android has no browser origin, so the feed is fetched directly through the
 * shared catalog transport — no native bridge and no CORS workaround (the
 * desktop needs its Rust `fetchCatalogFeed` command for exactly that reason).
 */
open class StandardEbooksDataSource(
    private val transport: CatalogHttpTransport,
    private val feedUrl: String = STANDARD_EBOOKS_ATOM_FEED_URL,
) {
    /**
     * The newest-releases shelf. A feed that cannot be fetched is an upstream
     * fault (mapped error), never a "no books" answer; a feed that parses to
     * nothing surfaces as an empty page, so its rail auto-hides.
     */
    open suspend fun newest(page: Int): CatalogSearchResult {
        if (page != STANDARD_EBOOKS_ONLY_PAGE) return CatalogSearchResult(emptyList(), 0)
        val res = transport.getWithRetry(feedUrl, mapOf("Accept" to ATOM_ACCEPT))
        val books = mapStandardEbooksAtomFeed(res.body)
        return CatalogSearchResult(books, books.size)
    }

    /** One book by its namespaced id; anything absent from the feed is unknown. */
    open suspend fun getById(id: String): CatalogBook =
        newest(STANDARD_EBOOKS_ONLY_PAGE).books.firstOrNull { it.id == id }
            ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "standard ebooks book unavailable")
}
