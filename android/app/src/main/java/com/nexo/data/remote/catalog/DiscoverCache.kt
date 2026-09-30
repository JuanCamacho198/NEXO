package com.nexo.data.remote.catalog

import com.nexo.data.local.dao.DiscoverCacheDao
import com.nexo.data.local.entity.DiscoverCacheEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Search-page TTL: 24h. */
const val PAGE_TTL_S = 86_400L

/** Detail TTL: 7d. */
const val DETAIL_TTL_S = 604_800L

/** Featured rail TTL: 6h (rails go stale faster than search pages). */
const val FEATURED_TTL_S = 21_600L

/** Discover cache format version (design A7): prefixed into every key. */
const val DISCOVER_CACHE_VERSION = "v3"

/**
 * Discover-cache size cap in payload bytes only (25_165_824 B = 24 MiB).
 * DB pages/index are excluded; the metric is `SUM(LENGTH(CAST(payload AS BLOB)))`.
 */
const val DISCOVER_CACHE_MAX_BYTES = 24L * 1024L * 1024L

private const val PAGE_KEY_PREFIX = "p:$DISCOVER_CACHE_VERSION:"
private const val DETAIL_KEY_PREFIX = "d:$DISCOVER_CACHE_VERSION:"
private const val FEATURED_KEY_PREFIX = "f:$DISCOVER_CACHE_VERSION:"

/** Page cache key: `p:v3:{sourceId}:{query}:{page}` (24h). Query is normalized. */
fun pageCacheKey(
    sourceId: String,
    query: String,
    page: Int,
): String = "$PAGE_KEY_PREFIX$sourceId:${query.trim().lowercase()}:$page"

/** Detail cache key: `d:v3:{sourceId}:{id}` (7d). */
fun detailCacheKey(
    sourceId: String,
    id: String,
): String = "$DETAIL_KEY_PREFIX$sourceId:$id"

/**
 * Featured rail cache key: `f:v3:{sourceId}:{sort}:{page}` (6h featured TTL).
 *
 * Bumped to `v3` with the rest of the catalog namespace; stale `v2` rows
 * (search/detail/featured) are never served under the new prefixes.
 */
fun featuredCacheKey(
    sourceId: String,
    sort: CatalogFeaturedSort,
    page: Int,
): String = "$FEATURED_KEY_PREFIX$sourceId:${sort.name}:$page"

/**
 * TTL cache store for catalog pages/details.
 * Mirrors desktop `discover_cache` (migration 0016) key-for-key.
 * Never touches user_books/outbox — separate table, separate DAO.
 */
interface DiscoverCacheStore {
    suspend fun get(
        key: String,
        nowEpochSecs: Long,
    ): String?

    /**
     * Resident read that never mutates the store: returns the row even when it is
     * past its TTL, flagged with [DiscoverCacheRead.fresh] so the caller can serve
     * stale-while-revalidate. Returns null only when the key is absent.
     */
    suspend fun read(
        key: String,
        nowEpochSecs: Long,
    ): DiscoverCacheRead?

    suspend fun put(
        key: String,
        payload: String,
        fetchedAtEpochSecs: Long,
        ttlSecs: Long,
    )
}

/**
 * A resident cache row plus its freshness. [fresh] is false when the row is past
 * its TTL but still present (SWR serves it and refreshes behind it).
 */
data class DiscoverCacheRead(
    val payload: String,
    val fresh: Boolean,
)

/**
 * In-memory TTL store with lazy expiry: reads past TTL return null and
 * evict the row eagerly. Used by unit tests and as the offline fallback.
 */
class InMemoryDiscoverCache : DiscoverCacheStore {
    private data class Entry(
        val payload: String,
        val fetchedAt: Long,
        val ttlSecs: Long,
    )

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    override suspend fun get(
        key: String,
        nowEpochSecs: Long,
    ): String? =
        mutex.withLock {
            val entry = entries[key] ?: return@withLock null
            if (nowEpochSecs - entry.fetchedAt > entry.ttlSecs) {
                entries.remove(key)
                return@withLock null
            }
            entry.payload
        }

    override suspend fun read(
        key: String,
        nowEpochSecs: Long,
    ): DiscoverCacheRead? =
        mutex.withLock {
            val entry = entries[key] ?: return@withLock null
            DiscoverCacheRead(entry.payload, nowEpochSecs - entry.fetchedAt <= entry.ttlSecs)
        }

    override suspend fun put(
        key: String,
        payload: String,
        fetchedAtEpochSecs: Long,
        ttlSecs: Long,
    ) {
        mutex.withLock { entries[key] = Entry(payload, fetchedAtEpochSecs, ttlSecs) }
    }

    suspend fun size(): Int = mutex.withLock { entries.size }
}

/** Room-backed store: SQL targets `discover_cache` only. */
class RoomDiscoverCache(
    private val dao: DiscoverCacheDao,
    private val maxBytes: Long = DISCOVER_CACHE_MAX_BYTES,
) : DiscoverCacheStore {
    override suspend fun get(
        key: String,
        nowEpochSecs: Long,
    ): String? {
        val row = dao.getByKey(key) ?: return null
        if (nowEpochSecs - row.fetchedAtEpochSecs > row.ttlSecs) {
            dao.deleteByKey(key)
            return null
        }
        return row.payloadJson
    }

    override suspend fun read(
        key: String,
        nowEpochSecs: Long,
    ): DiscoverCacheRead? {
        val row = dao.getByKey(key) ?: return null
        val fresh = nowEpochSecs - row.fetchedAtEpochSecs <= row.ttlSecs
        return DiscoverCacheRead(row.payloadJson, fresh)
    }

    /**
     * Size-capped write with TTL-respecting eviction:
     * 1. a single payload larger than the cap is never stored (skip);
     * 2. TTL-dead rows (`fetched_at + ttl_s < now`) are always evictable and are
     *    removed first (oldest `fetched_at` wins when the DAO orders victims);
     * 3. unexpired rows are NEVER evicted: if they alone would exceed the cap
     *    together with the new payload, the write is skipped (soft ceiling).
     */
    override suspend fun put(
        key: String,
        payload: String,
        fetchedAtEpochSecs: Long,
        ttlSecs: Long,
    ) {
        val bytes = payload.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > maxBytes) return
        dao.deleteExpired(fetchedAtEpochSecs)
        val others = dao.payloadBytes() - dao.payloadBytesForKey(key)
        if (others + bytes > maxBytes) return
        dao.put(DiscoverCacheEntity(key, payload, fetchedAtEpochSecs, ttlSecs))
    }
}
