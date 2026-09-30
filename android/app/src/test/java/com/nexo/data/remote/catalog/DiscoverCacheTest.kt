package com.nexo.data.remote.catalog

import com.nexo.data.local.dao.DiscoverCacheDao
import com.nexo.data.local.entity.DiscoverCacheEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline cache suite: TTL semantics, composite read-through, isolation. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverCacheTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader
            ?.getResourceAsStream("catalog/$name")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing test fixture catalog/$name")

    private inner class FakeGutendex(
        var calls: Int = 0,
    ) : GutendexDataSource(
            FakeCatalogHttpTransport({ error("no I/O in cache test") }),
        ) {
        override suspend fun search(
            query: String,
            page: Int,
            pageSize: Int,
        ): CatalogSearchResult {
            calls += 1
            val data = json.decodeFromString<GutendexSearchResponse>(fixture("gutendex-search.json"))
            val books = data.results.mapNotNull(::mapGutendexBook)
            return CatalogSearchResult(books, data.count ?: books.size)
        }

        override suspend fun getById(numericId: Int): CatalogBook {
            calls += 1
            val data = json.decodeFromString<GutendexSearchResponse>(fixture("gutendex-search.json"))
            val record =
                data.results.firstOrNull { it.id == numericId }
                    ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $numericId")
            return mapGutendexBook(record)
                ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "gutendex book $numericId unavailable")
        }
    }

    private inner class FakeOpenLibrary(
        var calls: Int = 0,
    ) : OpenLibraryDataSource(
            FakeCatalogHttpTransport({ error("no I/O in cache test") }),
            RateLimiter(0L),
        ) {
        override suspend fun search(
            query: String,
            page: Int,
            pageSize: Int,
        ): CatalogSearchResult {
            calls += 1
            val data =
                json.decodeFromString<OpenLibrarySearchResponse>(fixture("openlibrary-search.json"))
            val books = data.docs.mapNotNull(::mapOpenLibraryDoc)
            return CatalogSearchResult(books, data.numFound ?: books.size)
        }
    }

    /** Fake DAO: records every call; SQL targets discover_cache only by construction. */
    private inner class FakeDiscoverCacheDao : DiscoverCacheDao {
        val rows = mutableMapOf<String, DiscoverCacheEntity>()
        val calls = mutableListOf<String>()

        private fun payloadBytesOf(entry: DiscoverCacheEntity): Long =
            entry.payloadJson
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong()

        override suspend fun getByKey(key: String): DiscoverCacheEntity? {
            calls.add("get:$key")
            return rows[key]
        }

        override suspend fun put(entry: DiscoverCacheEntity) {
            calls.add("put:${entry.key}")
            rows[entry.key] = entry
        }

        override suspend fun deleteByKey(key: String) {
            calls.add("delete:$key")
            rows.remove(key)
        }

        override suspend fun count(): Int {
            calls.add("count")
            return rows.size
        }

        override suspend fun payloadBytes(): Long {
            calls.add("payloadBytes")
            return rows.values.sumOf { payloadBytesOf(it) }
        }

        override suspend fun payloadBytesForKey(key: String): Long {
            calls.add("payloadBytesForKey:$key")
            return rows[key]?.let { payloadBytesOf(it) } ?: 0L
        }

        override suspend fun deleteExpired(nowEpochSecs: Long): Int {
            calls.add("deleteExpired:$nowEpochSecs")
            val expired = rows.filterValues { it.fetchedAtEpochSecs + it.ttlSecs < nowEpochSecs }
            expired.keys.forEach { rows.remove(it) }
            return expired.size
        }

        override suspend fun deleteLegacyNamespaces(): Int {
            calls.add("deleteLegacyNamespaces")
            val legacy =
                rows.keys.filter { key ->
                    key.startsWith("p:v1:") ||
                        key.startsWith("d:v1:") ||
                        key.startsWith("f:v1:") ||
                        key.startsWith("p:v2:") ||
                        key.startsWith("d:v2:") ||
                        key.startsWith("f:v2:")
                }
            legacy.forEach { rows.remove(it) }
            return legacy.size
        }

        override suspend fun deleteAll(): Int {
            calls.add("deleteAll")
            val removed = rows.size
            rows.clear()
            return removed
        }
    }

    private fun provider(
        g: FakeGutendex,
        o: FakeOpenLibrary,
        cache: DiscoverCacheStore,
        now: () -> Long,
        refreshScope: kotlinx.coroutines.CoroutineScope,
    ) = CompositeCatalogProvider(
        listOf(GutendexCatalogProvider(g), OpenLibraryCatalogProvider(o)),
        cache = cache,
        nowEpochSecs = now,
        refreshScope = refreshScope,
    )

    @Test fun keys_useSourceScopedV3PrefixesAndTtls() {
        assertEquals("p:v3:builtin:gutendex:pride:1", pageCacheKey(BUILTIN_GUTENDEX, "Pride ", 1))
        assertEquals("d:v3:builtin:gutendex:gutendex:1342", detailCacheKey(BUILTIN_GUTENDEX, "gutendex:1342"))
        assertEquals("f:v3:builtin:gutendex:POPULAR:1", featuredCacheKey(BUILTIN_GUTENDEX, CatalogFeaturedSort.POPULAR, 1))
        assertEquals(86_400L, PAGE_TTL_S)
        assertEquals(604_800L, DETAIL_TTL_S)
        assertEquals(21_600L, FEATURED_TTL_S)
    }

    @Test fun memoryCache_hitWithinTtlAndEvictsExpiredRows() =
        runTest {
            val cache = InMemoryDiscoverCache()
            cache.put("p:v3:builtin:gutendex:pride:1", "{\"n\":1}", 1_000L, PAGE_TTL_S)
            assertEquals("{\"n\":1}", cache.get("p:v3:builtin:gutendex:pride:1", 1_000L + 3_600L))
            assertNull(cache.get("p:v3:builtin:gutendex:pride:1", 1_000L + PAGE_TTL_S + 1L))
            assertEquals(0, cache.size())
        }

    @Test fun memoryCache_missAndOverwrite() =
        runTest {
            val cache = InMemoryDiscoverCache()
            assertNull(cache.get("p:v3:builtin:gutendex:missing:1", 1_000L))
            cache.put("p:v3:builtin:gutendex:pride:1", "{\"n\":1}", 1_000L, PAGE_TTL_S)
            cache.put("p:v3:builtin:gutendex:pride:1", "{\"n\":2}", 2_000L, PAGE_TTL_S)
            assertEquals("{\"n\":2}", cache.get("p:v3:builtin:gutendex:pride:1", 2_001L))
        }

    @Test fun search_servesRepeatedQueryFromCacheWithoutIo() =
        runTest {
            val g = FakeGutendex()
            val o = FakeOpenLibrary()
            val catalog = provider(g, o, InMemoryDiscoverCache(), { 1_000L }, this)
            val first = catalog.search("pride", 1)
            assertEquals(1, g.calls)
            assertEquals(1, o.calls)
            val second = catalog.search("pride", 1)
            assertEquals(first, second)
            assertEquals(1, g.calls)
            assertEquals(1, o.calls)
        }

    @Test fun search_refetchesExpiredPagesAndReplacesEntry() =
        runTest {
            val g = FakeGutendex()
            val o = FakeOpenLibrary()
            var now = 1_000L
            val catalog = provider(g, o, InMemoryDiscoverCache(), { now }, this)
            catalog.search("pride", 1)
            now += PAGE_TTL_S + 1L
            // Stale-while-revalidate: the resident stale page is served without I/O...
            catalog.search("pride", 1)
            assertEquals(1, g.calls)
            assertEquals(1, o.calls)
            // ...and the background refresh replaces it once the refresh scope runs.
            advanceUntilIdle()
            assertEquals(2, g.calls)
            assertEquals(2, o.calls)
        }

    @Test fun getDetails_cached7dAndDownloadPathStaysPure() =
        runTest {
            val g = FakeGutendex()
            val o = FakeOpenLibrary()
            var now = 5_000L
            val catalog = provider(g, o, InMemoryDiscoverCache(), { now }, this)
            val first = catalog.getDetails("gutendex:1342")
            assertEquals("gutendex:1342", first.id)
            now += 3_600L
            catalog.getDetails("gutendex:1342")
            assertEquals(1, g.calls)
            // Pure path: no I/O, no cache interaction.
            assertEquals(
                "https://example.com/b.txt",
                catalog.resolveDownloadUrl(mapOf("text/plain" to "https://example.com/b.txt"), true),
            )
            assertEquals(1, g.calls)
        }

    @Test fun search_mergeCoverFallbackIdenticalOnHit() =
        runTest {
            val g = FakeGutendex()
            val o = FakeOpenLibrary()
            val catalog = provider(g, o, InMemoryDiscoverCache(), { 1_000L }, this)
            val miss = catalog.search("pride", 1)
            val hit = catalog.search("pride", 1)
            val prideMiss = miss.results.first { it.id == "gutendex:1342" }
            val prideHit = hit.results.first { it.id == "gutendex:1342" }
            // U2: Gutenberg-derived cover (record id) wins over the OL fallback.
            assertEquals("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg", prideMiss.coverUrl)
            assertEquals(prideMiss, prideHit)
        }

    @Test fun roomStore_passThroughWithTtlExpiryAndCacheOnlyKeys() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            val store = RoomDiscoverCache(dao)
            store.put("p:v3:builtin:gutendex:pride:1", "{\"n\":1}", 1_000L, PAGE_TTL_S)
            assertEquals("{\"n\":1}", store.get("p:v3:builtin:gutendex:pride:1", 2_000L))
            assertNull(store.get("p:v3:builtin:gutendex:pride:1", 1_000L + PAGE_TTL_S + 1L))
            assertEquals(0, dao.count())
            // Isolation: every key-scoped call is cache-scoped; the DAO interface
            // exposes discover_cache SQL only — no user_books/outbox method exists.
            // (deleteExpired/payloadBytes are the slice-6a size-cap bookkeeping.)
            assertTrue(
                dao.calls
                    .filter { it.startsWith("get:") || it.startsWith("put:") || it.startsWith("delete:") }
                    .all { it.contains("p:v3:") },
            )
            assertTrue(dao.calls.any { it.startsWith("delete:p:v3:") })
            assertTrue(dao.calls.any { it.startsWith("deleteExpired:") })
        }

    /** Counts cache writes while delegating reads to a real TTL store. */
    private class CountingStore(
        private val delegate: DiscoverCacheStore = InMemoryDiscoverCache(),
    ) : DiscoverCacheStore {
        val puts = mutableListOf<Triple<String, String, Long>>()

        override suspend fun get(
            key: String,
            nowEpochSecs: Long,
        ): String? = delegate.get(key, nowEpochSecs)

        override suspend fun read(
            key: String,
            nowEpochSecs: Long,
        ): DiscoverCacheRead? = delegate.read(key, nowEpochSecs)

        override suspend fun put(
            key: String,
            payload: String,
            fetchedAtEpochSecs: Long,
            ttlSecs: Long,
        ) {
            puts.add(Triple(key, payload, ttlSecs))
            delegate.put(key, payload, fetchedAtEpochSecs, ttlSecs)
        }
    }

    private fun emptyStub(sourceId: String): CatalogProvider =
        object : CatalogProvider {
            override suspend fun search(
                query: String,
                page: Int,
            ): PagedResult = PagedResult(emptyList(), null, 0)

            override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "n/a")

            override fun resolveDownloadUrl(
                formats: Map<String, String>,
                preferEpub: Boolean,
            ): String = throw catalogError(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "n/a")

            override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(sourceId, "Empty", CatalogSourceKind.BUILTIN))
        }

    @Test fun search_neverCachesEmptyPages() =
        runTest {
            val store = CountingStore()
            val catalog =
                CompositeCatalogProvider(
                    listOf(emptyStub(BUILTIN_GUTENDEX), emptyStub(BUILTIN_OPENLIBRARY)),
                    cache = store,
                    nowEpochSecs = { 1_000L },
                    refreshScope = this,
                )
            val page = catalog.search("pride", 1)
            assertTrue(page.results.isEmpty())
            // Never-cache-empty: empty search results persist nothing.
            assertTrue(store.puts.isEmpty())
        }
}
