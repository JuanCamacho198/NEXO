/**
 * Catalog fan-out over installed v3 addons.
 *
 * One addon failing must never fail the search: healthy addons still render,
 * and the per-addon failure is reported through the non-blocking notice sink.
 * Book ids keep the addon's namespaced form (`gutenberg:1342`) because that is
 * what `meta` and `stream` routing depends on.
 */
import { describe, expect, it, vi } from 'vitest';

import {
  createAddonClient,
  type AddonClient,
  type AddonDescriptor,
  type AddonManifestV3,
  type ResourceTransport,
} from '@nexo/addon-protocol';
import { AddonFanoutCatalogProvider } from '$lib/shared/services/addons/AddonCatalogProvider';
import { addonSource, type CatalogSource } from '$lib/shared/services/catalog/CatalogProvider';

const ADDON_A = 'aaaaaaaaaaaaaaaa';
const ADDON_B = 'bbbbbbbbbbbbbbbb';
const ADDON_STREAM_ONLY = 'cccccccccccccccc';
const ADDON_DISABLED = 'dddddddddddddddd';

function catalogManifest(id: string, catalogId: string, name: string): AddonManifestV3 {
  return {
    id,
    version: '3.0.0',
    name,
    types: ['book'],
    catalogs: [
      {
        type: 'book',
        id: catalogId,
        name,
        extra: [{ name: 'search' }, { name: 'skip' }],
      },
    ],
    resources: [{ name: 'catalog' }, { name: 'meta', types: ['book'] }],
  };
}

function streamOnlyManifest(id: string): AddonManifestV3 {
  return {
    id,
    version: '3.0.0',
    name: 'Stream only',
    types: ['book'],
    catalogs: [],
    resources: [{ name: 'stream', types: ['book'], idPrefixes: ['gutenberg:'] }],
  };
}

function descriptor(
  addonId: string,
  installUrl: string,
  manifest: AddonManifestV3,
  options: { addedAt: number; enabled?: boolean },
): AddonDescriptor {
  return {
    id: addonId,
    installUrl,
    addedAt: options.addedAt,
    enabled: options.enabled ?? true,
    manifest,
  };
}

const ADDON_A_DESCRIPTOR = descriptor(
  ADDON_A,
  'https://a.example/manifest.json',
  catalogManifest('org.example.a', 'popular', 'Popular'),
  { addedAt: 1 },
);
const ADDON_B_DESCRIPTOR = descriptor(
  ADDON_B,
  'https://b.example/addons/b/manifest.json',
  catalogManifest('org.example.b', 'all', 'Everything'),
  { addedAt: 2 },
);
const STREAM_ONLY_DESCRIPTOR = descriptor(
  ADDON_STREAM_ONLY,
  'https://streams.example/manifest.json',
  streamOnlyManifest('org.example.streams'),
  { addedAt: 3 },
);
const DISABLED_DESCRIPTOR = descriptor(
  ADDON_DISABLED,
  'https://off.example/manifest.json',
  catalogManifest('org.example.off', 'main', 'Off'),
  { addedAt: 4, enabled: false },
);

const GUTENBERG_META = {
  id: 'gutenberg:1342',
  type: 'book',
  name: 'Moby Dick',
  poster: 'https://covers.example/moby.jpg',
  description: 'A whale, an obsession.',
  authors: ['Herman Melville'],
  languages: ['en'],
  subjects: ['sea'],
};

const OPENLIBRARY_META = {
  id: 'openlibrary:OL1W',
  type: 'book',
  name: 'Dune',
  poster: 'https://covers.example/dune.jpg',
  description: 'Spice and dynasties.',
  authors: ['Frank Herbert'],
  languages: ['en'],
  subjects: ['desert'],
};

type Handler = (url: string) => { status: number; body?: unknown };

function scriptedClient(handler: Handler): { client: AddonClient; urls: string[] } {
  const urls: string[] = [];
  const transport: ResourceTransport = async (url) => {
    urls.push(url);
    const response = handler(url);
    if (response.status < 200 || response.status > 299) {
      return { status: response.status, body: undefined };
    }
    return { status: response.status, body: response.body };
  };
  return { client: createAddonClient({ transport }), urls };
}

/** Two healthy addons: A answers `popular`, B answers its own `all` catalog. */
function twoAddonHandler(url: string): { status: number; body?: unknown } {
  if (url.includes('/catalog/book/popular'))
    return { status: 200, body: { metas: [GUTENBERG_META] } };
  if (url.includes('/catalog/book/all'))
    return { status: 200, body: { metas: [OPENLIBRARY_META] } };
  return { status: 404 };
}

function providerWith(
  addons: AddonDescriptor[],
  client: AddonClient,
  onFailure?: (failures: readonly { addonId: string; reason: string }[]) => void,
): AddonFanoutCatalogProvider {
  return new AddonFanoutCatalogProvider({
    loadAddons: async () => addons,
    clientFor: () => client,
    onFailure,
  });
}

describe('AddonFanoutCatalogProvider.search', () => {
  it('unions results from every selected addon in install order', async () => {
    const { client, urls } = scriptedClient(twoAddonHandler);
    const provider = providerWith([ADDON_A_DESCRIPTOR, ADDON_B_DESCRIPTOR], client);

    const page = await provider.search('whale', 1);

    expect(page.results.map((book) => book.id)).toEqual(['gutenberg:1342', 'openlibrary:OL1W']);
    expect(page.results.map((book) => book.provider)).toEqual([
      addonSource(ADDON_A),
      addonSource(ADDON_B),
    ]);
    expect(urls).toEqual([
      'https://a.example/catalog/book/popular/search=whale.json',
      'https://b.example/addons/b/catalog/book/all/search=whale.json',
    ]);
    expect(page.nexoPage).toBeNull();
    expect(page.totalCount).toBe(2);
  });

  it('maps MetaPreview onto CatalogBook and keeps the namespaced id', async () => {
    const { client } = scriptedClient(twoAddonHandler);
    const provider = providerWith([ADDON_A_DESCRIPTOR], client);

    const [book] = (await provider.search('whale', 1)).results;

    expect(book).toMatchObject({
      id: 'gutenberg:1342',
      provider: `addon:${ADDON_A}`,
      title: 'Moby Dick',
      coverUrl: 'https://covers.example/moby.jpg',
      description: 'A whale, an obsession.',
      authors: ['Herman Melville'],
      languages: ['en'],
      subjects: ['sea'],
      downloadUrl: null,
    });
  });

  it('keeps healthy results and reports a failing addon without throwing', async () => {
    const onFailure = vi.fn();
    const { client } = scriptedClient((url) =>
      url.includes('/catalog/book/popular') ? twoAddonHandler(url) : { status: 503 },
    );
    const provider = providerWith([ADDON_A_DESCRIPTOR, ADDON_B_DESCRIPTOR], client, onFailure);

    const page = await provider.search('whale', 1);

    expect(page.results.map((book) => book.id)).toEqual(['gutenberg:1342']);
    expect(onFailure).toHaveBeenCalledTimes(1);
    expect(onFailure).toHaveBeenCalledWith([{ addonId: ADDON_B, reason: 'status' }]);
  });

  it('reports a network failure per addon and still renders the rest', async () => {
    const onFailure = vi.fn();
    const transport: ResourceTransport = async (url) => {
      if (url.includes('b.example')) throw new Error('socket hang up');
      return { status: 200, body: { metas: [GUTENBERG_META] } };
    };
    const provider = providerWith(
      [ADDON_A_DESCRIPTOR, ADDON_B_DESCRIPTOR],
      createAddonClient({ transport }),
      onFailure,
    );

    const page = await provider.search('whale', 1);

    expect(page.results).toHaveLength(1);
    expect(onFailure).toHaveBeenCalledWith([{ addonId: ADDON_B, reason: 'network' }]);
  });

  it('leaves a legacy-v2 row to the v2 adapter instead of requesting it twice', async () => {
    const { client, urls } = scriptedClient(twoAddonHandler);
    const adapted = descriptor(
      ADDON_STREAM_ONLY,
      'https://legacy.example/manifest.json',
      {
        ...catalogManifest('org.example.legacy', 'main', 'Legacy'),
        nexo: {
          shape: 'legacy-v2',
          templates: { catalog: 'https://legacy.example/books?q={search}&page={skip}' },
        },
      },
      { addedAt: 3 },
    );
    const provider = providerWith([adapted], client);

    const page = await provider.search('whale', 1);

    expect(page.results).toEqual([]);
    expect(urls).toEqual([]);
  });

  it('never requests an addon that declares no catalog, nor a disabled one', async () => {
    const { client, urls } = scriptedClient(twoAddonHandler);
    const provider = providerWith(
      [ADDON_A_DESCRIPTOR, STREAM_ONLY_DESCRIPTOR, DISABLED_DESCRIPTOR],
      client,
    );

    await provider.search('whale', 1);

    expect(urls).toHaveLength(1);
    expect(urls[0]).toContain('a.example');
  });

  it('lists every enabled addon as a source, including the stream-only one', async () => {
    const { client } = scriptedClient(twoAddonHandler);
    const provider = providerWith(
      [ADDON_A_DESCRIPTOR, ADDON_B_DESCRIPTOR, STREAM_ONLY_DESCRIPTOR, DISABLED_DESCRIPTOR],
      client,
    );
    await provider.ready();

    expect(provider.listSources().map((source) => source.sourceId)).toEqual([
      addonSource(ADDON_A),
      addonSource(ADDON_B),
      addonSource(ADDON_STREAM_ONLY),
    ]);
    expect(provider.listSources()[0]).toMatchObject({ name: 'Popular', kind: 'addon' });
  });

  it('pages the request with the declared skip argument', async () => {
    const { client, urls } = scriptedClient(twoAddonHandler);
    const provider = providerWith([ADDON_A_DESCRIPTOR], client);

    await provider.search('whale', 2);

    expect(urls[0]).toBe('https://a.example/catalog/book/popular/search=whale&skip=32.json');
  });

  it('rejects an invalid page before any I/O', async () => {
    const { client, urls } = scriptedClient(twoAddonHandler);
    const provider = providerWith([ADDON_A_DESCRIPTOR], client);

    await expect(provider.search('whale', 0)).rejects.toMatchObject({ code: 'INVALID_PAGE' });
    expect(urls).toEqual([]);
  });
});

describe('AddonFanoutCatalogProvider.searchSource', () => {
  it('routes the source filter to the owning addon only', async () => {
    const { client, urls } = scriptedClient(twoAddonHandler);
    const provider = providerWith([ADDON_A_DESCRIPTOR, ADDON_B_DESCRIPTOR], client);

    const page = await provider.searchSource(addonSource(ADDON_B), 'whale', 1);

    expect(page.results.map((book) => book.id)).toEqual(['openlibrary:OL1W']);
    expect(urls).toHaveLength(1);
    expect(urls[0]).toContain('b.example');
  });

  it('fails closed with an empty page for an unknown source and zero I/O', async () => {
    const { client, urls } = scriptedClient(twoAddonHandler);
    const provider = providerWith([ADDON_A_DESCRIPTOR], client);

    const page = await provider.searchSource('addon:ffffffffffffffff' as CatalogSource, 'x', 1);

    expect(page).toEqual({ results: [], nexoPage: null, totalCount: 0 });
    expect(urls).toEqual([]);
  });
});
