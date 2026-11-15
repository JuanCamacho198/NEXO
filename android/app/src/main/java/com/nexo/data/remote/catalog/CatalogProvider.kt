package com.nexo.data.remote.catalog

import com.nexo.domain.model.CatalogBook
import com.nexo.domain.model.CatalogErrorCode
import com.nexo.domain.model.catalogError
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
    const val STANDARD_EBOOKS = "builtin:standard-ebooks"

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
const val BUILTIN_STANDARD_EBOOKS = CatalogSources.STANDARD_EBOOKS

/**
 * User-facing brand names for the first-party providers. Brand names are not
 * translated copy, so they live in code (desktop `providerLabel()` documents
 * the same decision). The upstream service is Gutendex; the catalog it serves
 * is Project Gutenberg, which is what the user sees.
 */
const val PROVIDER_LABEL_GUTENDEX = "Gutenberg"
const val PROVIDER_LABEL_OPENLIBRARY = "Open Library"
const val PROVIDER_LABEL_STANDARD_EBOOKS = "Standard Ebooks"

fun addonSource(addonId: String): String = CatalogSources.addonSource(addonId)

fun addonSourceIdOf(source: String): String? = CatalogSources.addonIdOf(source)

fun parseCatalogSource(raw: String): String = CatalogSources.parse(raw)

@Serializable
data class PagedResult(
    val results: List<CatalogBook>,
    /** Next 1-based page, or null when the last page was reached. */
    val nexoPage: Int?,
    val totalCount: Int,
)

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
     * Fail-closed capability probe for the composite keyword fan-out: false
     * means "this provider cannot answer a keyword query", so it is never
     * called by a search and never participates in the whole-composite cache
     * pre-read. Its source stays browsable and detail-routable through
     * [searchSource] / [getDetails].
     *
     * Standard Ebooks is the only provider that opts out: the site publishes a
     * single newest-releases feed with no query parameter, so a keyword search
     * has nothing to return. Leaving it in the fan-out would not change any
     * result (it yields an empty page), but it would permanently defeat the
     * "every provider page is cached, serve without I/O" fast path, because an
     * empty page is never cached.
     */
    fun supportsSearch(): Boolean = true

    /**
     * Per-source search scoped to one [sourceId]. Fail-closed default: an
     * unsupported id yields an empty page rather than a crash.
     */
    suspend fun searchSource(
        sourceId: String,
        query: String,
        page: Int,
    ): PagedResult = PagedResult(emptyList(), null, 0)

    /**
     * Closed topic filter, NOT free text. Gutendex's `?topic=` selector is a
     * different parameter from `?search=`: measured live, `topic=science fiction`
     * answers 3 530 books while `search=science fiction` answers 6. [term] must be
     * a member of the closed `TOPIC_ROTATION` table; a raw string must never reach
     * the upstream, exactly like [CatalogFeaturedSort].
     *
     * Fail-closed default: a provider with no topic capability returns an empty
     * page, so its rail auto-hides instead of erroring.
     */
    suspend fun topic(
        term: String,
        page: Int,
    ): PagedResult = PagedResult(emptyList(), null, 0)

    /** Fail-closed capability probe: false means "do not build a topic rail". */
    fun supportsTopic(): Boolean = false
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
