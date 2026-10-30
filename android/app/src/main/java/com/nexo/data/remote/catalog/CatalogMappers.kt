package com.nexo.data.remote.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.text.Normalizer

/**
 * Normalizers, PD predicates, download resolution, merge and pagination math.
 * Pure functions — fully testable offline with fixture JSON.
 * Mirrors desktop `services/catalog/mappers.ts` rule-for-rule.
 */
@Serializable
data class GutendexAuthor(
    val name: String = "",
)

@Serializable
data class GutendexRecord(
    val id: Int = 0,
    val title: String = "",
    val authors: List<GutendexAuthor> = emptyList(),
    val copyright: Boolean? = null,
    val languages: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val formats: Map<String, String> = emptyMap(),
    @SerialName("summaries") val summaries: List<String> = emptyList(),
)

@Serializable
data class OpenLibraryDoc(
    val key: String = "",
    val title: String = "",
    @SerialName("author_name") val authorName: List<String> = emptyList(),
    @SerialName("cover_i") val coverId: Int? = null,
    @SerialName("ebook_access") val ebookAccess: String? = null,
    val language: List<String> = emptyList(),
    val subject: List<String> = emptyList(),
    /** Identity fields carried by the permissive mapper (U1); defaulted so old payloads keep decoding. */
    val isbn: List<String> = emptyList(),
    @SerialName("ia") val internetArchiveIds: List<String> = emptyList(),
)

@Serializable
data class GutendexSearchResponse(
    val count: Int? = null,
    val results: List<GutendexRecord> = emptyList(),
)

@Serializable
data class OpenLibrarySearchResponse(
    @SerialName("numFound") val numFound: Int? = null,
    val docs: List<OpenLibraryDoc> = emptyList(),
)

/** Gutendex record is public-domain only when `copyright == false`. */
fun isGutendexPublicDomain(record: GutendexRecord): Boolean = record.copyright == false

/** OL doc is usable only with full public ebook access (never borrowable). */
fun isOpenLibraryPublic(doc: OpenLibraryDoc): Boolean = doc.ebookAccess == "public"

fun openLibraryCoverUrl(coverId: Int?): String? {
    if (coverId == null) return null
    return "https://covers.openlibrary.org/b/id/$coverId-M.jpg"
}

/**
 * Project Gutenberg cover derived from the record id (no mirroring, no HTML
 * scraping). Coil loads it; the UI keeps the initial-letter fallback on load
 * failure (see `DiscoverBookCover`).
 */
fun gutenbergCoverUrl(gutenbergId: Int): String? {
    if (gutenbergId < 1) return null
    return "https://www.gutenberg.org/cache/epub/$gutenbergId/pg$gutenbergId.cover.medium.jpg"
}

/** Map a Gutendex record; null when in-copyright (excluded, never surfaced). */
fun mapGutendexBook(record: GutendexRecord): CatalogBook? {
    if (!isGutendexPublicDomain(record)) return null
    return CatalogBook(
        id = "gutendex:${record.id}",
        provider = BUILTIN_GUTENDEX,
        title = record.title,
        authors = record.authors.map { it.name },
        coverUrl = gutenbergCoverUrl(record.id),
        languages = record.languages,
        subjects = record.subjects,
        downloadUrl =
            runCatching {
                resolveDownloadUrl(record.formats, preferEpub = true)
            }.getOrNull(),
        description = record.summaries.joinToString("\n\n").takeIf { it.isNotBlank() },
        isPublicDomain = record.copyright?.let { !it },
        formats = record.formats,
    )
}

/** Map an OL doc; null when borrow-restricted or non-public. */
fun mapOpenLibraryDoc(doc: OpenLibraryDoc): CatalogBook? {
    if (!isOpenLibraryPublic(doc)) return null
    return CatalogBook(
        id = "openlibrary:${doc.key}",
        provider = BUILTIN_OPENLIBRARY,
        title = doc.title,
        authors = doc.authorName,
        coverUrl = openLibraryCoverUrl(doc.coverId),
        languages = doc.language,
        subjects = doc.subject.take(8),
        downloadUrl = null,
        isbn13 = firstIsbn13(doc.isbn),
        isbn10 = firstIsbn10(doc.isbn),
        openLibraryWorkId = doc.key.takeIf { it.isNotBlank() },
        internetArchiveId = doc.internetArchiveIds.firstOrNull(),
    )
}

/**
 * Permissive OL mapping (U1): keeps non-public/borrowable docs that the strict
 * [mapOpenLibraryDoc] drops. Identity is preserved (`openlibrary:<key>` id,
 * `isbn`/`ia` parsed on the doc, `cover_i` via cover URL). U3 surfaces that
 * identity on [CatalogBook] (`isbn13`/`isbn10` split by length, work key, first
 * IA id); this mapper never returns null.
 */
fun mapOpenLibraryAnyDoc(doc: OpenLibraryDoc): CatalogBook =
    CatalogBook(
        id = "openlibrary:${doc.key}",
        provider = BUILTIN_OPENLIBRARY,
        title = doc.title,
        authors = doc.authorName,
        coverUrl = openLibraryCoverUrl(doc.coverId),
        languages = doc.language,
        subjects = doc.subject.take(8),
        downloadUrl = null,
        isbn13 = firstIsbn13(doc.isbn),
        isbn10 = firstIsbn10(doc.isbn),
        openLibraryWorkId = doc.key.takeIf { it.isNotBlank() },
        internetArchiveId = doc.internetArchiveIds.firstOrNull(),
    )

private val EPUB_MIMES = listOf("application/epub+zip", "application/x-mobipocket-ebook")
private val FALLBACK_MIMES = listOf("text/plain", "text/html", "application/pdf")

/**
 * Deterministic download priority: EPUB-first (or fallback-first when
 * [preferEpub] is false). First `https://` match wins, else UNAVAILABLE_DOWNLOAD.
 */
fun resolveDownloadUrl(
    formats: Map<String, String>,
    preferEpub: Boolean,
): String {
    val order = if (preferEpub) EPUB_MIMES + FALLBACK_MIMES else FALLBACK_MIMES + EPUB_MIMES
    for (mime in order) {
        val url = formats[mime]
        if (url != null && url.startsWith("https://")) return url
    }
    throw catalogError(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "no usable format")
}

private fun normalizeText(value: String): String {
    val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
    val stripped = decomposed.replace(Regex("\\p{Mn}+"), "")
    return stripped.replace(Regex("[^a-z0-9]+"), " ").trim()
}

/** Normalized title+author key used to pair OL docs with Gutendex records. */
fun normalizeMatchKey(
    title: String,
    authors: List<String>,
): String {
    // Author names arrive in different orders per source ("Austen, Jane" vs
    // "Jane Austen"), so compare sorted word bags instead of raw strings.
    fun authorKey(value: String): String =
        normalizeText(value)
            .split(" ")
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(" ")
    return normalizeText(title) + "|" + authors.map(::authorKey).sorted().joinToString(",")
}

/**
 * Merge sources: every Gutendex field wins; an empty Gutendex `coverUrl`
 * is filled from the matching OL doc. Unmatched OL books are appended.
 * U3: identity gaps (`isbn13`/`isbn10`/work key/IA id) are filled from the same
 * [normalizeMatchKey] match — no parallel identity model.
 */
fun mergeResults(
    gutendexBooks: List<CatalogBook>,
    olBooks: List<CatalogBook>,
): List<CatalogBook> {
    val olByKey = olBooks.associateBy { normalizeMatchKey(it.title, it.authors) }
    val usedOlKeys = mutableSetOf<String>()
    val merged =
        gutendexBooks.map { g ->
            val key = normalizeMatchKey(g.title, g.authors)
            val match = olByKey[key]
            if (match != null) usedOlKeys.add(key)
            if (match == null) return@map g
            val withCover =
                if (g.coverUrl == null && match.coverUrl != null) g.copy(coverUrl = match.coverUrl) else g
            withCover.copy(
                isbn13 = withCover.isbn13 ?: match.isbn13,
                isbn10 = withCover.isbn10 ?: match.isbn10,
                openLibraryWorkId = withCover.openLibraryWorkId ?: match.openLibraryWorkId,
                internetArchiveId = withCover.internetArchiveId ?: match.internetArchiveId,
            )
        }
    val extras =
        olBooks.filter {
            !usedOlKeys.contains(normalizeMatchKey(it.title, it.authors))
        }
    return merged + extras
}

/** Gutendex `count` wins when present, else OL `numFound`. */
fun resolveTotalCount(
    gutendexCount: Int?,
    olNumFound: Int,
): Int {
    if (gutendexCount != null && gutendexCount >= 0) return gutendexCount
    return olNumFound.coerceAtLeast(0)
}

/** Next 1-based page, or null when `offset + pageLength` reached `totalCount`. */
fun computeNextPage(
    page: Int,
    pageLength: Int,
    totalCount: Int,
): Int? {
    val offset = (page - 1) * pageLength
    return if (offset + pageLength < totalCount) page + 1 else null
}

fun toPagedResult(
    results: List<CatalogBook>,
    page: Int,
    totalCount: Int,
): PagedResult =
    PagedResult(
        results = results,
        nexoPage = computeNextPage(page, results.size, totalCount),
        totalCount = totalCount,
    )

// ── Identity (U3) ───────────────────────────────────────────────────

/** ISBN-13 digit length. */
private const val ISBN13_LENGTH = 13

/** ISBN-10 digit length. */
private const val ISBN10_LENGTH = 10

/** Google Books `industryIdentifiers` type for ISBN-13. */
private const val GOOGLE_ISBN13_TYPE = "ISBN_13"

/** Google Books `industryIdentifiers` type for ISBN-10. */
private const val GOOGLE_ISBN10_TYPE = "ISBN_10"

private fun digitsOf(value: String): String = value.filter { it.isDigit() }

/** First ISBN with 13 digits (dashes/spaces ignored); null when absent. */
fun firstIsbn13(isbns: List<String>): String? = isbns.firstOrNull { digitsOf(it).length == ISBN13_LENGTH }?.let(::digitsOf)

/** First ISBN with 10 digits (dashes/spaces ignored); null when absent. */
fun firstIsbn10(isbns: List<String>): String? = isbns.firstOrNull { digitsOf(it).length == ISBN10_LENGTH }?.let(::digitsOf)

/** Identifier of [type] from a Google Books `industryIdentifiers` list. */
fun googleIndustryIdentifier(
    identifiers: List<Map<String, String>>,
    type: String,
): String? = identifiers.firstOrNull { it["type"] == type }?.get("identifier")?.takeIf { it.isNotBlank() }

// ── Google Books (U2) ─────────────────────────────────────────────

@Serializable
data class GoogleBooksImageLinks(
    val thumbnail: String? = null,
    @SerialName("smallThumbnail") val smallThumbnail: String? = null,
)

@Serializable
data class GoogleBooksVolumeInfo(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
    val categories: List<String> = emptyList(),
    val imageLinks: GoogleBooksImageLinks? = null,
    @SerialName("industryIdentifiers") val industryIdentifiers: List<Map<String, String>> = emptyList(),
)

@Serializable
data class GoogleBooksVolumeItem(
    val id: String = "",
    @SerialName("volumeInfo") val volumeInfo: GoogleBooksVolumeInfo = GoogleBooksVolumeInfo(),
)

@Serializable
data class GoogleBooksSearchResponse(
    @SerialName("totalItems") val totalItems: Int? = null,
    val items: List<GoogleBooksVolumeItem> = emptyList(),
)

/** Normalize a Google Books thumbnail to https (mixed-content safe). */
fun googleBooksCoverUrl(thumbnail: String?): String? {
    if (thumbnail.isNullOrBlank()) return null
    val https = if (thumbnail.startsWith("http://")) "https://" + thumbnail.removePrefix("http://") else thumbnail
    return if (https.startsWith("https://")) https else null
}

/**
 * Map a Google Books volume; null when the volume carries no usable title.
 * Google Books is a metadata/enrichment source (no download URL surfaced).
 */
fun mapGoogleBooksVolume(item: GoogleBooksVolumeItem): CatalogBook? {
    val title =
        item.volumeInfo.title
            ?.trim()
            .orEmpty()
    if (title.isBlank() || item.id.isBlank()) return null
    val info = item.volumeInfo
    return CatalogBook(
        id = "googlebooks:${item.id}",
        provider = BUILTIN_GOOGLEBOOKS,
        title = title,
        authors = info.authors,
        coverUrl = googleBooksCoverUrl(info.imageLinks?.thumbnail ?: info.imageLinks?.smallThumbnail),
        languages = listOfNotNull(info.language?.takeIf { it.isNotBlank() }),
        subjects = info.categories.take(8),
        downloadUrl = null,
        description = info.description?.takeIf { it.isNotBlank() },
        isbn13 =
            googleIndustryIdentifier(info.industryIdentifiers, GOOGLE_ISBN13_TYPE)
                ?.let { digitsOf(it).takeIf { digits -> digits.length == ISBN13_LENGTH } },
        isbn10 =
            googleIndustryIdentifier(info.industryIdentifiers, GOOGLE_ISBN10_TYPE)
                ?.let { digitsOf(it).takeIf { digits -> digits.length == ISBN10_LENGTH } },
        googleBooksId = item.id,
    )
}

// ── Standard Ebooks (Atom acquisition feed) ─────────────────────────

/**
 * Prefix every Standard Ebooks book id carries. Kept here beside the mapper so
 * the id space and the source that owns it (`builtin:standard-ebooks`, whose
 * `bookIdPrefixForSource` derives the same string) can never drift.
 */
const val STANDARD_EBOOKS_ID_PREFIX = "standard-ebooks:"

/** Atom local names the feed actually uses. Namespace prefixes are stripped. */
private const val ATOM_ENTRY = "entry"
private const val ATOM_AUTHOR = "author"
private const val ATOM_NAME = "name"
private const val ATOM_TITLE = "title"
private const val ATOM_ID = "id"
private const val ATOM_SUMMARY = "summary"
private const val ATOM_CATEGORY = "category"
private const val ATOM_LINK = "link"
private const val MEDIA_THUMBNAIL = "thumbnail"

/** The enclosure title the feed marks as its default EPUB. */
private const val SE_RECOMMENDED_EPUB = "Recommended compatible epub"

/** The acquisition MIME the rail's EPUB link must carry. */
private const val SE_EPUB_MIME = "application/epub+zip"
private const val HTTPS_PREFIX = "https://"

/** At most this many subject terms are kept per book (same bound as Open Library). */
private const val SE_MAX_SUBJECTS = 8

/**
 * Feed nesting, counted from the document element: `<feed>` is depth 1, an
 * `<entry>` depth 2, its direct children depth 3, `<author><name>` depth 4.
 * The feed also carries a feed-level `<title>`, so entry text is only read at
 * entry depth — never by tag name alone.
 */
private const val FEED_CHILD_DEPTH = 2
private const val ENTRY_CHILD_DEPTH = 3
private const val ENTRY_GRANDCHILD_DEPTH = 4

/** Which textual field the parser is currently accumulating. */
private enum class StandardEbooksField { NONE, TITLE, ID, SUMMARY, AUTHOR }

/**
 * Namespaced book id from the entry permalink
 * (`https://standardebooks.org/ebooks/<author-slug>/<book-slug>[/<translator-slug>]`).
 * The slug pair is stable and unique per work; a third segment is the
 * translator and is dropped, so the id survives a re-translation.
 */
fun standardEbooksSlugOf(permalink: String): String? {
    val marker = "/ebooks/"
    val at = permalink.indexOf(marker)
    if (at < 0) return null
    val segments =
        permalink
            .substring(at + marker.length)
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    return "${segments[0]}/${segments[1]}"
}

/** Mutable per-entry accumulator; [toBook] is the whole validation gate. */
private class StandardEbooksEntryBuilder {
    private var title: String = ""
    private var permalink: String = ""
    private var summary: String? = null
    private var coverUrl: String? = null
    private var downloadUrl: String? = null
    private val authors = mutableListOf<String>()
    private val subjects = mutableListOf<String>()
    private val text = StringBuilder()

    fun capture(chunk: String?) {
        if (chunk != null) text.append(chunk)
    }

    fun commit(field: StandardEbooksField) {
        val value = text.toString().trim()
        text.setLength(0)
        when (field) {
            StandardEbooksField.TITLE -> title = value
            StandardEbooksField.ID -> permalink = value
            StandardEbooksField.SUMMARY -> summary = value.takeIf { it.isNotEmpty() }
            StandardEbooksField.AUTHOR -> if (value.isNotEmpty()) authors.add(value)
            StandardEbooksField.NONE -> Unit
        }
    }

    fun readAttribute(
        name: String,
        parser: XmlPullParser,
    ) {
        when (name) {
            ATOM_CATEGORY -> {
                val term = attribute(parser, "term")?.trim().orEmpty()
                if (term.isNotEmpty() && subjects.size < SE_MAX_SUBJECTS) subjects.add(term)
            }
            MEDIA_THUMBNAIL -> {
                val url = attribute(parser, "url")?.trim().orEmpty()
                if (url.startsWith(HTTPS_PREFIX)) coverUrl = url
            }
            ATOM_LINK -> {
                if (downloadUrl == null && isRecommendedEpub(parser)) {
                    val href = attribute(parser, "href")?.trim().orEmpty()
                    if (href.startsWith(HTTPS_PREFIX)) downloadUrl = href
                }
            }
        }
    }

    /**
     * Entries without a title, an author, a slug or an https EPUB link are
     * dropped: a rail card needs all three text fields, and a book with no
     * download could never be read from here. `languages` stays empty rather
     * than guessed — the feed carries no language element.
     */
    fun toBook(): CatalogBook? {
        val slug = standardEbooksSlugOf(permalink) ?: return null
        val download = downloadUrl ?: return null
        if (title.isBlank() || authors.isEmpty()) return null
        return CatalogBook(
            id = STANDARD_EBOOKS_ID_PREFIX + slug,
            provider = BUILTIN_STANDARD_EBOOKS,
            title = title,
            authors = authors.toList(),
            coverUrl = coverUrl,
            languages = emptyList(),
            subjects = subjects.toList(),
            downloadUrl = download,
            description = summary,
            isPublicDomain = true,
        )
    }
}

/**
 * Map the Standard Ebooks Atom feed (`/feeds/atom/new-releases`) to catalog
 * books using the platform `XmlPullParser` — no XML package is added for one
 * feed. A malformed document collapses to an empty list (the desktop
 * `DOMParser` `parsererror` path), so a rail built on it auto-hides instead of
 * rendering a broken shelf.
 */
fun mapStandardEbooksAtomFeed(xml: String): List<CatalogBook> =
    try {
        parseStandardEbooksFeed(xml)
    } catch (err: XmlPullParserException) {
        emptyList()
    } catch (err: IOException) {
        emptyList()
    }

private fun parseStandardEbooksFeed(xml: String): List<CatalogBook> {
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(StringReader(xml))
    val books = mutableListOf<CatalogBook>()
    var depth = 0
    var builder: StandardEbooksEntryBuilder? = null
    var directChild: String? = null
    var field = StandardEbooksField.NONE
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                depth++
                val name = parser.name.substringAfter(':')
                val current = builder
                when {
                    depth == FEED_CHILD_DEPTH && name == ATOM_ENTRY -> builder = StandardEbooksEntryBuilder()
                    depth == ENTRY_CHILD_DEPTH && current != null -> {
                        directChild = name
                        field = fieldFor(name)
                        current.readAttribute(name, parser)
                    }
                    depth == ENTRY_GRANDCHILD_DEPTH &&
                        current != null &&
                        directChild == ATOM_AUTHOR &&
                        name == ATOM_NAME -> field = StandardEbooksField.AUTHOR
                    else -> Unit
                }
            }
            XmlPullParser.TEXT -> if (field != StandardEbooksField.NONE) builder?.capture(parser.text)
            XmlPullParser.END_TAG -> {
                when (depth) {
                    ENTRY_GRANDCHILD_DEPTH -> {
                        builder?.commit(field)
                        field = StandardEbooksField.NONE
                    }
                    ENTRY_CHILD_DEPTH -> {
                        builder?.commit(field)
                        field = StandardEbooksField.NONE
                        directChild = null
                    }
                    FEED_CHILD_DEPTH -> {
                        builder?.toBook()?.let(books::add)
                        builder = null
                    }
                }
                depth--
            }
        }
        event = parser.next()
    }
    return books
}

private fun fieldFor(name: String): StandardEbooksField =
    when (name) {
        ATOM_TITLE -> StandardEbooksField.TITLE
        ATOM_ID -> StandardEbooksField.ID
        ATOM_SUMMARY -> StandardEbooksField.SUMMARY
        else -> StandardEbooksField.NONE
    }

/** First attribute whose local name matches, prefix-stripped (namespace agnostic). */
private fun attribute(
    parser: XmlPullParser,
    localName: String,
): String? {
    for (index in 0 until parser.attributeCount) {
        if (parser.getAttributeName(index).substringAfter(':') == localName) {
            return parser.getAttributeValue(index)
        }
    }
    return null
}

private fun isRecommendedEpub(parser: XmlPullParser): Boolean =
    attribute(parser, "rel") == "enclosure" &&
        attribute(parser, "type") == SE_EPUB_MIME &&
        attribute(parser, "title") == SE_RECOMMENDED_EPUB
