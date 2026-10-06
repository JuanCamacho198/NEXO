import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  DiscoverDomainState,
  TRENDING_CHIPS,
  filterBooksByChip,
  matchesChip,
} from '$lib/features/discover/DiscoverDomainState.svelte';
import { DISCOVER_RAIL_FETCH_LIMIT } from '$lib/features/discover/railPlan';
import { homeState } from '$lib/features/home/state.svelte';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  BUILTIN_GUTENDEX,
  type CatalogBook,
  type CatalogFeaturedSort,
  type CatalogProvider,
  type CatalogSource,
  type CatalogSourceInfo,
  type PagedResult,
} from '$lib/shared/services/catalog/CatalogProvider';
import {
  GutendexCatalogProvider,
  OpenLibraryCatalogProvider,
} from '$lib/shared/services/catalog/BuiltInCatalogProviders';
import { catalogError } from '$lib/shared/services/catalog/errors';
import { liveCatalogProvider } from '$lib/shared/services/catalog/liveComposite';
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

function stateWith(handler: (url: string) => { status: number; body: unknown }): {
  state: DiscoverDomainState;
  calls: { n: number };
} {
  const calls = { n: 0 };
  const counting = stubFetch((url) => {
    calls.n += 1;
    return handler(url);
  });
  const provider = new CompositeCatalogProvider(
    [
      new GutendexCatalogProvider(new GutendexDataSource(counting)),
      new OpenLibraryCatalogProvider(new OpenLibraryDataSource(counting)),
    ],
    { debounceMs: 0 },
  );
  return { state: new DiscoverDomainState(provider), calls };
}

function searchBodies() {
  return (url: string) => {
    if (url.includes('/books/1342/')) return { status: 200, body: gutendexFixture.results[0] };
    if (url.includes('gutendex')) return { status: 200, body: gutendexFixture };
    return { status: 200, body: openLibraryFixture };
  };
}

describe('DiscoverDomainState (PR2 RED)', () => {
  it('blank query resets to idle without I/O', async () => {
    const { state, calls } = stateWith(searchBodies());
    state.setQuery('pride');
    await state.searchFirstPage();
    expect(calls.n).toBeGreaterThan(0);
    state.setQuery('   ');
    expect(state.status).toBe('idle');
    expect(state.books).toEqual([]);
    const afterBlank = calls.n;
    await state.searchFirstPage();
    expect(calls.n).toBe(afterBlank);
    expect(state.status).toBe('idle');
  });

  it('search loads books with Gutendex authority and Gutenberg cover', async () => {
    const { state } = stateWith(searchBodies());
    state.setQuery('pride');
    await state.searchFirstPage();
    expect(state.status).toBe('loaded');
    expect(state.totalCount).toBe(3);
    const pride = state.books.find((b) => b.id === 'gutendex:1342');
    expect(pride?.coverUrl).toBe(
      'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg',
    );
    expect(state.books.some((b) => b.title === 'Borrow Restricted Title')).toBe(false);
  });

  it('loadNextPage appends without duplicates and guards null nextPage', async () => {
    const page1Records = Array.from({ length: 21 }, (_, i) => ({
      ...gutendexFixture.results[0],
      id: 1000 + i,
      title: `Page One Book ${i}`,
    }));
    const page2Records = Array.from({ length: 5 }, (_, i) => ({
      ...gutendexFixture.results[0],
      id: 2000 + i,
      title: `Page Two Book ${i}`,
    }));
    const handler = (url: string) => {
      if (url.includes('gutendex')) {
        const page2 = url.includes('page=2');
        const records = page2 ? page2Records : page1Records;
        return { status: 200, body: { count: 50, results: records } };
      }
      return { status: 200, body: { numFound: 50, docs: [] } };
    };
    const { state, calls } = stateWith(handler);
    state.setQuery('史诗');
    await state.searchFirstPage();
    expect(state.status).toBe('loaded');
    expect(state.nextPage).toBe(2);
    const firstCount = state.books.length;
    await state.loadNextPage();
    expect(state.activePage).toBe(2);
    expect(state.books.length).toBeGreaterThan(firstCount);
    const ids = state.books.map((b) => b.id);
    expect(new Set(ids).size).toBe(ids.length);
    const afterEnd = calls.n;
    state.nextPage = null;
    await state.loadNextPage();
    expect(calls.n).toBe(afterEnd);
  });

  it('NETWORK_ERROR maps to offline and retry recovers', async () => {
    let attempt = 0;
    const { state } = stateWith((url) => {
      attempt += 1;
      // One provider per search now (Open Library opted out of the fan-out),
      // so a single failed fetch is enough to surface the offline state.
      if (attempt <= 1) throw new TypeError('fetch failed');
      return searchBodies()(url);
    });
    state.setQuery('pride');
    await state.searchFirstPage();
    expect(state.status).toBe('offline');
    expect(state.errorCode).toBe('NETWORK_ERROR');
    await state.retry();
    expect(state.status).toBe('loaded');
    expect(state.errorCode).toBeNull();
    expect(state.books.length).toBeGreaterThan(0);
  });

  it('zero results map to empty, distinct from error', async () => {
    const { state } = stateWith((url) =>
      url.includes('gutendex')
        ? { status: 200, body: { count: 0, results: [] } }
        : { status: 200, body: { numFound: 0, docs: [] } },
    );
    state.setQuery('no-such-book-xyz');
    await state.searchFirstPage();
    expect(state.status).toBe('empty');
    expect(state.errorCode).toBeNull();
  });

  it('bad-id detail maps to notFound and dismiss preserves the list', async () => {
    const { state } = stateWith(searchBodies());
    state.setQuery('pride');
    await state.searchFirstPage();
    expect(state.status).toBe('loaded');
    const preserved = state.books.length;
    await state.openDetail('openlibrary:/works/OL11W');
    expect(state.detailStatus).toBe('notFound');
    expect(state.detail).toBeNull();
    state.dismissDetail();
    expect(state.detailStatus).toBe('closed');
    expect(state.books.length).toBe(preserved);
  });

  it('imports no user_books/outbox/sync/addon surface', () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const source = readFileSync(
      resolve(here, '../../lib/features/discover/DiscoverDomainState.svelte.ts'),
      'utf8',
    );
    for (const banned of ['user_books', 'outbox', 'addon']) {
      expect(source).not.toContain(banned);
    }
    expect(source).not.toMatch(/\bsync\b/);
  });
});

describe('DiscoverDomainState (PR3 grid/detail state mapping)', () => {
  it('UPSTREAM_ERROR maps to error (not offline) and retry recovers', async () => {
    let failing = true;
    const { state } = stateWith((url) => {
      if (failing) return { status: 500, body: { error: 'boom' } };
      return searchBodies()(url);
    });
    state.setQuery('pride');
    await state.searchFirstPage();
    expect(state.status).toBe('error');
    expect(state.errorCode).toBe('UPSTREAM_ERROR');
    failing = false;
    await state.retry();
    expect(state.status).toBe('loaded');
    expect(state.errorCode).toBeNull();
  });

  it('RATE_LIMITED maps to error not offline (throttling is not a connectivity failure)', async () => {
    const { state } = stateWith(() => ({ status: 429, body: { error: 'slow down' } }));
    state.setQuery('pride');
    await state.searchFirstPage();
    expect(state.status).toBe('error');
    expect(state.errorCode).toBe('RATE_LIMITED');
  });

  it('retry after failed loadNextPage re-requests the failed page', async () => {
    const page1Records = Array.from({ length: 21 }, (_, i) => ({
      ...gutendexFixture.results[0],
      id: 1000 + i,
      title: `Page One Book ${i}`,
    }));
    const page2Records = Array.from({ length: 3 }, (_, i) => ({
      ...gutendexFixture.results[0],
      id: 2000 + i,
      title: `Page Two Book ${i}`,
    }));
    let page2Failing = true;
    const handler = (url: string) => {
      if (url.includes('gutendex')) {
        const page2 = url.includes('page=2');
        if (page2) {
          if (page2Failing) return { status: 500, body: { error: 'boom' } };
          return { status: 200, body: { count: 50, results: page2Records } };
        }
        return { status: 200, body: { count: 50, results: page1Records } };
      }
      if (url.includes('page=2') && page2Failing) {
        return { status: 500, body: { error: 'boom' } };
      }
      return { status: 200, body: { numFound: 50, docs: [] } };
    };
    const { state } = stateWith(handler);
    state.setQuery('pride');
    await state.searchFirstPage();
    await state.loadNextPage();
    expect(state.status).toBe('error');
    expect(state.errorCode).toBe('UPSTREAM_ERROR');
    page2Failing = false;
    await state.retry();
    expect(state.status).toBe('loaded');
    expect(state.activePage).toBe(2);
  });

  it('successful detail load populates detail without touching the list', async () => {
    const { state } = stateWith(searchBodies());
    state.setQuery('pride');
    await state.searchFirstPage();
    const preserved = state.books.length;
    await state.openDetail('gutendex:1342');
    expect(state.detailStatus).toBe('loaded');
    expect(state.detail?.id).toBe('gutendex:1342');
    expect(state.detail?.title.length).toBeGreaterThan(0);
    expect(state.books.length).toBe(preserved);
  });

  it('UNAVAILABLE_DOWNLOAD detail failure maps to error status (code-only, redacted)', async () => {
    const { state } = stateWith((url) => {
      if (url.includes('/books/1342/')) return { status: 500, body: { error: 'boom' } };
      if (url.includes('gutendex')) return { status: 200, body: gutendexFixture };
      return { status: 200, body: openLibraryFixture };
    });
    state.setQuery('pride');
    await state.searchFirstPage();
    await state.openDetail('gutendex:1342');
    expect(state.detailStatus).toBe('error');
    expect(state.detail).toBeNull();
  });
});

describe('DiscoverDomainState (PR4 hardening)', () => {
  it('INVALID_PAGE (page < 1) rejects before any I/O and maps to error', async () => {
    const { state, calls } = stateWith(searchBodies());
    state.setQuery('pride');
    await state.searchFirstPage();
    expect(state.status).toBe('loaded');
    const callsAfterLoad = calls.n;
    state.nextPage = 0;
    await state.loadNextPage();
    expect(state.status).toBe('error');
    expect(state.errorCode).toBe('INVALID_PAGE');
    expect(calls.n).toBe(callsAfterLoad);
  });
});

function fakeBook(id: string, subjects: string[] = ['Fiction']): CatalogBook {
  return {
    id,
    provider: 'gutendex',
    title: `Title ${id}`,
    authors: ['Author'],
    coverUrl: null,
    languages: ['en'],
    subjects,
    downloadUrl: null,
  };
}

function paged(books: CatalogBook[]): PagedResult {
  return { results: books, nexoPage: null, totalCount: books.length };
}

const EMPTY_PAGE: PagedResult = { results: [], nexoPage: null, totalCount: 0 };

interface FakeProviderOptions {
  sources?: CatalogSourceInfo[];
  featuredBySort?: Partial<Record<CatalogFeaturedSort, CatalogBook[] | Error>>;
  featuredError?: Error;
  supports?: boolean;
  searchSourceError?: Error;
  searchSourceBooks?: CatalogBook[];
  /** Per-query outcome for `searchSource`; an Error value is thrown. */
  searchSourceByQuery?: (query: string) => CatalogBook[] | Error;
}

function fakeProvider(opts: FakeProviderOptions = {}): CatalogProvider & {
  calls: { featured: number; search: number; searchSource: number };
} {
  const calls = { featured: 0, search: 0, searchSource: 0 };
  const sources: CatalogSourceInfo[] = opts.sources ?? [
    { sourceId: 'builtin:fake' as CatalogSource, name: 'Fake', kind: 'builtin' },
  ];
  return {
    calls,
    async search(_query: string, _page: number): Promise<PagedResult> {
      calls.search += 1;
      return EMPTY_PAGE;
    },
    async getDetails(id: string): Promise<CatalogBook> {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    },
    async featured(sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
      calls.featured += 1;
      if (!Number.isInteger(limit) || limit < 1) {
        throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
      }
      if (opts.featuredError) throw opts.featuredError;
      const outcome = opts.featuredBySort?.[sort];
      if (outcome instanceof Error) throw outcome;
      return paged(outcome ?? []);
    },
    supportsFeatured(_sort: CatalogFeaturedSort): boolean {
      return opts.supports ?? true;
    },
    async searchSource(
      _sourceId: CatalogSource,
      query: string,
      _page: number,
    ): Promise<PagedResult> {
      calls.searchSource += 1;
      if (opts.searchSourceError) throw opts.searchSourceError;
      const outcome = opts.searchSourceByQuery?.(query);
      if (outcome instanceof Error) throw outcome;
      return paged(outcome ?? opts.searchSourceBooks ?? []);
    },
    resolveDownloadUrl(_formats: Record<string, string>, _preferEpub: boolean): string {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    listSources(): CatalogSourceInfo[] {
      return sources;
    },
  };
}

describe('desktop-descubrir Phase 3.1 — featured() sorts + liveComposite forwarding', () => {
  it('Gutendex featured() maps POPULAR to sort=popular and NEWEST to sort=descending', async () => {
    const seen: string[] = [];
    const fetchFn = stubFetch((url) => {
      seen.push(url);
      return { status: 200, body: gutendexFixture };
    });
    const provider = new GutendexCatalogProvider(new GutendexDataSource(fetchFn));
    await provider.featured('POPULAR', 6);
    await provider.featured('NEWEST', 6);
    expect(seen.some((url) => url.includes('sort=popular'))).toBe(true);
    expect(seen.some((url) => url.includes('sort=descending'))).toBe(true);
  });

  it('featured() rejects INVALID_PAGE for limit < 1 before any I/O', async () => {
    const seen: string[] = [];
    const fetchFn = stubFetch((url) => {
      seen.push(url);
      return { status: 200, body: gutendexFixture };
    });
    const provider = new GutendexCatalogProvider(new GutendexDataSource(fetchFn));
    await expect(provider.featured('POPULAR', 0)).rejects.toMatchObject({
      code: 'INVALID_PAGE',
    });
    expect(seen).toEqual([]);
    const composite = new CompositeCatalogProvider([fakeProvider()], { debounceMs: 0 });
    await expect(composite.featured('NEWEST', 0)).rejects.toMatchObject({
      code: 'INVALID_PAGE',
    });
  });

  it('Composite featured() fans out over supportsFeatured providers and propagates a failure', async () => {
    const healthy = fakeProvider({
      sources: [{ sourceId: 'builtin:gutendex' as CatalogSource, name: 'G', kind: 'builtin' }],
      featuredBySort: { POPULAR: [fakeBook('gutendex:1'), fakeBook('gutendex:2')] },
    });
    const throwing = fakeProvider({
      sources: [{ sourceId: 'builtin:thrower' as CatalogSource, name: 'T', kind: 'builtin' }],
      featuredError: catalogError('UPSTREAM_ERROR', 'boom'),
    });
    const optedOut = fakeProvider({
      sources: [{ sourceId: 'builtin:quiet' as CatalogSource, name: 'Q', kind: 'builtin' }],
      supports: false,
      featuredBySort: { POPULAR: [fakeBook('quiet:1')] },
    });
    const composite = new CompositeCatalogProvider([healthy, throwing, optedOut], {
      debounceMs: 0,
    });
    expect(composite.supportsFeatured('POPULAR')).toBe(true);
    // A failing opted-in provider fails the rail (→ `Error`) instead of being
    // swallowed into an empty page that would silently render `Hidden`.
    await expect(composite.featured('POPULAR', 6)).rejects.toMatchObject({
      code: 'UPSTREAM_ERROR',
    });
    expect(optedOut.calls.featured).toBe(0);
  });

  it('Composite featured() keeps a genuinely empty successful response empty', async () => {
    const empty = fakeProvider({
      sources: [{ sourceId: 'builtin:gutendex' as CatalogSource, name: 'G', kind: 'builtin' }],
      featuredBySort: { POPULAR: [] },
    });
    const composite = new CompositeCatalogProvider([empty], { debounceMs: 0 });
    const page = await composite.featured('POPULAR', 6);
    // Empty success still merges to an empty page (the rail renders `Hidden`).
    expect(page).toEqual({ results: [], nexoPage: null, totalCount: 0 });
  });

  it('Composite featured() merges the opted-in fan-out and leaves an empty rail Hidden', async () => {
    const first = fakeProvider({
      sources: [{ sourceId: 'builtin:gutendex' as CatalogSource, name: 'G', kind: 'builtin' }],
      featuredBySort: { POPULAR: [fakeBook('gutendex:1'), fakeBook('gutendex:2')] },
    });
    const second = fakeProvider({
      sources: [{ sourceId: 'builtin:openlibrary' as CatalogSource, name: 'O', kind: 'builtin' }],
      featuredBySort: { POPULAR: [] },
    });
    const composite = new CompositeCatalogProvider([first, second], { debounceMs: 0 });
    const page = await composite.featured('POPULAR', 6);
    // The healthy provider's page flows through the fan-out untouched.
    expect(page.results.map((b) => b.id)).toEqual(['gutendex:1', 'gutendex:2']);

    // Rail level: an empty featured page renders `Hidden`, never `Error`.
    const emptyComposite = new CompositeCatalogProvider(
      [
        fakeProvider({
          sources: [{ sourceId: 'builtin:gutendex' as CatalogSource, name: 'G', kind: 'builtin' }],
          featuredBySort: {},
        }),
      ],
      { debounceMs: 0 },
    );
    const state = new DiscoverDomainState(emptyComposite, {}, { now: () => new Date(2026, 5, 10) });
    await state.refreshRails();
    expect(state.rails[0]).toEqual({ kind: 'Hidden' });
    expect(state.isOnline).toBe(true);
  });

  it('Composite searchSource() routes the exact source and fails closed on unknown ids', async () => {
    const { state } = stateWith(searchBodies());
    void state;
    const provider = new CompositeCatalogProvider(
      [
        new GutendexCatalogProvider(new GutendexDataSource(stubFetch(searchBodies()))),
        new OpenLibraryCatalogProvider(new OpenLibraryDataSource(stubFetch(searchBodies()))),
      ],
      { debounceMs: 0 },
    );
    const owned = await provider.searchSource(BUILTIN_GUTENDEX, '', 1);
    expect(owned.results.length).toBeGreaterThan(0);
    const unknown = await provider.searchSource('builtin:nope' as CatalogSource, '', 1);
    expect(unknown).toEqual(EMPTY_PAGE);
  });

  it('liveComposite forwards featured/supportsFeatured/searchSource/listSources', () => {
    expect(typeof liveCatalogProvider.featured).toBe('function');
    expect(typeof liveCatalogProvider.supportsFeatured).toBe('function');
    expect(typeof liveCatalogProvider.searchSource).toBe('function');
    expect(typeof liveCatalogProvider.listSources).toBe('function');
    // Pre-build peek is fail-closed: no crash, empty sources, false capability.
    expect(liveCatalogProvider.listSources()).toEqual([]);
    expect(liveCatalogProvider.supportsFeatured('POPULAR')).toBe(false);
    const here = dirname(fileURLToPath(import.meta.url));
    const source = readFileSync(
      resolve(here, '../../lib/shared/services/catalog/liveComposite.ts'),
      'utf8',
    );
    expect(source).toContain('c.featured(sort, limit)');
    expect(source).toContain('c.searchSource(sourceId, query, page)');
  });
});

describe('desktop-descubrir Phase 3.2 — pill count, chip filter, fail-closed, truncation', () => {
  it('pill N equals listSources().length', () => {
    const { state } = stateWith(searchBodies());
    const sources = state.refreshSources();
    expect(sources.length).toBe(2);
    expect(sources.length).toBe(
      new CompositeCatalogProvider(
        [
          new GutendexCatalogProvider(new GutendexDataSource(stubFetch(searchBodies()))),
          new OpenLibraryCatalogProvider(new OpenLibraryDataSource(stubFetch(searchBodies()))),
        ],
        { debounceMs: 0 },
      ).listSources().length,
    );
  });

  it('chip filter is client-side with zero catalog calls', async () => {
    const provider = fakeProvider({ searchSourceBooks: [fakeBook('googlebooks:1')] });
    const state = new DiscoverDomainState(provider, {}, { now: () => new Date(2026, 5, 10) });
    await state.refreshRails();
    const afterRails = provider.calls.searchSource;
    expect(afterRails).toBeGreaterThan(0);
    const loaded = state.rails.find((rail) => rail.kind === 'Loaded');
    expect(loaded?.kind).toBe('Loaded');
    const books = loaded?.kind === 'Loaded' ? loaded.books : [];
    expect(books.length).toBeGreaterThan(0);
    const filtered = filterBooksByChip(books, 'Ficción');
    expect(filtered.length).toBeGreaterThan(0);
    expect(filtered.length).toBeLessThanOrEqual(books.length);
    expect(provider.calls.searchSource).toBe(afterRails);
    // Spanish label bridges to English catalog subjects.
    expect(matchesChip(fakeBook('x:1', ['Classic fiction']), 'Ficción')).toBe(true);
    expect(matchesChip(fakeBook('x:2', ['Science fiction']), 'Ciencia ficción')).toBe(true);
    expect(filterBooksByChip(books, null)).toBe(books);
  });

  it('exposes 7 static trending chips', () => {
    expect(TRENDING_CHIPS).toHaveLength(7);
  });

  it('per-rail fail-closed: a failing author shelf fails only that rail', async () => {
    const provider = fakeProvider({
      searchSourceByQuery: (query) =>
        query === 'edgar allan poe'
          ? catalogError('UPSTREAM_ERROR', 'boom')
          : [fakeBook('googlebooks:ok')],
    });
    const state = new DiscoverDomainState(provider, {}, { now: () => new Date(2026, 5, 10) });
    await state.refreshRails();
    expect(state.rails).toHaveLength(3);
    expect(state.rails[0]?.kind).toBe('Loaded');
    expect(state.rails[1]).toEqual({ kind: 'Error', code: 'UPSTREAM_ERROR', offline: false });
    expect(state.rails[2]?.kind).toBe('Loaded');
    expect(state.isOnline).toBe(true);
  });

  it('per-rail fail-closed: a term error fails only the last rail', async () => {
    const provider = fakeProvider({
      searchSourceByQuery: (query) =>
        query === 'oscar wilde'
          ? catalogError('UPSTREAM_ERROR', 'term search down')
          : [fakeBook('googlebooks:ok')],
    });
    const state = new DiscoverDomainState(provider, {}, { now: () => new Date(2026, 5, 10) });
    await state.refreshRails();
    expect(state.rails).toHaveLength(3);
    expect(state.rails[0]?.kind).toBe('Loaded');
    expect(state.rails[1]?.kind).toBe('Loaded');
    expect(state.rails[2]).toEqual({ kind: 'Error', code: 'UPSTREAM_ERROR', offline: false });
  });

  it('short rails render as-is and long rails truncate to the fetch limit', async () => {
    const many = Array.from({ length: 30 }, (_, i) => fakeBook(`googlebooks:${100 + i}`));
    const provider = fakeProvider({
      searchSourceByQuery: (query) =>
        query === 'arthur conan doyle'
          ? many
          : [fakeBook('googlebooks:1'), fakeBook('googlebooks:2'), fakeBook('googlebooks:3')],
    });
    const state = new DiscoverDomainState(provider, {}, { now: () => new Date(2026, 5, 10) });
    await state.refreshRails();
    const longRail = state.rails[0];
    const shortRail = state.rails[1];
    expect(longRail.kind === 'Loaded' ? longRail.books.length : -1).toBe(DISCOVER_RAIL_FETCH_LIMIT);
    expect(shortRail.kind === 'Loaded' ? shortRail.books.length : -1).toBe(3);
  });
});

describe('desktop-descubrir Phase 3.3 — skeleton, offline retry, discover route', () => {
  it('skeleton then rails: refreshRails exposes Loading before settling to Loaded', async () => {
    let release!: (books: CatalogBook[]) => void;
    const gate = new Promise<CatalogBook[]>((resolve) => {
      release = resolve;
    });
    const provider = fakeProvider({ searchSourceBooks: [fakeBook('googlebooks:1')] });
    const gated: CatalogProvider = {
      ...provider,
      searchSource: (_sourceId, _query, _page) =>
        gate.then((books) => paged(books.slice(0, DISCOVER_RAIL_FETCH_LIMIT))),
    };
    const state = new DiscoverDomainState(gated, {}, { now: () => new Date(2026, 5, 10) });
    expect(state.rails.every((rail) => rail.kind === 'Hidden')).toBe(true);
    const pending = state.refreshRails();
    expect(state.rails.every((rail) => rail.kind === 'Loading')).toBe(true);
    release([fakeBook('googlebooks:1'), fakeBook('googlebooks:2')]);
    await pending;
    expect(state.rails.filter((rail) => rail.kind === 'Loaded').length).toBeGreaterThan(0);
  });

  it('offline then retry: connectivity failure flips isOnline false and retry recovers', async () => {
    let failing = true;
    const base = fakeProvider({ searchSourceBooks: [fakeBook('googlebooks:3')] });
    const flaky: CatalogProvider = {
      ...base,
      searchSource: async (sourceId, query, page) => {
        if (failing) throw catalogError('NETWORK_ERROR', 'offline');
        return base.searchSource(sourceId, query, page);
      },
    };
    const state = new DiscoverDomainState(flaky, {}, { now: () => new Date(2026, 5, 10) });
    await state.refreshRails();
    expect(state.isOnline).toBe(false);
    expect(state.rails).toHaveLength(3);
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Error', 'Error', 'Error']);
    expect(state.rails[0]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    failing = false;
    await state.refreshRails();
    expect(state.isOnline).toBe(true);
    expect(state.rails[0]?.kind).toBe('Loaded');
  });

  it('discover route literal typechecks and AppRouter renders DiscoverScreen', () => {
    homeState.setRoute('discover');
    expect(homeState.route).toBe('discover');
    homeState.setRoute('home');
    const here = dirname(fileURLToPath(import.meta.url));
    const router = readFileSync(
      resolve(here, '../../lib/shared/ui/layout/AppRouter.svelte'),
      'utf8',
    );
    expect(router).toContain("navigationState.route === 'discover'");
    expect(router).toContain('DiscoverScreen');
    const homeStateSource = readFileSync(
      resolve(here, '../../lib/features/home/state.svelte.ts'),
      'utf8',
    );
    expect(homeStateSource).toContain("'discover'");
  });
});
