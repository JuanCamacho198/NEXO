package com.nexo.data.remote.catalog

import kotlinx.serialization.Serializable

/**
 * CatalogProvider port — identical contract on Android (Kotlin) and desktop (TS).
 * Gutendex is metadata/download authority; Open Library enriches + cover fallback.
 */

/** addonId = sha256(url) first 16 hex chars (design A6) — lowercase only. */
private val ADDON_ID_RE = Regex("^[0-9a-f]{16}$")

/**
 * Strict catalog source ids: 'builtin:<name>' for first-party sources,
 * 'addon:<addonId>' for registry addons. Exact-prefix parsing only —
 * no tolerant parsing, malformed ids rejected. Mirrors desktop
 * `CatalogProvider.ts` (branded string there, constants here).
 */
object CatalogSources {
    const val GUTENDEX = "builtin:gutendex"
    const val OPENLIBRARY = "builtin:openlibrary"
    const val GOOGLEBOOKS = "builtin:googlebooks"

    /** Closed registry of first-party built-in source names. */
    private val KNOWN_BUILTIN_NAMES =
        setOf("gutendex", "openlibrary", "googlebooks", "standard-ebooks", "librivox", "wikisource", "faded-page")

    fun addonSource(addonId: String): String = "addon:$addonId"

    /** Extract the addonId from an addon source id; null for non-addon sources. */
    fun addonIdOf(source: String): String? =
        if (source.startsWith("addon:") && ADDON_ID_RE.matches(source.removePrefix("addon:"))) {
            source.removePrefix("addon:")
        } else {
            null
        }

    /** Exact-prefix parse: built-ins are a closed registry; addons require 16-hex ids. */
    fun parse(raw: String): String {
        if (raw.startsWith("builtin:")) {
            val name = raw.removePrefix("builtin:")
            if (name in KNOWN_BUILTIN_NAMES) return raw
        } else if (raw.startsWith("addon:") && ADDON_ID_RE.matches(raw.removePrefix("addon:"))) {
            return raw
        }
        throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog source $raw")
    }
}

const val BUILTIN_GUTENDEX = CatalogSources.GUTENDEX
const val BUILTIN_OPENLIBRARY = CatalogSources.OPENLIBRARY
const val BUILTIN_GOOGLEBOOKS = CatalogSources.GOOGLEBOOKS

fun addonSource(addonId: String): String = CatalogSources.addonSource(addonId)

fun addonSourceIdOf(source: String): String? = CatalogSources.addonIdOf(source)

fun parseCatalogSource(raw: String): String = CatalogSources.parse(raw)

@Serializable
data class CatalogBook(
    val id: String,
    val provider: String,
    val title: String,
    val authors: List<String>,
    val coverUrl: String?,
    val languages: List<String>,
    val subjects: List<String>,
    val downloadUrl: String?,
    /**
     * Additive fields (defaulted) so payloads cached before they existed keep
     * decoding with `ignoreUnknownKeys = true` and call sites keep compiling.
     */
    val description: String? = null,
    val isPublicDomain: Boolean? = null,
    val formats: Map<String, String> = emptyMap(),
    /**
     * U3 identity fields (all additive + defaulted): ISBN pair split by length,
     * Open Library work key (`/works/OL…W`), first Internet Archive id, and the
     * Google Books volume id. Unknown stays null; old cached payloads keep
     * decoding via `ignoreUnknownKeys = true`. No Room migration.
     */
    val isbn13: String? = null,
    val isbn10: String? = null,
    val openLibraryWorkId: String? = null,
    val internetArchiveId: String? = null,
    val googleBooksId: String? = null,
)

@Serializable
data class PagedResult(
    val results: List<CatalogBook>,
    /** Next 1-based page, or null when the last page was reached. */
    val nexoPage: Int?,
    val totalCount: Int,
)

/**
 * Stable catalog error codes shared by desktop and Android.
 * Messages are redacted at the boundary; only [code] is contractual.
 */
enum class CatalogErrorCode {
    INVALID_PAGE,
    UNAVAILABLE_DOWNLOAD,
    NOT_FOUND,
    RATE_LIMITED,
    UPSTREAM_ERROR,
    NETWORK_ERROR,
}

class CatalogException(
    val code: CatalogErrorCode,
    message: String,
    val retryable: Boolean =
        code == CatalogErrorCode.RATE_LIMITED ||
            code == CatalogErrorCode.NETWORK_ERROR,
) : Exception("$code: $message")

/** Build a typed catalog error without leaking upstream details. */
fun catalogError(
    code: CatalogErrorCode,
    detail: String? = null,
): CatalogException = CatalogException(code, detail ?: code.name)

fun isCatalogError(err: Throwable): Boolean = err is CatalogException

/** Map an upstream HTTP status to a stable contract code. */
fun mapHttpStatusToCode(status: Int): CatalogErrorCode =
    when (status) {
        HTTP_NOT_FOUND -> CatalogErrorCode.NOT_FOUND
        HTTP_TOO_MANY_REQUESTS -> CatalogErrorCode.RATE_LIMITED
        in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> CatalogErrorCode.UPSTREAM_ERROR
        else -> CatalogErrorCode.UPSTREAM_ERROR
    }

interface CatalogProvider {
    /** [page] is 1-based; `page < 1` rejects with INVALID_PAGE before any I/O. */
    suspend fun search(
        query: String,
        page: Int,
    ): PagedResult

    /** Unknown id rejects with NOT_FOUND. */
    suspend fun getDetails(id: String): CatalogBook

    /**
     * Pure function (no I/O): pick a download URL from a Gutendex `formats` map.
     * Throws UNAVAILABLE_DOWNLOAD when no usable URL exists.
     */
    fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String

    /** Pure function (no I/O): the sources this provider can serve, in order. */
    fun listSources(): List<CatalogSourceInfo>

    /**
     * Featured/popular rail page. [page] is 1-based like [search].
     *
     * Fail-closed default: a provider with no featured capability returns an
     * empty page, so its rail auto-hides instead of surfacing an error or a
     * placeholder section.
     */
    suspend fun featured(
        sort: CatalogFeaturedSort,
        page: Int,
    ): PagedResult = PagedResult(emptyList(), null, 0)

    /** Fail-closed capability probe: false means "do not build a featured rail". */
    fun supportsFeatured(): Boolean = false

    /**
     * Per-source search scoped to one [sourceId]. Fail-closed default: an
     * unsupported id yields an empty page rather than a crash.
     */
    suspend fun searchSource(
        sourceId: String,
        query: String,
        page: Int,
    ): PagedResult = PagedResult(emptyList(), null, 0)
}

enum class CatalogSourceKind {
    BUILTIN,
    CURATED,
    ADDON,
}

data class CatalogSourceInfo(
    val sourceId: String,
    val name: String,
    val kind: CatalogSourceKind,
)
