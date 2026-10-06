import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CHIP_KEYWORDS,
  DiscoverDomainState,
  TRENDING_CHIPS,
} from '$lib/features/discover/DiscoverDomainState.svelte';
import { DiscoverRailsDomainState } from '$lib/features/discover/DiscoverRailsDomainState.svelte';
import {
  buildRailSpecs,
  DISCOVER_RAIL_COUNT,
  DISCOVER_RAIL_FETCH_LIMIT,
  DISCOVER_RAIL_LIMIT,
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
  BUILTIN_GOOGLEBOOKS,
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

/** 2026-06-10: local day-of-year 161 and start index 0 ⇒ doyle, poe, wilde. */
const PINNED_DAY = new Date(2026, 5, 10, 12);
const pinnedNow = (): Date => PINNED_DAY;
const PINNED_TERMS = ['arthur conan doyle', 'edgar allan poe', 'oscar wilde'] as const;
const PINNED_KEYS = [
  'discover.rail.author.conan-doyle',
  'discover.rail.author.poe',
  'discover.rail.author.wilde',
] as const;

function book(id: string): CatalogBook {
  return {
    id,
    provider: 'googlebooks',
    title: `Title ${id}`,
    authors: ['Author'],
    coverUrl: null,
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
      return paged([]);
    },
    supportsFeatured(): boolean {
      return false;
    },
    async searchSource(sourceId, query, page): Promise<PagedResult> {
      requests.push({ kind: 'searchSource', sourceId, query, page });
      if (behaviour.searchSourceError) throw behaviour.searchSourceError;
      const outcome = behaviour.searching?.(query) ?? [];
      if (outcome instanceof Error) throw outcome;
      return paged(outcome);
    },
    resolveDownloadUrl(): string {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    listSources(): CatalogSourceInfo[] {
      return [{ sourceId: BUILTIN_GOOGLEBOOKS, name: 'Google Books', kind: 'builtin' }];
    },
  };
  return { provider, requests };
}

function sourceRequests(requests: RailRequest[]): Extract<RailRequest, { kind: 'searchSource' }>[] {
  return requests.filter(
    (request): request is Extract<RailRequest, { kind: 'searchSource' }> =>
      request.kind === 'searchSource',
  );
}

describe('discover rails — fixed three-rail author shelves', () => {
  it('plans exactly three distinct author shelves, all termed', () => {
    const specs = buildRailSpecs(PINNED_DAY);
    expect(DISCOVER_RAIL_COUNT).toBe(3);
    expect(DISCOVER_RAIL_LIMIT).toBe(6);
    expect(RAIL_SCOPE_LIMIT).toBe(24);
    expect(specs).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(specs.map((spec) => spec.kind)).toEqual(['author', 'author', 'author']);
    expect(specs.map((spec) => spec.term)).toEqual([...PINNED_TERMS]);
    expect(specs.map((spec) => spec.titleKey)).toEqual([...PINNED_KEYS]);
    // Distinct within a day.
    expect(new Set(specs.map((spec) => spec.term)).size).toBe(DISCOVER_RAIL_COUNT);
    // Stable for the same local calendar day, shifting across days.
    expect(buildRailSpecs(new Date(2026, 5, 10, 0, 5))).toEqual(specs);
    expect(buildRailSpecs(new Date(2026, 5, 11))).not.toEqual(specs);
  });

  it('refreshRails issues one Google Books source search per rail and never featured', async () => {
    const { provider, requests } = recordingProvider({ searching: () => [book('googlebooks:1')] });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(requests.map((request) => request.kind)).toEqual([
      'searchSource',
      'searchSource',
      'searchSource',
    ]);
    for (const call of sourceRequests(requests)) {
      expect(call.sourceId).toBe(BUILTIN_GOOGLEBOOKS);
      expect(call.query.trim()).not.toBe('');
      expect(call.query).not.toContain(':');
      expect(call.page).toBe(1);
    }
    // The retired featured and Gutendex paths are never touched by the rails.
    expect(requests.some((request) => request.kind === 'featured')).toBe(false);
    expect(requests.some((request) => request.kind === 'search')).toBe(false);
  });

  it('keeps rail positions index-stable and publishes the whole plan', async () => {
    const { provider } = recordingProvider({
      searching: (query) => [book(`googlebooks:${query}`)],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.specs).toHaveLength(DISCOVER_RAIL_COUNT);
    const loaded = state.rails.map((rail) => (rail.kind === 'Loaded' ? rail.books[0]?.id : ''));
    expect(loaded).toEqual(PINNED_TERMS.map((term) => `googlebooks:${term}`));
  });

  it('truncates a long rail to the fetch limit while short rails render as-is', async () => {
    const many = Array.from({ length: 30 }, (_, i) => book(`googlebooks:${100 + i}`));
    const { provider } = recordingProvider({
      searching: (query) => (query === PINNED_TERMS[0] ? many : [book('googlebooks:one')]),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const longRail = state.rails[0];
    expect(longRail.kind === 'Loaded' ? longRail.books.length : -1).toBe(DISCOVER_RAIL_FETCH_LIMIT);
    const shortRail = state.rails[2];
    expect(shortRail.kind === 'Loaded' ? shortRail.books.length : -1).toBe(1);
  });

  it('gives every rail a distinct query signature so no two shelves share a request', async () => {
    const { provider, requests } = recordingProvider({ searching: () => [book('googlebooks:1')] });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const signatures = sourceRequests(requests).map(
      (request) => `searchSource:${request.sourceId}:${request.query}`,
    );
    expect(signatures).toEqual(
      PINNED_TERMS.map((term) => `searchSource:${BUILTIN_GOOGLEBOOKS}:${term}`),
    );
    expect(new Set(signatures).size).toBe(signatures.length);
  });

  it('exposes the three-shelf set through the DiscoverDomainState facade', async () => {
    const { provider } = recordingProvider({ searching: () => [book('googlebooks:1')] });
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });
    await state.ensureRailsLoaded();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded']);
    expect(state.railsState.specs).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(state.railsState.specs.map((spec) => spec.term)).toEqual([...PINNED_TERMS]);
    expect(state.isOnline).toBe(true);
  });
});

describe('discover rails — isolated error and retry', () => {
  it('isolates a failing rail: the other rails keep their books', async () => {
    const failingTerm = PINNED_TERMS[1];
    const base = recordingProvider({ searching: () => [book('googlebooks:1')] });
    const requests = base.requests;
    const flaky: CatalogProvider = {
      ...base.provider,
      searchSource: async (sourceId, query, page) => {
        if (query === failingTerm) {
          requests.push({ kind: 'searchSource', sourceId, query, page });
          throw catalogError('UPSTREAM_ERROR', 'boom');
        }
        return base.provider.searchSource(sourceId, query, page);
      },
    };
    const state = new DiscoverRailsDomainState({ provider: flaky, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails[0].kind).toBe('Loaded');
    expect(state.rails[1]).toEqual({ kind: 'Error', code: 'UPSTREAM_ERROR', offline: false });
    expect(state.rails[2].kind).toBe('Loaded');
    expect(state.settled).toBe(true);

    const untouched = state.rails[0];
    const beforeRetry = requests.length;
    await state.retryRail(1);
    expect(state.rails[0]).toBe(untouched);
    expect(state.rails[2].kind).toBe('Loaded');
    // Retry re-resolved ONLY the failing rail: exactly one new request, for it.
    expect(requests.slice(beforeRetry)).toEqual([
      { kind: 'searchSource', sourceId: BUILTIN_GOOGLEBOOKS, query: failingTerm, page: 1 },
    ]);

    // A rail that is not in `Error` is never re-resolved.
    await state.retryRail(0);
    expect(requests.length).toBe(beforeRetry + 1);
  });

  it('flips the connectivity flag only for offline failures', async () => {
    const { provider } = recordingProvider({
      searchSourceError: catalogError('NETWORK_ERROR', 'offline'),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Error', 'Error', 'Error']);
    expect(state.rails[0]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    expect(state.isOnline).toBe(false);
    // NETWORK_ERROR is retryable, so automatic retries are pending; release them.
    state.dispose();
  });

  it('does not refetch a settled rail set on remount', async () => {
    const { provider, requests } = recordingProvider({ searching: () => [book('googlebooks:1')] });
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
    const { provider, requests } = recordingProvider({ searching: () => [book('googlebooks:1')] });
    // The screen shares one `DiscoverDomainState` singleton, so a remount runs
    // this same load path again; requests are counted, not just state observed.
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });

    await state.ensureRailsLoaded();
    const afterFirstMount = requests.length;
    expect(afterFirstMount).toBe(DISCOVER_RAIL_COUNT);
    const published = state.rails;
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded']);

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
        return paged([]);
      },
      supportsFeatured(): boolean {
        return false;
      },
      async searchSource(sourceId, query, page): Promise<PagedResult> {
        requests.push({ kind: 'searchSource', sourceId, query, page });
        await gate;
        return paged([book(`googlebooks:${query}`)]);
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
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loading', 'Loading', 'Loading']);
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT);

    // A remount while `Loading` must not re-request any rail.
    const remount = state.ensureLoaded();
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT);

    releaseRails();
    await Promise.all([firstMount, remount]);

    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(state.settled).toBe(true);
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded']);
  });
});

describe('discover rails — curated author rotation', () => {
  it('never degrades to an unsorted query across empty, error and retry', async () => {
    const behaviour: FakeBehaviour = {
      searching: (query) => (query === PINNED_TERMS[2] ? [] : [book('googlebooks:1')]),
    };
    const { provider, requests } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.rails[2]).toEqual({ kind: 'Hidden' });
    expect(state.rails[0].kind).toBe('Loaded');

    behaviour.searching = (query) =>
      query === PINNED_TERMS[2] ? catalogError('UPSTREAM_ERROR', 'boom') : [book('googlebooks:1')];
    await state.refreshRails();
    expect(state.rails[2].kind).toBe('Error');
    expect(state.rails[0].kind).toBe('Loaded');

    behaviour.searching = () => [book('googlebooks:9')];
    await state.retryRail(2);
    expect(state.rails[2].kind).toBe('Loaded');

    const termCalls = sourceRequests(requests);
    expect(termCalls.length).toBeGreaterThanOrEqual(3);
    for (const call of termCalls) {
      expect(call.sourceId).toBe(BUILTIN_GOOGLEBOOKS);
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
    expect(CHIP_KEYWORDS['Ciencia ficción']).toEqual(['science']);
    expect(TRENDING_CHIPS).toHaveLength(7);
    expect(AUTHOR_ROTATION.map((entry) => entry.term)).not.toContain('science');
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
    const { provider, requests } = recordingProvider({ searching: () => [book('googlebooks:1')] });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    await state.refreshRails();
    const remounted = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await remounted.ensureLoaded();

    const terms = sourceRequests(requests).map((call) => call.query);
    expect(terms).toHaveLength(9);
    expect(new Set(terms)).toEqual(new Set(PINNED_TERMS));
    expect(remounted.specs[2].term).toBe(PINNED_TERMS[2]);
  });
});

describe('discover rails — retired rail paths in code', () => {
  it('keeps no Recomendados rail, curated first slice, featured rail or Gutendex rail', () => {
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
    expect(readSource('railPlan.ts')).toContain(
      'provider.searchSource(BUILTIN_GOOGLEBOOKS, spec.term, 1)',
    );
    expect(readSource('railPlan.ts')).not.toContain('BUILTIN_GUTENDEX');
    expect(readSource('railPlan.ts')).not.toContain('provider.featured(');
    // The scoped browse resolves against Google Books too.
    expect(readSource('DiscoverRailsDomainState.svelte.ts')).toContain(
      'this.provider.searchSource(BUILTIN_GOOGLEBOOKS, scope.term, this.scopePage + 1)',
    );
  });

  it('resolves rail titles from i18n in both locales', () => {
    const railKeys = [
      'discover.rail.newest',
      'discover.rail.popular',
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
    const base = recordingProvider({ searching: () => [book('googlebooks:1')] }).provider;
    const provider: CatalogProvider = {
      ...base,
      searchSource: async (sourceId, query, page) =>
        query === PINNED_TERMS[1]
          ? paged((await slow).slice(0, DISCOVER_RAIL_FETCH_LIMIT))
          : base.searchSource(sourceId, query, page),
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    const pending = state.refreshRails();
    await flushMicrotasks();
    // Each rail published on its own; nothing waits for the slowest rail.
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loading', 'Loaded']);
    expect(state.settled).toBe(false);

    releaseSlow([book('googlebooks:slow')]);
    await pending;
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded']);
    expect(state.rails[1].kind === 'Loaded' ? state.rails[1].books[0]?.id : '').toBe(
      'googlebooks:slow',
    );
    expect(state.settled).toBe(true);
  });

  it('settles a hung rail to Error at the deadline and never leaves it Loading', async () => {
    vi.useFakeTimers();
    const hung = new Promise<PagedResult>(() => undefined);
    const base = recordingProvider({ searching: () => [book('googlebooks:1')] }).provider;
    const provider: CatalogProvider = {
      ...base,
      searchSource: (sourceId, query, page) =>
        query === PINNED_TERMS[1] ? hung : base.searchSource(sourceId, query, page),
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    const pending = state.refreshRails();
    await vi.advanceTimersByTimeAsync(0);
    // One hung rail never blocks the healthy rails from settling.
    expect(state.rails[0].kind).toBe('Loaded');
    expect(state.rails[2].kind).toBe('Loaded');
    expect(state.rails[1].kind).toBe('Loading');

    await vi.advanceTimersByTimeAsync(REQUEST_DEADLINE_MS);
    await pending;
    // A slow source is a timeout, not a connectivity failure: the rail errors
    // with the retryable UPSTREAM_TIMEOUT code and the hero pill stays online.
    expect(state.rails[1]).toEqual({
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

describe('discover rails — rail-scoped "Ver todo"', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('opens an author term scope and pages the Google Books source search', async () => {
    const { provider, requests } = recordingProvider({
      searching: (query) => [book(`googlebooks:${query}`)],
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

    await state.openScope({ kind: 'term', term: PINNED_TERMS[2], titleKey: PINNED_KEYS[2] });
    expect(state.scopeExhausted).toBe(false);
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([
      `googlebooks:${PINNED_TERMS[2]}:1`,
    ]);

    await state.loadScopeNextPage();
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([
      `googlebooks:${PINNED_TERMS[2]}:1`,
      `googlebooks:${PINNED_TERMS[2]}:2`,
    ]);
    expect(state.scopeExhausted).toBe(true);

    // Exhausted: a further page is a no-op.
    await state.loadScopeNextPage();
    expect(requests.length).toBe(before + 2);
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
    // The retired featured scope's single-page copy is no longer rendered.
    expect(source).not.toContain('discover.railScope.singlePage');
    expect(source).not.toContain('railViews');
  });
});
