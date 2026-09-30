package com.nexo.domain.access

import com.nexo.data.remote.catalog.CatalogBook
import java.net.URLEncoder

/**
 * U3 pure-domain resolver: maps a [CatalogBook] identity to grouped legal
 * access options. Zero Android/network dependencies — pure string building
 * over JVM stdlib, fully testable on the JVM.
 *
 * Rules (from tasks):
 * - Groups are exactly FREE / BUY / SUBSCRIBE; no lending branch exists.
 * - Every emitted link is an `https` web link; anything else is dropped.
 * - Only `isPublicDomain == true` books with an `https` download URL resolve
 *   to an in-app download path; everything else is external-open only.
 * - Books without identity resolve to generic web-search links.
 */

private const val HTTPS_PREFIX = "https://"
private const val OPEN_LIBRARY_BASE = "https://openlibrary.org"
private const val INTERNET_ARCHIVE_BASE = "https://archive.org/details/"
private const val GOOGLE_BOOKS_PREVIEW_BASE = "https://books.google.com/books?id="
private const val GUTENBERG_EBOOK_BASE = "https://www.gutenberg.org/ebooks/"
private const val WEB_SEARCH_BASE = "https://www.google.com/search?q="
private const val GUTENDEX_ID_PREFIX = "gutendex:"
private const val MIN_GUTENBERG_ID = 1
private const val MAX_AUTHORS_IN_QUERY = 3
private const val QUERY_AUTHOR_SEPARATOR = ", "

private const val TITLE_DOWNLOAD = "Download EPUB"
private const val TITLE_OPEN_LIBRARY = "Open Library"
private const val TITLE_INTERNET_ARCHIVE = "Internet Archive"
private const val TITLE_GOOGLE_BOOKS_PREVIEW = "Google Books preview"
private const val TITLE_GUTENBERG = "Project Gutenberg"
private const val TITLE_WEB_SEARCH = "Search the web"
private const val TITLE_BUY_SEARCH = "Find places to buy"
private const val TITLE_SUBSCRIBE_SEARCH = "Find subscription options"

private const val BUY_KEYWORD = "buy book"
private const val SUBSCRIBE_KEYWORD = "subscription library"
private const val ISBN_KEYWORD = "isbn"

/** True only for non-blank `https://` URLs. */
fun isHttpsUrl(url: String?): Boolean = !url.isNullOrBlank() && url.startsWith(HTTPS_PREFIX)

private fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun describeQuery(book: CatalogBook): String {
    val authors = book.authors.take(MAX_AUTHORS_IN_QUERY).joinToString(QUERY_AUTHOR_SEPARATOR)
    return if (authors.isBlank()) book.title else "${book.title} $authors"
}

private fun gutendexIdOf(book: CatalogBook): Int? =
    book.id
        .removePrefix(GUTENDEX_ID_PREFIX)
        .takeIf { book.id.startsWith(GUTENDEX_ID_PREFIX) }
        ?.toIntOrNull()
        ?.takeIf { it >= MIN_GUTENBERG_ID }

/**
 * Resolve grouped legal access for [book]. Never throws for malformed
 * identity — unknown/missing fields simply yield fewer specific links and the
 * generic web-search fallbacks.
 */
fun resolveAccess(book: CatalogBook): LegalAccess {
    val canDownloadInApp = book.isPublicDomain == true && isHttpsUrl(book.downloadUrl)
    val downloadUrl = if (canDownloadInApp) book.downloadUrl else null
    val query = describeQuery(book)
    val isbn = book.isbn13 ?: book.isbn10

    val options =
        buildList {
            if (canDownloadInApp && downloadUrl != null) {
                add(AccessOption(AccessGroup.FREE, TITLE_DOWNLOAD, downloadUrl, opensInApp = true))
            }
            val workId = book.openLibraryWorkId?.takeIf { it.isNotBlank() }
            if (workId != null) {
                val path = if (workId.startsWith("/")) workId else "/$workId"
                add(AccessOption(AccessGroup.FREE, TITLE_OPEN_LIBRARY, OPEN_LIBRARY_BASE + path))
            }
            val archiveId = book.internetArchiveId?.takeIf { it.isNotBlank() }
            if (archiveId != null) {
                add(
                    AccessOption(
                        AccessGroup.FREE,
                        TITLE_INTERNET_ARCHIVE,
                        INTERNET_ARCHIVE_BASE + encodeQuery(archiveId),
                    ),
                )
            }
            val googleId = book.googleBooksId?.takeIf { it.isNotBlank() }
            if (googleId != null) {
                add(
                    AccessOption(
                        AccessGroup.FREE,
                        TITLE_GOOGLE_BOOKS_PREVIEW,
                        GOOGLE_BOOKS_PREVIEW_BASE + encodeQuery(googleId),
                    ),
                )
            }
            val gutenbergId = gutendexIdOf(book)
            if (gutenbergId != null) {
                add(
                    AccessOption(
                        AccessGroup.FREE,
                        TITLE_GUTENBERG,
                        GUTENBERG_EBOOK_BASE + gutenbergId.toString(),
                    ),
                )
            }
            if (isbn != null) {
                add(
                    AccessOption(
                        AccessGroup.BUY,
                        TITLE_BUY_SEARCH,
                        WEB_SEARCH_BASE + encodeQuery("$ISBN_KEYWORD $isbn $BUY_KEYWORD"),
                    ),
                )
                add(
                    AccessOption(
                        AccessGroup.SUBSCRIBE,
                        TITLE_SUBSCRIBE_SEARCH,
                        WEB_SEARCH_BASE + encodeQuery("$ISBN_KEYWORD $isbn $SUBSCRIBE_KEYWORD"),
                    ),
                )
            } else {
                add(
                    AccessOption(
                        AccessGroup.BUY,
                        TITLE_BUY_SEARCH,
                        WEB_SEARCH_BASE + encodeQuery("$query $BUY_KEYWORD"),
                    ),
                )
                add(
                    AccessOption(
                        AccessGroup.SUBSCRIBE,
                        TITLE_SUBSCRIBE_SEARCH,
                        WEB_SEARCH_BASE + encodeQuery("$query $SUBSCRIBE_KEYWORD"),
                    ),
                )
            }
            val hasFree = any { it.group == AccessGroup.FREE }
            if (!hasFree) {
                add(
                    0,
                    AccessOption(
                        AccessGroup.FREE,
                        TITLE_WEB_SEARCH,
                        WEB_SEARCH_BASE + encodeQuery(query),
                    ),
                )
            }
        }.filter { isHttpsUrl(it.url) }

    return LegalAccess(
        bookId = book.id,
        canDownloadInApp = canDownloadInApp,
        downloadUrl = downloadUrl,
        options = options,
    )
}
