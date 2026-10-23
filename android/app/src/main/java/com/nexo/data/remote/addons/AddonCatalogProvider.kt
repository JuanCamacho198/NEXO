package com.nexo.data.remote.addons

import com.nexo.data.remote.addons.protocol.AddonClient
import com.nexo.data.remote.addons.protocol.AddonDescriptorV3
import com.nexo.data.remote.addons.protocol.AddonManifestV3
import com.nexo.data.remote.addons.protocol.AddonMetaItem
import com.nexo.data.remote.addons.protocol.AddonProtocolConstants
import com.nexo.data.remote.addons.protocol.AddonResourceRouter
import com.nexo.data.remote.addons.protocol.AddonRouteRequest
import com.nexo.data.remote.addons.protocol.AddonShape
import com.nexo.data.remote.addons.protocol.BookStream
import com.nexo.data.remote.addons.protocol.CatalogRequest
import com.nexo.data.remote.addons.protocol.IdRequest
import com.nexo.data.remote.addons.protocol.StreamAccessType
import com.nexo.data.remote.addons.protocol.StreamSource
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.MAX_PAGE_SIZE
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.data.remote.catalog.addonSource
import com.nexo.data.remote.catalog.catalogError
import com.nexo.data.remote.catalog.resolveDownloadUrl
import com.nexo.domain.access.AccessGroup
import com.nexo.domain.access.AccessOption
import com.nexo.domain.access.LegalAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * U4 external-open link title (localized copy, if any, belongs to U5).
 */
private const val TITLE_OPEN_EXTERNAL = "Open"

/**
 * Payload shapes this build has no reader for. A `legacy-v2` row is served by
 * [LegacyAddonCatalogProvider], which still understands its wire format, so the
 * router skips those addons rather than sending a request whose answer it would
 * discard.
 */
private val UNREADABLE_CATALOG_SHAPES = setOf(AddonShape.LEGACY_V2)

/**
 * One [CatalogProvider] for every installed addon: the port stays
 * `search` / `getDetails` / `resolveAccess`, and the v3 router decides who
 * answers (`AddonResourceRouter.selectAddons`), so no screen or ViewModel ever
 * learns about `metas`, `streams` or `types`.
 *
 * Sources are `addon:<addonId>`, one per enabled addon in install order, and
 * every page item keeps its namespaced protocol id (`gutenberg:1342`) so `meta`
 * and `stream` can route on it later.
 *
 * Isolation: one addon failing — timeout, non-2xx, malformed payload, an
 * unbuildable request URL — never fails the aggregate, and a disabled or
 * uninstalled addon contributes nothing at all.
 */
class AddonCatalogProvider(
    addons: List<InstalledAddonRow>,
    private val client: AddonClient,
    private val consent: AddonConsentStore = InMemoryAddonConsentStore(),
    /**
     * Local lookup by `books.source_item_id`. Omitted ⇒ nothing is owned, which
     * is the only safe default: an unknown item must never hide a download.
     */
    private val isOwned: suspend (sourceItemId: String) -> Boolean = { false },
) : CatalogProvider {
    private val descriptors: List<AddonDescriptorV3> =
        addons
            .filter { it.enabled }
            .map { row -> AddonDescriptorV3(row.id, row.url, row.addedAt, row.enabled, row.manifest) }

    override fun listSources(): List<CatalogSourceInfo> =
        descriptors.map { addon ->
            CatalogSourceInfo(
                sourceId = addonSource(addon.id),
                name = addon.manifest.name,
                kind = CatalogSourceKind.ADDON,
            )
        }

    /** Namespaced ids are not source-prefixed: the composite delegates here. */
    override fun servesNamespacedIds(): Boolean = true

    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult {
        if (page < 1) throw catalogError(CatalogErrorCode.INVALID_PAGE, "page must be >= 1, got $page")
        return fanOut(catalogCandidates(), query, page)
    }

    /**
     * Per-source search: the source chip filter. An unknown or disabled source
     * fails closed with an empty page and zero I/O.
     */
    override suspend fun searchSource(
        sourceId: String,
        query: String,
        page: Int,
    ): PagedResult {
        if (page < 1) throw catalogError(CatalogErrorCode.INVALID_PAGE, "page must be >= 1, got $page")
        val target = catalogCandidates().filter { addonSource(it.id) == sourceId }
        if (target.isEmpty()) return PagedResult(emptyList(), null, 0)
        return fanOut(target, query, page)
    }

    /**
     * Detail for one namespaced id, through the `meta` resource. `meta` carries
     * no consent requirement: it is a read-only description, and the addon
     * already answered `catalog` for it. Only a NON_NULL answer is returned —
     * an addon that cannot describe the id is skipped, never an error.
     */
    override suspend fun getDetails(id: String): CatalogBook {
        val candidates = candidates("meta", id)
        if (candidates.isEmpty()) throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
        for (addon in candidates) {
            val item = client.getMeta(listOf(addon), IdRequest(id = id)).items.firstOrNull() ?: continue
            return toCatalogBook(item)
        }
        throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
    }

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String = resolveDownloadUrl(formats, preferEpub)

    /**
     * Reading access through the `stream` resource.
     *
     * - No addon declares `stream` for this id ⇒ empty resolution, zero I/O.
     * - Consent is checked BEFORE any transport call and only here: `stream` is
     *   the one resource that can point at an arbitrary third-party file. When
     *   no candidate holds a grant the resolve returns an empty [LegalAccess]
     *   with zero I/O (the same outcome the v2 adapter produced); an unconsented
     *   addon is skipped while a consented one still answers.
     * - Every answering addon contributes, in the product order the client
     *   already applied (access type, format, install time, id). One addon
     *   failing is a dropped contribution, never an error.
     * - `AddonDeliveryGuard.assertDeliverableStream` has already dropped every
     *   torrent; this class adds no second filtering path.
     * - An owned book keeps its options but is never downloaded again.
     */
    suspend fun resolveAccess(book: CatalogBook): LegalAccess {
        val candidates = candidates("stream", book.id)
        if (candidates.isEmpty()) return emptyAccess(book.id)
        val consented = candidates.filter { consent.hasConsent(it.id) }
        if (consented.isEmpty()) return emptyAccess(book.id)

        val streams = uniqueByUrl(client.getStreams(consented, IdRequest(id = book.id)).items)
        if (streams.isEmpty()) return emptyAccess(book.id)

        val owned = ownedByLocalCatalog(book.id)
        val downloadable = streams.firstOrNull { it.source == StreamSource.DOWNLOAD && it.accessType == StreamAccessType.PUBLIC_DOMAIN }
        val canDownloadInApp = downloadable != null && !owned
        return LegalAccess(
            bookId = book.id,
            canDownloadInApp = canDownloadInApp,
            downloadUrl = if (canDownloadInApp) downloadable?.url else null,
            options = streams.map { toAccessOption(it, owned) },
        )
    }

    private fun candidates(
        resource: String,
        id: String,
    ): List<AddonDescriptorV3> =
        AddonResourceRouter.selectAddons(
            descriptors,
            AddonRouteRequest(resource = resource, type = AddonProtocolConstants.TYPE_BOOK, id = id),
        )

    /** Enabled addons that declare `catalog` for books and whose shape this build can read. */
    private fun catalogCandidates(): List<AddonDescriptorV3> =
        AddonResourceRouter
            .selectAddons(
                descriptors,
                AddonRouteRequest(resource = "catalog", type = AddonProtocolConstants.TYPE_BOOK),
            ).filter { addon ->
                addon.manifest.catalogs.isNotEmpty() && readableShape(addon.manifest)
            }

    private fun readableShape(manifest: AddonManifestV3): Boolean = (manifest.nexo?.shape ?: AddonShape.RAW) !in UNREADABLE_CATALOG_SHAPES

    /**
     * One addon's every declared catalog, unioned in install order. `search` is
     * always sent; `skip` only past the first page, and the package drops any
     * argument the catalog did not declare.
     */
    private suspend fun fanOut(
        candidates: List<AddonDescriptorV3>,
        query: String,
        page: Int,
    ): PagedResult {
        if (candidates.isEmpty()) return PagedResult(emptyList(), null, 0)
        val extra: Map<String, Any?> =
            if (page > 1) {
                mapOf("search" to query, "skip" to (page - 1) * MAX_PAGE_SIZE)
            } else {
                mapOf("search" to query)
            }

        val answers =
            coroutineScope {
                candidates
                    .flatMap { addon ->
                        addon.manifest.catalogs.map { catalog ->
                            async { searchOne(addon, catalog.id, extra) }
                        }
                    }.awaitAll()
            }

        val results = mutableListOf<CatalogBook>()
        val seenIds = HashSet<String>()
        var truncated = false
        for (answer in answers) {
            for (item in answer) {
                if (!seenIds.add(item.meta.id)) continue
                if (results.size >= MAX_PAGE_SIZE) {
                    truncated = true
                    continue
                }
                results += toCatalogBook(item)
            }
        }
        return PagedResult(
            results = results,
            nexoPage = if (truncated) page + 1 else null,
            totalCount = results.size,
        )
    }

    private suspend fun searchOne(
        addon: AddonDescriptorV3,
        catalogId: String,
        extra: Map<String, Any?>,
    ): List<AddonMetaItem> =
        try {
            client.searchCatalog(listOf(addon), CatalogRequest(catalogId = catalogId, extra = extra)).items
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The fan-out's isolation guarantee has to survive an adapter throwing.
            emptyList()
        }

    /** First occurrence wins, so a duplicate link keeps its better-ranked option. */
    private fun uniqueByUrl(streams: List<BookStream>): List<BookStream> {
        val seen = HashSet<String>()
        return streams.filter { seen.add(it.url) }
    }

    /** `gutenberg:1342` stays intact: `meta` and `stream` route on this id. */
    private fun toCatalogBook(item: AddonMetaItem): CatalogBook =
        CatalogBook(
            id = item.meta.id,
            provider = addonSource(item.addonId),
            title = item.meta.name,
            authors = item.meta.authors,
            coverUrl = item.meta.poster?.takeIf { it.startsWith("https://") },
            languages = item.meta.languages,
            subjects = item.meta.subjects,
            downloadUrl = null,
            description = item.meta.description,
            isbn13 = item.meta.isbn13,
        )

    /**
     * One stream → one option. A loan behaves like a lending subscription:
     * there is deliberately no separate LEND group.
     */
    private fun toAccessOption(
        stream: BookStream,
        owned: Boolean,
    ): AccessOption {
        val group =
            when (stream.accessType) {
                StreamAccessType.PURCHASE -> AccessGroup.BUY
                StreamAccessType.BORROW -> AccessGroup.SUBSCRIBE
                else -> AccessGroup.FREE
            }
        return AccessOption(group, TITLE_OPEN_EXTERNAL, stream.url, opensInApp = false, owned = owned)
    }

    /** Local catalog match on the provenance item id; a failed lookup owns nothing. */
    private suspend fun ownedByLocalCatalog(sourceItemId: String): Boolean =
        try {
            isOwned(sourceItemId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    private companion object {
        fun emptyAccess(bookId: String): LegalAccess = LegalAccess(bookId, false, null, emptyList())
    }
}
