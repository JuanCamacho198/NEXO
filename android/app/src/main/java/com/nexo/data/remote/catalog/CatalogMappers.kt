package com.nexo.data.remote.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
