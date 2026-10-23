import { describe, expect, it } from 'vitest';
import { CircuitBreaker } from '../src/breaker';
import { TtlCache } from '../src/cache';
import { createAddonClient, normalizeUrl, type ResourceTransport } from '../src/fanout';
import type { AddonManifestV3 } from '../src/manifest';
import type { AddonDescriptor } from '../src/router';

function manifest(overrides: Partial<AddonManifestV3> = {}): AddonManifestV3 {
  return {
    id: 'org.example.catalog',
    version: '3.0.0',
    name: 'Catalog',
    types: ['book'],
    catalogs: [{ type: 'book', id: 'popular', name: 'Popular' }],
    resources: [{ name: 'catalog' }, { name: 'meta' }, { name: 'stream' }],
    ...overrides,
  };
}

function addon(
  id: string,
  addedAt: number,
  overrides: Partial<AddonDescriptor> = {},
): AddonDescriptor {
  return {
    id,
    installUrl: `https://${id}.test/manifest.json`,
    addedAt,
    enabled: true,
    manifest: manifest({ id }),
    ...overrides,
  };
}

type Reply = { status: number; body: unknown; cacheControl?: string } | { error: Error };

/** No real network: every call resolves from the handler. */
function spy(handler: (url: string) => Reply) {
  const urls: string[] = [];
  const transport: ResourceTransport = (url) => {
    urls.push(url);
    const reply = handler(url);
    return 'error' in reply ? Promise.reject(reply.error) : Promise.resolve(reply);
  };
  return { transport, urls };
}

function timeoutError(): Error {
  const error = new Error('request timed out');
  error.name = 'TimeoutError';
  return error;
}

function clock(start = 1_000_000) {
  let current = start;
  return {
    now: () => current,
    advance(ms: number) {
      current += ms;
    },
  };
}

const CATALOG_BODY = {
  metas: [{ id: 'gutenberg:1342', type: 'book', name: 'Pride and Prejudice' }],
};

describe('createAddonClient caching', () => {
  it('serves a repeated catalog request from cache', async () => {
    const { transport, urls } = spy(() => ({ status: 200, body: CATALOG_BODY }));
    const client = createAddonClient({ transport, now: clock().now });
    const addons = [addon('one', 1)];

    const first = await client.searchCatalog(addons, { catalogId: 'popular' });
    const second = await client.searchCatalog(addons, { catalogId: 'popular' });

    expect(urls).toHaveLength(1);
    expect(second.items).toEqual(first.items);
  });

  it('expires a catalog entry after 300 s', async () => {
    const time = clock();
    const { transport, urls } = spy(() => ({ status: 200, body: CATALOG_BODY }));
    const client = createAddonClient({ transport, now: time.now });
    const addons = [addon('one', 1)];

    await client.searchCatalog(addons, { catalogId: 'popular' });
    time.advance(300_000);
    await client.searchCatalog(addons, { catalogId: 'popular' });

    expect(urls).toHaveLength(2);
  });

  it('never lets an upstream max-age exceed the default ttl', async () => {
    const time = clock();
    const { transport, urls } = spy(() => ({
      status: 200,
      body: CATALOG_BODY,
      cacheControl: 'public, max-age=86400',
    }));
    const client = createAddonClient({ transport, now: time.now });
    const addons = [addon('one', 1)];

    await client.searchCatalog(addons, { catalogId: 'popular' });
    time.advance(300_000);
    await client.searchCatalog(addons, { catalogId: 'popular' });

    expect(urls).toHaveLength(2);
  });

  it('honors an upstream max-age that is shorter than the default', async () => {
    const time = clock();
    const { transport, urls } = spy(() => ({
      status: 200,
      body: CATALOG_BODY,
      cacheControl: 'max-age=30',
    }));
    const client = createAddonClient({ transport, now: time.now });
    const addons = [addon('one', 1)];

    await client.searchCatalog(addons, { catalogId: 'popular' });
    time.advance(29_999);
    await client.searchCatalog(addons, { catalogId: 'popular' });
    expect(urls).toHaveLength(1);

    time.advance(1);
    await client.searchCatalog(addons, { catalogId: 'popular' });
    expect(urls).toHaveLength(2);
  });

  it('keeps catalog and stream entries in separate namespaces', async () => {
    const { transport, urls } = spy((url) =>
      url.includes('/stream/')
        ? { status: 200, body: { streams: [{ url: 'https://cdn.test/a.epub' }] } }
        : { status: 200, body: CATALOG_BODY },
    );
    const client = createAddonClient({ transport, now: clock().now });
    const addons = [addon('one', 1)];

    await client.searchCatalog(addons, { catalogId: 'popular' });
    await client.getStreams(addons, { id: 'popular' });

    expect(urls).toHaveLength(2);
  });
});

describe('createAddonClient isolation', () => {
  it('keeps results from healthy addons when one times out', async () => {
    const { transport } = spy((url) =>
      url.includes('slow.test') ? { error: timeoutError() } : { status: 200, body: CATALOG_BODY },
    );
    const client = createAddonClient({ transport });

    const result = await client.searchCatalog([addon('healthy', 1), addon('slow', 2)], {
      catalogId: 'popular',
    });

    expect(result.items.map((item) => item.addonId)).toEqual(['healthy']);
    expect(result.failures).toEqual([{ addonId: 'slow', reason: 'timeout' }]);
    expect(result.skipped).toBe(0);
  });

  it('reports a non-2xx response as a status failure', async () => {
    const { transport } = spy(() => ({ status: 503, body: {} }));
    const client = createAddonClient({ transport });

    const result = await client.searchCatalog([addon('broken', 1)], { catalogId: 'popular' });

    expect(result.items).toEqual([]);
    expect(result.failures).toEqual([{ addonId: 'broken', reason: 'status' }]);
  });

  it('reports malformed JSON as a payload failure', async () => {
    const { transport } = spy(() => ({
      error: new SyntaxError('Unexpected token < in JSON at position 0'),
    }));
    const client = createAddonClient({ transport });

    const result = await client.searchCatalog([addon('html', 1)], { catalogId: 'popular' });

    expect(result.failures).toEqual([{ addonId: 'html', reason: 'payload' }]);
  });

  it('reports an oversized body as a payload failure', async () => {
    const body = { metas: [{ id: 'gutenberg:1', type: 'book', name: 'n'.repeat(600 * 1024) }] };
    const { transport } = spy(() => ({ status: 200, body }));
    const client = createAddonClient({ transport });

    const result = await client.searchCatalog([addon('fat', 1)], { catalogId: 'popular' });

    expect(result.items).toEqual([]);
    expect(result.failures).toEqual([{ addonId: 'fat', reason: 'payload' }]);
  });

  it('reports an unknown transport error as a network failure', async () => {
    const { transport } = spy(() => ({ error: new TypeError('fetch failed') }));
    const client = createAddonClient({ transport });

    const result = await client.searchCatalog([addon('offline', 1)], { catalogId: 'popular' });

    expect(result.failures).toEqual([{ addonId: 'offline', reason: 'network' }]);
  });
});

describe('createAddonClient circuit breaker', () => {
  it('skips an open-circuit addon without a failure entry', async () => {
    const breaker = new CircuitBreaker({ now: clock().now });
    for (let i = 0; i < 3; i += 1) breaker.recordFailure('dead');

    const { transport, urls } = spy(() => ({ status: 200, body: CATALOG_BODY }));
    const client = createAddonClient({ transport, breaker });

    const result = await client.searchCatalog([addon('dead', 1), addon('healthy', 2)], {
      catalogId: 'popular',
    });

    expect(urls).toHaveLength(1);
    expect(urls[0]).toContain('healthy.test');
    expect(result.skipped).toBe(1);
    expect(result.failures).toEqual([]);
    expect(result.items.map((item) => item.addonId)).toEqual(['healthy']);
  });

  it('opens the circuit after three failed fan-outs', async () => {
    const breaker = new CircuitBreaker({ now: clock().now });
    const { transport, urls } = spy(() => ({ status: 500, body: {} }));
    const client = createAddonClient({ transport, breaker });

    for (let i = 0; i < 3; i += 1) {
      await client.searchCatalog([addon('dead', 1)], { catalogId: 'popular' });
    }
    const result = await client.searchCatalog([addon('dead', 1)], { catalogId: 'popular' });

    expect(urls).toHaveLength(3);
    expect(result.skipped).toBe(1);
    expect(result.failures).toEqual([]);
  });

  it('closes the circuit again after a successful probe', async () => {
    const time = clock();
    const breaker = new CircuitBreaker({ now: time.now });
    let healthy = false;
    const { transport, urls } = spy(() =>
      healthy ? { status: 200, body: CATALOG_BODY } : { status: 500, body: {} },
    );
    const client = createAddonClient({ transport, breaker });

    for (let i = 0; i < 3; i += 1) {
      await client.searchCatalog([addon('flaky', 1)], { catalogId: 'popular' });
    }
    healthy = true;
    time.advance(300_000);

    const probe = await client.searchCatalog([addon('flaky', 1)], { catalogId: 'popular' });
    expect(probe.failures).toEqual([]);
    expect(breaker.canRequest('flaky')).toBe(true);

    const after = await client.searchCatalog([addon('flaky', 1)], { catalogId: 'popular' });
    expect(after.items).toHaveLength(1);
    // Three failures, one probe: the probe payload is cached, so the next call
    // is served without touching the transport.
    expect(urls).toHaveLength(4);
  });
});

describe('createAddonClient dedupe and extras', () => {
  it('dedupes by normalized url keeping the first addon', async () => {
    const { transport, urls } = spy(() => ({ status: 200, body: CATALOG_BODY }));
    const client = createAddonClient({ transport });
    const addons = [
      addon('first', 1, { installUrl: 'https://shared.test/addons/manifest.json' }),
      addon('second', 2, { installUrl: 'https://SHARED.test/addons/' }),
    ];

    const result = await client.searchCatalog(addons, { catalogId: 'popular' });

    expect(urls).toHaveLength(1);
    expect(result.items.map((item) => item.addonId)).toEqual(['first']);
    expect(result.failures).toEqual([]);
  });

  it('normalizes scheme and host case, a trailing slash and the fragment', () => {
    expect(normalizeUrl('https://Example.TEST/addons/')).toBe(
      normalizeUrl('https://example.test/addons#section'),
    );
    expect(normalizeUrl('https://example.test/addons/?q=1')).toBe(
      normalizeUrl('https://example.test/addons?q=1'),
    );
  });

  it('sends only extra names the catalog declares', async () => {
    const { transport, urls } = spy(() => ({ status: 200, body: CATALOG_BODY }));
    const client = createAddonClient({ transport });
    const addons = [
      addon('one', 1, {
        manifest: manifest({
          catalogs: [
            {
              type: 'book',
              id: 'search',
              name: 'Search',
              extra: [{ name: 'search' }, { name: 'skip' }],
            },
          ],
        }),
      }),
    ];

    await client.searchCatalog(addons, {
      catalogId: 'search',
      extra: { search: 'dune', genre: 'scifi', skip: 0 },
    });

    expect(urls[0]).toContain('search=dune');
    expect(urls[0]).toContain('skip=0');
    expect(urls[0]).not.toContain('genre');
  });
});

describe('createAddonClient stamping and ordering', () => {
  it('reads a meta payload and stamps the addon', async () => {
    const { transport } = spy(() => ({
      status: 200,
      body: { meta: { id: 'isbn:9780141439518', type: 'book', name: 'Emma' } },
    }));
    const client = createAddonClient({ transport });

    const result = await client.getMeta([addon('meta', 7)], { id: 'isbn:9780141439518' });

    expect(result.items).toHaveLength(1);
    expect(result.items[0]?.name).toBe('Emma');
    expect(result.items[0]?.addonId).toBe('meta');
    expect(result.items[0]?.addedAt).toBe(7);
  });

  it('stamps addonId and addedAt on every stream and orders across addons', async () => {
    const { transport } = spy((url) =>
      url.includes('late.test')
        ? {
            status: 200,
            body: {
              streams: [
                { url: 'https://cdn.test/free.epub', behaviorHints: { accessType: 'public-domain' } },
              ],
            },
          }
        : {
            status: 200,
            body: {
              streams: [
                { url: 'https://cdn.test/borrowed.epub', behaviorHints: { accessType: 'borrow' } },
                { infoHash: 'deadbeef' },
              ],
            },
          },
    );
    const client = createAddonClient({ transport });

    const result = await client.getStreams([addon('early', 1), addon('late', 2)], {
      id: 'gutenberg:1342',
    });

    expect(result.items.map((stream) => stream.addonId)).toEqual(['late', 'early']);
    expect(result.items.map((stream) => stream.addedAt)).toEqual([2, 1]);
    // The torrent entry is dropped by the payload guard, not counted as a failure.
    expect(result.failures).toEqual([]);
  });
});
