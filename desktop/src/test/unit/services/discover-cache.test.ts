import { describe, expect, it } from 'vitest';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  DETAIL_TTL_S,
  FEATURED_TTL_S,
  InMemoryDiscoverCache,
  PAGE_TTL_S,
  PersistentDiscoverCache,
  detailCacheKey,
  featuredCacheKey,
  pageCacheKey,
  type DurableDiscoverCachePort,
} from '$lib/shared/services/catalog/DiscoverCache';
import {
  GutendexCatalogProvider,
  OpenLibraryCatalogProvider,
} from '$lib/shared/services/catalog/BuiltInCatalogProviders';
import { GutendexDataSource } from '$lib/shared/services/catalog/GutendexDataSource';
import { OpenLibraryDataSource } from '$lib/shared/services/catalog/OpenLibraryDataSource';
import gutendexFixture from '$lib/shared/services/catalog/fixtures/gutendex-search.json';
import openLibraryFixture from '$lib/shared/services/catalog/fixtures/openlibrary-search.json';

function stubFetch(handler: (url: string) => { status: number; body: unknown }): typeof fetch {
  return (async (input: unknown) => {
    const { status, body } = handler(String(input));
    return new Response(JSON.stringify(body), { status });
  }) as typeof fetch;
}

function stubbedSources(calls: { g: number; o: number }): {
  g: GutendexDataSource;
  o: OpenLibraryDataSource;
} {
  const g = new GutendexDataSource(
    stubFetch(() => {
      calls.g += 1;
      return { status: 200, body: gutendexFixture };
    }),
  );
  const o = new OpenLibraryDataSource(
    stubFetch(() => {
      calls.o += 1;
      return { status: 200, body: openLibraryFixture };
    }),
  );
  return { g, o };
}

describe('DiscoverCache keys and TTL', () => {
  it('uses p:v2:{sourceId}:{query}:{page} page keys with 24h TTL', () => {
    expect(pageCacheKey('builtin:gutendex', 'Pride ', 1)).toBe('p:v2:builtin:gutendex:pride:1');
    expect(PAGE_TTL_S).toBe(86_400);
  });

  it('uses d:v2:{sourceId}:{id} detail keys with 7d TTL', () => {
    expect(detailCacheKey('builtin:gutendex', 'gutendex:1342')).toBe(
      'd:v2:builtin:gutendex:gutendex:1342',
    );
    expect(DETAIL_TTL_S).toBe(604_800);
  });

  it('returns hits within TTL and evicts expired rows eagerly', () => {
    const cache = new InMemoryDiscoverCache();
    cache.put('p:composite:pride:1', '{"n":1}', 1_000, PAGE_TTL_S);
    expect(cache.get('p:composite:pride:1', 1_000 + 3_600)).toBe('{"n":1}');
    expect(cache.get('p:composite:pride:1', 1_000 + PAGE_TTL_S + 1)).toBeNull();
    expect(cache.size()).toBe(0);
  });

  it('misses on unknown keys without writes', () => {
    const cache = new InMemoryDiscoverCache();
    expect(cache.get('p:composite:missing:1', 1_000)).toBeNull();
    expect(cache.size()).toBe(0);
  });

  it('overwrites existing keys on re-put', () => {
    const cache = new InMemoryDiscoverCache();
    cache.put('p:composite:pride:1', '{"n":1}', 1_000, PAGE_TTL_S);
    cache.put('p:composite:pride:1', '{"n":2}', 2_000, PAGE_TTL_S);
    expect(cache.get('p:composite:pride:1', 2_001)).toBe('{"n":2}');
  });
});

describe('CompositeCatalogProvider cache read-through', () => {
  it('serves a repeated search from cache without network I/O', async () => {
    const calls = { g: 0, o: 0 };
    const { g, o } = stubbedSources(calls);
    const cache = new InMemoryDiscoverCache();
    const provider = new CompositeCatalogProvider(
      [new GutendexCatalogProvider(g), new OpenLibraryCatalogProvider(o)],
      {
        debounceMs: 0,
        cache,
        nowEpochSecs: () => 1_000,
      },
    );
    const first = await provider.search('pride', 1);
    expect(calls).toEqual({ g: 1, o: 0 });
    const second = await provider.search('pride', 1);
    expect(second).toEqual(first);
    expect(calls).toEqual({ g: 1, o: 0 });
  });

  it('refetches expired pages and replaces the entry', async () => {
    const calls = { g: 0, o: 0 };
    const { g, o } = stubbedSources(calls);
    const cache = new InMemoryDiscoverCache();
    let now = 1_000;
    const provider = new CompositeCatalogProvider(
      [new GutendexCatalogProvider(g), new OpenLibraryCatalogProvider(o)],
      {
        debounceMs: 0,
        cache,
        nowEpochSecs: () => now,
      },
    );
    await provider.search('pride', 1);
    now += PAGE_TTL_S + 1;
    await provider.search('pride', 1);
    expect(calls).toEqual({ g: 2, o: 0 });
  });

  it('caches details 7d and keeps the pure download path I/O-free', async () => {
    const detailFetch = stubFetch((url) =>
      url.endsWith('/books/1342/')
        ? { status: 200, body: gutendexFixture.results[0] }
        : { status: 404, body: {} },
    );
    const calls = { g: 0, o: 0 };
    const counting: typeof fetch = (async (...args: Parameters<typeof fetch>) => {
      calls.g += 1;
      return detailFetch(...args);
    }) as typeof fetch;
    const cache = new InMemoryDiscoverCache();
    let now = 5_000;
    const provider = new CompositeCatalogProvider(
      [
        new GutendexCatalogProvider(new GutendexDataSource(counting)),
        new OpenLibraryCatalogProvider(stubbedSources(calls).o),
      ],
      {
        debounceMs: 0,
        cache,
        nowEpochSecs: () => now,
      },
    );
    const first = await provider.getDetails('gutendex:1342');
    expect(first.id).toBe('gutendex:1342');
    now += 3_600;
    await provider.getDetails('gutendex:1342');
    expect(calls.g).toBe(1);
    // Pure path: no I/O, no cache interaction.
    expect(provider.resolveDownloadUrl({ 'text/plain': 'https://example.com/b.txt' }, true)).toBe(
      'https://example.com/b.txt',
    );
    expect(calls.g).toBe(1);
  });

  it('merges Gutenberg cover identically on cache miss and hit', async () => {
    const calls = { g: 0, o: 0 };
    const { g, o } = stubbedSources(calls);
    const provider = new CompositeCatalogProvider(
      [new GutendexCatalogProvider(g), new OpenLibraryCatalogProvider(o)],
      {
        debounceMs: 0,
        cache: new InMemoryDiscoverCache(),
        nowEpochSecs: () => 1_000,
      },
    );
    const miss = await provider.search('pride', 1);
    const hit = await provider.search('pride', 1);
    const prideMiss = miss.results.find((b) => b.id === 'gutendex:1342');
    const prideHit = hit.results.find((b) => b.id === 'gutendex:1342');
    expect(prideMiss?.coverUrl).toBe(
      'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
    );
    expect(prideHit).toEqual(prideMiss);
  });
});

/** Recording durable port: proves the key scheme and preload bounds. */
class FakeDurablePort implements DurableDiscoverCachePort {
  readonly reads: string[] = [];
  readonly writes: { key: string; payload: string; fetchedAt: number; ttlS: number }[] = [];
  readonly rows = new Map<string, { payload: string; fetchedAt: number; ttlS: number }>();
  failReads = false;
  failWrites = false;

  async read(key: string): Promise<{ payload: string; fetchedAt: number; ttlS: number } | null> {
    this.reads.push(key);
    if (this.failReads) throw new Error('durable read unavailable');
    return this.rows.get(key) ?? null;
  }

  async write(key: string, payload: string, fetchedAt: number, ttlS: number): Promise<void> {
    if (this.failWrites) throw new Error('durable write unavailable');
    this.writes.push({ key, payload, fetchedAt, ttlS });
    this.rows.set(key, { payload, fetchedAt, ttlS });
  }
}

async function flushAsync(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('DiscoverCache featured keys and 6h TTL', () => {
  it('uses f:v2:{sourceId}:{sort} featured keys with FEATURED_TTL_S = 21600', () => {
    expect(featuredCacheKey('builtin:gutendex', 'NEWEST')).toBe('f:v2:builtin:gutendex:NEWEST');
    expect(featuredCacheKey('builtin:openlibrary', 'POPULAR')).toBe(
      'f:v2:builtin:openlibrary:POPULAR',
    );
    expect(FEATURED_TTL_S).toBe(21_600);
    expect(FEATURED_TTL_S).toBe(6 * 60 * 60);
  });

  it('read() returns fresh then stale while get() keeps fresh-only eviction', () => {
    const cache = new InMemoryDiscoverCache();
    const key = featuredCacheKey('builtin:gutendex', 'NEWEST');
    cache.put(key, '{"n":1}', 1_000, FEATURED_TTL_S);

    const fresh = cache.read(key, 1_000 + 3_600);
    expect(fresh).toEqual({
      payload: '{"n":1}',
      fetchedAt: 1_000,
      ttlS: FEATURED_TTL_S,
      stale: false,
    });
    expect(cache.get(key, 1_000 + 3_600)).toBe('{"n":1}');

    // Seven hours on: stale through read(), but still resident (read never evicts).
    const stale = cache.read(key, 1_000 + 7 * 3_600);
    expect(stale?.stale).toBe(true);
    expect(stale?.payload).toBe('{"n":1}');
    expect(cache.size()).toBe(1);

    // get() keeps its eager eviction: miss AND the row is gone.
    expect(cache.get(key, 1_000 + FEATURED_TTL_S + 1)).toBeNull();
    expect(cache.size()).toBe(0);
  });

  it('read() misses unknown keys without writes', () => {
    const cache = new InMemoryDiscoverCache();
    expect(cache.read('f:v2:missing:NEWEST', 1_000)).toBeNull();
    expect(cache.size()).toBe(0);
  });
});

describe('PersistentDiscoverCache durable port', () => {
  it('mirror is authoritative within a session and put writes it synchronously', () => {
    const port = new FakeDurablePort();
    const cache = new PersistentDiscoverCache(port);
    const key = featuredCacheKey('builtin:gutendex', 'NEWEST');

    cache.put(key, '{"n":1}', 1_000, FEATURED_TTL_S);
    // No await: the mirror read path is synchronous.
    expect(cache.get(key, 1_000)).toBe('{"n":1}');
    expect(cache.read(key, 1_000)?.payload).toBe('{"n":1}');
    expect(cache.size()).toBe(1);
  });

  it('write-through uses the same featured key scheme asynchronously', async () => {
    const port = new FakeDurablePort();
    const cache = new PersistentDiscoverCache(port);
    const key = featuredCacheKey('builtin:openlibrary', 'POPULAR');

    cache.put(key, '{"n":9}', 5_000, FEATURED_TTL_S);
    await flushAsync();

    expect(port.writes).toEqual([
      {
        key: 'f:v2:builtin:openlibrary:POPULAR',
        payload: '{"n":9}',
        fetchedAt: 5_000,
        ttlS: 21_600,
      },
    ]);
    expect(port.rows.get(key)?.payload).toBe('{"n":9}');
  });

  it('swallows a failing durable write so caching never fails a rail', async () => {
    const port = new FakeDurablePort();
    port.failWrites = true;
    const cache = new PersistentDiscoverCache(port);
    const key = featuredCacheKey('builtin:gutendex', 'POPULAR');

    expect(() => cache.put(key, '{"n":1}', 1_000, FEATURED_TTL_S)).not.toThrow();
    await flushAsync();
    expect(cache.get(key, 1_000)).toBe('{"n":1}');
  });

  it('preload seeds the mirror from the durable table, bounded to featured keys', async () => {
    const port = new FakeDurablePort();
    const key = featuredCacheKey('builtin:gutendex', 'NEWEST');
    port.rows.set(key, { payload: '{"n":1}', fetchedAt: 1_000, ttlS: FEATURED_TTL_S });
    const cache = new PersistentDiscoverCache(port);

    await cache.preload(['builtin:gutendex']);

    // Exactly one featured read per known sort, nothing else.
    expect(port.reads.sort()).toEqual([
      'f:v2:builtin:gutendex:NEWEST',
      'f:v2:builtin:gutendex:POPULAR',
    ]);
    expect(cache.read(key, 1_000)?.payload).toBe('{"n":1}');

    // Two sources → four bounded reads, still no page/detail keys.
    port.reads.length = 0;
    await cache.preload(['builtin:gutendex', 'builtin:openlibrary']);
    expect(port.reads).toHaveLength(4);
    expect(port.reads.every((read) => read.startsWith('f:v2:'))).toBe(true);
    expect(port.reads.some((read) => read.startsWith('p:') || read.startsWith('d:'))).toBe(false);
  });

  it('preloads explicit bounded extra keys alongside the featured keys', async () => {
    const port = new FakeDurablePort();
    const thematicKey = pageCacheKey('builtin:gutendex', 'fiction', 1);
    port.rows.set(thematicKey, { payload: '{"n":7}', fetchedAt: 1_000, ttlS: PAGE_TTL_S });
    const cache = new PersistentDiscoverCache(port);

    await cache.preload(['builtin:gutendex'], [thematicKey]);

    expect(port.reads.sort()).toEqual(
      ['f:v2:builtin:gutendex:NEWEST', 'f:v2:builtin:gutendex:POPULAR', thematicKey].sort(),
    );
    // Exactly the requested extra key: no arbitrary page keys get enumerated.
    expect(port.reads).toHaveLength(3);
    expect(cache.get(thematicKey, 1_000)).toBe('{"n":7}');

    // Duplicate extra keys collapse back into one read (bounded).
    port.reads.length = 0;
    await cache.preload(['builtin:gutendex'], [thematicKey, thematicKey]);
    expect(port.reads).toHaveLength(3);
  });

  it('a failing preload read degrades to an empty mirror, never an error', async () => {
    const port = new FakeDurablePort();
    port.failReads = true;
    const cache = new PersistentDiscoverCache(port);
    await expect(cache.preload(['builtin:gutendex'])).resolves.toBeUndefined();
    expect(cache.size()).toBe(0);
  });
});
