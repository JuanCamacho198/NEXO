import { describe, expect, it } from 'vitest';
import { CatalogError } from '$lib/shared/services/catalog/errors';
import {
  BUILTIN_GUTENDEX,
  BUILTIN_OPENLIBRARY,
  addonSource,
} from '$lib/shared/services/catalog/CatalogProvider';
import type {
  CatalogBook,
  CatalogProvider,
  CatalogSourceInfo,
  PagedResult,
} from '$lib/shared/services/catalog/CatalogProvider';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  GutendexCatalogProvider,
  OpenLibraryCatalogProvider,
} from '$lib/shared/services/catalog/BuiltInCatalogProviders';
import { GutendexDataSource } from '$lib/shared/services/catalog/GutendexDataSource';
import { OpenLibraryDataSource } from '$lib/shared/services/catalog/OpenLibraryDataSource';
import {
  InMemoryDiscoverCache,
  PAGE_TTL_S,
  pageCacheKey,
} from '$lib/shared/services/catalog/DiscoverCache';
import { resolveDownloadUrl } from '$lib/shared/services/catalog/mappers';
import gutendexFixture from '$lib/shared/services/catalog/fixtures/gutendex-search.json';
import openLibraryFixture from '$lib/shared/services/catalog/fixtures/openlibrary-search.json';
import type { GutendexRecord, OpenLibraryDoc } from '$lib/shared/services/catalog/mappers';

const gutendexRecords = gutendexFixture.results as unknown as GutendexRecord[];
const olDocs = openLibraryFixture.docs as unknown as OpenLibraryDoc[];

const ADDON_ID = 'a1b2c3d4e5f60718';
const OTHER_ADDON_ID = '0123456789abcdef';

function stubFetch(handler: (url: string) => { status: number; body: unknown }): typeof fetch {
  return (async (input: unknown) => {
    const { status, body } = handler(String(input));
    return new Response(JSON.stringify(body), { status });
  }) as typeof fetch;
}

function noIoFetch(): typeof fetch {
  return (async () => {
    throw new Error('unexpected network I/O');
  }) as typeof fetch;
}

const prideGolden: CatalogBook = {
  id: 'gutendex:1342',
  provider: 'builtin:gutendex',
  title: 'Pride and Prejudice',
  authors: ['Austen, Jane'],
  coverUrl: 'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
  languages: ['en'],
  subjects: ['Love stories', 'Domestic fiction'],
  downloadUrl: 'https://www.gutenberg.org/ebooks/1342.epub3.images',
  description:
    'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.\n\nElizabeth Bennet spars with the proud Mr. Darcy.',
  formats: {
    'application/epub+zip': 'https://www.gutenberg.org/ebooks/1342.epub3.images',
    'text/plain': 'https://www.gutenberg.org/ebooks/1342.txt.utf8',
    'text/html': 'https://www.gutenberg.org/ebooks/1342.html.images',
  },
  isPublicDomain: true,
};

const aliceGolden: CatalogBook = {
  id: 'gutendex:11',
  provider: 'builtin:gutendex',
  title: "Alice's Adventures in Wonderland",
  authors: ['Carroll, Lewis'],
  coverUrl: 'https://www.gutenberg.org/cache/epub/11/pg11.cover.medium.jpg',
  languages: ['en'],
  subjects: ['Fantasy fiction'],
  downloadUrl: null,
  description: undefined,
  formats: {},
  isPublicDomain: true,
};

class FakeAddonProvider implements CatalogProvider {
  searchCalls = 0;
  detailCalls = 0;
  constructor(private readonly enabled = true) {}

  async search(query: string, page: number): Promise<PagedResult> {
    this.searchCalls += 1;
    const book: CatalogBook = {
      id: `addon:${ADDON_ID}:${query}`,
      provider: addonSource(ADDON_ID),
      title: `Addon Book ${query}`,
      authors: [],
      coverUrl: null,
      languages: [],
      subjects: [],
      downloadUrl: null,
    };
    return { results: page >= 1 ? [book] : [], nexoPage: null, totalCount: 1 };
  }

  async getDetails(id: string): Promise<CatalogBook> {
    this.detailCalls += 1;
    return {
      id,
      provider: addonSource(ADDON_ID),
      title: 'Addon Detail',
      authors: [],
      coverUrl: null,
      languages: [],
      subjects: [],
      downloadUrl: null,
    };
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }

  listSources(): CatalogSourceInfo[] {
    return this.enabled
      ? [{ sourceId: addonSource(ADDON_ID), name: 'Fake Addon', kind: 'addon' }]
      : [];
  }

  /** Test fake: no featured capability (fail-closed, never called by fan-out). */
  async featured(): Promise<PagedResult> {
    return { results: [], nexoPage: null, totalCount: 0 };
  }

  supportsFeatured(): boolean {
    return false;
  }

  async searchSource(): Promise<PagedResult> {
    return this.search('', 1);
  }
}

class DisabledProvider implements CatalogProvider {
  searchCalls = 0;
  async search(): Promise<PagedResult> {
    this.searchCalls += 1;
    throw new Error('disabled provider must not be searched');
  }
  async getDetails(id: string): Promise<CatalogBook> {
    throw new Error(`disabled provider must not resolve ${id}`);
  }
  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }
  listSources(): CatalogSourceInfo[] {
    return [];
  }

  /** Test fake: no featured capability (fail-closed, never called by fan-out). */
  async featured(): Promise<PagedResult> {
    return { results: [], nexoPage: null, totalCount: 0 };
  }

  supportsFeatured(): boolean {
    return false;
  }

  async searchSource(): Promise<PagedResult> {
    return { results: [], nexoPage: null, totalCount: 0 };
  }
}

function builtinPair(calls: { g: number; o: number }, fetchCounters = true): CatalogProvider[] {
  const g = new GutendexCatalogProvider(
    new GutendexDataSource(
      stubFetch(() => {
        if (fetchCounters) calls.g += 1;
        return { status: 200, body: gutendexFixture };
      }),
    ),
  );
  const o = new OpenLibraryCatalogProvider(
    new OpenLibraryDataSource(
      stubFetch(() => {
        if (fetchCounters) calls.o += 1;
        return { status: 200, body: openLibraryFixture };
      }),
    ),
  );
  return [g, o];
}

describe('CompositeCatalogProvider (ordered dynamic providers)', () => {
  it('zero-addons: the Gutendex-only fan-out maps the page byte-for-byte', async () => {
    const calls = { g: 0, o: 0 };
    const provider = new CompositeCatalogProvider(builtinPair(calls), { debounceMs: 0 });
    const page = await provider.search('pride', 1);
    expect(page.results).toEqual([prideGolden, aliceGolden]);
    expect(page.totalCount).toBe(3);
    expect(page.nexoPage).toBe(2);
    // Open Library opts out of the composite fan-out: it is never called.
    expect(calls).toEqual({ g: 1, o: 0 });
  });

  it('concat merges in provider order: addon books append after built-ins', async () => {
    const calls = { g: 0, o: 0 };
    const addon = new FakeAddonProvider();
    const provider = new CompositeCatalogProvider([...builtinPair(calls), addon], {
      debounceMs: 0,
    });
    const page = await provider.search('pride', 1);
    expect(page.results.slice(0, 2)).toEqual([prideGolden, aliceGolden]);
    expect(page.results[2]?.id).toBe(`addon:${ADDON_ID}:pride`);
    expect(page.results[2]?.provider).toBe(addonSource(ADDON_ID));
    expect(addon.searchCalls).toBe(1);
  });

  it('listSources(): built-ins first, addons next, disabled excluded, deduped', () => {
    const calls = { g: 0, o: 0 };
    const provider = new CompositeCatalogProvider(
      [...builtinPair(calls, false), new FakeAddonProvider(), new DisabledProvider()],
      { debounceMs: 0 },
    );
    expect(provider.listSources()).toEqual([
      { sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' },
      { sourceId: BUILTIN_OPENLIBRARY, name: 'Open Library', kind: 'builtin' },
      { sourceId: addonSource(ADDON_ID), name: 'Fake Addon', kind: 'addon' },
    ]);
  });

  it('routes getDetails by exact book-id prefix; unroutable ids reject with no I/O', async () => {
    const calls = { g: 0, o: 0 };
    const addon = new FakeAddonProvider();
    const provider = new CompositeCatalogProvider([...builtinPair(calls, false), addon], {
      debounceMs: 0,
    });
    const detail = await provider.getDetails(`addon:${ADDON_ID}:book7`);
    expect(detail.title).toBe('Addon Detail');
    expect(addon.detailCalls).toBe(1);

    for (const id of [
      `addon:${OTHER_ADDON_ID}:book7`,
      'gutendex:abc',
      'gutendex:',
      'addon:',
      'unknown:1',
    ]) {
      const err = await provider.getDetails(id).catch((e: unknown) => e);
      expect(err).toBeInstanceOf(CatalogError);
      expect((err as CatalogError).code).toBe('NOT_FOUND');
    }
    expect(addon.detailCalls).toBe(1);
  });

  it('openlibrary ids stay not detail-resolvable (behavior preserved)', async () => {
    const provider = new CompositeCatalogProvider(builtinPair({ g: 0, o: 0 }, false), {
      debounceMs: 0,
    });
    const err = await provider.getDetails('openlibrary:/works/OL11W').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(CatalogError);
    expect((err as CatalogError).code).toBe('NOT_FOUND');
  });

  it('v1 cache rows age out unread: legacy keys are never served', async () => {
    const calls = { g: 0, o: 0 };
    const cache = new InMemoryDiscoverCache();
    cache.put('p:composite:pride:1', '{"legacy":true}', 1_000, PAGE_TTL_S);
    const provider = new CompositeCatalogProvider(builtinPair(calls), {
      debounceMs: 0,
      cache,
      nowEpochSecs: () => 2_000,
    });
    const page = await provider.search('pride', 1);
    expect(calls).toEqual({ g: 1, o: 0 });
    expect(page.results).toEqual([prideGolden, aliceGolden]);
  });

  it('v2 keys include the full source string', async () => {
    const calls = { g: 0, o: 0 };
    const cache = new InMemoryDiscoverCache();
    const provider = new CompositeCatalogProvider(builtinPair(calls), {
      debounceMs: 0,
      cache,
      nowEpochSecs: () => 1_000,
    });
    await provider.search('pride', 1);
    expect(cache.get(pageCacheKey('builtin:gutendex', 'pride', 1), 1_000)).not.toBeNull();
    // Open Library opted out of the composite fan-out, so it never caches a page.
    expect(cache.get(pageCacheKey('builtin:openlibrary', 'pride', 1), 1_000)).toBeNull();
  });

  it('serves repeated searches from cache without network I/O', async () => {
    const calls = { g: 0, o: 0 };
    const cache = new InMemoryDiscoverCache();
    const provider = new CompositeCatalogProvider(builtinPair(calls), {
      debounceMs: 0,
      cache,
      nowEpochSecs: () => 1_000,
    });
    const first = await provider.search('pride', 1);
    const second = await provider.search('pride', 1);
    expect(second).toEqual(first);
    expect(calls).toEqual({ g: 1, o: 0 });
  });

  it('entries for uninstalled sources are never served (existence check)', async () => {
    const calls = { g: 0, o: 0 };
    const cache = new InMemoryDiscoverCache();
    const withAddon = new CompositeCatalogProvider(
      [...builtinPair({ g: 0, o: 0 }, false), new FakeAddonProvider()],
      { debounceMs: 0, cache, nowEpochSecs: () => 1_000 },
    );
    await withAddon.search('pride', 1);
    expect(cache.get(pageCacheKey(addonSource(ADDON_ID), 'pride', 1), 1_000)).not.toBeNull();

    // Same store, addon removed from the provider list (simulated uninstall).
    const withoutAddon = new CompositeCatalogProvider(builtinPair(calls), {
      debounceMs: 0,
      cache,
      nowEpochSecs: () => 2_000,
    });
    const page = await withoutAddon.search('pride', 1);
    expect(page.results.some((b) => b.provider === addonSource(ADDON_ID))).toBe(false);
    // The built-in entries are still fresh, so no refetch is needed.
    expect(calls).toEqual({ g: 0, o: 0 });
  });

  it('disabled addon providers contribute nothing and are not searched', async () => {
    const calls = { g: 0, o: 0 };
    const disabled = new DisabledProvider();
    const cache = new InMemoryDiscoverCache();
    cache.put(pageCacheKey(addonSource(ADDON_ID), 'pride', 1), '{"stale":true}', 1_000, PAGE_TTL_S);
    const provider = new CompositeCatalogProvider([...builtinPair(calls), disabled], {
      debounceMs: 0,
      cache,
      nowEpochSecs: () => 2_000,
    });
    const page = await provider.search('pride', 1);
    expect(disabled.searchCalls).toBe(0);
    expect(page.results).toEqual([prideGolden, aliceGolden]);
  });

  it('rejects page < 1 before any I/O across the provider list', async () => {
    const calls = { g: 0, o: 0 };
    const addon = new FakeAddonProvider();
    const provider = new CompositeCatalogProvider([...builtinPair(calls, false), addon], {
      debounceMs: 0,
    });
    const err = await provider.search('x', 0).catch((e: unknown) => e);
    expect((err as CatalogError).code).toBe('INVALID_PAGE');
    expect(addon.searchCalls).toBe(0);
  });
});
