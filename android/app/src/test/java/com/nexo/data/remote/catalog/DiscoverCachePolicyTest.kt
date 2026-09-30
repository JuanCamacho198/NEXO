package com.nexo.data.remote.catalog

import com.nexo.data.local.dao.DiscoverCacheDao
import com.nexo.data.local.entity.DiscoverCacheEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 6a — `discover_cache` policy (spec capability `cache-lifecycle`).
 * Covers CL1 (prune), CL2 (legacy purge), CL3 (SWR), CL4 (cap eviction),
 * CL5 (TTL boundary never evicted), CL6 (books/covers never candidates).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverCachePolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun entity(
        key: String,
        payload: String,
        fetchedAt: Long,
        ttl: Long,
    ) = DiscoverCacheEntity(key, payload, fetchedAt, ttl)

    // ── CL1: prune deletes TTL-dead rows only ────────────────────────────

    @Test
    fun pruneBoundary_exactNow_keptJustExpired_deleted() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            // expiry == now (1_000 + 100 == 1_100): the `== now` boundary is kept.
            dao.put(entity("p:v3:boundary", "x", 1_000L, 100L))
            // expiry < now (1_000 + 99 == 1_099 < 1_100): deleted.
            dao.put(entity("p:v3:justExpired", "y", 1_000L, 99L))

            val removed = dao.deleteExpired(1_100L)

            assertEquals(1, removed)
            assertNotNull(dao.getByKey("p:v3:boundary"))
            assertNull(dao.getByKey("p:v3:justExpired"))
        }

    @Test
    fun prune_keepsUnexpiredRows() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            dao.put(entity("p:v3:fresh", "x", 1_000L, 86_400L))
            dao.put(entity("p:v3:dead", "y", 1_000L, 10L))

            assertEquals(1, dao.deleteExpired(2_000L))
            assertEquals(1, dao.count())
            assertNull(dao.getByKey("p:v3:dead"))
        }

    // ── CL2: purge legacy v1/v2 namespaces ───────────────────────────────

    @Test
    fun purge_deletesV1V2Keys_keepsV3() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            dao.put(entity("p:v1:builtin:gutendex:pride:1", "a", 1L, 100L))
            dao.put(entity("d:v2:builtin:gutendex:gutendex:1", "b", 1L, 100L))
            dao.put(entity("f:v1:builtin:gutendex:POPULAR:1", "c", 1L, 100L))
            dao.put(entity("p:v3:builtin:gutendex:pride:1", "live", 1L, 100L))

            val removed = dao.deleteLegacyNamespaces()

            assertEquals(3, removed)
            assertEquals(listOf("p:v3:builtin:gutendex:pride:1"), dao.rows.keys.toList())
        }

    // ── resident (`read`) is non-mutating and flags staleness ────────────

    @Test
    fun read_returnsStaleResidentWithoutEvicting() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            val store = RoomDiscoverCache(dao)
            store.put("p:v3:a", "{\"n\":1}", 1_000L, 100L)

            val fresh = store.read("p:v3:a", 1_050L)
            assertEquals("{\"n\":1}", fresh?.payload)
            assertEquals(true, fresh?.fresh)

            val stale = store.read("p:v3:a", 1_101L)
            assertEquals("{\"n\":1}", stale?.payload)
            assertEquals(false, stale?.fresh)
            // Non-mutating: the stale row is still resident for SWR to serve.
            assertNotNull(dao.getByKey("p:v3:a"))

            // `get` keeps its old lazy-eviction semantics.
            assertNull(store.get("p:v3:a", 1_102L))
            assertNull(dao.getByKey("p:v3:a"))
        }

    // ── CL3: stale-while-revalidate ──────────────────────────────────────

    @Test
    fun swr_staleServe_startsBackgroundRefresh() =
        runTest {
            val store = InMemoryDiscoverCache()
            val stalePage = PagedResult(listOf(book("gutendex:1")), null, 1)
            store.put(
                pageCacheKey(BUILTIN_GUTENDEX, "pride", 1),
                json.encodeToString(PagedResult.serializer(), stalePage),
                1_000L,
                100L,
            )
            val provider = CountingStubProvider(BUILTIN_GUTENDEX, book("gutendex:2"))
            val catalog =
                CompositeCatalogProvider(
                    listOf(provider),
                    cache = store,
                    nowEpochSecs = { 2_000L },
                    refreshScope = this,
                )

            // Stale payload served immediately, no upstream call yet.
            val served = catalog.search("pride", 1)
            assertEquals(listOf("gutendex:1"), served.results.map { it.id })
            assertEquals(0, provider.calls)

            // The background refresh runs behind the served page and replaces it.
            advanceUntilIdle()
            assertEquals(1, provider.calls)
            val refreshed = store.read(pageCacheKey(BUILTIN_GUTENDEX, "pride", 1), 2_000L)
            assertEquals(true, refreshed?.fresh)
            assertTrue(refreshed!!.payload.contains("gutendex:2"))
        }

    // ── CL4: cap evicts an expired (eligible) row to admit a write ───────

    @Test
    fun atCap_evictsExpiredRowAndAdmitsWrite() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            val store = RoomDiscoverCache(dao, maxBytes = 110L)
            dao.put(entity("p:v3:expired", "E".repeat(50), 1_000L, 10L)) // expiry 1_010
            dao.put(entity("p:v3:keep", "K".repeat(50), 1_000L, 100_000L))

            store.put("p:v3:fresh", "F".repeat(30), 2_000L, 100L)

            // Expired row is always evictable and was removed to make room.
            assertNull(dao.getByKey("p:v3:expired"))
            assertNotNull(dao.getByKey("p:v3:keep"))
            assertNotNull(dao.getByKey("p:v3:fresh"))
        }

    // ── CL5: unexpired rows are never evicted — write is skipped ─────────

    @Test
    fun unexpiredRow_notEvicted_writeSkipped() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            val store = RoomDiscoverCache(dao, maxBytes = 70L)
            dao.put(entity("p:v3:keep", "K".repeat(50), 1_000L, 100_000L))

            // 50 + 30 > 70 with no expired rows: skip, never evict the unexpired row.
            store.put("p:v3:fresh", "F".repeat(30), 2_000L, 100L)

            assertNull(dao.getByKey("p:v3:fresh"))
            assertNotNull(dao.getByKey("p:v3:keep"))
        }

    @Test
    fun singleRowOverCap_isNeverStored() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            val store = RoomDiscoverCache(dao, maxBytes = 50L)

            store.put("p:v3:big", "X".repeat(51), 2_000L, 100L)

            assertNull(dao.getByKey("p:v3:big"))
            assertEquals(0, dao.count())
            // Step (b) short-circuits before any eviction bookkeeping.
            assertTrue(dao.calls.none { it.startsWith("deleteExpired") })
        }

    @Test
    fun replace_isMeasuredNetOfExistingKey() =
        runTest {
            val dao = FakeDiscoverCacheDao()
            val store = RoomDiscoverCache(dao, maxBytes = 60L)
            dao.put(entity("p:v3:a", "A".repeat(50), 1_000L, 100_000L))

            // others = 0 after discounting the key being replaced, so 0 + 30 <= 60.
            store.put("p:v3:a", "A".repeat(30), 2_000L, 100L)

            assertEquals(30, dao.getByKey("p:v3:a")?.payloadJson?.length)
        }

    // ── CL6: only `discover_cache` rows are eviction candidates ──────────

    @Test
    fun eviction_candidatesOnlyDiscoverCache() =
        runTest {
            val cacheOnlyMethods =
                setOf(
                    "getByKey",
                    "put",
                    "deleteByKey",
                    "count",
                    "payloadBytes",
                    "payloadBytesForKey",
                    "deleteExpired",
                    "deleteLegacyNamespaces",
                    "deleteAll",
                )
            val declared =
                DiscoverCacheDao::class.java.declaredMethods
                    .map { it.name }
                    .toSet()
            assertTrue(
                "unexpected DAO method(s): ${declared - cacheOnlyMethods}",
                declared.all { it in cacheOnlyMethods },
            )

            // Behavioral: a cap eviction only ever removes `discover_cache` keys.
            val dao = FakeDiscoverCacheDao()
            val store = RoomDiscoverCache(dao, maxBytes = 70L)
            dao.put(entity("p:v3:expired", "E".repeat(50), 1_000L, 10L))
            store.put("p:v3:new", "N".repeat(30), 2_000L, 100L)
            assertTrue(dao.rows.keys.all { it.startsWith("p:v3:") })
        }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun book(id: String) =
        CatalogBook(
            id = id,
            provider = BUILTIN_GUTENDEX,
            title = "Title $id",
            authors = listOf("Author"),
            coverUrl = null,
            languages = listOf("en"),
            subjects = emptyList(),
            downloadUrl = null,
        )

    private class CountingStubProvider(
        private val sourceId: String,
        private val page: CatalogBook,
    ) : CatalogProvider {
        var calls = 0

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult {
            calls += 1
            return toPagedResult(listOf(this.page), page, 1)
        }

        override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "n/a")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = throw catalogError(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "n/a")

        override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(sourceId, "Stub", CatalogSourceKind.BUILTIN))
    }

    /** In-memory DAO fake mirroring the Room queries, including the new policy methods. */
    private class FakeDiscoverCacheDao : DiscoverCacheDao {
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
}
