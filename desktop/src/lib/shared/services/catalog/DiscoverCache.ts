/**
 * DiscoverCache — isolated TTL cache for public-domain catalog pages/details.
 * Mirrors the Rust `discover_cache` table (key, payload, fetched_at, ttl_s)
 * and the Android `DiscoverCache` store key-for-key: page keys live 24h,
 * detail keys live 7d, featured rail keys live 6h. Payloads are opaque JSON.
 * This module never touches user_books/outbox by construction.
 *
 * Durability is layered *around* the synchronous store, never inside it: the
 * composite's read path is synchronous by contract, so
 * `PersistentDiscoverCache` keeps a synchronous mirror the composite reads,
 * fires a best-effort durable write on `put`, and seeds the mirror from the
 * durable table through a bounded `preload`. Within a session the mirror is
 * authoritative; a durable row written earlier becomes visible on the next
 * preload (once per composite build).
 */
import { discoverCachePut, discoverCacheRead } from '$lib/shared/api/discoverCacheApi';
import type { CatalogFeaturedSort } from './CatalogProvider';

export const PAGE_TTL_S = 86_400;
export const DETAIL_TTL_S = 604_800;

/** Featured rail TTL: 6h (Android `FEATURED_TTL_S` parity — rails go stale faster than search pages). */
export const FEATURED_TTL_S = 21_600;

/**
 * Discover cache format version (design A7): prefixed into every key.
 * v2 — keys carry the full source id; v1 rows age out unread.
 */
const DISCOVER_CACHE_VERSION = 'v2';

const PAGE_KEY_PREFIX = `p:${DISCOVER_CACHE_VERSION}:`;
const DETAIL_KEY_PREFIX = `d:${DISCOVER_CACHE_VERSION}:`;
const FEATURED_KEY_PREFIX = `f:${DISCOVER_CACHE_VERSION}:`;

/** Page cache key: `p:v2:{sourceId}:{query}:{page}` (24h). Query is normalized. */
export function pageCacheKey(sourceId: string, query: string, page: number): string {
  return `${PAGE_KEY_PREFIX}${sourceId}:${query.trim().toLowerCase()}:${page}`;
}

/** Detail cache key: `d:v2:{sourceId}:{id}` (7d). */
export function detailCacheKey(sourceId: string, id: string): string {
  return `${DETAIL_KEY_PREFIX}${sourceId}:${id}`;
}

/** Featured rail cache key: `f:v2:{sourceId}:{sort}` (6h, stale-while-revalidate). */
export function featuredCacheKey(sourceId: string, sort: CatalogFeaturedSort): string {
  return `${FEATURED_KEY_PREFIX}${sourceId}:${sort}`;
}

/**
 * Resident cache read: the stored payload plus whether it is past its TTL.
 * The store never mutates on this path, so a stale row stays resident and can
 * be served while a background refresh replaces it.
 */
export interface DiscoverCacheRead {
  payload: string;
  fetchedAt: number;
  ttlS: number;
  stale: boolean;
}

export interface DiscoverCacheStore {
  get(key: string, nowEpochSecs: number): string | null;
  put(key: string, payload: string, fetchedAtEpochSecs: number, ttlS: number): void;
  /**
   * Fresh-or-stale resident read — additive and optional, so existing stores
   * and fakes keep compiling. Callers feature-detect it and fall back to the
   * fresh-only `get` when it is absent.
   */
  read?(key: string, nowEpochSecs: number): DiscoverCacheRead | null;
  /**
   * Durable read-through for exactly ONE key — additive and optional. On a
   * mirror miss it reads the durable backing store, seeds the mirror and
   * returns the resident read; a miss or a failure resolves to `null`, never a
   * throw. Detail keys are per-book and cannot be enumerated by `preload`, so
   * this is how a detail fetched in an earlier session stays reachable.
   */
  readDurable?(key: string, nowEpochSecs: number): Promise<DiscoverCacheRead | null>;
}

/** Async durable backing store: the Tauri commands in production, a fake in tests. */
export interface DurableDiscoverCachePort {
  read(key: string): Promise<{ payload: string; fetchedAt: number; ttlS: number } | null>;
  write(key: string, payload: string, fetchedAtEpochSecs: number, ttlS: number): Promise<void>;
}

/** A cache that can seed itself from durable storage before its first read. */
export interface PreloadableCache {
  /**
   * Seed the mirror from durable storage. `sourceIds` bounds the featured keys
   * (two per source); `extraKeys` carries explicit deterministic keys the
   * feature layer owns (e.g. today's thematic rail page). `extraKeys` MUST stay
   * bounded — preload must never enumerate arbitrary page keys.
   */
  preload(sourceIds: readonly string[], extraKeys?: readonly string[]): Promise<void>;
}

interface CacheEntry {
  payload: string;
  fetchedAt: number;
  ttlS: number;
}

/**
 * Featured sorts preloaded per featured-capable source. Featured rail reads are
 * the only ones whose keys are deterministic from the source set alone, so
 * preload is bounded to exactly these two keys per such source.
 */
export const FEATURED_SORTS: readonly CatalogFeaturedSort[] = ['NEWEST', 'POPULAR'];

/**
 * In-memory TTL store with lazy expiry: reads past TTL return null and
 * evict the row eagerly so the map cannot grow unbounded. Mirrors
 * `discover_cache_get` in `desktop/src-tauri/src/db.rs`.
 */
export class InMemoryDiscoverCache implements DiscoverCacheStore {
  private readonly entries = new Map<string, CacheEntry>();

  get(key: string, nowEpochSecs: number): string | null {
    const entry = this.entries.get(key);
    if (!entry) return null;
    if (nowEpochSecs - entry.fetchedAt > entry.ttlS) {
      this.entries.delete(key);
      return null;
    }
    return entry.payload;
  }

  /** Never mutates: an expired row stays resident, flagged `stale`. */
  read(key: string, nowEpochSecs: number): DiscoverCacheRead | null {
    const entry = this.entries.get(key);
    if (!entry) return null;
    return {
      payload: entry.payload,
      fetchedAt: entry.fetchedAt,
      ttlS: entry.ttlS,
      stale: nowEpochSecs - entry.fetchedAt > entry.ttlS,
    };
  }

  put(key: string, payload: string, fetchedAtEpochSecs: number, ttlS: number): void {
    this.entries.set(key, { payload, fetchedAt: fetchedAtEpochSecs, ttlS });
  }

  size(): number {
    return this.entries.size;
  }
}

/**
 * Durable, synchronously-readable cache: an in-memory mirror plus best-effort
 * write-through. `get`/`read` only ever touch the mirror, so the composite's
 * synchronous read path stays synchronous; `preload(sourceIds)` seeds the
 * mirror from the durable table, bounded to the featured keys of those sources.
 */
export class PersistentDiscoverCache implements DiscoverCacheStore, PreloadableCache {
  private readonly mirror = new InMemoryDiscoverCache();

  constructor(private readonly port: DurableDiscoverCachePort) {}

  get(key: string, nowEpochSecs: number): string | null {
    return this.mirror.get(key, nowEpochSecs);
  }

  read(key: string, nowEpochSecs: number): DiscoverCacheRead | null {
    return this.mirror.read(key, nowEpochSecs);
  }

  /**
   * One-key durable read-through (bounded: exactly one backing-store read per
   * miss). A resident mirror entry is returned without I/O, and a failed
   * durable read degrades to a miss — a cache never fails a fetch.
   */
  async readDurable(key: string, nowEpochSecs: number): Promise<DiscoverCacheRead | null> {
    const resident = this.mirror.read(key, nowEpochSecs);
    if (resident) return resident;
    try {
      const row = await this.port.read(key);
      if (!row) return null;
      this.mirror.put(key, row.payload, row.fetchedAt, row.ttlS);
      return this.mirror.read(key, nowEpochSecs);
    } catch {
      return null;
    }
  }

  put(key: string, payload: string, fetchedAtEpochSecs: number, ttlS: number): void {
    this.mirror.put(key, payload, fetchedAtEpochSecs, ttlS);
    // Best-effort durability: a cache write must never fail a rail.
    void this.port.write(key, payload, fetchedAtEpochSecs, ttlS).catch(() => {});
  }

  async preload(sourceIds: readonly string[], extraKeys: readonly string[] = []): Promise<void> {
    const featured = sourceIds.flatMap((sourceId) =>
      FEATURED_SORTS.map((sort) => featuredCacheKey(sourceId, sort)),
    );
    // Deduped so a repeated extra key cannot multiply reads; still bounded.
    const keys = [...new Set([...featured, ...extraKeys])];
    await Promise.all(
      keys.map(async (key) => {
        try {
          const row = await this.port.read(key);
          if (row) this.mirror.put(key, row.payload, row.fetchedAt, row.ttlS);
        } catch {
          // A failed preload read is a cache miss, never a build error.
        }
      }),
    );
  }

  size(): number {
    return this.mirror.size();
  }
}

/** Durable port over the thin `discoverCache*` Tauri commands. */
export class TauriDiscoverCachePort implements DurableDiscoverCachePort {
  async read(key: string): Promise<{ payload: string; fetchedAt: number; ttlS: number } | null> {
    const row = await discoverCacheRead(key);
    return row ? { payload: row.payload, fetchedAt: row.fetchedAt, ttlS: row.ttlS } : null;
  }

  async write(
    key: string,
    payload: string,
    fetchedAtEpochSecs: number,
    ttlS: number,
  ): Promise<void> {
    await discoverCachePut(key, payload, fetchedAtEpochSecs, ttlS);
  }
}
