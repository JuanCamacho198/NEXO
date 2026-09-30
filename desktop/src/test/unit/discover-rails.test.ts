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
  dayOfYear,
  THEMATIC_ROTATION,
  thematicEntryFor,
  thematicIndexFor,
} from '$lib/features/discover/railRotation';
import {
  BUILTIN_GUTENDEX,
  catalogError,
  REQUEST_DEADLINE_MS,
  type CatalogBook,
  type CatalogFeaturedSort,
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

/** 2026-06-10: local day-of-year 161 and 161 mod 7 === 0 ⇒ the Ficción entry. */
const PINNED_DAY = new Date(2026, 5, 10, 12);
const pinnedNow = (): Date => PINNED_DAY;

function book(id: string): CatalogBook {
  return {
    id,
    provider: 'gutendex',
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
  | { kind: 'featured'; sort: CatalogFeaturedSort; limit: number }
  | { kind: 'searchSource'; sourceId: CatalogSource; query: string; page: number }
  | { kind: 'search'; query: string; page: number };

interface FakeBehaviour {
  featured?: (sort: CatalogFeaturedSort, limit: number) => CatalogBook[];
  searching?: (query: string) => CatalogBook[];
  featuredError?: Error | null;
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
    async featured(sort, limit): Promise<PagedResult> {
      requests.push({ kind: 'featured', sort, limit });
      if (behaviour.featuredError) throw behaviour.featuredError;
      return paged(behaviour.featured?.(sort, limit) ?? []);
    },
    supportsFeatured(): boolean {
      return true;
    },
    async searchSource(sourceId, query, page): Promise<PagedResult> {
      requests.push({ kind: 'searchSource', sourceId, query, page });
      if (behaviour.searchSourceError) throw behaviour.searchSourceError;
      return paged(behaviour.searching?.(query) ?? []);
    },
    resolveDownloadUrl(): string {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    listSources(): CatalogSourceInfo[] {
      return [{ sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' }];
    },
  };
  return { provider, requests };
}

function featuredRequests(requests: RailRequest[]): Extract<RailRequest, { kind: 'featured' }>[] {
  return requests.filter(
    (request): request is Extract<RailRequest, { kind: 'featured' }> => request.kind === 'featured',
  );
}

function sourceRequests(requests: RailRequest[]): Extract<RailRequest, { kind: 'searchSource' }>[] {
  return requests.filter(
    (request): request is Extract<RailRequest, { kind: 'searchSource' }> =>
      request.kind === 'searchSource',
  );
}

describe('discover rails — fixed three-rail set', () => {
  it('plans exactly three rails in a fixed order, all ordered or termed', () => {
    const specs = buildRailSpecs(PINNED_DAY);
    expect(DISCOVER_RAIL_COUNT).toBe(3);
    expect(DISCOVER_RAIL_LIMIT).toBe(6);
    expect(RAIL_SCOPE_LIMIT).toBe(24);
    expect(specs).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(specs.map((spec) => spec.kind)).toEqual(['featured', 'featured', 'thematic']);
    expect(specs[0]).toEqual({
      kind: 'featured',
      sort: 'NEWEST',
      titleKey: 'discover.rail.newest',
    });
    expect(specs[1]).toEqual({
      kind: 'featured',
      sort: 'POPULAR',
      titleKey: 'discover.rail.popular',
    });
    expect(specs[2].kind === 'thematic' ? specs[2].term : '').toBe('fiction');
  });

  it('refreshRails issues one ordered request per rail and never an unsorted query', async () => {
    const { provider, requests } = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:2')],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(requests.map((request) => request.kind)).toEqual([
      'featured',
      'featured',
      'searchSource',
    ]);
    expect(featuredRequests(requests).map((request) => request.sort)).toEqual([
      'NEWEST',
      'POPULAR',
    ]);
    const termCalls = sourceRequests(requests);
    expect(termCalls).toHaveLength(1);
    for (const call of termCalls) {
      expect(call.sourceId).toBe(BUILTIN_GUTENDEX);
      expect(call.query.trim()).not.toBe('');
    }
    // The former unsorted "De Project Gutenberg" source rail is gone.
    expect(requests.some((request) => request.kind === 'search')).toBe(false);
  });

  it('keeps rail positions index-stable and publishes the whole plan', async () => {
    const { provider } = recordingProvider({
      featured: (sort) => [book(sort === 'NEWEST' ? 'gutendex:new' : 'gutendex:pop')],
      searching: () => [book('gutendex:term')],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.specs).toHaveLength(DISCOVER_RAIL_COUNT);
    const loaded = state.rails.map((rail) => (rail.kind === 'Loaded' ? rail.books[0]?.id : ''));
    expect(loaded).toEqual(['gutendex:new', 'gutendex:pop', 'gutendex:term']);
  });

  it('truncates a long rail to the fetch limit while short rails render as-is', async () => {
    const many = Array.from({ length: 30 }, (_, i) => book(`gutendex:${100 + i}`));
    const { provider } = recordingProvider({
      featured: () => many,
      searching: () => [book('gutendex:term')],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const featured = state.rails[0];
    expect(featured.kind === 'Loaded' ? featured.books.length : -1).toBe(DISCOVER_RAIL_FETCH_LIMIT);
    const thematic = state.rails[2];
    expect(thematic.kind === 'Loaded' ? thematic.books.length : -1).toBe(1);
  });

  it('gives every rail a distinct request signature so no two rails share a source query', async () => {
    // The duplicate-content root cause was an unsorted query whose default
    // Gutendex ordering coincided with `sort=popular`. Even when a source
    // returns the identical list, the rails must ask with distinct orderings.
    const { provider, requests } = recordingProvider({
      featured: () => [book('gutendex:shared')],
      searching: () => [book('gutendex:shared')],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const signatures = requests.map((request) => {
      if (request.kind === 'featured') return `featured:${request.sort}`;
      if (request.kind === 'searchSource') {
        return `searchSource:${request.sourceId}:${request.query}`;
      }
      return `search:${request.query}`;
    });
    expect(signatures).toEqual([
      'featured:NEWEST',
      'featured:POPULAR',
      'searchSource:builtin:gutendex:fiction',
    ]);
    expect(new Set(signatures).size).toBe(signatures.length);
  });

  it('exposes the three-rail set through the DiscoverDomainState facade', async () => {
    const { provider } = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:2')],
    });
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });
    await state.ensureRailsLoaded();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded']);
    expect(state.railsState.specs).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(state.isOnline).toBe(true);
  });
});

describe('discover rails — isolated error and retry', () => {
  it('isolates a failing rail: the other rails keep their books', async () => {
    const { provider, requests } = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:2')],
    });
    // The failing rail still issues its request (recorded) before throwing.
    const flaky: CatalogProvider = {
      ...provider,
      featured: async (sort, limit) => {
        if (sort === 'POPULAR') {
          requests.push({ kind: 'featured', sort, limit });
          throw catalogError('UPSTREAM_ERROR', 'boom');
        }
        return provider.featured(sort, limit);
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
      { kind: 'featured', sort: 'POPULAR', limit: DISCOVER_RAIL_FETCH_LIMIT },
    ]);

    // A rail that is not in `Error` is never re-resolved.
    await state.retryRail(0);
    expect(requests.length).toBe(beforeRetry + 1);
  });

  it('flips the connectivity flag only for offline failures', async () => {
    const { provider } = recordingProvider({
      featuredError: catalogError('NETWORK_ERROR', 'offline'),
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
    const { provider, requests } = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:2')],
    });
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
    const { provider, requests } = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:2')],
    });
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
      async featured(sort, limit): Promise<PagedResult> {
        requests.push({ kind: 'featured', sort, limit });
        await gate;
        return paged([book(`gutendex:${sort}`)]);
      },
      supportsFeatured(): boolean {
        return true;
      },
      async searchSource(sourceId, query, page): Promise<PagedResult> {
        requests.push({ kind: 'searchSource', sourceId, query, page });
        await gate;
        return paged([book(`gutendex:${query}`)]);
      },
      resolveDownloadUrl(): string {
        throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
      },
      listSources(): CatalogSourceInfo[] {
        return [{ sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' }];
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

describe('discover rails — thematic rotation', () => {
  it('never degrades to an unsorted query across empty, error and retry', async () => {
    const behaviour: FakeBehaviour = {
      featured: () => [book('gutendex:1')],
      searching: () => [],
    };
    const { provider, requests } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.rails[2]).toEqual({ kind: 'Hidden' });

    behaviour.searchSourceError = catalogError('UPSTREAM_ERROR', 'boom');
    await state.refreshRails();
    expect(state.rails[2].kind).toBe('Error');
    expect(state.rails[0].kind).toBe('Loaded');

    behaviour.searchSourceError = null;
    behaviour.searching = () => [book('gutendex:9')];
    await state.retryRail(2);
    expect(state.rails[2].kind).toBe('Loaded');

    const termCalls = sourceRequests(requests);
    expect(termCalls.length).toBeGreaterThanOrEqual(3);
    for (const call of termCalls) {
      expect(call.query.trim()).not.toBe('');
      expect(call.query).toBe('fiction');
      expect(call.page).toBe(1);
    }
  });

  it('pins the trending-chip taxonomy with the CHIP_KEYWORDS first terms', () => {
    expect(THEMATIC_ROTATION).toHaveLength(TRENDING_CHIPS.length);
    expect(THEMATIC_ROTATION.map((entry) => entry.term)).toEqual([
      'fiction',
      'classic',
      'adventure',
      'mystery',
      'romance',
      'science',
      'history',
    ]);
    TRENDING_CHIPS.forEach((label, index) => {
      expect(THEMATIC_ROTATION[index].term).toBe(CHIP_KEYWORDS[label]?.[0]);
      expect(messagesEs[THEMATIC_ROTATION[index].titleKey]).toBe(label);
      expect(messagesEn[THEMATIC_ROTATION[index].titleKey]).toBeDefined();
    });
  });

  it('reuses the chip keyword mapping instead of forking a parallel table', () => {
    const source = readSource('railRotation.ts');
    expect(source).toContain('CHIP_KEYWORDS');
    expect(source).toContain('TRENDING_CHIPS');
    expect(source).not.toMatch(/'(fiction|classic|adventure|mystery|romance|science|history)'/);
  });

  it('uses the trending-chip taxonomy and no shelf-genre label', () => {
    const labels = THEMATIC_ROTATION.map((entry) => messagesEs[entry.titleKey]);
    expect(labels).toEqual([...TRENDING_CHIPS]);
    for (const shelfGenre of ['Ensayo', 'Poesía', 'Ciencia', 'Filosofía']) {
      expect(labels).not.toContain(shelfGenre);
    }
  });

  it('selects dayOfYear mod listLength and is stable within a local day', () => {
    const morning = new Date(2026, 5, 10, 0, 5);
    const night = new Date(2026, 5, 10, 23, 55);
    expect(dayOfYear(morning)).toBe(161);
    expect(thematicIndexFor(161)).toBe(161 % THEMATIC_ROTATION.length);
    expect(thematicEntryFor(morning)).toEqual(thematicEntryFor(night));
  });

  it('advances by one modulo the list length across consecutive local days', () => {
    const today = thematicIndexFor(dayOfYear(new Date(2026, 5, 10)));
    const tomorrow = thematicIndexFor(dayOfYear(new Date(2026, 5, 11)));
    expect(tomorrow).toBe((today + 1) % THEMATIC_ROTATION.length);
    expect(thematicEntryFor(new Date(2026, 5, 11)).term).not.toBe(
      thematicEntryFor(new Date(2026, 5, 10)).term,
    );
  });

  it('reuses the same theme across refresh, retry and a fresh mount', async () => {
    const behaviour: FakeBehaviour = {
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:2')],
    };
    const { provider, requests } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    await state.refreshRails();
    const remounted = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await remounted.ensureLoaded();

    const terms = sourceRequests(requests).map((call) => call.query);
    expect(terms.length).toBe(3);
    expect(new Set(terms)).toEqual(new Set(['fiction']));
    expect(remounted.specs[2].kind === 'thematic' ? remounted.specs[2].term : '').toBe('fiction');
  });
});

describe('discover rails — unsorted rail removal in code', () => {
  it('keeps no Recomendados rail, curated first slice or 4-rail literal', () => {
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
      'provider.searchSource(BUILTIN_GUTENDEX, spec.term, 1)',
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
    ] as const;
    for (const key of railKeys) {
      expect(messagesEn[key]).toBeTruthy();
      expect(messagesEs[key]).toBeTruthy();
      expect(messagesEs[key]).not.toContain('Gutenberg');
      expect(messagesEn[key]).not.toContain('Gutenberg');
    }
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

  it('rejects a hung attempt at the deadline with NETWORK_ERROR', async () => {
    vi.useFakeTimers();
    const hung = new Promise<string>(() => undefined);
    const assertion = expect(withDeadline(hung, 15_000)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
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
    const base = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:3')],
    }).provider;
    const provider: CatalogProvider = {
      ...base,
      featured: async (sort, limit) =>
        sort === 'POPULAR' ? paged((await slow).slice(0, limit)) : base.featured(sort, limit),
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    const pending = state.refreshRails();
    await flushMicrotasks();
    // Each rail published on its own; nothing waits for the slowest rail.
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loading', 'Loaded']);
    expect(state.settled).toBe(false);

    releaseSlow([book('gutendex:2')]);
    await pending;
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded']);
    expect(state.rails[1].kind === 'Loaded' ? state.rails[1].books[0]?.id : '').toBe('gutendex:2');
    expect(state.settled).toBe(true);
  });

  it('settles a hung rail to Error at the deadline and never leaves it Loading', async () => {
    vi.useFakeTimers();
    const hung = new Promise<PagedResult>(() => undefined);
    const base = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:3')],
    }).provider;
    const provider: CatalogProvider = {
      ...base,
      featured: async (sort, limit) => (sort === 'POPULAR' ? hung : base.featured(sort, limit)),
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
    expect(state.rails[1]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    expect(state.rails.some((rail) => rail.kind === 'Loading')).toBe(false);
    expect(state.isOnline).toBe(false);
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

  it('opens a term scope and pages the source search', async () => {
    const { provider, requests } = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: (query) => [book(`gutendex:${query}`)],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({
      kind: 'term',
      term: 'fiction',
      titleKey: 'discover.rail.thematic.fiction',
    });

    expect(state.scope).toEqual({
      kind: 'term',
      term: 'fiction',
      titleKey: 'discover.rail.thematic.fiction',
    });
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual(['gutendex:fiction']);
    expect(state.scopeError).toBeNull();
    expect(sourceRequests(requests.slice(before))).toEqual([
      { kind: 'searchSource', sourceId: BUILTIN_GUTENDEX, query: 'fiction', page: 1 },
    ]);
  });

  it('opens a featured scope with a single page and no extra query', async () => {
    const { provider, requests } = recordingProvider({
      featured: (sort, limit) => [book(`${sort}:${limit}`)],
      searching: () => [book('gutendex:term')],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({ kind: 'featured', sort: 'POPULAR', titleKey: 'discover.rail.popular' });

    expect(requests.slice(before)).toEqual([
      { kind: 'featured', sort: 'POPULAR', limit: RAIL_SCOPE_LIMIT },
    ]);
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([`POPULAR:${RAIL_SCOPE_LIMIT}`]);
    expect(state.scopeExhausted).toBe(true);

    // The featured port is first-page-only: a further page is a no-op.
    await state.loadScopeNextPage();
    expect(requests.length).toBe(before + 1);
  });

  it('captures a scope failure instead of throwing and clears it on retry', async () => {
    const behaviour: FakeBehaviour = {
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:term')],
    };
    const { provider } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    behaviour.searchSourceError = catalogError('NETWORK_ERROR', 'offline');
    await state.openScope({
      kind: 'term',
      term: 'fiction',
      titleKey: 'discover.rail.thematic.fiction',
    });
    expect(state.scopeError).toBe('NETWORK_ERROR');
    expect(state.scopeBooks).toEqual([]);

    behaviour.searchSourceError = null;
    behaviour.searching = () => [book('gutendex:recovered')];
    await state.loadScopeNextPage();
    expect(state.scopeError).toBeNull();
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual(['gutendex:recovered']);
  });

  it('leaves hero, search and chips state untouched when a rail scope opens', async () => {
    const { provider } = recordingProvider({
      featured: () => [book('gutendex:1')],
      searching: () => [book('gutendex:2')],
    });
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });
    await state.ensureRailsLoaded();
    state.setQuery('dune');

    await state.openRailScope({
      kind: 'featured',
      sort: 'NEWEST',
      titleKey: 'discover.rail.newest',
    });

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
  it('exposes an inline rail error component reusing the shared three-state copy', () => {
    const component = readSource('DiscoverRailError.svelte');
    expect(component).toContain('discoverErrorKey');
    expect(component).toContain("t('discover.retry')");
    // The three-state split lives in one place and now covers rate limiting.
    const copy = readSource('discoverErrorCopy.ts');
    expect(copy).toContain("'discover.offline'");
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

  it('wires rail actions and the scoped view in the screen without an aggregate gate', () => {
    const source = readSource('DiscoverScreen.svelte');
    expect(source).toContain('openRailScope(index)');
    expect(source).toContain('discoverState.retryRail(index)');
    expect(source).toContain("t('discover.railScope.back')");
    expect(source).toContain('discover.railScope.singlePage');
    expect(source).not.toContain('railViews');
  });
});
