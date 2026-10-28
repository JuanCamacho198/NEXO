/**
 * Discover failure honesty (contract, "the governing rule": when something
 * fails the failure must be findable, and the UI must not lie about it).
 *
 * Measured live on 2026-10-20: all three Discover rails returned HTTP 403
 * (`API_KEY_HTTP_REFERRER_BLOCKED`) from Google Books — classified as the
 * non-retryable `UPSTREAM_ERROR` — while the hero pill still read
 * "En línea · 7 fuentes". The user's report ("Reintentar shows a skeleton for
 * half a second and then the error returns") is consistent with a fast,
 * deterministic 403, NOT with a timeout or an exhausted rate limit.
 *
 * These tests pin invariants that hold for ANY upstream outcome and therefore
 * never depend on an external API being reachable:
 *   1. A failed page is never cached (so "Reintentar" is never a lie).
 *   2. A successful page IS cached (so the read path above is meaningful).
 *   3. One failing rail does not take its siblings down.
 *   4. The connectivity projection is honest.
 *
 * NOTE on wiring: the composite's `searchSource(sourceId, ...)` resolves the
 * owning provider and calls that provider's `search()`, so the scripted fake
 * below scripts `search`, not `searchSource`.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DiscoverRailsDomainState } from '$lib/features/discover/DiscoverRailsDomainState.svelte';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { InMemoryDiscoverCache, pageCacheKey } from '$lib/shared/services/catalog/DiscoverCache';
import {
  BUILTIN_GOOGLEBOOKS,
  catalogError,
  type CatalogBook,
  type CatalogProvider,
  type PagedResult,
} from '$lib/shared/services/catalog';

/** 2026-06-10 ⇒ the doyle / poe / wilde author shelves (railRotation fixture). */
const PINNED_DAY = new Date(2026, 5, 10, 12);
const pinnedNow = (): Date => PINNED_DAY;

const SOURCE = BUILTIN_GOOGLEBOOKS;

function book(id: string, authors: string[] = ['Author']): CatalogBook {
  return {
    id,
    provider: 'googlebooks',
    title: `Title ${id}`,
    authors,
    // Rail books must carry a cover: coverless books are dropped from rails.
    coverUrl: `https://covers.example.org/${id}.jpg`,
    languages: ['en'],
    subjects: ['Fiction'],
    downloadUrl: null,
  };
}

function paged(books: CatalogBook[]): PagedResult {
  return { results: books, nexoPage: null, totalCount: books.length };
}

/** Single-source provider whose every search is recorded and scripted. */
function scriptedProvider(onSearch: (query: string) => Promise<PagedResult>) {
  const calls: string[] = [];
  const provider: CatalogProvider = {
    async search(query): Promise<PagedResult> {
      calls.push(query);
      return onSearch(query);
    },
    async getDetails(id: string): Promise<CatalogBook> {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    },
    async featured(): Promise<PagedResult> {
      return paged([]);
    },
    supportsFeatured(): boolean {
      return false;
    },
    async topic(): Promise<PagedResult> {
      return paged([]);
    },
    supportsTopic(): boolean {
      return false;
    },
    resolveDownloadUrl(): string {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    async searchSource(_sourceId, query): Promise<PagedResult> {
      calls.push(query);
      return onSearch(query);
    },
    listSources() {
      return [{ sourceId: SOURCE, name: 'Google Books', kind: 'builtin' as const }];
    },
  };
  return { provider, calls };
}

function composite(providers: CatalogProvider[], cache: InMemoryDiscoverCache) {
  return new CompositeCatalogProvider(providers, { debounceMs: 0, cache });
}

describe('Discover failure honesty — cache', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('never caches a failed page, so a retry issues a real request', async () => {
    const cache = new InMemoryDiscoverCache();
    const forbidden = catalogError('UPSTREAM_ERROR', 'upstream status 403');
    let attempts = 0;
    const { provider } = scriptedProvider(() => {
      attempts += 1;
      return Promise.reject(forbidden);
    });
    const subject = composite([provider], cache);
    const key = pageCacheKey(SOURCE, 'oscar wilde', 1);

    await expect(subject.searchSource(SOURCE, 'oscar wilde', 1)).rejects.toMatchObject({
      code: 'UPSTREAM_ERROR',
    });
    expect(attempts).toBe(1);
    // A stored error is the one thing that would make "Reintentar" a lie.
    expect(cache.get(key, Math.floor(Date.now() / 1000))).toBeNull();

    await expect(subject.searchSource(SOURCE, 'oscar wilde', 1)).rejects.toMatchObject({
      code: 'UPSTREAM_ERROR',
    });
    // A second try reaches the provider again — no cached failure to replay.
    expect(attempts).toBe(2);
  });

  it('caches a successful page and serves it without a second provider call', async () => {
    const cache = new InMemoryDiscoverCache();
    let calls = 0;
    const { provider } = scriptedProvider(() => {
      calls += 1;
      return Promise.resolve(paged([book('googlebooks:1')]));
    });
    const subject = composite([provider], cache);

    const first = await subject.searchSource(SOURCE, 'oscar wilde', 1);
    expect(first.results).toHaveLength(1);
    expect(calls).toBe(1);

    const second = await subject.searchSource(SOURCE, 'oscar wilde', 1);
    expect(second.results).toHaveLength(1);
    // Served from the cache: the write path is real, not dead code.
    expect(calls).toBe(1);
  });

  it('keeps serving the other queries when one errors', async () => {
    const cache = new InMemoryDiscoverCache();
    const forbidden = catalogError('UPSTREAM_ERROR', 'upstream status 403');
    let n = 0;
    const { provider } = scriptedProvider(() => {
      n += 1;
      return n === 1
        ? Promise.reject(forbidden)
        : Promise.resolve(paged([book(`googlebooks:${n}`)]));
    });
    const subject = composite([provider], cache);

    await expect(subject.searchSource(SOURCE, 'arthur conan doyle', 1)).rejects.toThrow();
    const ok = await subject.searchSource(SOURCE, 'edgar allan poe', 1);
    expect(ok.results).toHaveLength(1);
  });
});

describe('Discover failure honesty — rail state', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  function railsWith(outcome: (query: string) => Promise<PagedResult>) {
    const { provider } = scriptedProvider(outcome);
    // The plan has four rails: two topic shelves (the `?topic=` door), the popular
    // shelf (via `featured`) and the opds shelf. The scripted outcome must cover
    // every door, or a rail would hide instead of failing.
    const withFeatured: CatalogProvider = {
      ...provider,
      supportsTopic: () => true,
      topic: (term) => outcome(term),
      featured: () => outcome('featured'),
    };
    return new DiscoverRailsDomainState({ provider: withFeatured, now: pinnedNow });
  }

  it('marks every rail Error (not offline) on a 403 upstream failure', async () => {
    const forbidden = catalogError('UPSTREAM_ERROR', 'upstream status 403');
    const state = railsWith(() => Promise.reject(forbidden));

    await state.refreshRails();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Error', 'Error', 'Error', 'Error']);
    // A permission failure is not a connectivity failure.
    expect(state.rails.every((rail) => rail.kind === 'Error' && !rail.offline)).toBe(true);
    state.dispose();
  });

  it('isolates one failing rail from its siblings', async () => {
    const forbidden = catalogError('UPSTREAM_ERROR', 'upstream status 403');
    const failedOnce = { done: false };
    const state = railsWith((query) => {
      // Fail exactly the first rail request, let the other three succeed.
      if (!failedOnce.done) {
        failedOnce.done = true;
        return Promise.reject(forbidden);
      }
      return Promise.resolve(paged([book('googlebooks:ok', [query.split(' ').pop() ?? ''])]));
    });

    await state.refreshRails();

    expect(state.rails[0]?.kind).toBe('Error');
    expect(state.rails.filter((rail) => rail.kind === 'Loaded')).toHaveLength(3);
    state.dispose();
  });

  it('exposes isOnline=true while every rail is Error — the pill currently lies', async () => {
    const forbidden = catalogError('UPSTREAM_ERROR', 'upstream status 403');
    const state = railsWith(() => Promise.reject(forbidden));

    await state.refreshRails();

    // `isOnline` is the ONLY input the hero pill reads, and `DiscoverHero`
    // renders "En línea · {sources.length} fuentes" whenever it is true — with
    // `sources.length` counting CONFIGURED sources, not working ones. So the
    // pill reads "En línea · 7 fuentes" above three rail errors. This assertion
    // documents the live defect; when the pill is made honest it must change.
    expect(state.rails.every((rail) => rail.kind === 'Error')).toBe(true);
    expect(state.isOnline).toBe(true);
    state.dispose();
  });

  it('still reports offline for a genuine connectivity failure', async () => {
    const offline = catalogError('NETWORK_ERROR', 'no route to host');
    const state = railsWith(() => Promise.reject(offline));

    await state.refreshRails();

    // The projection must keep distinguishing offline from upstream — a fix
    // that made `isOnline` simply track "any error" would break this.
    expect(state.isOnline).toBe(false);
    state.dispose();
  });
});
