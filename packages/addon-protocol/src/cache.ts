/**
 * Per-addon response cache. Pure bookkeeping, no I/O.
 *
 * Time comes from an injected `now` so ttl behavior is testable without
 * sleeping, and entries are held least-recently-used first: a `get` moves a
 * key to the back, and `set` evicts from the front at `maxEntries`.
 */

interface CacheEntry {
  value: unknown;
  expiresAt: number;
}

const DEFAULT_MAX_ENTRIES = 500;

export class TtlCache {
  private readonly now: () => number;
  private readonly maxEntries: number;
  private readonly entries = new Map<string, CacheEntry>();

  constructor(opts: { now?: () => number; maxEntries?: number } = {}) {
    this.now = opts.now ?? Date.now;
    this.maxEntries = Math.max(1, Math.trunc(opts.maxEntries ?? DEFAULT_MAX_ENTRIES));
  }

  /** `undefined` when absent or expired. A ttl boundary is already expired. */
  get(key: string): unknown {
    const entry = this.entries.get(key);
    if (entry === undefined) return undefined;
    if (entry.expiresAt <= this.now()) {
      this.entries.delete(key);
      return undefined;
    }
    // Re-inserting moves the key to the back of the map: the LRU order.
    this.entries.delete(key);
    this.entries.set(key, entry);
    return entry.value;
  }

  /** A non-positive ttl stores nothing and drops any stale value for the key. */
  set(key: string, value: unknown, ttlMs: number): void {
    this.entries.delete(key);
    if (ttlMs <= 0) return;

    if (this.entries.size >= this.maxEntries) {
      this.purgeExpired();
      while (this.entries.size >= this.maxEntries) {
        const oldest = this.entries.keys().next();
        if (oldest.done === true) break;
        this.entries.delete(oldest.value);
      }
    }
    this.entries.set(key, { value, expiresAt: this.now() + ttlMs });
  }

  clear(): void {
    this.entries.clear();
  }

  private purgeExpired(): void {
    const now = this.now();
    for (const [key, entry] of this.entries) {
      if (entry.expiresAt <= now) this.entries.delete(key);
    }
  }
}
