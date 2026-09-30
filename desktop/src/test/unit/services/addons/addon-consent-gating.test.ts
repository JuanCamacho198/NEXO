/**
 * Addon consent gating (slice 8, task 8.13; slice 9 fills the resolve shell):
 * consent gates RESOLVE ONLY.
 *
 * With a COUNTING fake transport: no consent ⇒ `CONSENT_REQUIRED` with 0
 * transport calls; granted ⇒ resolution proceeds (slice 9: the resolveUrl is
 * fetched — the fake answers zero items, so the resolution is empty);
 * withdrawn ⇒ blocked again with 0 calls.
 * `search` / `getDetails` stay ungated (Android parity + the spec's "resolve
 * operation" phrasing): the existing `routeDetails` prefix routing and the
 * `NOT_FOUND` zero-I/O behaviour are preserved, covered by regression tests.
 *
 * Fully offline: the built-in providers that `defaultCatalogProviders` builds
 * are served by an injected offline `fetch`, so no test in this file can reach
 * the network.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import {
  AddonCatalogProvider,
  EMPTY_ADDON_ACCESS,
} from '$lib/shared/services/addons/AddonCatalogProvider';
import {
  AddonConsentService,
  InMemoryAddonConsentStore,
} from '$lib/shared/services/addons/AddonConsent';
import {
  createRebuildingCatalogProvider,
  defaultCatalogProviders,
  CompositeCatalogProvider,
} from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { CatalogError } from '$lib/shared/services/catalog/errors';
import type { CatalogBook, CatalogSource } from '$lib/shared/services/catalog/CatalogProvider';
import type { AddonManifest } from '@nexo/manifest-validator';
import type { AddonTransport, InstalledAddonRow } from '$lib/shared/services/addons/AddonRegistry';

/**
 * Offline built-in datasources.
 *
 * `defaultCatalogProviders` — which `createRebuildingCatalogProvider` always
 * calls — constructs the REAL Gutendex and Open Library providers, and both
 * datasources default to the ambient global `fetch` (`GutendexDataSource.ts:19`,
 * `OpenLibraryDataSource.ts:28`). Unit tests must never touch the network, so
 * this file injects a deterministic offline `fetch` into those built-ins: every
 * URL is recorded and answered locally, never by gutendex.com/openlibrary.org.
 * The addon transport under test stays the injected counting fake; only the
 * built-in providers are stubbed.
 */
const builtInFetchCalls: string[] = [];

function offlineBuiltInFetch(url: string): Promise<Response> {
  builtInFetchCalls.push(url);
  const payload = url.includes('openlibrary.org')
    ? { numFound: 0, docs: [] }
    : { results: [], count: 0 };
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () => Promise.resolve(payload),
  } as unknown as Response);
}

const ambientFetch = globalThis.fetch;

beforeAll(() => {
  globalThis.fetch = offlineBuiltInFetch as unknown as typeof fetch;
});

afterAll(() => {
  globalThis.fetch = ambientFetch;
});

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

/** v2-shaped manifest (resolveUrl arrives in slice 9): proves the gate runs before any fetch. */
const RESOLVE_MANIFEST = Object.assign({}, SPACE_MANIFEST, {
  resolveUrl: 'https://space.example/resolve?isbn={isbn}',
}) as AddonManifest;

const BOOK: CatalogBook = {
  id: `addon:${ADDON_ID}:book-1`,
  provider: `addon:${ADDON_ID}`,
  title: 'Dune',
  authors: ['Herbert'],
  coverUrl: null,
  languages: ['en'],
  subjects: ['sci-fi'],
  downloadUrl: null,
};

function countingTransport(): AddonTransport & { calls: string[] } {
  const calls: string[] = [];
  const transport = (async (url: string) => {
    calls.push(url);
    // Search URLs answer a page payload; resolve URLs answer a resolve
    // payload; every other addon URL answers one book.
    const body = url.includes('/search?')
      ? { results: [{ id: 'book-1', title: 'Dune' }], totalCount: 1 }
      : url.includes('/resolve?')
        ? { results: [] }
        : { id: 'book-1', title: 'Dune' };
    return {
      status: 200,
      contentType: 'application/json',
      body: new TextEncoder().encode(JSON.stringify(body)),
    };
  }) as AddonTransport;
  return Object.assign(transport, { calls });
}

function errOf(promise: Promise<unknown>): Promise<CatalogError> {
  return promise.catch((err: unknown) => err as CatalogError) as Promise<CatalogError>;
}

describe('consent gates resolve only (counting transport)', () => {
  it('no consent ⇒ CONSENT_REQUIRED with 0 transport calls', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    // The manifest declares a resolve endpoint: the gate must fire BEFORE any fetch.
    const provider = new AddonCatalogProvider(RESOLVE_MANIFEST, ADDON_ID, transport, 0, consent);

    const err = await errOf(provider.resolveAddonAccess(BOOK));
    expect(err).toBeInstanceOf(CatalogError);
    expect(err.code).toBe('CONSENT_REQUIRED');
    expect(err.retryable).toBe(false);
    expect(transport.calls).toHaveLength(0);
  });

  it('granted consent ⇒ resolution proceeds (slice 9: the resolveUrl is fetched)', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    await consent.grant(ADDON_ID);
    const provider = new AddonCatalogProvider(RESOLVE_MANIFEST, ADDON_ID, transport, 0, consent);

    const resolution = await provider.resolveAddonAccess(BOOK);
    expect(resolution).toEqual({ ...EMPTY_ADDON_ACCESS, options: [] });
    // Slice-9 fills the shell: a granted resolve with an endpoint fetches it
    // (the fake answers zero items, so the resolution is empty).
    expect(transport.calls).toHaveLength(1);
  });

  it('a v1 manifest (no resolveUrl) resolves empty with 0 calls once granted', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    await consent.grant(ADDON_ID);
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport, 0, consent);

    const resolution = await provider.resolveAddonAccess(BOOK);
    expect(resolution).toEqual({ canDownloadInApp: false, downloadUrl: null, options: [] });
    expect(transport.calls).toHaveLength(0);
  });

  it('withdrawn consent ⇒ blocked again with 0 calls', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    await consent.grant(ADDON_ID);
    await consent.revoke(ADDON_ID);
    const provider = new AddonCatalogProvider(RESOLVE_MANIFEST, ADDON_ID, transport, 0, consent);

    const err = await errOf(provider.resolveAddonAccess(BOOK));
    expect(err.code).toBe('CONSENT_REQUIRED');
    expect(transport.calls).toHaveLength(0);
  });

  it('a provider without an explicit gate denies every resolve (fail-closed default)', async () => {
    const transport = countingTransport();
    const provider = new AddonCatalogProvider(RESOLVE_MANIFEST, ADDON_ID, transport, 0);

    const err = await errOf(provider.resolveAddonAccess(BOOK));
    expect(err.code).toBe('CONSENT_REQUIRED');
    expect(transport.calls).toHaveLength(0);
  });
});

describe('search/getDetails stay ungated (regression)', () => {
  function installedRow(): InstalledAddonRow {
    return {
      id: ADDON_ID,
      url: 'https://space.example/manifest.json',
      manifest: SPACE_MANIFEST,
      enabled: true,
      addedAt: 1,
    };
  }

  it('search still fetches without consent (resolve-only gating)', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport, 0, consent);

    const page = await provider.search('dune', 1);
    expect(transport.calls).toHaveLength(1);
    expect(page.results).toHaveLength(1);
  });

  it('getDetails still fetches without consent (resolve-only gating)', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    const provider = new AddonCatalogProvider(SPACE_MANIFEST, ADDON_ID, transport, 0, consent);

    const book = await provider.getDetails(`addon:${ADDON_ID}:book-1`);
    expect(transport.calls).toHaveLength(1);
    expect(book.title).toBe('Dune');
  });

  it('unknown ids still reject NOT_FOUND with 0 calls (routeDetails prefix routing)', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    const composite = new CompositeCatalogProvider(
      defaultCatalogProviders([installedRow()], transport, '', consent),
    );

    const err = await errOf(composite.getDetails('no-such-prefix:book-1'));
    expect(err.code).toBe('NOT_FOUND');
    expect(transport.calls).toHaveLength(0);
  });

  it('an unowned source still fails closed with an empty page and 0 calls', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    const composite = new CompositeCatalogProvider(
      defaultCatalogProviders([installedRow()], transport, '', consent),
    );

    // No provider owns this addon source: empty page, never a crash, no I/O.
    const page = await composite.searchSource('addon:0000000000000000' as CatalogSource, 'dune', 1);
    expect(page).toEqual({ results: [], nextPage: null, totalCount: 0 });
    expect(transport.calls).toHaveLength(0);
  });

  it('the rebuilding supplier forwards the consent gate into addon providers', async () => {
    const transport = countingTransport();
    const consent = new AddonConsentService(new InMemoryAddonConsentStore());
    const supplier = createRebuildingCatalogProvider(
      () => Promise.resolve([installedRow()]),
      transport,
      '',
      {
        consent,
      },
    );

    const built = await supplier.current();
    const addonSources = built.listSources().filter((s) => s.kind === 'addon');
    expect(addonSources.map((s) => s.sourceId)).toEqual([`addon:${ADDON_ID}`]);
    // Composite search fans out ungated (transport fires); resolve stays gated.
    const builtInCallsBefore = builtInFetchCalls.length;
    await built.search('dune', 1);
    expect(transport.calls.length).toBeGreaterThan(0);
    // The built-in providers went through the injected offline fetch, never the
    // network: the fan-out is served locally and deterministically.
    expect(builtInFetchCalls.length).toBeGreaterThan(builtInCallsBefore);
    expect(builtInFetchCalls.some((url) => url.includes('gutendex.com'))).toBe(true);
    expect(builtInFetchCalls.some((url) => url.includes('openlibrary.org'))).toBe(true);
  });
});
