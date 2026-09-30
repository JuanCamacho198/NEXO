package com.nexo.data.remote.catalog

import com.nexo.debug.SentryMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Ordered dynamic composite behind the port.
 * Providers are searched in construction order (built-ins first, curated,
 * then enabled addons in install order); results concat-merge in that order.
 * Page/details caching is per-source with v2 keys carrying the full source
 * string, and reads enforce the existence check (design A1): entries whose
 * source id is not in the active source set at read time are never served.
 * Search entry is direct (cache pre-read then executeSearch); the single
 * 250ms trailing debounce is owned by DiscoverViewModel (U2).
 * Mirrors desktop `CompositeCatalogProvider.ts`.
 */
class CompositeCatalogProvider(
    private val providers: List<CatalogProvider>,
    private val cache: DiscoverCacheStore? = null,
    private val nowEpochSecs: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Scope for stale-while-revalidate background refreshes. Tests pass the
     * `runTest` scope so the refresh is deterministic; production defaults to an
     * app-lifetime IO scope whose launches never block the caller's read.
     */
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CatalogProvider {
    private val cacheJson = Json { ignoreUnknownKeys = true }

    /** Sources in provider order, deduped by sourceId (first occurrence wins). */
    override fun listSources(): List<CatalogSourceInfo> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<CatalogSourceInfo>()
        for (provider in searchableProviders()) {
            for (source in provider.listSources()) {
                if (seen.add(source.sourceId)) out.add(source)
            }
        }
        return out
    }

    /**
     * Direct entry point (U2): cache pre-read, then straight to executeSearch.
     * Fresh cache entries return immediately; page < 1 rejects before I/O.
     */
    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult {
        if (page < 1) throw catalogError(CatalogErrorCode.INVALID_PAGE, "page must be >= 1, got $page")
        readAllProviderPages(query, page)?.let { return it }
        return executeSearch(query, page)
    }

    private suspend fun executeSearch(
        query: String,
        page: Int,
    ): PagedResult =
        coroutineScope {
            // Re-check before fan-out: a concurrent caller may have filled the cache.
            readAllProviderPages(query, page)?.let { return@coroutineScope it }
            val active = activeSourceIds()
            val searches =
                searchableProviders().map { provider ->
                    async {
                        val startedMs = System.currentTimeMillis()
                        try {
                            val cached = readProviderPage(provider, query, page, active)
                            val sourceId = singleSource(provider)?.sourceId ?: "unknown"
                            if (cached != null) {
                                emitDiscoverSearch(cached, sourceId, startedMs, true)
                                cached
                            } else {
                                val result = provider.search(query, page)
                                cacheProviderPage(provider, query, page, result, active)
                                emitDiscoverSearch(result, sourceId, startedMs, false)
                                result
                            }
                        } catch (err: Throwable) {
                            // Failure isolation (U1): one failing source fails closed to
                            // an empty page so it can never fail the whole fan-out.
                            // Cancellation still propagates to respect coroutine scope.
                            if (err is CancellationException) throw err
                            val code =
                                (err as? CatalogException)?.code?.name
                                    ?: CatalogErrorCode.UPSTREAM_ERROR.name
                            val sourceId = singleSource(provider)?.sourceId ?: "unknown"
                            SentryMetrics.count(
                                "discover_search_error",
                                mapOf("provider" to sourceId, "code" to code),
                            )
                            PagedResult(emptyList(), null, 0)
                        }
                    }
                }
            mergePaged(searches.map { it.await() }, page)
        }

    /**
     * Whole-composite cache pre-read: when every searchable provider has a
     * fresh page entry, the merged page returns without debounce or I/O.
     */
    private suspend fun readAllProviderPages(
        query: String,
        page: Int,
    ): PagedResult? {
        val cache = cache ?: return null
        val active = activeSourceIds()
        val providers = searchableProviders().filter { it.listSources().size == 1 }
        if (providers.isEmpty()) return null
        val pages = mutableListOf<PagedResult>()
        for (provider in providers) {
            val cached = readProviderPage(provider, query, page, active) ?: return null
            pages.add(cached)
        }
        return mergePaged(pages, page)
    }

    /** Providers without sources (disabled) contribute nothing and are never called. */
    private fun searchableProviders(): List<CatalogProvider> = providers.filter { it.listSources().isNotEmpty() }

    private fun activeSourceIds(): Set<String> = searchableProviders().flatMap { it.listSources() }.map { it.sourceId }.toSet()

    /** Existence check (design A1): only sources active at read time may hit. */
    private suspend fun readProviderPage(
        provider: CatalogProvider,
        query: String,
        page: Int,
        active: Set<String>,
    ): PagedResult? {
        val store = cache ?: return null
        val source = singleSource(provider) ?: return null
        if (source.sourceId !in active) return null
        val key = pageCacheKey(source.sourceId, query, page)
        val hit = store.read(key, nowEpochSecs()) ?: return null
        if (!hit.fresh) {
            // Stale-while-revalidate: serve the resident page immediately and
            // refresh it behind the caller. The refresh is best-effort — a
            // failure (or empty page) leaves the served stale page untouched.
            refreshScope.launch {
                try {
                    val result = provider.search(query, page)
                    cacheProviderPage(provider, query, page, result, active)
                } catch (err: Throwable) {
                    if (err is CancellationException) throw err
                }
            }
        }
        return cacheJson.decodeFromString(PagedResult.serializer(), hit.payload)
    }

    private suspend fun cacheProviderPage(
        provider: CatalogProvider,
        query: String,
        page: Int,
        result: PagedResult,
        active: Set<String>,
    ) {
        // Never-cache-empty: empty search results persist nothing and extend
        // no TTL, so a prior valid cached page keeps its existing TTL.
        if (result.results.isEmpty()) return
        val store = cache ?: return
        val source = singleSource(provider) ?: return
        if (source.sourceId !in active) return
        store.put(
            pageCacheKey(source.sourceId, query, page),
            cacheJson.encodeToString(PagedResult.serializer(), result),
            nowEpochSecs(),
            PAGE_TTL_S,
        )
    }

    /**
     * Featured page cache read for a single-source provider under the `f:v3:`
     * namespace. Same existence check as [readProviderPage]: sources that are not
     * active at read time are never served.
     */
    private suspend fun readProviderFeaturedPage(
        provider: CatalogProvider,
        sort: CatalogFeaturedSort,
        page: Int,
        active: Set<String>,
    ): PagedResult? {
        val store = cache ?: return null
        val source = singleSource(provider) ?: return null
        if (source.sourceId !in active) return null
        val hit = store.get(featuredCacheKey(source.sourceId, sort, page), nowEpochSecs()) ?: return null
        return cacheJson.decodeFromString(PagedResult.serializer(), hit)
    }

    private suspend fun cacheProviderFeaturedPage(
        provider: CatalogProvider,
        sort: CatalogFeaturedSort,
        page: Int,
        result: PagedResult,
        active: Set<String>,
    ) {
        // Never-cache-empty: empty rails persist nothing and extend no TTL,
        // so a prior valid cached page keeps its existing TTL.
        if (result.results.isEmpty()) return
        val store = cache ?: return
        val source = singleSource(provider) ?: return
        if (source.sourceId !in active) return
        store.put(
            featuredCacheKey(source.sourceId, sort, page),
            cacheJson.encodeToString(PagedResult.serializer(), result),
            nowEpochSecs(),
            FEATURED_TTL_S,
        )
    }

    /**
     * Ordered merge: left-fold the provider pages — earlier providers win fields,
     * later ones fill cover gaps and append unmatched books (the [Gutendex,
     * OpenLibrary] fold reproduces the legacy hardcoded-pair merge exactly).
     */

    /**
     * U3-2 search emission: latency distributionRaw + request/empty
     * counters per provider completion. NEVER emits user IDs, query
     * text, or book IDs; attribute set stays {provider, cached} /
     * {surface, provider}.
     */
    private fun emitDiscoverSearch(
        page: PagedResult,
        sourceId: String,
        startedMs: Long,
        cached: Boolean,
    ) {
        val elapsedMs = System.currentTimeMillis() - startedMs
        SentryMetrics.distributionRaw(
            "discover_search_latency",
            elapsedMs,
            mapOf("provider" to sourceId, "cached" to cached.toString()),
        )
        SentryMetrics.count(
            "discover_request_total",
            mapOf("surface" to "search", "provider" to sourceId),
        )
        if (page.results.isEmpty()) {
            SentryMetrics.count(
                "discover_empty_total",
                mapOf("surface" to "search", "provider" to sourceId),
            )
        }
    }

    /**
     * U3-2 rail emission: latency distributionRaw + request/empty
     * counters per provider completion. NEVER emits user IDs, query
     * text, or book IDs; attribute set stays {provider, cached} /
     * {surface, provider}.
     */
    private fun emitDiscoverRail(
        page: PagedResult,
        sourceId: String,
        startedMs: Long,
        cached: Boolean,
    ) {
        val elapsedMs = System.currentTimeMillis() - startedMs
        SentryMetrics.distributionRaw(
            "discover_search_latency",
            elapsedMs,
            mapOf("provider" to sourceId, "cached" to cached.toString()),
        )
        SentryMetrics.count(
            "discover_request_total",
            mapOf("surface" to "rail", "provider" to sourceId),
        )
        if (page.results.isEmpty()) {
            SentryMetrics.count(
                "discover_empty_total",
                mapOf("surface" to "rail", "provider" to sourceId),
            )
        }
    }

    private fun mergePaged(
        pages: List<PagedResult>,
        page: Int,
    ): PagedResult {
        if (pages.isEmpty()) return toPagedResult(emptyList(), page, 0)
        var results = pages.first().results
        var totalCount = pages.first().totalCount
        for (i in 1 until pages.size) {
            results = mergeResults(results, pages[i].results)
            totalCount = resolveTotalCount(totalCount, pages[i].totalCount)
        }
        return toPagedResult(results, page, totalCount)
    }

    /**
     * True when at least one active provider opts in, so the shell can decide
     * whether any featured work is possible at all.
     */
    override fun supportsFeatured(): Boolean = searchableProviders().any { it.supportsFeatured() }

    /**
     * Featured rails fan out with `async` over the providers that opt in via
     * [CatalogProvider.supportsFeatured], then merge with the same [mergePaged]
     * left-fold used by search. A provider that does not opt in is never called,
     * so its rail can only ever come back empty (fail-closed) and be hidden.
     */
    override suspend fun featured(
        sort: CatalogFeaturedSort,
        page: Int,
    ): PagedResult {
        if (page < 1) throw catalogError(CatalogErrorCode.INVALID_PAGE, "page must be >= 1, got $page")
        return coroutineScope {
            val active = activeSourceIds()
            val pages =
                searchableProviders()
                    .filter { it.supportsFeatured() }
                    .map { provider ->
                        async {
                            val startedMs = System.currentTimeMillis()
                            try {
                                val cached = readProviderFeaturedPage(provider, sort, page, active)
                                val sourceId = singleSource(provider)?.sourceId ?: "unknown"
                                if (cached != null) {
                                    emitDiscoverRail(cached, sourceId, startedMs, true)
                                    cached
                                } else {
                                    val result = provider.featured(sort, page)
                                    cacheProviderFeaturedPage(provider, sort, page, result, active)
                                    emitDiscoverRail(result, sourceId, startedMs, false)
                                    result
                                }
                            } catch (err: Throwable) {
                                if (err is CancellationException) throw err
                                currentCoroutineContext().ensureActive()
                                // U3-2 (U1-3 catch path): error counter is the ONLY
                                // addition authorized here; isolation stays untouched.
                                val code =
                                    (err as? CatalogException)?.code?.name
                                        ?: CatalogErrorCode.UPSTREAM_ERROR.name
                                val sourceId = singleSource(provider)?.sourceId ?: "unknown"
                                SentryMetrics.count(
                                    "discover_search_error",
                                    mapOf("provider" to sourceId, "code" to code),
                                )
                                PagedResult(emptyList(), null, 0)
                            }
                        }
                    }
            mergePaged(pages.map { it.await() }, page)
        }
    }

    /**
     * Per-source search: exact match over the active source set, routed to the
     * single provider that owns [sourceId]. An unknown or inactive source fails
     * closed with an empty page — never a crash, never a silent composite search.
     */
    override suspend fun searchSource(
        sourceId: String,
        query: String,
        page: Int,
    ): PagedResult {
        if (page < 1) throw catalogError(CatalogErrorCode.INVALID_PAGE, "page must be >= 1, got $page")
        val owner =
            searchableProviders().firstOrNull { provider ->
                provider.listSources().any { it.sourceId == sourceId }
            } ?: return PagedResult(emptyList(), null, 0)
        return owner.search(query, page)
    }

    /**
     * Exact-prefix routing over the active source set: `gutendex:`/`openlibrary:`
     * book ids hit the built-ins, `addon:<addonId>:<bookId>` the owning addon.
     * Unroutable ids reject NOT_FOUND without any I/O.
     */
    override suspend fun getDetails(id: String): CatalogBook {
        val route =
            routeDetails(id)
                ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $id")
        val active = activeSourceIds()
        if (cache != null && route.source.sourceId in active) {
            val hit = cache.get(detailCacheKey(route.source.sourceId, id), nowEpochSecs())
            if (hit != null) return cacheJson.decodeFromString(CatalogBook.serializer(), hit)
        }
        val book = route.provider.getDetails(id)
        if (cache != null && route.source.sourceId in active) {
            cache.put(
                detailCacheKey(route.source.sourceId, id),
                cacheJson.encodeToString(CatalogBook.serializer(), book),
                nowEpochSecs(),
                DETAIL_TTL_S,
            )
        }
        return book
    }

    private fun routeDetails(id: String): RoutedDetails? {
        var best: RoutedDetails? = null
        var bestPrefix = ""
        for (provider in searchableProviders()) {
            for (source in provider.listSources()) {
                val prefix = bookIdPrefixForSource(source.sourceId) ?: continue
                if (!id.startsWith(prefix) || id.length <= prefix.length) continue
                if (best == null || prefix.length > bestPrefix.length) {
                    best = RoutedDetails(provider, source)
                    bestPrefix = prefix
                }
            }
        }
        return best
    }

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String =
        com.nexo.data.remote.catalog
            .resolveDownloadUrl(formats, preferEpub)

    data class RoutedDetails(
        val provider: CatalogProvider,
        val source: CatalogSourceInfo,
    )

    companion object {
        /** Providers exposing exactly one source are page-cacheable under that source. */
        fun singleSource(provider: CatalogProvider): CatalogSourceInfo? = provider.listSources().singleOrNull()

        /**
         * Book-id prefix owned by a source: built-ins drop the `builtin:` namespace
         * (`builtin:gutendex` -> `gutendex:`); addons use the source id itself
         * (`addon:<id>` -> `addon:<id>:`).
         */
        fun bookIdPrefixForSource(sourceId: String): String? =
            when {
                sourceId.startsWith("builtin:") -> "${sourceId.removePrefix("builtin:")}:"
                sourceId.startsWith("addon:") -> "$sourceId:"
                else -> null
            }
    }
}
