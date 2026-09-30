import { describe, expect, it } from 'vitest';

import { AddonCatalogProvider } from '$lib/shared/services/addons/AddonCatalogProvider';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { CatalogError } from '$lib/shared/services/catalog/errors';
import { GutendexCatalogProvider } from '$lib/shared/services/catalog/BuiltInCatalogProviders';
import { GutendexDataSource } from '$lib/shared/services/catalog/GutendexDataSource';
import gutendexFixture from '$lib/shared/services/catalog/fixtures/gutendex-search.json';
import type { GutendexRecord } from '$lib/shared/services/catalog/mappers';
import { addonIdFromUrl } from '$lib/shared/services/addons/addonId';
import type { AddonManifest } from '@nexo/manifest-validator';
import type { AddonTransport } from '$lib/shared/services/addons/AddonRegistry';

const ADDON_ID = 'a1b2c3d4e5f60718';

const SPACE_MANIFEST: AddonManifest = {
  id: 'space-books',
  name: 'Space Books',
  version: '1.0.0',
  catalogs: [{ type: 'book', id: 'main', name: 'Main catalog' }],
  resources: ['catalog'],
  searchUrl: 'https://space.example/search?q={query}&page={page}',
  detailsUrl: 'https://space.example/book/{bookId}',
};

function fakeTransport(
  handler: (url: string) => { status?: number; contentType?: string | null; body: Uint8Array },
): AddonTransport & { calls: string[] } {
  const calls: string[] = [];
  return Object.assign(
    async (url: string) => {
      calls.push(url);
      return { status: 200, contentType: 'application/json', ...handler(url) };
    },
    { calls },
  );
}

function jsonBytes(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

const SPACE_PAYLOAD = {
  results: [
    {
      id: 'book-1',
      title: 'Dune',
      authors: ['Herbert'],
      coverUrl: null,
      languages: ['en'],
      subjects: ['sci-fi'],
      downloadUrl: null,
    },
  ],
  totalCount: 1,
};

/** Transport that answers every addon URL from a payload map (by host). */
function payloadTransport(
  payloads: Record<string, unknown>,
  oversize = false,
): AddonTransport & { calls: string[] } {
  return fakeTransport((url) => {
    const host = new URL(url).host;
    if (!(host in payloads)) throw new Error(`unexpected addon fetch ${url}`);
    if (oversize) return { body: new Uint8Array(65 * 1024) };
    return { body: jsonBytes(payloads[host]) };
  });
}

function errOf(p: Promise<unknown>): Promise<CatalogError> {
  return p.catch((e: unknown) => e as CatalogError) as Promise<CatalogError>;
}

describe('AddonCatalogProvider payload flow', () => {
  it('search renders the searchUrl template and maps payload books to addon source ids', async () => {
    const transport = fakeTransport(() => ({ body: jsonBytes(SPACE_PAYLOAD) }));
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport);
    const page = await provider.search('dune mess iah', 1);
    expect(transport.calls).toEqual(['https://space.example/search?q=dune%20mess%20iah&page=1']);
    expect(page.results).toHaveLength(1);
    expect(page.results[0]).toMatchObject({
      id: `addon:${ADDON_ID}:book-1`,
      provider: `addon:${ADDON_ID}`,
      title: 'Dune',
      authors: ['Herbert'],
      languages: ['en'],
      subjects: ['sci-fi'],
    });
    expect(page.totalCount).toBe(1);
    expect(page.nextPage).toBeNull();
  });

  it('clamps results to the shared page size and computes nextPage from totalCount', async () => {
    const many = Array.from({ length: 40 }, (_, i) => ({ id: `b${i}`, title: `Book ${i}` }));
    const transport = fakeTransport(() => ({
      body: jsonBytes({ results: many, totalCount: 100 }),
    }));
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport);
    const page = await provider.search('x', 1);
    expect(page.results).toHaveLength(32);
    expect(page.totalCount).toBe(100);
    expect(page.nextPage).toBe(2);
  });

  it('order test (addon-provider R2.1): built-in results come first, then addons A, B, C in install order', async () => {
    const hosts = ['a.example', 'b.example', 'c.example'];
    const payloads: Record<string, unknown> = {};
    const manifests: AddonManifest[] = [];
    const ids: string[] = [];
    for (const host of hosts) {
      const addonId = await addonIdFromUrl(`https://${host}/m.json`);
      payloads[host] = { results: [{ id: 'only', title: `${host} Book` }], totalCount: 1 };
      manifests.push({
        ...SPACE_MANIFEST,
        searchUrl: `https://${host}/search?q={query}&page={page}`,
      });
      ids.push(addonId);
    }
    const transport = payloadTransport(payloads);
    const addonProviders = manifests.map((m, i) => new AddonCatalogProvider(m, ids[i], transport));
    const calls = { g: 0 };
    const gutendex = new GutendexCatalogProvider(
      new GutendexDataSource((async () => {
        calls.g += 1;
        return new Response(JSON.stringify(gutendexFixture), { status: 200 });
      }) as typeof fetch),
    );
    const composite = new CompositeCatalogProvider([gutendex, ...addonProviders], {
      debounceMs: 0,
    });
    const page = await composite.search('dune', 1);
    const providers = page.results.map((b) => b.provider);
    expect(providers[0]).toBe('builtin:gutendex');
    expect(providers.slice(-3)).toEqual([`addon:${ids[0]}`, `addon:${ids[1]}`, `addon:${ids[2]}`]);
    expect(calls.g).toBe(1);
    expect(transport.calls).toHaveLength(3);
  });

  it('getDetails routes to the owning addon and renders the detailsUrl (addon-provider R3.1)', async () => {
    const transport = fakeTransport((url) => {
      expect(url).toBe('https://space.example/book/book-7');
      return { body: jsonBytes({ id: 'book-7', title: 'Detail Book', authors: ['A'] }) };
    });
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport);
    const composite = new CompositeCatalogProvider([provider], { debounceMs: 0 });
    const book = await composite.getDetails(`addon:${ADDON_ID}:book-7`);
    expect(book.id).toBe(`addon:${ADDON_ID}:book-7`);
    expect(book.provider).toBe(`addon:${ADDON_ID}`);
    expect(book.title).toBe('Detail Book');
    expect(transport.calls).toHaveLength(1);
  });

  it('malformed payloads reject the whole page with a stable error (no partial page)', async () => {
    const cases: unknown[] = [
      'not json at all',
      42,
      {},
      { results: 'nope' },
      { results: [{ title: 'no id' }] },
      { results: [{ id: 'x', title: 'ok' }, { id: 'y' }] },
      { results: [{ id: 'x', title: 'ok', authors: 'not-an-array' }] },
    ];
    for (const bad of cases) {
      const transport =
        typeof bad === 'string'
          ? fakeTransport(() => ({ body: new TextEncoder().encode(bad) }))
          : fakeTransport(() => ({ body: jsonBytes(bad) }));
      const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport);
      const err = await errOf(provider.search('q', 1));
      expect(err).toBeInstanceOf(CatalogError);
      expect(err.code).toBe('UPSTREAM_ERROR');
      expect(transport.calls).toHaveLength(1);
    }
    const detailTransport = fakeTransport(() => ({ body: jsonBytes({ title: 'no id' }) }));
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, detailTransport);
    const err = await errOf(provider.getDetails(`addon:${ADDON_ID}:x`));
    expect(err.code).toBe('UPSTREAM_ERROR');
  });

  it('enforces the payload size cap before parse', async () => {
    const transport = fakeTransport(() => ({ body: new Uint8Array(65 * 1024) }));
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport);
    const err = await errOf(provider.search('q', 1));
    expect(err.code).toBe('UPSTREAM_ERROR');
  });

  it('maps non-2xx statuses to stable catalog codes after at most one retry', async () => {
    const transport = fakeTransport(() => ({ status: 429, body: new Uint8Array(0) }));
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport, 0);
    expect((await errOf(provider.search('q', 1))).code).toBe('RATE_LIMITED');
    expect(transport.calls).toHaveLength(2);

    const transport404 = fakeTransport(() => ({ status: 404, body: new Uint8Array(0) }));
    const provider404 = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport404, 0);
    expect((await errOf(provider404.getDetails(`addon:${ADDON_ID}:x`))).code).toBe('NOT_FOUND');
    expect(transport404.calls).toHaveLength(1);

    const transport500 = fakeTransport(() => ({ status: 500, body: new Uint8Array(0) }));
    const provider500 = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport500, 0);
    expect((await errOf(provider500.search('q', 1))).code).toBe('UPSTREAM_ERROR');
    expect(transport500.calls).toHaveLength(2);
  });

  it('maps transport failures to NETWORK_ERROR and retries a 429 into success once', async () => {
    let calls = 0;
    const flaky = fakeTransport(() => {
      calls += 1;
      if (calls === 1) throw new Error('boom');
      return { body: jsonBytes(SPACE_PAYLOAD) };
    });
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, flaky, 0);
    const err = await errOf(provider.search('q', 1));
    expect(err.code).toBe('NETWORK_ERROR');

    let rate = 0;
    const recover = fakeTransport(() => {
      rate += 1;
      if (rate === 1) return { status: 429, body: new Uint8Array(0) };
      return { body: jsonBytes(SPACE_PAYLOAD) };
    });
    const recovered = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, recover, 0);
    const page = await recovered.search('q', 1);
    expect(page.results[0].title).toBe('Dune');
  });

  it('endpoint-less manifests stay browse-only with zero I/O', async () => {
    const transport = fakeTransport(() => ({ body: jsonBytes(SPACE_PAYLOAD) }));
    const bare: AddonManifest = { ...SPACE_MANIFEST, searchUrl: undefined, detailsUrl: undefined };
    const provider = new AddonCatalogProvider(bare, ADDON_ID, transport);
    await expect(provider.search('q', 1)).resolves.toEqual({
      results: [],
      nextPage: null,
      totalCount: 0,
    });
    await expect(provider.getDetails(`addon:${ADDON_ID}:x`)).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
    expect(transport.calls).toHaveLength(0);
  });

  it('rejects ids for other addons with NOT_FOUND and no I/O', async () => {
    const transport = fakeTransport(() => ({ body: jsonBytes(SPACE_PAYLOAD) }));
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport);
    await expect(provider.getDetails('addon:0123456789abcdef:x')).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
    expect(transport.calls).toHaveLength(0);
  });
});

describe('gutendex fixture sanity', () => {
  it('fixture has records for the ordering test', () => {
    const records = gutendexFixture.results as unknown as GutendexRecord[];
    expect(records.length).toBeGreaterThan(0);
  });
});
