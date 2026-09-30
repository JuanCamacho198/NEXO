package com.nexo.data.remote.addons

import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.MAX_PAGE_SIZE
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.data.remote.catalog.RETRY_BASE_DELAY_MS
import com.nexo.data.remote.catalog.addonSource
import com.nexo.data.remote.catalog.catalogError
import com.nexo.data.remote.catalog.computeNextPage
import com.nexo.data.remote.catalog.isHttpSuccess
import com.nexo.data.remote.catalog.mapHttpStatusToCode
import com.nexo.data.remote.catalog.resolveDownloadUrl
import com.nexo.data.remote.catalog.shouldRetryStatus
import com.nexo.domain.access.AccessGroup
import com.nexo.domain.access.AccessOption
import com.nexo.domain.access.LegalAccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.nexo.domain.access.resolveAccess as resolveU3Access

/**
 * U4 external-open link title (localized copy, if any, belongs to U5).
 */
private const val TITLE_OPEN_EXTERNAL = "Open"

/**
 * U4 v2 resolve item: per-item `accessType`/`license`/`readUrl`/
 * `downloadUrl`. Unknown access types parse to null and fail closed
 * ([mayDownloadInApp] requires an explicit free or cleared license, so an
 * unknown item is never downloadable); its `readUrl` may still surface as
 * an external-open link.
 */
data class AddonResolveItem(
    val accessType: AddonAccessType?,
    val license: String?,
    val readUrl: String?,
    val downloadUrl: String?,
) {
    /** Only `free` (or license-cleared) items with an https download may flow in-app. */
    val mayDownloadInApp: Boolean
        get() =
            (accessType == AddonAccessType.FREE || ManifestValidator.isLicenseCleared(license)) &&
                downloadUrl != null

    /** External-open link for this item, or null when it has no https read URL. */
    fun toExternalOption(): AccessOption? {
        val url = readUrl ?: return null
        val group =
            when (accessType) {
                AddonAccessType.BUY -> AccessGroup.BUY
                AddonAccessType.SUBSCRIBE -> AccessGroup.SUBSCRIBE
                else -> AccessGroup.FREE
            }
        return AccessOption(group, TITLE_OPEN_EXTERNAL, url, opensInApp = false)
    }
}

/**
 * One [CatalogProvider] per installed addon manifest. Sources are
 * `addon:<addonId>`. Manifests MAY declare endpoint templates
 * (searchUrl/detailsUrl, validated https by [ManifestValidator]); when
 * present, search/getDetails fetch the addon's own catalog payloads through
 * the platform network layer (KtorAddonHttpTransport over the shared catalog
 * HttpClient) and parse them into CatalogBooks with provider = the addon
 * source id. Endpoint-less manifests stay browse-only: stable empty page /
 * NOT_FOUND, zero I/O. Mirrors desktop AddonCatalogProvider.ts.
 *
 * U4: v2 manifests MAY also declare `resolveUrl` + `capabilities`.
 * [resolveAccess] renders `resolveUrl` from the book identity, but only
 * after per-addon disclosure consent was recorded in [consent] — without
 * consent it returns an empty [LegalAccess] with zero I/O.
 */
class AddonCatalogProvider(
    manifest: AddonManifest,
    addonId: String,
    private val transport: AddonHttpTransport?,
    private val retryDelayMs: Long = RETRY_BASE_DELAY_MS,
    private val consent: AddonConsentStore = InMemoryAddonConsentStore(),
) : CatalogProvider {
    init {
        val hasEndpoint = manifest.searchUrl != null || manifest.detailsUrl != null
        require(!(hasEndpoint && transport == null)) {
            "addon declares catalog endpoints but no transport is configured"
        }
    }

    private val source =
        CatalogSourceInfo(
            sourceId = addonSource(addonId),
            name = manifest.name,
            kind = CatalogSourceKind.ADDON,
        )

    private val searchUrl: String? = manifest.searchUrl
    private val detailsUrl: String? = manifest.detailsUrl
    private val resolveUrl: String? = manifest.resolveUrl
    private val addonIdKey: String = addonId

    /** U4: declared v2 capabilities, disclosed before consent is recorded (U5 UI). */
    val capabilities: List<String> = manifest.capabilities

    private val json = Json { ignoreUnknownKeys = true }

    override fun listSources(): List<CatalogSourceInfo> = listOf(source)

    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult {
        val template = searchUrl ?: return BROWSE_ONLY_PAGE
        val url =
            renderTemplate(
                template,
                mapOf("query" to encodeValue(query), "page" to page.toString()),
            )
        return parseSearchPayload(fetchJson(url), source.sourceId, page)
    }

    override suspend fun getDetails(id: String): CatalogBook {
        val prefix = "${source.sourceId}:"
        if (!id.startsWith(prefix) || id.length <= prefix.length) {
            throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
        }
        val template =
            detailsUrl
                ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "addon source has no details endpoint: $id")
        val url =
            renderTemplate(
                template,
                mapOf("bookId" to encodeValue(id.removePrefix(prefix))),
            )
        return parseAddonBook(fetchJson(url), id, source.sourceId)
    }

    private suspend fun fetchJson(url: String): JsonElement {
        var response = callTransport(url)
        if (shouldRetryStatus(response.status)) {
            if (retryDelayMs > 0) delay(retryDelayMs)
            response = callTransport(url)
        }
        if (!response.status.isHttpSuccess()) {
            throw catalogError(mapHttpStatusToCode(response.status), "addon payload status ${response.status}")
        }
        if (response.body.size > ManifestValidator.MAX_MANIFEST_BYTES) {
            throw catalogError(CatalogErrorCode.UPSTREAM_ERROR, "addon payload exceeds size cap")
        }
        return try {
            json.parseToJsonElement(response.body.decodeToString())
        } catch (_: Exception) {
            throw catalogError(CatalogErrorCode.UPSTREAM_ERROR, "addon payload is not valid JSON")
        }
    }

    private suspend fun callTransport(url: String): AddonResource {
        val transport =
            transport
                ?: throw catalogError(CatalogErrorCode.NETWORK_ERROR, "addon transport unavailable")
        return try {
            transport.fetch(url)
        } catch (err: Throwable) {
            if (err is com.nexo.data.remote.catalog.CatalogException) throw err
            throw catalogError(CatalogErrorCode.NETWORK_ERROR, "addon payload request failed")
        }
    }

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String = resolveDownloadUrl(formats, preferEpub)

    /**
     * U4 consent-gated resolve. Without recorded consent for this addon —
     * or without a `resolveUrl` capability — returns an empty [LegalAccess]
     * with zero I/O. With consent, `resolveUrl` renders from the book
     * identity (`{isbn}`, `{title}`, `{author}`, `{openLibraryId}`,
     * `{googleBooksId}`), the free-gate admits only `free`/license-cleared
     * items to the in-app download path, everything else surfaces as
     * external-open links, and the in-app download additionally reuses the
     * U3 gate (`isPublicDomain == true` on the book).
     */
    suspend fun resolveAccess(book: CatalogBook): LegalAccess {
        if (!consent.hasConsent(addonIdKey)) return LegalAccess(book.id, false, null, emptyList())
        val template = resolveUrl ?: return LegalAccess(book.id, false, null, emptyList())
        val items = parseResolvePayload(fetchJson(renderTemplate(template, resolveParams(book))))
        val candidate = items.firstOrNull { it.mayDownloadInApp }?.downloadUrl
        val gate = resolveU3Access(book.copy(downloadUrl = candidate))
        return LegalAccess(
            bookId = book.id,
            canDownloadInApp = gate.canDownloadInApp,
            downloadUrl = gate.downloadUrl,
            options = items.mapNotNull { it.toExternalOption() },
        )
    }

    companion object {
        /** Parity with desktop encodeURIComponent: spaces are %20, never +. */
        private fun encodeValue(value: String): String =
            java.net.URLEncoder
                .encode(value, "UTF-8")
                .replace("+", "%20")

        private val BROWSE_ONLY_PAGE = PagedResult(emptyList(), null, 0)

        /** U4 resolveUrl template keys, rendered from the U3 book identity. */
        private const val PARAM_ISBN = "isbn"
        private const val PARAM_TITLE = "title"
        private const val PARAM_AUTHOR = "author"
        private const val PARAM_OPEN_LIBRARY_ID = "openLibraryId"
        private const val PARAM_GOOGLE_BOOKS_ID = "googleBooksId"

        /** Substitute `{placeholders}` in a validated endpoint template. */
        internal fun renderTemplate(
            template: String,
            params: Map<String, String>,
        ): String =
            Regex("\\{(\\w+)\\}").replace(template) { match ->
                params[match.groupValues[1]] ?: match.value
            }

        /** U4: identity params for a resolveUrl template (missing identity ⇒ empty). */
        private fun resolveParams(book: CatalogBook): Map<String, String> =
            mapOf(
                PARAM_ISBN to encodeValue(book.isbn13 ?: book.isbn10 ?: ""),
                PARAM_TITLE to encodeValue(book.title),
                PARAM_AUTHOR to encodeValue(book.authors.firstOrNull() ?: ""),
                PARAM_OPEN_LIBRARY_ID to encodeValue(book.openLibraryWorkId ?: ""),
                PARAM_GOOGLE_BOOKS_ID to encodeValue(book.googleBooksId ?: ""),
            )

        private fun httpsOrNull(value: JsonElement?): String? =
            ((value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content)
                ?.takeIf { it.startsWith("https://") }

        private fun stringArray(
            value: JsonElement?,
            field: String,
        ): List<String> {
            if (value == null || value is JsonNull) return emptyList()
            val array =
                value as? JsonArray
                    ?: throw catalogError(CatalogErrorCode.UPSTREAM_ERROR, "malformed addon payload: $field must be an array of strings")
            return array.map { element ->
                (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    ?: throw catalogError(CatalogErrorCode.UPSTREAM_ERROR, "malformed addon payload: $field must be strings")
            }
        }

        private fun malformed(detail: String): Nothing = throw catalogError(CatalogErrorCode.UPSTREAM_ERROR, "malformed addon payload: $detail")

        private fun parseAddonBook(
            value: JsonElement,
            bookId: String,
            sourceId: String,
        ): CatalogBook {
            val obj = value as? JsonObject ?: malformed("book must be an object")
            val rawId = (obj["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val rawTitle = (obj["title"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (rawId.isNullOrEmpty() || rawTitle.isNullOrEmpty()) malformed("book missing id/title")
            return CatalogBook(
                id = bookId,
                provider = sourceId,
                title = rawTitle,
                authors = stringArray(obj["authors"], "authors"),
                coverUrl = httpsOrNull(obj["coverUrl"]),
                languages = stringArray(obj["languages"], "languages"),
                subjects = stringArray(obj["subjects"], "subjects"),
                downloadUrl = httpsOrNull(obj["downloadUrl"]),
            )
        }

        /**
         * U4: parse a resolve payload (`{"results": [...]}`) into access
         * items. `accessType`/`license`/`readUrl`/`downloadUrl` are all
         * optional per item; non-https URLs are dropped (never fail the
         * whole payload); unknown `accessType` parses to null (fail closed
         * for the download path at [AddonResolveItem.mayDownloadInApp]).
         */
        internal fun parseResolvePayload(payload: JsonElement): List<AddonResolveItem> {
            val obj = payload as? JsonObject ?: malformed("resolve payload must be an object with a results array")
            val results = obj["results"] as? JsonArray ?: malformed("resolve payload must be an object with a results array")
            return results.mapIndexed { i, element ->
                val entry = element as? JsonObject ?: malformed("resolve item $i must be an object")
                AddonResolveItem(
                    accessType =
                        ManifestValidator.parseAccessType(
                            (entry["accessType"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                        ),
                    license = (entry["license"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                    readUrl = httpsOrNull(entry["readUrl"]),
                    downloadUrl = httpsOrNull(entry["downloadUrl"]),
                )
            }
        }

        internal fun parseSearchPayload(
            payload: JsonElement,
            sourceId: String,
            page: Int,
        ): PagedResult {
            val obj = payload as? JsonObject ?: malformed("payload must be an object with a results array")
            val results = obj["results"] as? JsonArray ?: malformed("payload must be an object with a results array")
            var totalCount = results.size
            val declared = obj["totalCount"]
            if (declared != null && declared !is JsonNull) {
                val n =
                    (declared as? JsonPrimitive)?.content?.toIntOrNull()
                        ?: malformed("totalCount must be a non-negative integer")
                if (n < 0) malformed("totalCount must be a non-negative integer")
                totalCount = n
            }
            val books =
                results.mapIndexed { i, element ->
                    val entry = element as? JsonObject ?: malformed("book $i must be an object")
                    val rawId = (entry["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    if (rawId.isNullOrEmpty()) malformed("book $i missing id")
                    parseAddonBook(entry, "$sourceId:$rawId", sourceId)
                }
            val clamped = books.take(MAX_PAGE_SIZE)
            return PagedResult(
                results = clamped,
                nexoPage = computeNextPage(page, clamped.size, totalCount),
                totalCount = totalCount,
            )
        }
    }
}
