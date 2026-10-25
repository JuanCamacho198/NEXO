/**
 * Regression: the `opds` rail must reach the Standard Ebooks feed through the
 * COMPOSITE, not only through the provider.
 *
 * Measured before this fix: `loadRail(opds)` returned 0 books, 0 totalCount, in
 * 0 ms, with zero requests to standardebooks.org. The cause was routing, not the
 * feed: `CompositeCatalogProvider.searchSource` asked the owning provider's
 * `search`, and `StandardEbooksCatalogProvider.search` is contractually
 * fail-closed (one unpaged newest-releases shelf has no query parameter), so
 * the provider's real `searchSource`/`newest` doors were unreachable through the
 * `CatalogProvider` interface.
 *
 * These tests pin the routing AND the contract it must not break: `search` stays
 * fail-closed, `searchSource` answers the shelf.
 */
import { describe, expect, it } from 'vitest';
import { InMemoryDiscoverCache } from '$lib/shared/services/catalog/DiscoverCache';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  BUILTIN_STANDARD_EBOOKS,
  STANDARD_EBOOKS_ATOM_FEED_URL,
  StandardEbooksCatalogProvider,
  StandardEbooksDataSource,
  computeNextPage,
} from '$lib/shared/services/catalog';
import { STANDARD_EBOOKS_ATOM_FEED as FEED } from '../../../fixtures-recorded/catalogPayloads';

interface FeedCall {
  url: string;
  accept: string;
}

/** Stub transport: records every request, answers the recorded Atom feed. */
function stubFeedFetch(calls: FeedCall[], status = 200): typeof fetch {
  return (async (url: string, init: RequestInit) => {
    calls.push({ url, accept: String((init.headers as Record<string, string>).Accept) });
    return {
      ok: status >= 200 && status < 300,
      status,
      text: async () => FEED,
    } as unknown as Response;
  }) as unknown as typeof fetch;
}

function compositeOver(calls: FeedCall[], cache?: InMemoryDiscoverCache) {
  const datasource = new StandardEbooksDataSource(stubFeedFetch(calls));
  return new CompositeCatalogProvider([new StandardEbooksCatalogProvider(datasource)], {
    debounceMs: 0,
    ...(cache ? { cache } : {}),
  });
}

describe('opds rail routing — composite to Standard Ebooks', () => {
  it('searchSource(opds) issues one Atom request and returns the whole shelf', async () => {
    const calls: FeedCall[] = [];
    const page = await compositeOver(calls).searchSource(BUILTIN_STANDARD_EBOOKS, '', 1);

    expect(calls).toEqual([{ url: STANDARD_EBOOKS_ATOM_FEED_URL, accept: 'application/atom+xml' }]);
    expect(page.results).toHaveLength(15);
    expect(page.totalCount).toBe(15);
    expect(page.results.every((book) => book.coverUrl !== null)).toBe(true);
    expect(page.results.every((book) => /\.epub(\?|$)/.test(book.downloadUrl ?? ''))).toBe(true);
    expect(page.results[0]).toMatchObject({
      id: 'standard-ebooks:henry-seton-merriman/suspense',
      title: 'Suspense',
      provider: BUILTIN_STANDARD_EBOOKS,
    });
  });

  it('keeps the provider search() door fail-closed — the interface stays honest', async () => {
    const calls: FeedCall[] = [];
    const provider = new StandardEbooksCatalogProvider(
      new StandardEbooksDataSource(stubFeedFetch(calls)),
    );

    // A keyword search cannot be honored by a single unpaged feed, so it must
    // stay empty. Routing through it was the bug; making it answer the shelf
    // would be a second bug wearing the first one's fix.
    await expect(provider.search('dickens', 1)).resolves.toEqual({
      results: [],
      nexoPage: null,
      totalCount: 0,
    });
    expect(calls).toEqual([]);
  });

  it('answers page 2 with an empty page and no extra I/O', async () => {
    const calls: FeedCall[] = [];
    const page = await compositeOver(calls).searchSource(BUILTIN_STANDARD_EBOOKS, '', 2);

    expect(page.results).toEqual([]);
    expect(page.totalCount).toBe(0);
    expect(page.nexoPage).toBe(computeNextPage(2, 0, 0));
    expect(calls).toEqual([]);
  });

  it('serves the cached shelf on a repeat call without touching the feed again', async () => {
    const calls: FeedCall[] = [];
    const subject = compositeOver(calls, new InMemoryDiscoverCache());

    const first = await subject.searchSource(BUILTIN_STANDARD_EBOOKS, '', 1);
    const second = await subject.searchSource(BUILTIN_STANDARD_EBOOKS, '', 1);

    expect(first.results).toHaveLength(15);
    expect(second.results).toHaveLength(15);
    expect(calls).toHaveLength(1);
  });

  it('propagates an upstream failure instead of hiding it behind an empty page', async () => {
    const calls: FeedCall[] = [];
    const datasource = new StandardEbooksDataSource(stubFeedFetch(calls, 401));
    const subject = new CompositeCatalogProvider([new StandardEbooksCatalogProvider(datasource)], {
      debounceMs: 0,
    });

    await expect(subject.searchSource(BUILTIN_STANDARD_EBOOKS, '', 1)).rejects.toMatchObject({
      code: 'UPSTREAM_ERROR',
    });
  });
});
