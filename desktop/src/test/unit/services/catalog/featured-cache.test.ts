/**
 * Featured rail caching (Domain A, slice 3): the production composite must be
 * built with a NON-NULL cache, featured reads must honor the 6h TTL with
 * stale-while-revalidate, an inactive source must never be served, and an
 * author rail's `searchSource` page must be cached too.
 */
import { describe, expect, it } from 'vitest';
import {
  CompositeCatalogProvider,
  createRebuildingCatalogProvider,
} from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  DETAIL_TTL_S,
  FEATURED_TTL_S,
  InMemoryDiscoverCache,
  PAGE_TTL_S,
  PersistentDiscoverCache,
  detailCacheKey,
  featuredCacheKey,
  pageCacheKey,
  type DiscoverCacheStore,
  type DurableDiscoverCachePort,
} from '$lib/shared/services/catalog/DiscoverCache';
import type {
  CatalogBook,
  CatalogFeaturedSort,
  CatalogProvider,
  CatalogSource,
  CatalogSourceInfo,
  PagedResult,
} from '$lib/shared/services/catalog/CatalogProvider';

const SOURCE = 'builtin:gutendex' as CatalogSource;

function book(id: string): CatalogBook {
  return {
    id,
    provider: 'fake',
    title: `Book ${id}`,
    authors: [],
    coverUrl: null,
    languages: [],
    subjects: [],
    downloadUrl: null,
  };
}

function page(ids: string[]): PagedResult {
  return { results: ids.map(book), nexoPage: null, totalCount: ids.length };
}

/** Single-source provider that counts featured/search calls and can defer them. */
class FakeFeaturedProvider implements CatalogProvider {
  featuredCalls = 0;
  searchCalls = 0;
  /** Flip to simulate an addon that does (not) declare featured support. */
  supports = true;
  readonly sources: CatalogSourceInfo[];
  page: PagedResult;
  featuredImpl: (() => Promise<PagedResult>) | null = null;

  constructor(sourceId: CatalogSource, initial: PagedResult) {
    this.sources = [{ sourceId, name: 'Fake', kind: 'builtin' }];
    this.page = initial;
  }

  listSources(): CatalogSourceInfo[] {
    return this.sources;
  }

  supportsFeatured(_sort: CatalogFeaturedSort): boolean {
    return this.supports;
  }

  async featured(_sort: CatalogFeaturedSort, _limit: number): Promise<PagedResult> {
    this.featuredCalls += 1;
    if (this.featuredImpl) return this.featuredImpl();
    return this.page;
  }

  async search(_query: string, _page: number): Promise<PagedResult> {
    this.searchCalls += 1;
    return this.page;
  }

  async searchSource(
    _sourceId: CatalogSource,
    _query: string,
    _page: number,
  ): Promise<PagedResult> {
    this.searchCalls += 1;
    return this.page;
  }

  async getDetails(id: string): Promise<CatalogBook> {
    throw new Error(`unexpected getDetails(${id}) — the cache should have served it`);
  }

  resolveDownloadUrl(): string {
    return 'https://example.com/book.epub';
  }
}

/** Original-contract store only: no additive `read()`. */
class FreshOnlyStore implements DiscoverCacheStore {
  private readonly inner = new InMemoryDiscoverCache();

  get(key: string, nowEpochSecs: number): string | null {
    return this.inner.get(key, nowEpochSecs);
  }

  put(key: string, payload: string, fetchedAtEpochSecs: number, ttlS: number): void {
    this.inner.put(key, payload, fetchedAtEpochSecs, ttlS);
  }

  size(): number {
    return this.inner.size();
  }
}

class RecordingDurablePort implements DurableDiscoverCachePort {
  readonly reads: string[] = [];
  readonly rows = new Map<string, { payload: string; fetchedAt: number; ttlS: number }>();

  async read(key: string): Promise<{ payload: string; fetchedAt: number; ttlS: number } | null> {
    this.reads.push(key);
    return this.rows.get(key) ?? null;
  }

  async write(key: string, payload: string, fetchedAt: number, ttlS: number): Promise<void> {
    this.rows.set(key, { payload, fetchedAt, ttlS });
  }
}

async function flushAsync(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('production composite wiring', () => {
  it('gives the rebuilt composite a non-null cache and preloads no featured key', async () => {
    const port = new RecordingDurablePort();
    const cache = new PersistentDiscoverCache(port);
    const supplier = createRebuildingCatalogProvider(async () => [], undefined, '', { cache });

    const composite = await supplier.current();
    const sourceIds = composite.listSources().map((source) => source.sourceId);

    // Real built-in sources are present, but the featured keyspace is no longer
    // preloaded (DISC-04b: the rails read page keys now), so a build with no
    // feature-owned extra keys performs zero durable reads.
    expect(sourceIds).toContain('builtin:gutendex');
    expect(sourceIds).toContain('builtin:openlibrary');
    expect(composite.featuredSourceIds()).toEqual(['builtin:gutendex']);
    expect(port.reads).toEqual([]);
    expect(port.reads.some((key) => key.startsWith('f:v2:'))).toBe(false);

    // The cache is reachable from the composite's synchronous read path: a
    // seeded detail entry is served with ZERO provider I/O (a null cache would
    // have hit the network here).
    const nowSecs = Math.floor(Date.now() / 1000);
    cache.put(
      detailCacheKey('builtin:gutendex', 'gutendex:1342'),
      JSON.stringify(book('gutendex:1342')),
      nowSecs,
      DETAIL_TTL_S,
    );
    const details = await composite.getDetails('gutendex:1342');
    expect(details.id).toBe('gutendex:1342');
  });

  it('preloads the feature-owned page keys supplied by the composition root', async () => {
    const port = new RecordingDurablePort();
    const cache = new PersistentDiscoverCache(port);
    const thematicKey = pageCacheKey('builtin:googlebooks', 'oscar wilde', 1);
    const supplier = createRebuildingCatalogProvider(async () => [], undefined, '', {
      cache,
      preloadPageKeys: () => [thematicKey],
    });

    const composite = await supplier.current();
    const sourceIds = composite.listSources().map((source) => source.sourceId);

    expect(port.reads).toContain(thematicKey);
    // Exactly the feature-owned page key; the retired featured keys are gone.
    expect(port.reads).toHaveLength(1);
    expect(port.reads.every((key) => key.startsWith('p:v2:'))).toBe(true);
    expect(sourceIds).toContain('builtin:openlibrary');
  });

  it('does not preload when the cache is absent', async () => {
    const supplier = createRebuildingCatalogProvider(async () => []);
    const composite = await supplier.current();
    expect(composite.listSources().length).toBeGreaterThan(0);
  });
});

describe('CompositeCatalogProvider.featuredSourceIds', () => {
  it('tracks the live supportsFeatured probe, not a hardcoded provider list', () => {
    const gutendex = new FakeFeaturedProvider('builtin:gutendex' as CatalogSource, page([]));
    const quiet = new FakeFeaturedProvider('builtin:quiet' as CatalogSource, page([]));
    quiet.supports = false;
    const composite = new CompositeCatalogProvider([gutendex, quiet], {});

    expect(composite.featuredSourceIds()).toEqual(['builtin:gutendex']);

    // An addon installed at runtime that opts into featured is picked up on the
    // next preload without any provider-list edit.
    quiet.supports = true;
    expect([...composite.featuredSourceIds()].sort()).toEqual([
      'builtin:gutendex',
      'builtin:quiet',
    ]);
  });
});

describe('CompositeCatalogProvider featured caching', () => {
  it('caches a featured miss under the 6h TTL and then serves it with zero I/O', async () => {
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1']));
    const cache = new InMemoryDiscoverCache();
    const key = featuredCacheKey(SOURCE, 'NEWEST');
    let now = 10_000;
    const composite = new CompositeCatalogProvider([provider], {
      cache,
      nowEpochSecs: () => now,
    });

    const first = await composite.featured('NEWEST', 6);
    expect(first.results.map((b) => b.id)).toEqual(['gutendex:1']);
    expect(provider.featuredCalls).toBe(1);
    const entry = cache.read(key, now);
    expect(entry?.ttlS).toBe(FEATURED_TTL_S);
    expect(entry?.fetchedAt).toBe(now);
    expect(entry?.stale).toBe(false);

    const second = await composite.featured('NEWEST', 6);
    expect(second.results.map((b) => b.id)).toEqual(['gutendex:1']);
    expect(provider.featuredCalls).toBe(1);

    now += FEATURED_TTL_S + 1;
    expect(cache.read(key, now)?.stale).toBe(true);
  });

  it('serves a 7-hour-old entry stale-first and replaces it after the background refresh', async () => {
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1']));
    const cache = new InMemoryDiscoverCache();
    const key = featuredCacheKey(SOURCE, 'NEWEST');
    let now = 1_000;
    const composite = new CompositeCatalogProvider([provider], {
      cache,
      nowEpochSecs: () => now,
    });

    cache.put(key, JSON.stringify(page(['gutendex:stale'])), now, FEATURED_TTL_S);
    now += 7 * 3_600;

    provider.page = page(['gutendex:fresh']);
    const served = await composite.featured('NEWEST', 6);

    // Stale value first, and the refresh was kicked off behind the caller.
    expect(served.results.map((b) => b.id)).toEqual(['gutendex:stale']);
    expect(provider.featuredCalls).toBe(1);

    await flushAsync();

    // The refresh replaced the entry, so the next read is a pure fresh hit.
    expect(cache.read(key, now)?.payload).toBe(JSON.stringify(page(['gutendex:fresh'])));
    expect(cache.read(key, now)?.stale).toBe(false);
    const second = await composite.featured('NEWEST', 6);
    expect(second.results.map((b) => b.id)).toEqual(['gutendex:fresh']);
    expect(provider.featuredCalls).toBe(1);
  });

  it('guards the background refresh to one in-flight request per cache key', async () => {
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1']));
    const cache = new InMemoryDiscoverCache();
    const key = featuredCacheKey(SOURCE, 'NEWEST');
    let now = 1_000;
    const composite = new CompositeCatalogProvider([provider], {
      cache,
      nowEpochSecs: () => now,
    });
    cache.put(key, JSON.stringify(page(['gutendex:stale'])), now, FEATURED_TTL_S);
    now += 7 * 3_600;

    let release: (value: PagedResult) => void = () => {};
    provider.featuredImpl = () =>
      new Promise<PagedResult>((resolve) => {
        release = resolve;
      });

    const first = await composite.featured('NEWEST', 6);
    const second = await composite.featured('NEWEST', 6);
    expect(first.results.map((b) => b.id)).toEqual(['gutendex:stale']);
    expect(second.results.map((b) => b.id)).toEqual(['gutendex:stale']);
    expect(provider.featuredCalls).toBe(1);

    release(page(['gutendex:fresh']));
    await flushAsync();
    expect(cache.read(key, now)?.payload).toBe(JSON.stringify(page(['gutendex:fresh'])));
  });

  it('keeps serving the stale value when the background refresh fails', async () => {
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1']));
    const cache = new InMemoryDiscoverCache();
    const key = featuredCacheKey(SOURCE, 'NEWEST');
    let now = 1_000;
    const composite = new CompositeCatalogProvider([provider], {
      cache,
      nowEpochSecs: () => now,
    });
    cache.put(key, JSON.stringify(page(['gutendex:stale'])), now, FEATURED_TTL_S);
    now += 7 * 3_600;

    provider.featuredImpl = () => Promise.reject(new Error('upstream down'));
    const served = await composite.featured('NEWEST', 6);
    expect(served.results.map((b) => b.id)).toEqual(['gutendex:stale']);

    await flushAsync();
    // The failed refresh left the stale entry in place, still served.
    expect(cache.read(key, now)?.payload).toBe(JSON.stringify(page(['gutendex:stale'])));
  });

  it('never serves a cached entry for a source that is not active', async () => {
    const other = new FakeFeaturedProvider(
      'addon:00000000000000ab' as CatalogSource,
      page(['addon:00000000000000ab:9']),
    );
    const cache = new InMemoryDiscoverCache();
    const now = 600;
    cache.put(
      featuredCacheKey('builtin:gutendex', 'NEWEST'),
      JSON.stringify(page(['gutendex:1342'])),
      500,
      FEATURED_TTL_S,
    );
    const composite = new CompositeCatalogProvider([other], {
      cache,
      nowEpochSecs: () => now,
    });

    const result = await composite.featured('NEWEST', 6);
    expect(result.results.map((b) => b.id)).toEqual(['addon:00000000000000ab:9']);
    expect(other.featuredCalls).toBe(1);
    // Still resident, but it was never served.
    expect(cache.get(featuredCacheKey('builtin:gutendex', 'NEWEST'), now)).not.toBeNull();
  });

  it('serves nothing when no active provider owns the cached source', async () => {
    const cache = new InMemoryDiscoverCache();
    cache.put(
      featuredCacheKey('builtin:gutendex', 'NEWEST'),
      JSON.stringify(page(['gutendex:1342'])),
      500,
      FEATURED_TTL_S,
    );
    const composite = new CompositeCatalogProvider([], {
      cache,
      nowEpochSecs: () => 600,
    });
    const result = await composite.featured('NEWEST', 6);
    expect(result.results).toEqual([]);
  });

  it('falls back to fresh-only get() for stores without the additive read()', async () => {
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1']));
    const store = new FreshOnlyStore();
    store.put(
      featuredCacheKey(SOURCE, 'NEWEST'),
      JSON.stringify(page(['gutendex:cached'])),
      1_000,
      FEATURED_TTL_S,
    );
    const composite = new CompositeCatalogProvider([provider], {
      cache: store,
      nowEpochSecs: () => 1_000,
    });

    const served = await composite.featured('NEWEST', 6);
    expect(served.results.map((b) => b.id)).toEqual(['gutendex:cached']);
    expect(provider.featuredCalls).toBe(0);
  });
});

describe('CompositeCatalogProvider durable detail read-through (G4)', () => {
  it('serves a detail stored only in the durable table with zero provider I/O', async () => {
    const port = new RecordingDurablePort();
    const cache = new PersistentDiscoverCache(port);
    const key = detailCacheKey(SOURCE, 'gutendex:1342');
    port.rows.set(key, {
      payload: JSON.stringify(book('gutendex:1342')),
      fetchedAt: 1_000,
      ttlS: DETAIL_TTL_S,
    });
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1']));
    const composite = new CompositeCatalogProvider([provider], {
      cache,
      nowEpochSecs: () => 1_000,
    });

    // FakeFeaturedProvider.getDetails throws: serving an id proves the durable
    // row was reachable across sessions without any provider I/O.
    const details = await composite.getDetails('gutendex:1342');
    expect(details.id).toBe('gutendex:1342');
    expect(port.reads).toEqual([key]);

    // Seeded into the mirror: the next read does not touch the durable port.
    port.reads.length = 0;
    await composite.getDetails('gutendex:1342');
    expect(port.reads).toEqual([]);
  });

  it('refetches a stale durable detail instead of serving it', async () => {
    const port = new RecordingDurablePort();
    const cache = new PersistentDiscoverCache(port);
    port.rows.set(detailCacheKey(SOURCE, 'gutendex:1342'), {
      payload: JSON.stringify(book('gutendex:1342')),
      fetchedAt: 1_000,
      ttlS: DETAIL_TTL_S,
    });
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1']));
    const composite = new CompositeCatalogProvider([provider], {
      cache,
      nowEpochSecs: () => 1_000 + DETAIL_TTL_S + 1,
    });

    await expect(composite.getDetails('gutendex:1342')).rejects.toThrow(
      /unexpected getDetails\(gutendex:1342\)/,
    );
  });
});

describe('CompositeCatalogProvider searchSource page caching', () => {
  it('caches the thematic rail page under p:v2 with PAGE_TTL_S and serves repeats', async () => {
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1342']));
    const cache = new InMemoryDiscoverCache();
    const composite = new CompositeCatalogProvider([provider], {
      cache,
      nowEpochSecs: () => 1_000,
    });

    const first = await composite.searchSource(SOURCE, 'Pride', 1);
    expect(provider.searchCalls).toBe(1);
    expect(cache.read(pageCacheKey(SOURCE, 'Pride', 1), 1_000)?.ttlS).toBe(PAGE_TTL_S);
    // Featured keys are untouched by a page read.
    expect(cache.read(featuredCacheKey(SOURCE, 'NEWEST'), 1_000)).toBeNull();

    const second = await composite.searchSource(SOURCE, 'Pride', 1);
    expect(second).toEqual(first);
    expect(provider.searchCalls).toBe(1);
  });

  it('returns an empty page, issues no I/O and caches nothing for an unknown source', async () => {
    const provider = new FakeFeaturedProvider(SOURCE, page(['gutendex:1342']));
    const cache = new InMemoryDiscoverCache();
    const composite = new CompositeCatalogProvider([provider], {
      cache,
      nowEpochSecs: () => 1_000,
    });

    const result = await composite.searchSource('builtin:openlibrary' as CatalogSource, 'pride', 1);
    expect(result).toEqual({ results: [], nexoPage: null, totalCount: 0 });
    expect(provider.searchCalls).toBe(0);
    expect(cache.size()).toBe(0);
  });
});
