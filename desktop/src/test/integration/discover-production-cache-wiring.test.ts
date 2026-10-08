/**
 * Integration (design/testing `Integration` row): the PRODUCTION Discover
 * cache wiring.
 *
 * `liveComposite.ts` builds its supplier with `{ cache: discoverCache }`, so
 * the production composite must read and write the real `discoverCache`
 * singleton. This suite proves that BEHAVIORALLY through the production path
 * (`liveCatalogProvider` → `createRebuildingCatalogProvider` → composite) —
 * previously the only guard was a `toContain` source-read plus a unit test with
 * an injected cache.
 *
 * Offline by construction: the built-in datasources are served by an injected
 * `fetch`, and the Tauri IPC seam is a local mock (no Rust, no network).
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { invoke } from '@tauri-apps/api/core';

import gutendexFixture from '$lib/shared/services/catalog/fixtures/gutendex-search.json';
import { discoverCache, liveCatalogProvider } from '$lib/shared/services/catalog/liveComposite';
import { PAGE_TTL_S, pageCacheKey } from '$lib/shared/services/catalog/DiscoverCache';
import { getAddonRegistry } from '$lib/shared/services/addons/AddonRegistry';
import {
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_GUTENDEX,
} from '$lib/shared/services/catalog/CatalogProvider';
import type { CatalogBook, CatalogSource } from '$lib/shared/services/catalog/CatalogProvider';

const builtInFetchCalls: string[] = [];

/** Never echo an ambient Google Books key into test output. */
function redact(url: string): string {
  return url.replace(/key=[^&]+/, 'key=<redacted>');
}

/**
 * Offline stand-in for the built-in datasources' default global `fetch`. The
 * production composition may include a third built-in (Google Books) whose
 * provider is key-gated by the host's ambient `VITE_GOOGLE_BOOKS_KEY`; serving
 * every host keeps this suite identical with or without that key and offline in
 * both cases.
 */
function offlineBuiltInFetch(url: string): Promise<Response> {
  builtInFetchCalls.push(redact(url));
  const payload = url.includes('openlibrary.org')
    ? { numFound: 0, docs: [] }
    : url.includes('googleapis.com')
      ? {
          totalItems: 1,
          items: [{ id: 'gbs-1', volumeInfo: { title: 'Google Book One', authors: ['A'] } }],
        }
      : gutendexFixture;
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

beforeEach(() => {
  // No installed addons; every durable Discover-cache read misses. The real
  // `liveComposite` registry then builds the built-in-only production composite.
  vi.mocked(invoke).mockImplementation(async (cmd: string) => {
    if (cmd === 'listInstalledAddons') return [];
    return null;
  });
});

function seededBook(id: string, provider: CatalogSource): CatalogBook {
  return {
    id,
    provider,
    title: 'Seeded From The Durable Cache',
    authors: ['Cache, Ada'],
    coverUrl: null,
    languages: ['en'],
    subjects: ['cache'],
    downloadUrl: null,
  };
}

describe('production discover cache wiring (liveComposite path)', () => {
  it('serves a seeded production cache entry with zero provider I/O', async () => {
    const query = 'seeded-cache-read';
    const nowEpochSecs = Math.floor(Date.now() / 1000);
    // Seed both possible primary sources; the composite fan-out serves whichever
    // is registered (Google Books when the ambient key is set, else Gutendex)
    // with zero provider I/O either way. Open Library is out of the fan-out.
    discoverCache.put(
      pageCacheKey(BUILTIN_GUTENDEX, query, 1),
      JSON.stringify({
        results: [seededBook('gutendex:4242', BUILTIN_GUTENDEX)],
        nexoPage: null,
        totalCount: 1,
      }),
      nowEpochSecs,
      PAGE_TTL_S,
    );
    discoverCache.put(
      pageCacheKey(BUILTIN_GOOGLEBOOKS, query, 1),
      JSON.stringify({
        results: [seededBook('googlebooks:4242', BUILTIN_GOOGLEBOOKS)],
        nexoPage: null,
        totalCount: 1,
      }),
      nowEpochSecs,
      PAGE_TTL_S,
    );

    const callsBefore = builtInFetchCalls.length;
    const page = await liveCatalogProvider.search(query, 1);

    // The seeded payload came back through the production provider…
    expect(page.results.some((book) => book.id.endsWith(':4242'))).toBe(true);
    // …with zero provider I/O: a null-cache composite would have refetched.
    expect(builtInFetchCalls.slice(callsBefore)).toEqual([]);
  });

  it('a rebuilt production composite still serves the cached page with zero new I/O', async () => {
    const query = 'live-cache-roundtrip';
    const nowEpochSecs = Math.floor(Date.now() / 1000);

    const first = await liveCatalogProvider.search(query, 1);
    expect(first.results.length).toBeGreaterThan(0);
    const primaryId = first.results[0]!.id;
    const primarySource = primaryId.startsWith('googlebooks:')
      ? BUILTIN_GOOGLEBOOKS
      : BUILTIN_GUTENDEX;
    const callsAfterFirstRead = builtInFetchCalls.length;
    expect(callsAfterFirstRead).toBeGreaterThan(0);

    // The production composite wrote the fetched page into `discoverCache`.
    const cached = discoverCache.get(pageCacheKey(primarySource, query, 1), nowEpochSecs);
    expect(cached).toContain(primaryId);

    // Production rebuild trigger: a registry mutation invalidates the shared
    // supplier (`liveComposite` subscribes through `registry.onChanged`).
    await getAddonRegistry().setEnabled('0000000000000000', true);

    const rebuilt = await liveCatalogProvider.search(query, 1);
    expect(rebuilt.results.map((book) => book.id)).toContain(primaryId);
    // The rebuilt composite received the same non-null production cache.
    expect(builtInFetchCalls.length).toBe(callsAfterFirstRead);
  });
});
