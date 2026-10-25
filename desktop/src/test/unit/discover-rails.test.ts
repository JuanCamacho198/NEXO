import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CHIP_KEYWORDS,
  DiscoverDomainState,
  TRENDING_CHIPS,
} from '$lib/features/discover/DiscoverDomainState.svelte';
import { CHIP_TAXONOMY } from '$lib/features/discover/discoverChips';
import { DiscoverRailsDomainState } from '$lib/features/discover/DiscoverRailsDomainState.svelte';
import {
  buildRailSpecs,
  DISCOVER_RAIL_COUNT,
  DISCOVER_RAIL_FETCH_LIMIT,
  DISCOVER_RAIL_LIMIT,
  loadRail,
  RAIL_SCOPE_LIMIT,
  withDeadline,
} from '$lib/features/discover/railPlan';
import {
  AUTHOR_ROTATION,
  authorEntriesFor,
  authorStartIndex,
  dayOfYear,
} from '$lib/features/discover/railRotation';
import {
  BUILTIN_GUTENDEX,
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_STANDARD_EBOOKS,
  catalogError,
  REQUEST_DEADLINE_MS,
  type CatalogBook,
  type CatalogProvider,
  type CatalogSource,
  type CatalogSourceInfo,
  type PagedResult,
} from '$lib/shared/services/catalog';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

const HERE = dirname(fileURLToPath(import.meta.url));
const DISCOVER_DIR = resolve(HERE, '../../lib/features/discover');

function readSource(file: string): string {
  return readFileSync(resolve(DISCOVER_DIR, file), 'utf8');
}

/** 2026-06-10: local day-of-year 161 and start index 0 ⇒ doyle, poe. */
const PINNED_DAY = new Date(2026, 5, 10, 12);
const pinnedNow = (): Date => PINNED_DAY;
const PINNED_TERMS = ['arthur conan doyle', 'edgar allan poe'] as const;
const PINNED_KEYS = [
  'discover.rail.author.conan-doyle',
  'discover.rail.author.poe',
  'discover.rail.gutendexPopular',
  'discover.rail.standardEbooks',
] as const;
/** The two fixed-source rails' upstream sources, in plan order. */
const FIXED_RAIL_SOURCES = [BUILTIN_GUTENDEX, BUILTIN_STANDARD_EBOOKS] as const;

/**
 * Title-cased surname of a rail term: the author credit a fake returns so the
 * rail author filter keeps its books (`arthur conan doyle` → `Doyle`).
 */
function railAuthor(term: string): string {
  const surname = term.trim().split(/\s+/).pop() ?? term;
  return surname.charAt(0).toUpperCase() + surname.slice(1);
}

/**
 * Rail books carry a cover: a coverless book is dropped from rails by design,
 * so a fixture without one would hide every shelf it is fed to.
 */
function book(id: string, authors: string[] = ['Author']): CatalogBook {
  return {
    id,
    provider: 'googlebooks',
    title: `Title ${id}`,
    authors,
    coverUrl: `https://covers.example.org/${id}.jpg`,
    languages: ['en'],
    subjects: ['Fiction'],
    downloadUrl: null,
  };
}

function paged(books: CatalogBook[]): PagedResult {
  return { results: books, nexoPage: null, totalCount: books.length };
}

/** Let already-resolved rail promises publish without advancing fake timers. */
const flushMicrotasks = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

type RailRequest =
  | { kind: 'featured'; limit: number }
  | { kind: 'searchSource'; sourceId: CatalogSource; query: string; page: number }
  | { kind: 'search'; query: string; page: number };

interface FakeBehaviour {
  /** Per-query outcome; an Error value is thrown from `searchSource`. */
  searching?: (query: string) => CatalogBook[] | Error;
  /** Per-source outcome, applied before `searching` (falls back to it). */
  bySource?: (sourceId: string, query: string) => CatalogBook[] | Error | undefined;
  searchSourceError?: Error | null;
}

/** Recording fake provider: every catalog call is captured as a rail request. */
function recordingProvider(behaviour: FakeBehaviour = {}): {
  provider: CatalogProvider;
  requests: RailRequest[];
} {
  const requests: RailRequest[] = [];
  const provider: CatalogProvider = {
    async search(query, page): Promise<PagedResult> {
      requests.push({ kind: 'search', query, page });
      return paged([]);
    },
    async getDetails(id: string): Promise<CatalogBook> {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    },
    async featured(_sort, limit): Promise<PagedResult> {
      requests.push({ kind: 'featured', limit });
      // The popular rail resolves through this path, so it must answer with a
      // cover-bearing book for index-stability assertions to see a Loaded rail.
      return paged([book('gutendex:1342')]);
    },
    supportsFeatured(): boolean {
      return true;
    },
    async searchSource(sourceId, query, page): Promise<PagedResult> {
      requests.push({ kind: 'searchSource', sourceId, query, page });
      if (behaviour.searchSourceError) throw behaviour.searchSourceError;
      const outcome = behaviour.bySource?.(sourceId, query) ?? behaviour.searching?.(query) ?? [];
      if (outcome instanceof Error) throw outcome;
      return paged(outcome);
    },
    resolveDownloadUrl(): string {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    listSources(): CatalogSourceInfo[] {
      return [
        { sourceId: BUILTIN_GOOGLEBOOKS, name: 'Google Books', kind: 'builtin' },
        { sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' },
        { sourceId: BUILTIN_STANDARD_EBOOKS, name: 'Standard Ebooks', kind: 'builtin' },
      ];
    },
  };
  return { provider, requests };
}

/** One cover-bearing book per rail, whatever source the fake is answering for. */
function anyRailBook(sourceId: string, query: string): CatalogBook[] {
  return [book(`${sourceId}:${query}`, [railAuthor(query)])];
}

function sourceRequests(requests: RailRequest[]): Extract<RailRequest, { kind: 'searchSource' }>[] {
  return requests.filter(
    (request): request is Extract<RailRequest, { kind: 'searchSource' }> =>
      request.kind === 'searchSource',
  );
}

describe('discover rails — four-rail mixed-source plan', () => {
  it('plans two rotating author shelves plus one Gutendex and one Standard Ebooks rail', () => {
    const specs = buildRailSpecs(PINNED_DAY);
    expect(DISCOVER_RAIL_COUNT).toBe(4);
    expect(DISCOVER_RAIL_LIMIT).toBe(6);
    expect(RAIL_SCOPE_LIMIT).toBe(24);
    expect(specs).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(specs.map((spec) => spec.kind)).toEqual(['author', 'author', 'popular', 'opds']);
    // Only the author rails carry a term; the fixed shelves are term-free.
    expect(specs.filter((spec) => spec.kind === 'author').map((spec) => spec.term)).toEqual([
      ...PINNED_TERMS,
    ]);
    expect(specs.map((spec) => spec.titleKey)).toEqual([...PINNED_KEYS]);
    // Distinct author terms within a day.
    const authorTerms = specs
      .flatMap((spec) => (spec.kind === 'author' ? [spec.term] : []))
      .filter(Boolean);
    expect(new Set(authorTerms).size).toBe(PINNED_TERMS.length);
    // Stable for the same local calendar day.
    expect(buildRailSpecs(new Date(2026, 5, 10, 0, 5))).toEqual(specs);
  });

  it('rotates only the author shelves and never renumbers a fixed rail', () => {
    const today = buildRailSpecs(new Date(2026, 5, 10));
    const tomorrow = buildRailSpecs(new Date(2026, 5, 11));
    expect(tomorrow).not.toEqual(today);
    // The two fixed shelves keep their positions across the day boundary.
    expect(tomorrow[2]).toEqual(today[2]);
    expect(tomorrow[3]).toEqual(today[3]);
    expect(tomorrow[2]?.kind).toBe('popular');
    expect(tomorrow[3]?.kind).toBe('opds');
    // The author window slid one slot forward.
    expect(tomorrow[0]).toEqual(today[1]);
  });

  it('routes each rail kind to its own source, never an accidental empty search', async () => {
    const { provider, requests } = recordingProvider({ bySource: anyRailBook });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails).toHaveLength(DISCOVER_RAIL_COUNT);
    // The popular rail resolves through `featured('POPULAR')`, not a term search.
    expect(requests.filter((request) => request.kind === 'featured')).toHaveLength(1);
    const calls = sourceRequests(requests);
    expect(calls.map((call) => call.sourceId)).toEqual([
      BUILTIN_GOOGLEBOOKS,
      BUILTIN_GOOGLEBOOKS,
      BUILTIN_STANDARD_EBOOKS,
    ]);
    // Author rails search a real term; the Standard Ebooks rail passes none.
    expect(calls.slice(0, 2).map((call) => call.query)).toEqual([...PINNED_TERMS]);
    expect(calls[2]?.query).toBe('');
    for (const call of calls) expect(call.page).toBe(1);
    // No rail ever issues a keyword search on the composite path.
    expect(requests.some((request) => request.kind === 'search')).toBe(false);
  });

  it('keeps rail positions index-stable and publishes the whole plan', async () => {
    const { provider } = recordingProvider({ bySource: anyRailBook });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.specs).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded', 'Loaded']);
    const ids = state.rails.map((rail) => (rail.kind === 'Loaded' ? rail.books[0]?.id : ''));
    expect(ids).toEqual([
      `${BUILTIN_GOOGLEBOOKS}:${PINNED_TERMS[0]}`,
      `${BUILTIN_GOOGLEBOOKS}:${PINNED_TERMS[1]}`,
      // The popular rail comes from the featured fake, the opds rail from its source.
      'gutendex:1342',
      `${BUILTIN_STANDARD_EBOOKS}:`,
    ]);
  });

  it('truncates a long rail to the fetch limit while short rails render as-is', async () => {
    const many = Array.from({ length: 30 }, (_, i) =>
      book(`googlebooks:${100 + i}`, [railAuthor(PINNED_TERMS[0])]),
    );
    const { provider } = recordingProvider({
      bySource: (sourceId, query) =>
        sourceId === BUILTIN_GOOGLEBOOKS && query === PINNED_TERMS[0]
          ? many
          : [book(`${sourceId}:${query}`, [railAuthor(query)])],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const longRail = state.rails[0];
    expect(longRail.kind === 'Loaded' ? longRail.books.length : -1).toBe(DISCOVER_RAIL_FETCH_LIMIT);
    const shortRail = state.rails[1];
    expect(shortRail.kind === 'Loaded' ? shortRail.books.length : -1).toBe(1);
  });

  it('keeps only the rail author and hides an author rail whose page has none', async () => {
    const { provider } = recordingProvider({
      bySource: (sourceId, query) => {
        if (query === PINNED_TERMS[0]) {
          return [
            book('googlebooks:owned', ['Arthur Conan Doyle']),
            // The title mentions the author but `authors` does not: the old
            // free-text ranking would have surfaced it on the shelf.
            { ...book('googlebooks:critic', ['Some Critic']), title: 'Arthur Conan Doyle: A Life' },
            book('googlebooks:authorless', []),
          ];
        }
        if (sourceId === BUILTIN_GOOGLEBOOKS) {
          // No book by the rail author: the rail hides outright, never an empty
          // row and never a fallback to the unfiltered page.
          return [book('googlebooks:foreign', ['Herman Melville'])];
        }
        return [book(`${sourceId}:${query}`)];
      },
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const owned = state.rails[0];
    expect(owned.kind === 'Loaded' ? owned.books.map((entry) => entry.id) : []).toEqual([
      'googlebooks:owned',
    ]);
    expect(state.rails[1]).toEqual({ kind: 'Hidden' });
    // The fixed-source rails are not author-filtered, so they still render.
    expect(state.rails[2].kind).toBe('Loaded');
    expect(state.rails[3].kind).toBe('Loaded');
  });

  it('gives every rail a distinct source signature so no two shelves share a request', async () => {
    const { provider, requests } = recordingProvider({ bySource: anyRailBook });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const signatures = requests.map((request) => {
      if (request.kind === 'featured') return `featured:POPULAR:${request.limit}`;
      if (request.kind === 'searchSource') {
        return `${request.kind}:${request.sourceId}:${request.query}`;
      }
      return `${request.kind}:${request.query}`;
    });
    expect(signatures).toEqual([
      `searchSource:${BUILTIN_GOOGLEBOOKS}:${PINNED_TERMS[0]}`,
      `searchSource:${BUILTIN_GOOGLEBOOKS}:${PINNED_TERMS[1]}`,
      `featured:POPULAR:${DISCOVER_RAIL_FETCH_LIMIT}`,
      `searchSource:${BUILTIN_STANDARD_EBOOKS}:`,
    ]);
    expect(new Set(signatures).size).toBe(signatures.length);
  });

  it('exposes the four-rail set through the DiscoverDomainState facade', async () => {
    const { provider } = recordingProvider({ bySource: anyRailBook });
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });
    await state.ensureRailsLoaded();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded', 'Loaded']);
    expect(state.railsState.specs).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(state.railsState.specs.map((spec) => spec.kind)).toEqual([
      'author',
      'author',
      'popular',
      'opds',
    ]);
    expect(state.isOnline).toBe(true);
  });
});

describe('discover rails — coverless books are dropped from rails only', () => {
  it('drops a coverless book from a rail but keeps its covered siblings', async () => {
    const { provider } = recordingProvider({
      bySource: (sourceId, query) => [
        book(`${sourceId}:covered`, [railAuthor(query)]),
        { ...book(`${sourceId}:coverless`, [railAuthor(query)]), coverUrl: null },
      ],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    for (const rail of state.rails) {
      expect(rail.kind).toBe('Loaded');
      if (rail.kind !== 'Loaded') continue;
      expect(rail.books.every((entry) => entry.coverUrl !== null)).toBe(true);
      expect(rail.books).toHaveLength(1);
    }
  });

  it('hides an all-coverless rail instead of rendering a bare header', async () => {
    const { provider } = recordingProvider({
      bySource: (sourceId, query) => [
        { ...book(`${sourceId}:coverless`, [railAuthor(query)]), coverUrl: null },
      ],
    });
    // The popular rail resolves via featured(), so it needs its own coverless page.
    const coverlessPopular: CatalogProvider = {
      ...provider,
      featured: async () => paged([{ ...book('gutendex:1'), coverUrl: null }]),
    };
    const state = new DiscoverRailsDomainState({ provider: coverlessPopular, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Hidden', 'Hidden', 'Hidden', 'Hidden']);
  });

  it('keeps coverless books out of the rail loader, not out of search', async () => {
    // The filter lives on the rail path only: a coverless book still reaches
    // search results, which the reader explicitly asked for.
    const rails = readSource('DiscoverRailsDomainState.svelte.ts');
    expect(rails).toContain('book.coverUrl !== null');
    const screen = readSource('DiscoverScreen.svelte');
    expect(screen).not.toContain('coverUrl');
    const search = readSource('DiscoverDomainState.svelte.ts');
    expect(search).not.toContain('coverUrl');
  });
});

describe('discover rails — isolated error and retry', () => {
  it('isolates a failing rail: the other rails keep their books', async () => {
    const base = recordingProvider({ bySource: anyRailBook });
    const requests = base.requests;
    // The opds rail is the one that fails; every other rail answers normally.
    const flaky: CatalogProvider = {
      ...base.provider,
      searchSource: async (sourceId, query, page) => {
        if (sourceId === BUILTIN_STANDARD_EBOOKS) {
          requests.push({ kind: 'searchSource', sourceId, query, page });
          throw catalogError('UPSTREAM_ERROR', 'boom');
        }
        return base.provider.searchSource(sourceId, query, page);
      },
    };
    const state = new DiscoverRailsDomainState({ provider: flaky, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails[0].kind).toBe('Loaded');
    expect(state.rails[1].kind).toBe('Loaded');
    expect(state.rails[2].kind).toBe('Loaded');
    expect(state.rails[3]).toEqual({ kind: 'Error', code: 'UPSTREAM_ERROR', offline: false });
    expect(state.settled).toBe(true);

    const untouched = state.rails[0];
    const beforeRetry = requests.length;
    await state.retryRail(3);
    expect(state.rails[0]).toBe(untouched);
    expect(state.rails[2].kind).toBe('Loaded');
    // Retry re-resolved ONLY the failing rail: exactly one new request, for it.
    expect(requests.slice(beforeRetry)).toEqual([
      { kind: 'searchSource', sourceId: BUILTIN_STANDARD_EBOOKS, query: '', page: 1 },
    ]);

    // A rail that is not in `Error` is never re-resolved.
    await state.retryRail(0);
    expect(requests.length).toBe(beforeRetry + 1);
  });

  it('flips the connectivity flag only for offline failures', async () => {
    const { provider } = recordingProvider({
      searchSourceError: catalogError('NETWORK_ERROR', 'offline'),
    });
    const offline: CatalogProvider = {
      ...provider,
      featured: async () => {
        throw catalogError('NETWORK_ERROR', 'offline');
      },
    };
    const state = new DiscoverRailsDomainState({ provider: offline, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Error', 'Error', 'Error', 'Error']);
    expect(state.rails[0]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    expect(state.isOnline).toBe(false);
    // NETWORK_ERROR is retryable, so automatic retries are pending; release them.
    state.dispose();
  });

  it('does not refetch a settled rail set on remount', async () => {
    const { provider, requests } = recordingProvider({ bySource: anyRailBook });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.ensureLoaded();
    const afterFirstMount = requests.length;
    expect(afterFirstMount).toBe(DISCOVER_RAIL_COUNT);

    await state.ensureLoaded();
    expect(requests.length).toBe(afterFirstMount);
  });
});

describe('discover rails — remount dedup (spec: "Remount does not refetch resolved rails")', () => {
  it('issues no additional provider request when a resolved rail set is mounted again', async () => {
    const { provider, requests } = recordingProvider({ bySource: anyRailBook });
    // The screen shares one `DiscoverDomainState` singleton, so a remount runs
    // this same load path again; requests are counted, not just state observed.
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });

    await state.ensureRailsLoaded();
    const afterFirstMount = requests.length;
    expect(afterFirstMount).toBe(DISCOVER_RAIL_COUNT);
    const published = state.rails;
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded', 'Loaded']);

    await state.ensureRailsLoaded();
    await state.ensureRailsLoaded();

    expect(requests.length).toBe(afterFirstMount);
    // Reused content, not a refetched replacement.
    expect(state.rails).toBe(published);

    // "…until an explicit refresh or invalidation occurs": the explicit
    // refresh path still resolves a fresh plan.
    await state.refreshRails();
    expect(requests.length).toBe(afterFirstMount + DISCOVER_RAIL_COUNT);
  });

  it('joins an in-flight rail load instead of issuing a second round of requests', async () => {
    const requests: RailRequest[] = [];
    let releaseRails!: () => void;
    const gate = new Promise<void>((resolve) => {
      releaseRails = resolve;
    });
    const gated: CatalogProvider = {
      async search(query, page): Promise<PagedResult> {
        requests.push({ kind: 'search', query, page });
        return paged([]);
      },
      async getDetails(id: string): Promise<CatalogBook> {
        throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
      },
      async featured(_sort, limit): Promise<PagedResult> {
        requests.push({ kind: 'featured', limit });
        await gate;
        return paged([book('gutendex:1342')]);
      },
      supportsFeatured(): boolean {
        return false;
      },
      async searchSource(sourceId, query, page): Promise<PagedResult> {
        requests.push({ kind: 'searchSource', sourceId, query, page });
        await gate;
        return paged([book(`${sourceId}:${query}`, [railAuthor(query)])]);
      },
      resolveDownloadUrl(): string {
        throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
      },
      listSources(): CatalogSourceInfo[] {
        return [{ sourceId: BUILTIN_GOOGLEBOOKS, name: 'Google Books', kind: 'builtin' }];
      },
    };
    const state = new DiscoverRailsDomainState({ provider: gated, now: pinnedNow });

    const firstMount = state.ensureLoaded();
    await flushMicrotasks();
    // Every rail is in flight (`Loading`) and no rail has settled yet.
    expect(state.settled).toBe(false);
    expect(state.rails.map((rail) => rail.kind)).toEqual([
      'Loading',
      'Loading',
      'Loading',
      'Loading',
    ]);
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT);

    // A remount while `Loading` must not re-request any rail.
    const remount = state.ensureLoaded();
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT);

    releaseRails();
    await Promise.all([firstMount, remount]);

    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(state.settled).toBe(true);
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded', 'Loaded']);
  });
});

describe('discover rails — curated author rotation', () => {
  it('never degrades an author rail to an unsorted query across empty, error and retry', async () => {
    const behaviour: FakeBehaviour = {
      bySource: (sourceId, query) =>
        sourceId === BUILTIN_GOOGLEBOOKS && query === PINNED_TERMS[1]
          ? []
          : anyRailBook(sourceId, query),
    };
    const { provider, requests } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.rails[1]).toEqual({ kind: 'Hidden' });
    expect(state.rails[0].kind).toBe('Loaded');

    behaviour.bySource = (sourceId, query) =>
      sourceId === BUILTIN_GOOGLEBOOKS && query === PINNED_TERMS[1]
        ? catalogError('UPSTREAM_ERROR', 'boom')
        : anyRailBook(sourceId, query);
    await state.refreshRails();
    expect(state.rails[1].kind).toBe('Error');
    expect(state.rails[0].kind).toBe('Loaded');

    behaviour.bySource = anyRailBook;
    await state.retryRail(1);
    expect(state.rails[1].kind).toBe('Loaded');

    const authorCalls = sourceRequests(requests).filter(
      (call) => call.sourceId === BUILTIN_GOOGLEBOOKS,
    );
    expect(authorCalls.length).toBeGreaterThanOrEqual(3);
    for (const call of authorCalls) {
      expect(call.query.trim()).not.toBe('');
      expect(PINNED_TERMS).toContain(call.query);
      expect(call.page).toBe(1);
    }
  });

  it('pins the ten curated public-domain authors, best-vetted first', () => {
    expect(AUTHOR_ROTATION).toHaveLength(10);
    expect(AUTHOR_ROTATION.map((entry) => entry.term)).toEqual([
      'arthur conan doyle',
      'edgar allan poe',
      'oscar wilde',
      'jules verne',
      'franz kafka',
      'charles dickens',
      'jack london',
      'h g wells',
      'mark twain',
      'herman melville',
    ]);
    for (const entry of AUTHOR_ROTATION) {
      expect(entry.slug).toBe(entry.titleKey.slice('discover.rail.author.'.length));
      expect(messagesEn[entry.titleKey]).toBeTruthy();
      expect(messagesEs[entry.titleKey]).toBeTruthy();
      // Proper nouns: identical in EN and ES.
      expect(messagesEs[entry.titleKey]).toBe(messagesEn[entry.titleKey]);
    }
  });

  it('drops the measured-bad authors and never carries a colon operator', () => {
    const terms = AUTHOR_ROTATION.map((entry) => entry.term);
    expect(terms).not.toContain('jane austen');
    expect(terms).not.toContain('leo tolstoy');
    for (const term of terms) expect(term).not.toContain(':');

    // The exclusion decision is recorded in code so nobody adds them back.
    const source = readSource('railRotation.ts');
    expect(source).toContain('jane austen');
    expect(source).toContain('leo tolstoy');
    expect(source).toContain('colon operator');
  });

  it('does not borrow the client-side chip keyword taxonomy', () => {
    const source = readSource('railRotation.ts');
    expect(source).not.toContain('CHIP_KEYWORDS');
    expect(source).not.toContain('TRENDING_CHIPS');
    // The chip taxonomy itself is untouched — its subject substrings stay as-is.
    expect(CHIP_KEYWORDS['science']).toEqual(['science']);
    expect(CHIP_KEYWORDS['mystery']).toEqual(['mystery', 'detective']);
    expect(TRENDING_CHIPS).toHaveLength(7);
    expect(AUTHOR_ROTATION.map((entry) => entry.term)).not.toContain('science');
  });

  it('resolves each chip label from i18n while its ES fallback stays unchanged', () => {
    expect(CHIP_TAXONOMY).toHaveLength(7);
    for (const chip of CHIP_TAXONOMY) {
      expect(messagesEs[chip.labelKey]).toBe(chip.fallback);
      expect(messagesEn[chip.labelKey]).toBeTruthy();
    }
  });

  it('selects a deterministic window: stable within a day and sliding across days', () => {
    const morning = new Date(2026, 5, 10, 0, 5);
    const night = new Date(2026, 5, 10, 23, 55);
    expect(dayOfYear(morning)).toBe(161);
    expect(authorStartIndex(161)).toBe(0);
    expect(authorEntriesFor(morning)).toEqual(authorEntriesFor(night));

    const today = authorEntriesFor(new Date(2026, 5, 10)).map((entry) => entry.term);
    const tomorrow = authorEntriesFor(new Date(2026, 5, 11)).map((entry) => entry.term);
    // Consecutive days slide the three-author window one slot forward.
    expect(tomorrow).toEqual([...today.slice(1), AUTHOR_ROTATION[3]!.term]);
    expect(tomorrow).not.toEqual(today);
  });

  it('reuses the same shelves across refresh, retry and a fresh mount', async () => {
    const { provider, requests } = recordingProvider({ bySource: anyRailBook });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    await state.refreshRails();
    const remounted = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await remounted.ensureLoaded();

    const terms = sourceRequests(requests)
      .filter((call) => call.sourceId === BUILTIN_GOOGLEBOOKS)
      .map((call) => call.query);
    // Three loads (two refreshes + one mount) × two author rails.
    expect(terms).toHaveLength(6);
    expect(new Set(terms)).toEqual(new Set(PINNED_TERMS));
    expect(remounted.specs[1]?.kind === 'author' ? remounted.specs[1].term : '').toBe(
      PINNED_TERMS[1],
    );
  });
});

describe('discover rails — retired rail paths in code', () => {
  it('keeps no Recomendados rail, curated first slice or featured rail', () => {
    for (const file of [
      'DiscoverDomainState.svelte.ts',
      'DiscoverRailsDomainState.svelte.ts',
      'railPlan.ts',
    ]) {
      const source = readSource(file);
      expect(source).not.toContain('Recomendados');
      expect(source).not.toContain('CURATED_FIRST_SLICE');
      expect(source).not.toContain('DISCOVER_RAIL_SPECS');
      expect(source).not.toMatch(/searchSource\(BUILTIN_GUTENDEX, ''/);
    }
    // Each rail kind routes to the provider that can actually answer it.
    const plan = readSource('railPlan.ts');
    expect(plan).toContain('provider.searchSource(BUILTIN_GOOGLEBOOKS, spec.term, 1)');
    // The popular rail uses the verified sort ordering: an empty term search
    // took 64s live and would time out every rail deadline.
    expect(plan).toContain("provider.featured('POPULAR', limit)");
    expect(plan).not.toContain('BUILTIN_GUTENDEX,');
    expect(plan).toContain(
      'provider.searchSource(BUILTIN_STANDARD_EBOOKS, STANDARD_EBOOKS_RAIL_QUERY, 1)',
    );
    // The scoped browse resolves against Google Books too.
    expect(readSource('DiscoverRailsDomainState.svelte.ts')).toContain(
      'this.provider.searchSource(BUILTIN_GOOGLEBOOKS, scope.term, this.scopePage + 1)',
    );
  });

  it('resolves rail titles from i18n in both locales', () => {
    const railKeys = [
      'discover.rail.newest',
      'discover.rail.popular',
      'discover.rail.gutendexPopular',
      'discover.rail.standardEbooks',
      'discover.rail.thematic.fiction',
      'discover.rail.thematic.classic',
      'discover.rail.thematic.adventure',
      'discover.rail.thematic.mystery',
      'discover.rail.thematic.romance',
      'discover.rail.thematic.science',
      'discover.rail.thematic.history',
      'discover.rail.viewAll',
      ...AUTHOR_ROTATION.map((entry) => entry.titleKey),
    ] as const;
    for (const key of railKeys) {
      expect(messagesEn[key]).toBeTruthy();
      expect(messagesEs[key]).toBeTruthy();
      expect(messagesEs[key]).not.toContain('Gutenberg');
      expect(messagesEn[key]).not.toContain('Gutenberg');
    }
    expect(messagesEs['discover.rail.author.conan-doyle']).toBe('Arthur Conan Doyle');
    expect(messagesEs['discover.rail.newest']).toBe('Recién agregados');
    expect(messagesEs['discover.rail.popular']).toBe('Populares');
    // The two new rail titles exist in both locales and actually translate.
    expect(messagesEn['discover.rail.gutendexPopular']).toContain('downloaded');
    expect(messagesEs['discover.rail.gutendexPopular']).toContain('descargados');
    expect(messagesEn['discover.rail.standardEbooks']).toContain('Standard Ebooks');
    expect(messagesEs['discover.rail.standardEbooks']).toContain('Standard Ebooks');
    expect(messagesEs['discover.rail.standardEbooks']).not.toBe(
      messagesEn['discover.rail.standardEbooks'],
    );
  });
});

describe('discover rails — bounded rail attempt helper', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('resolves work that finishes before the deadline', async () => {
    await expect(withDeadline(Promise.resolve('ready'), 1_000)).resolves.toBe('ready');
  });

  it('rejects a hung attempt at the deadline with UPSTREAM_TIMEOUT', async () => {
    vi.useFakeTimers();
    const hung = new Promise<string>(() => undefined);
    const assertion = expect(withDeadline(hung, 15_000)).rejects.toMatchObject({
      code: 'UPSTREAM_TIMEOUT',
    });
    await vi.advanceTimersByTimeAsync(15_000);
    await assertion;
  });
});

describe('discover rails — progressive per-rail publish', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('publishes a fast rail while a slow rail is still Loading, index-stably', async () => {
    let releaseSlow!: (books: CatalogBook[]) => void;
    const slow = new Promise<CatalogBook[]>((resolve) => {
      releaseSlow = resolve;
    });
    const base = recordingProvider({ bySource: anyRailBook }).provider;
    const provider: CatalogProvider = {
      ...base,
      featured: async () => paged((await slow).slice(0, DISCOVER_RAIL_FETCH_LIMIT)),
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    const pending = state.refreshRails();
    await flushMicrotasks();
    // Each rail published on its own; nothing waits for the slowest rail.
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loading', 'Loaded']);
    expect(state.settled).toBe(false);

    releaseSlow([book('gutendex:1342', ['Author'])]);
    await pending;
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded', 'Loaded']);
    expect(state.rails[2].kind === 'Loaded' ? state.rails[2].books[0]?.id : '').toBe(
      'gutendex:1342',
    );
    expect(state.settled).toBe(true);
  });

  it('settles a hung rail to Error at the deadline and never leaves it Loading', async () => {
    vi.useFakeTimers();
    const hung = new Promise<PagedResult>(() => undefined);
    const base = recordingProvider({ bySource: anyRailBook }).provider;
    const provider: CatalogProvider = {
      ...base,
      featured: () => hung,
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    const pending = state.refreshRails();
    await vi.advanceTimersByTimeAsync(0);
    // One hung rail never blocks the healthy rails from settling.
    expect(state.rails[0].kind).toBe('Loaded');
    expect(state.rails[1].kind).toBe('Loaded');
    expect(state.rails[3].kind).toBe('Loaded');
    expect(state.rails[2].kind).toBe('Loading');

    await vi.advanceTimersByTimeAsync(REQUEST_DEADLINE_MS);
    await pending;
    // A slow source is a timeout, not a connectivity failure: the rail errors
    // with the retryable UPSTREAM_TIMEOUT code and the hero pill stays online.
    expect(state.rails[2]).toEqual({
      kind: 'Error',
      code: 'UPSTREAM_TIMEOUT',
      offline: false,
    });
    expect(state.rails.some((rail) => rail.kind === 'Loading')).toBe(false);
    expect(state.isOnline).toBe(true);
    state.dispose();
  });

  it('keeps no aggregate all-rails-settled publish gate in the state layer', () => {
    const source = readSource('DiscoverRailsDomainState.svelte.ts');
    expect(source).toContain('this.rails[index] = settled');
    expect(source).not.toMatch(/Promise\.all\(/);
  });
});

describe('discover rails — per-kind resolution', () => {
  function silentProvider(seen: { sourceId: string; query: string }[]): CatalogProvider {
    return {
      ...recordingProvider().provider,
      async searchSource(sourceId, query): Promise<PagedResult> {
        seen.push({ sourceId, query });
        return paged([book(`${sourceId}:1`)]);
      },
    };
  }

  it('sends the popular rail to the verified POPULAR featured ordering', async () => {
    const seen: string[] = [];
    const specs = buildRailSpecs(PINNED_DAY);
    const popular = specs.find((spec) => spec.kind === 'popular');
    expect(popular).toBeDefined();
    const provider: CatalogProvider = {
      ...recordingProvider().provider,
      async featured(sort): Promise<PagedResult> {
        seen.push(sort);
        return paged([book('gutendex:1342')]);
      },
      async searchSource(sourceId, query): Promise<PagedResult> {
        seen.push(`searchSource:${sourceId}:${query}`);
        return paged([book(`${sourceId}:1`)]);
      },
    };
    await loadRail(provider, popular!, 6);
    // An empty term search took 64s live; the sort ordering answers in ~250ms.
    expect(seen).toEqual(['POPULAR']);
  });

  it('sends the opds rail to Standard Ebooks', async () => {
    const seen: { sourceId: string; query: string }[] = [];
    const specs = buildRailSpecs(PINNED_DAY);
    const opds = specs.find((spec) => spec.kind === 'opds');
    expect(opds).toBeDefined();
    await loadRail(silentProvider(seen), opds!);
    expect(seen).toEqual([{ sourceId: BUILTIN_STANDARD_EBOOKS, query: '' }]);
  });

  it('sends an author rail to Google Books with its term', async () => {
    const seen: { sourceId: string; query: string }[] = [];
    await loadRail(silentProvider(seen), {
      kind: 'author',
      term: 'herman melville',
      titleKey: 'discover.rail.author.melville',
    });
    expect(seen).toEqual([{ sourceId: BUILTIN_GOOGLEBOOKS, query: 'herman melville' }]);
  });
});

describe('discover rails — rail-scoped "Ver todo"', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('opens an author term scope and pages the Google Books source search', async () => {
    const { provider, requests } = recordingProvider({
      searching: (query) => [book(`googlebooks:${query}`, [railAuthor(query)])],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
    });

    expect(state.scope).toEqual({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
    });
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([`googlebooks:${PINNED_TERMS[0]}`]);
    expect(state.scopeError).toBeNull();
    expect(sourceRequests(requests.slice(before))).toEqual([
      { kind: 'searchSource', sourceId: BUILTIN_GOOGLEBOOKS, query: PINNED_TERMS[0], page: 1 },
    ]);
  });

  it('pages the term scope until a page reports no next page', async () => {
    const requests: RailRequest[] = [];
    const provider: CatalogProvider = {
      ...recordingProvider().provider,
      async searchSource(sourceId, query, page): Promise<PagedResult> {
        requests.push({ kind: 'searchSource', sourceId, query, page });
        return {
          results: [book(`googlebooks:${query}:${page}`)],
          nexoPage: page === 1 ? 2 : null,
          totalCount: 2,
        };
      },
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({ kind: 'term', term: PINNED_TERMS[1], titleKey: PINNED_KEYS[1] });
    expect(state.scopeExhausted).toBe(false);
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([
      `googlebooks:${PINNED_TERMS[1]}:1`,
    ]);

    await state.loadScopeNextPage();
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([
      `googlebooks:${PINNED_TERMS[1]}:1`,
      `googlebooks:${PINNED_TERMS[1]}:2`,
    ]);
    expect(state.scopeExhausted).toBe(true);

    // Exhausted: a further page is a no-op.
    await state.loadScopeNextPage();
    expect(requests.length).toBe(before + 2);
    // Natural exhaustion is not a cap truncation: no truncation note.
    expect(state.scopeAtDepthCap).toBe(false);
  });

  it('stops the term scope at the depth cap and flags the truncation', async () => {
    const requests: RailRequest[] = [];
    const provider: CatalogProvider = {
      ...recordingProvider().provider,
      async searchSource(sourceId, query, page): Promise<PagedResult> {
        requests.push({ kind: 'searchSource', sourceId, query, page });
        return {
          results: [book(`googlebooks:${query}:${page}`)],
          nexoPage: page + 1,
          totalCount: 1_000_000,
        };
      },
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({ kind: 'term', term: PINNED_TERMS[1], titleKey: PINNED_KEYS[1] });
    for (let i = 0; i < 20; i += 1) {
      await state.loadScopeNextPage();
    }
    const pages = requests
      .slice(before)
      .filter((request) => request.kind === 'searchSource')
      .map((request) => (request as { page: number }).page);
    // Six 24-item pages (startIndex 0–120); the prod startIndex=216 is unreachable.
    expect(pages).toEqual([1, 2, 3, 4, 5, 6]);
    expect(state.scopeBooks).toHaveLength(6);
    expect(state.scopeExhausted).toBe(true);
    expect(state.scopeAtDepthCap).toBe(true);

    // Capped: a further page issues no request.
    await state.loadScopeNextPage();
    expect(requests.length).toBe(before + 6);

    // A fresh scope starts unflagged; closing drops the flag too.
    await state.openScope({ kind: 'term', term: PINNED_TERMS[0], titleKey: PINNED_KEYS[0] });
    expect(state.scopeAtDepthCap).toBe(false);
    state.closeScope();
    expect(state.scopeAtDepthCap).toBe(false);
  });

  it('joins concurrent scope loads instead of duplicating the request', async () => {
    const requests: RailRequest[] = [];
    let releasePage2: (value: PagedResult) => void = () => undefined;
    const provider: CatalogProvider = {
      ...recordingProvider().provider,
      async searchSource(sourceId, query, page): Promise<PagedResult> {
        requests.push({ kind: 'searchSource', sourceId, query, page });
        if (page === 1) {
          return {
            results: [book(`googlebooks:${query}:1`)],
            nexoPage: 2,
            totalCount: 3,
          };
        }
        return new Promise<PagedResult>((resolve) => {
          releasePage2 = resolve;
        });
      },
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    await state.openScope({ kind: 'term', term: PINNED_TERMS[0], titleKey: PINNED_KEYS[0] });
    const before = requests.length;

    const first = state.loadScopeNextPage();
    const second = state.loadScopeNextPage();
    await flushMicrotasks();
    // One threshold crossing, one request: the second call joins the in-flight page.
    expect(requests.length).toBe(before + 1);

    releasePage2({ results: [book('googlebooks:term:2')], nexoPage: null, totalCount: 2 });
    await first;
    await second;
    expect(requests.length).toBe(before + 1);
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([
      `googlebooks:${PINNED_TERMS[0]}:1`,
      'googlebooks:term:2',
    ]);
    expect(state.scopeExhausted).toBe(true);
    expect(state.scopeAtDepthCap).toBe(false);
  });

  it('captures a scope failure instead of throwing and clears it on retry', async () => {
    const behaviour: FakeBehaviour = { searching: () => [book('googlebooks:term')] };
    const { provider } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    behaviour.searchSourceError = catalogError('NETWORK_ERROR', 'offline');
    await state.openScope({ kind: 'term', term: PINNED_TERMS[0], titleKey: PINNED_KEYS[0] });
    expect(state.scopeError).toBe('NETWORK_ERROR');
    expect(state.scopeBooks).toEqual([]);

    behaviour.searchSourceError = null;
    behaviour.searching = () => [book('googlebooks:recovered')];
    await state.loadScopeNextPage();
    expect(state.scopeError).toBeNull();
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual(['googlebooks:recovered']);
  });

  it('leaves hero, search and chips state untouched when a rail scope opens', async () => {
    const { provider } = recordingProvider({ searching: () => [book('googlebooks:2')] });
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });
    await state.ensureRailsLoaded();
    state.setQuery('dune');

    await state.openRailScope({ kind: 'term', term: PINNED_TERMS[0], titleKey: PINNED_KEYS[0] });

    // "Ver todo" must never write the hero search query.
    expect(state.query).toBe('dune');
    expect(state.status).toBe('idle');
    expect(state.books).toEqual([]);

    state.closeRailScope();
    expect(state.railsState.scope).toBeNull();
    expect(state.railsState.scopeBooks).toEqual([]);
  });
});

describe('discover rails — per-rail error presentation', () => {
  it('exposes an inline rail error component reusing the shared split copy', () => {
    const component = readSource('DiscoverRailError.svelte');
    expect(component).toContain('discoverErrorKey');
    expect(component).toContain("t('discover.retry')");
    // The split lives in one place and covers slow sources and rate limiting.
    const copy = readSource('discoverErrorCopy.ts');
    expect(copy).toContain("'discover.offline'");
    expect(copy).toContain("'discover.errorSlow'");
    expect(copy).toContain("'discover.rateLimited'");
    expect(copy).toContain("'discover.errorUpstream'");
  });

  it('renders Loading, Loaded and Error per rail with a "Ver todo" header control', () => {
    const source = readSource('DiscoverRailSection.svelte');
    expect(source).toContain("railState.kind === 'Loading'");
    expect(source).toContain("railState.kind === 'Error'");
    expect(source).toContain('<DiscoverRailError');
    expect(source).toContain("t('discover.rail.viewAll')");
    expect(source).toContain('onViewAll');
  });

  it('wires rail actions and the term-scoped view in the screen without an aggregate gate', () => {
    const source = readSource('DiscoverScreen.svelte');
    expect(source).toContain('openRailScope(index)');
    expect(source).toContain('discoverState.retryRail(index)');
    expect(source).toContain("t('discover.railScope.back')");
    expect(source).toContain("kind: 'term'");
    // The depth cap truncates the scope instead of inventing results, so the
    // retired single-page copy is rendered again with the loaded count.
    expect(source).toContain('discover.railScope.singlePage');
    expect(source).toContain('scopeAtDepthCap');
    expect(source).not.toContain('railViews');
  });
});
