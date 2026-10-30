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
  TOPIC_ROTATION,
  dayOfYear,
  topicEntriesFor,
  topicStartIndex,
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

/** 2026-06-10: local day-of-year 161 and start index 0 ⇒ science fiction, adventure. */
const PINNED_DAY = new Date(2026, 5, 10, 12);
const pinnedNow = (): Date => PINNED_DAY;
const PINNED_TERMS = ['science fiction', 'adventure'] as const;
const PINNED_KEYS = [
  'discover.rail.topic.science-fiction',
  'discover.rail.topic.adventure',
  'discover.rail.gutendexPopular',
  'discover.rail.standardEbooks',
] as const;

/**
 * Rail books carry a cover by default. A coverless rail book is no longer
 * dropped: it renders a text card, so a fixture without a cover still produces
 * a `Loaded` shelf.
 */
function book(id: string, authors: string[] = ['Author']): CatalogBook {
  return {
    id,
    provider: 'gutendex',
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
  | { kind: 'topic'; term: string; page: number }
  | { kind: 'searchSource'; sourceId: CatalogSource; query: string; page: number }
  | { kind: 'search'; query: string; page: number };

interface FakeBehaviour {
  /** Per-topic outcome; falls back to `bySource('builtin:gutendex', term)`, then `searching`. */
  topic?: (term: string) => CatalogBook[] | Error;
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
      // book for index-stability assertions to see a Loaded rail.
      return paged([book('gutendex:1342')]);
    },
    supportsFeatured(): boolean {
      return true;
    },
    async topic(term, page): Promise<PagedResult> {
      requests.push({ kind: 'topic', term, page });
      if (behaviour.searchSourceError) throw behaviour.searchSourceError;
      const outcome =
        behaviour.topic?.(term) ??
        behaviour.bySource?.(BUILTIN_GUTENDEX, term) ??
        behaviour.searching?.(term) ??
        [];
      if (outcome instanceof Error) throw outcome;
      return paged(outcome);
    },
    supportsTopic(): boolean {
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

/** One book per rail, whatever source the fake is answering for. */
function anyRailBook(sourceId: string, query: string): CatalogBook[] {
  return [book(`${sourceId}:${query}`)];
}

function sourceRequests(requests: RailRequest[]): Extract<RailRequest, { kind: 'searchSource' }>[] {
  return requests.filter(
    (request): request is Extract<RailRequest, { kind: 'searchSource' }> =>
      request.kind === 'searchSource',
  );
}

describe('discover rails — four-rail two-source plan', () => {
  it('plans two rotating Gutendex topics plus one Gutendex and one Standard Ebooks rail', () => {
    const specs = buildRailSpecs(PINNED_DAY);
    expect(DISCOVER_RAIL_COUNT).toBe(4);
    expect(DISCOVER_RAIL_LIMIT).toBe(6);
    expect(RAIL_SCOPE_LIMIT).toBe(24);
    expect(specs).toHaveLength(DISCOVER_RAIL_COUNT);
    expect(specs.map((spec) => spec.kind)).toEqual(['topic', 'topic', 'popular', 'opds']);
    // Only the topic rails carry a term; the fixed shelves are term-free.
    expect(specs.filter((spec) => spec.kind === 'topic').map((spec) => spec.term)).toEqual([
      ...PINNED_TERMS,
    ]);
    expect(specs.map((spec) => spec.titleKey)).toEqual([...PINNED_KEYS]);
    // Distinct topic terms within a day.
    const topicTerms = specs
      .flatMap((spec) => (spec.kind === 'topic' ? [spec.term] : []))
      .filter(Boolean);
    expect(new Set(topicTerms).size).toBe(PINNED_TERMS.length);
    // Stable for the same local calendar day.
    expect(buildRailSpecs(new Date(2026, 5, 10, 0, 5))).toEqual(specs);
  });

  it('plans exactly the two sources the target names: Gutendex and Standard Ebooks', () => {
    const specs = buildRailSpecs(PINNED_DAY);
    const sources = specs.map((spec) =>
      spec.kind === 'opds' ? BUILTIN_STANDARD_EBOOKS : BUILTIN_GUTENDEX,
    );
    expect(new Set(sources)).toEqual(new Set([BUILTIN_GUTENDEX, BUILTIN_STANDARD_EBOOKS]));
    // Google Books left the rail plan entirely.
    expect(sources).not.toContain(BUILTIN_GOOGLEBOOKS);
    for (const spec of specs) expect(spec.kind).not.toBe('author');
  });

  it('rotates only the topic shelves and never renumbers a fixed rail', () => {
    const today = buildRailSpecs(new Date(2026, 5, 10));
    const tomorrow = buildRailSpecs(new Date(2026, 5, 11));
    expect(tomorrow).not.toEqual(today);
    // The two fixed shelves keep their positions across the day boundary.
    expect(tomorrow[2]).toEqual(today[2]);
    expect(tomorrow[3]).toEqual(today[3]);
    expect(tomorrow[2]?.kind).toBe('popular');
    expect(tomorrow[3]?.kind).toBe('opds');
    // The topic window slid one slot forward.
    expect(tomorrow[0]).toEqual(today[1]);
  });

  it('routes each rail kind to its own source, never an accidental empty search', async () => {
    const { provider, requests } = recordingProvider({
      topic: (term) => [book(`gutendex:${term}`)],
      bySource: anyRailBook,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails).toHaveLength(DISCOVER_RAIL_COUNT);
    // The popular rail resolves through `featured('POPULAR')`, not a term search.
    expect(requests.filter((request) => request.kind === 'featured')).toHaveLength(1);
    // The two topic rails ask the `?topic=` door with their closed-table terms…
    const topics = requests.filter(
      (request): request is Extract<RailRequest, { kind: 'topic' }> => request.kind === 'topic',
    );
    expect(topics.map((call) => call.term)).toEqual([...PINNED_TERMS]);
    for (const call of topics) expect(call.page).toBe(1);
    // …and only the opds rail uses the per-source free-text door, with no term.
    const calls = sourceRequests(requests);
    expect(calls.map((call) => call.sourceId)).toEqual([BUILTIN_STANDARD_EBOOKS]);
    expect(calls[0]?.query).toBe('');
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
      `${BUILTIN_GUTENDEX}:${PINNED_TERMS[0]}`,
      `${BUILTIN_GUTENDEX}:${PINNED_TERMS[1]}`,
      // The popular rail comes from the featured fake, the opds rail from its source.
      'gutendex:1342',
      `${BUILTIN_STANDARD_EBOOKS}:`,
    ]);
  });

  it('truncates a long rail to the fetch limit while short rails render as-is', async () => {
    const many = Array.from({ length: 30 }, (_, i) => book(`gutendex:${100 + i}`));
    const { provider } = recordingProvider({
      topic: (term) => (term === PINNED_TERMS[0] ? many : [book(`gutendex:${term}`)]),
      bySource: (sourceId, query) => [book(`${sourceId}:${query}`)],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const longRail = state.rails[0];
    expect(longRail.kind === 'Loaded' ? longRail.books.length : -1).toBe(DISCOVER_RAIL_FETCH_LIMIT);
    const shortRail = state.rails[1];
    expect(shortRail.kind === 'Loaded' ? shortRail.books.length : -1).toBe(1);
  });

  it('gives every rail a distinct source signature so no two shelves share a request', async () => {
    const { provider, requests } = recordingProvider({
      topic: (term) => [book(`gutendex:${term}`)],
      bySource: anyRailBook,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    const signatures = requests.map((request) => {
      if (request.kind === 'featured') return `featured:POPULAR:${request.limit}`;
      if (request.kind === 'topic') return `topic:${request.term}`;
      if (request.kind === 'searchSource') {
        return `${request.kind}:${request.sourceId}:${request.query}`;
      }
      return `${request.kind}:${request.query}`;
    });
    // The two topic shelves ask the `?topic=` door; only the opds shelf uses the
    // per-source free-text door.
    expect(signatures).toEqual([
      `topic:${PINNED_TERMS[0]}`,
      `topic:${PINNED_TERMS[1]}`,
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
      'topic',
      'topic',
      'popular',
      'opds',
    ]);
    expect(state.isOnline).toBe(true);
  });
});

describe('discover rails — coverless books render as text cards, not dropped', () => {
  it('keeps a coverless book in the rail alongside its covered siblings', async () => {
    const { provider } = recordingProvider({
      topic: (term) => [
        book(`gutendex:${term}:covered`),
        { ...book(`gutendex:${term}:coverless`), coverUrl: null },
      ],
      bySource: (sourceId, query) => [book(`${sourceId}:${query}`)],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    // The two topic rails carry both variants in order; nothing is dropped.
    for (const rail of [state.rails[0], state.rails[1]]) {
      expect(rail.kind).toBe('Loaded');
      if (rail.kind !== 'Loaded') continue;
      expect(rail.books).toHaveLength(2);
      expect(rail.books.map((entry) => entry.coverUrl === null)).toEqual([false, true]);
    }
  });

  it('renders an all-coverless rail as text cards instead of hiding it', async () => {
    const { provider } = recordingProvider({
      topic: (term) => [{ ...book(`gutendex:${term}`), coverUrl: null }],
      bySource: (sourceId, query) => [{ ...book(`${sourceId}:${query}`), coverUrl: null }],
    });
    const coverlessPopular: CatalogProvider = {
      ...provider,
      featured: async () => paged([{ ...book('gutendex:1'), coverUrl: null }]),
    };
    const state = new DiscoverRailsDomainState({ provider: coverlessPopular, now: pinnedNow });
    await state.refreshRails();

    // Not Hidden: every rail has books, they simply have no cover.
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded', 'Loaded']);
  });

  it('still hides a rail with zero books', async () => {
    const { provider } = recordingProvider({ topic: () => [], bySource: () => [] });
    const emptyPopular: CatalogProvider = {
      ...provider,
      featured: async () => paged([]),
    };
    const state = new DiscoverRailsDomainState({ provider: emptyPopular, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Hidden', 'Hidden', 'Hidden', 'Hidden']);
  });

  /**
   * Bug 2: an EMPTY page is not an error. The source answered and had nothing,
   * which is a different statement from "you are offline" — so a topic shelf that
   * resolves to zero books must hide, never surface the offline notice.
   */
  it('resolves an empty topic page to Hidden, never to Error', async () => {
    const { provider } = recordingProvider({
      topic: () => [],
      bySource: anyRailBook,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    // The request really went out: this is a served empty page, not a skipped rail.
    expect(state.rails[0]).toEqual({ kind: 'Hidden' });
    expect(state.rails[1]).toEqual({ kind: 'Hidden' });
    expect(state.rails.some((rail) => rail.kind === 'Error')).toBe(false);
    // No offline copy anywhere, and no grouped notice.
    expect(state.isOnline).toBe(true);
    expect(state.failedGroups).toEqual([]);
    expect(state.visibleFailedGroups).toEqual([]);
  });

  /**
   * Bug 2, the other half: a genuine transport failure is STILL an error. The
   * empty-page rule must not swallow it.
   */
  it('keeps a transport failure on a topic rail as Error', async () => {
    const { provider } = recordingProvider({
      topic: () => catalogError('NETWORK_ERROR', 'socket closed'),
      bySource: anyRailBook,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails[0]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    expect(state.rails[1]?.kind).toBe('Error');
    expect(state.isOnline).toBe(false);
    state.dispose();
  });

  it('keeps an upstream failure on a topic rail as Error without claiming offline', async () => {
    const { provider } = recordingProvider({
      topic: () => catalogError('UPSTREAM_ERROR', 'upstream 500'),
      bySource: anyRailBook,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails[0]).toEqual({ kind: 'Error', code: 'UPSTREAM_ERROR', offline: false });
    // A server error is not a connectivity failure: the pill stays online.
    expect(state.isOnline).toBe(true);
    state.dispose();
  });

  it('renders the coverless branch as a text card in theme tokens', () => {
    const card = readSource('DiscoverCard.svelte');
    // The text card is a real surface with the title and author, not a blank tile.
    expect(card).toContain('discover-text-card');
    expect(card).toContain('bg-(--color-surface)');
    expect(card).toContain('text-(--color-primary)');
    expect(card).toContain('text-(--color-text-muted)');
    expect(card).toContain('{book.title}');
    expect(card).toContain('{authors}');
    // No hardcoded colour, no CSS var() wrapper, no <style> block.
    expect(card).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(card).not.toContain('var(--');
    expect(card).not.toContain('<style');
  });

  it('keeps coverless books out of the rail filter path, not out of search', () => {
    const rails = readSource('DiscoverRailsDomainState.svelte.ts');
    // The old drop is gone: no rail-level coverUrl filter remains.
    expect(rails).not.toContain('book.coverUrl !== null');
    const screen = readSource('DiscoverScreen.svelte');
    expect(screen).not.toContain('coverUrl');
    const search = readSource('DiscoverDomainState.svelte.ts');
    expect(search).not.toContain('coverUrl');
  });
});

describe('discover rails — isolated error and retry', () => {
  it('isolates a failing rail: the other rails keep their books', async () => {
    const base = recordingProvider({
      topic: (term) => [book(`gutendex:${term}`)],
      bySource: anyRailBook,
    });
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

  it('retries one topic rail without refetching its Gutendex sibling', async () => {
    const behaviour: FakeBehaviour = {
      topic: (term) =>
        term === PINNED_TERMS[1] ? catalogError('UPSTREAM_ERROR', 'boom') : [book(`g:${term}`)],
    };
    const { provider, requests } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    expect(state.rails[0].kind).toBe('Loaded');
    expect(state.rails[1].kind).toBe('Error');

    const before = requests.length;
    behaviour.topic = (term) => [book(`g:${term}`)];
    await state.retryRail(1);
    expect(state.rails[1].kind).toBe('Loaded');
    // Only rail 1 was refetched: its sibling shares the source but was untouched.
    // The retry must also go back through `?topic=`, not a free-text search.
    expect(requests.slice(before)).toEqual([{ kind: 'topic', term: PINNED_TERMS[1], page: 1 }]);
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
      async topic(term, page): Promise<PagedResult> {
        requests.push({ kind: 'topic', term, page });
        await gate;
        return paged([book(`gutendex:${term}`)]);
      },
      supportsTopic(): boolean {
        return true;
      },
      async searchSource(sourceId, query, page): Promise<PagedResult> {
        requests.push({ kind: 'searchSource', sourceId, query, page });
        await gate;
        return paged([book(`${sourceId}:${query}`)]);
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

describe('discover rails — curated topic rotation', () => {
  it('never degrades a topic rail to an unsorted query across empty, error and retry', async () => {
    const behaviour: FakeBehaviour = {
      topic: (term) => (term === PINNED_TERMS[1] ? [] : [book(`gutendex:${term}`)]),
      bySource: anyRailBook,
    };
    const { provider, requests } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.rails[1]).toEqual({ kind: 'Hidden' });
    expect(state.rails[0].kind).toBe('Loaded');

    behaviour.topic = (term) =>
      term === PINNED_TERMS[1]
        ? catalogError('UPSTREAM_ERROR', 'boom')
        : [book(`gutendex:${term}`)];
    await state.refreshRails();
    expect(state.rails[1].kind).toBe('Error');
    expect(state.rails[0].kind).toBe('Loaded');

    behaviour.topic = (term) => [book(`gutendex:${term}`)];
    await state.retryRail(1);
    expect(state.rails[1].kind).toBe('Loaded');

    // Every topic resolution — first load, empty, error and retry — went through
    // `?topic=` with a member of the closed table. None fell back to free text.
    const topicCalls = requests.filter(
      (call): call is Extract<RailRequest, { kind: 'topic' }> => call.kind === 'topic',
    );
    expect(topicCalls.length).toBeGreaterThanOrEqual(3);
    for (const call of topicCalls) {
      expect(call.term.trim()).not.toBe('');
      expect(PINNED_TERMS).toContain(call.term);
      expect(call.page).toBe(1);
    }
    // No topic term ever travelled through the free-text door.
    for (const call of sourceRequests(requests)) {
      expect(PINNED_TERMS).not.toContain(call.query);
    }
  });

  it('pins the measured topic table with science fiction and adventure leading', () => {
    expect(TOPIC_ROTATION).toHaveLength(10);
    expect(TOPIC_ROTATION.slice(0, 2).map((entry) => entry.term)).toEqual([
      'science fiction',
      'adventure',
    ]);
    expect(TOPIC_ROTATION.map((entry) => entry.term)).toEqual([
      'science fiction',
      'adventure',
      'history',
      'travel',
      'poetry',
      'philosophy',
      'drama',
      'romance',
      'mystery',
      'horror',
    ]);
    for (const entry of TOPIC_ROTATION) {
      expect(entry.slug).toBe(entry.titleKey.slice('discover.rail.topic.'.length));
      expect(messagesEn[entry.titleKey]).toBeTruthy();
      expect(messagesEs[entry.titleKey]).toBeTruthy();
    }
  });

  it('omits the measured-thinnest topic from the leading slots and keeps terms clean', () => {
    const terms = TOPIC_ROTATION.map((entry) => entry.term);
    // `horror` measured only 283 books: it is a member, never a lead.
    expect(terms.indexOf('horror')).toBe(terms.length - 1);
    // No colon operator ever reaches an upstream parameter.
    for (const term of terms) expect(term).not.toContain(':');
    // Every term is a plain topic value, not a `sort` literal.
    for (const term of terms) expect(term).not.toBe('popular');
  });

  it('closes the table: rails cannot pass a raw string to the upstream', () => {
    const source = readSource('railRotation.ts');
    // The closed table is documented where the value is chosen.
    expect(source).toContain('closed table');
    expect(source).toContain('topic');
    const plan = readSource('railPlan.ts');
    // The only term a rail can carry comes from the closed rotation.
    expect(plan).toContain('topicEntriesFor');
    // No rail ever builds a `sort` query string: the only ordering comes from
    // the closed `CatalogFeaturedSort` enum handed to `featured`.
    expect(plan).not.toMatch(/sort=\$\{|`sort=|'sort=/);
    expect(plan).toContain("provider.featured('POPULAR', limit)");
    // A topic rail is the closed-table `?topic=` selector, never a free-text
    // search: the same words through `search` answer a different, smaller page.
    expect(plan).toContain('provider.topic(spec.term, 1)');
    expect(plan).not.toContain('provider.searchSource(BUILTIN_GUTENDEX');
  });

  it('does not borrow the client-side chip keyword taxonomy', () => {
    const source = readSource('railRotation.ts');
    expect(source).not.toContain('CHIP_KEYWORDS');
    expect(source).not.toContain('TRENDING_CHIPS');
    // The chip taxonomy itself is untouched — its subject substrings stay as-is.
    expect(CHIP_KEYWORDS['science']).toEqual(['science']);
    expect(CHIP_KEYWORDS['mystery']).toEqual(['mystery', 'detective']);
    expect(TRENDING_CHIPS).toHaveLength(7);
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
    expect(topicStartIndex(161)).toBe(0);
    expect(topicEntriesFor(morning)).toEqual(topicEntriesFor(night));

    const today = topicEntriesFor(new Date(2026, 5, 10)).map((entry) => entry.term);
    const tomorrow = topicEntriesFor(new Date(2026, 5, 11)).map((entry) => entry.term);
    // Consecutive days slide the two-topic window one slot forward.
    expect(today).toEqual(['science fiction', 'adventure']);
    expect(tomorrow).toEqual(['adventure', 'history']);
    expect(tomorrow).not.toEqual(today);
  });

  it('keeps the two topic rails distinct across a full rotation cycle', () => {
    for (let day = 1; day <= 40; day += 1) {
      const entries = topicEntriesFor(new Date(2026, 0, day));
      expect(new Set(entries.map((entry) => entry.term)).size).toBe(2);
    }
  });

  it('reuses the same shelves across refresh, retry and a fresh mount', async () => {
    const { provider, requests } = recordingProvider({
      topic: (term) => [book(`gutendex:${term}`)],
      bySource: anyRailBook,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    await state.refreshRails();
    const remounted = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await remounted.ensureLoaded();

    const terms = requests
      .filter((call): call is Extract<RailRequest, { kind: 'topic' }> => call.kind === 'topic')
      .map((call) => call.term);
    // Three loads (two refreshes + one mount) × two topic rails.
    expect(terms).toHaveLength(6);
    expect(new Set(terms)).toEqual(new Set(PINNED_TERMS));
    expect(remounted.specs[1]?.kind === 'topic' ? remounted.specs[1].term : '').toBe(
      PINNED_TERMS[1],
    );
  });
});

describe('discover rails — retired rail paths in code', () => {
  it('keeps no author rail, Recomendados rail, curated first slice or featured rail', () => {
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
    const plan = readSource('railPlan.ts');
    // Google Books is no longer a rail source at all.
    expect(plan).not.toContain('matchesAuthorQuery');
    expect(plan).not.toContain("kind: 'author'");
    expect(plan).not.toMatch(/searchSource\(BUILTIN_GOOGLEBOOKS/);
    // The popular rail uses the verified sort ordering: an empty term search
    // took 64s live and would time out every rail deadline.
    expect(plan).toContain("provider.featured('POPULAR', limit)");
    expect(plan).toContain(
      'provider.searchSource(BUILTIN_STANDARD_EBOOKS, STANDARD_EBOOKS_RAIL_QUERY, 1)',
    );
    // The scoped browse pages through the scope's own source, not a hardcoded one,
    // except a topic scope which keeps its `?topic=` selector.
    expect(readSource('DiscoverRailsDomainState.svelte.ts')).toContain(
      'this.provider.searchSource(scope.sourceId, scope.term, this.scopePage + 1)',
    );
    expect(readSource('DiscoverRailsDomainState.svelte.ts')).toContain(
      'this.provider.topic(scope.term, this.scopePage + 1)',
    );
  });

  it('keeps Google Books as a registered SEARCH source after leaving the rails', () => {
    // The provider is still built, still searchable, still detail-routable.
    const providers = readSource('../../shared/services/catalog/BuiltInCatalogProviders.ts');
    expect(providers).toContain('class GoogleBooksCatalogProvider');
    expect(providers).toContain('googleBooksProviderOrNull');
    const composite = readSource('../../shared/services/catalog/CompositeCatalogProvider.ts');
    expect(composite).toContain('googleBooksProviderOrNull(googleBooksKey)');
    const domain = readSource('DiscoverDomainState.svelte.ts');
    // Search itself is untouched.
    expect(domain).toContain('this.provider.search(this.query, 1)');
  });

  it('resolves rail titles from i18n in both locales', () => {
    const railKeys = [
      'discover.rail.gutendexPopular',
      'discover.rail.standardEbooks',
      'discover.rail.viewAll',
      ...TOPIC_ROTATION.map((entry) => entry.titleKey),
    ] as const;
    for (const key of railKeys) {
      expect(messagesEn[key]).toBeTruthy();
      expect(messagesEs[key]).toBeTruthy();
      expect(messagesEs[key]).not.toContain('Gutenberg');
      expect(messagesEn[key]).not.toContain('Gutenberg');
    }
    expect(messagesEn['discover.rail.gutendexPopular']).toBe('Most downloaded classics');
    expect(messagesEs['discover.rail.gutendexPopular']).toBe('Clásicos más descargados');
    expect(messagesEn['discover.rail.standardEbooks']).toContain('Standard Ebooks');
    expect(messagesEs['discover.rail.standardEbooks']).toContain('Standard Ebooks');
    expect(messagesEs['discover.rail.standardEbooks']).not.toBe(
      messagesEn['discover.rail.standardEbooks'],
    );
    // The topic titles actually translate instead of being copies.
    expect(messagesEn['discover.rail.topic.science-fiction']).toBe('Science fiction');
    expect(messagesEs['discover.rail.topic.science-fiction']).toBe('Ciencia ficción');
    expect(messagesEn['discover.rail.topic.adventure']).toBe('Adventure');
    expect(messagesEs['discover.rail.topic.adventure']).toBe('Aventura');
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
  function silentProvider(
    seen: { sourceId: string; query: string }[],
    topics: string[] = [],
  ): CatalogProvider {
    return {
      ...recordingProvider().provider,
      async topic(term): Promise<PagedResult> {
        topics.push(term);
        return paged([book(`gutendex:${term}`)]);
      },
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

  it('sends a topic rail to Gutendex with its closed-table term', async () => {
    const seen: { sourceId: string; query: string }[] = [];
    const topics: string[] = [];
    await loadRail(silentProvider(seen, topics), {
      kind: 'topic',
      term: 'science fiction',
      titleKey: 'discover.rail.topic.science-fiction',
    });
    // The `?topic=` door, with the closed-table term, and NOT the free-text one.
    expect(topics).toEqual(['science fiction']);
    expect(seen).toEqual([]);
  });
});

describe('discover rails — rail-scoped "Ver todo"', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('opens a topic term scope and pages the source that served the rail', async () => {
    const { provider, requests } = recordingProvider({
      searching: (query) => [book(`gutendex:${query}`)],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
      sourceId: BUILTIN_GUTENDEX,
    });

    expect(state.scope).toEqual({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
      sourceId: BUILTIN_GUTENDEX,
    });
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([`gutendex:${PINNED_TERMS[0]}`]);
    expect(state.scopeError).toBeNull();
    // A non-topic scope still pages its own source's free-text search.
    expect(sourceRequests(requests.slice(before))).toEqual([
      { kind: 'searchSource', sourceId: BUILTIN_GUTENDEX, query: PINNED_TERMS[0], page: 1 },
    ]);
  });

  /**
   * "Ver todo" on a topic shelf must keep asking `?topic=` — the rail bug, one
   * screen deeper. Handing the same words to `searchSource` answers 6 books for a
   * shelf whose topic query answers 3 530.
   */
  it('pages a topic scope through the topic door, never the free-text one', async () => {
    const { provider, requests } = recordingProvider({
      topic: (term) => [book(`gutendex:${term}`)],
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
      sourceId: BUILTIN_GUTENDEX,
      topic: true,
    });

    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([`gutendex:${PINNED_TERMS[0]}`]);
    expect(state.scopeError).toBeNull();
    // Exhausted after page 1 (the fake reports no next page): the scope asked the
    // `?topic=` door and NEVER the free-text `searchSource` door.
    const scopeCalls = requests.slice(before);
    expect(scopeCalls).toEqual([{ kind: 'topic', term: PINNED_TERMS[0], page: 1 }]);
    expect(sourceRequests(scopeCalls)).toEqual([]);
  });

  it('pages a topic scope through the topic door across multiple pages', async () => {
    const requests: RailRequest[] = [];
    const provider: CatalogProvider = {
      ...recordingProvider().provider,
      async topic(term, page): Promise<PagedResult> {
        requests.push({ kind: 'topic', term, page });
        return {
          results: [book(`gutendex:${term}:${page}`)],
          nexoPage: page === 1 ? 2 : null,
          totalCount: 2,
        };
      },
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
      sourceId: BUILTIN_GUTENDEX,
      topic: true,
    });
    await state.loadScopeNextPage();

    expect(requests.slice(before)).toEqual([
      { kind: 'topic', term: PINNED_TERMS[0], page: 1 },
      { kind: 'topic', term: PINNED_TERMS[0], page: 2 },
    ]);
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([
      `gutendex:${PINNED_TERMS[0]}:1`,
      `gutendex:${PINNED_TERMS[0]}:2`,
    ]);
  });

  it('pages the term scope until a page reports no next page', async () => {
    const requests: RailRequest[] = [];
    const provider: CatalogProvider = {
      ...recordingProvider().provider,
      async searchSource(sourceId, query, page): Promise<PagedResult> {
        requests.push({ kind: 'searchSource', sourceId, query, page });
        return {
          results: [book(`gutendex:${query}:${page}`)],
          nexoPage: page === 1 ? 2 : null,
          totalCount: 2,
        };
      },
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[1],
      titleKey: PINNED_KEYS[1],
      sourceId: BUILTIN_GUTENDEX,
    });
    expect(state.scopeExhausted).toBe(false);
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([`gutendex:${PINNED_TERMS[1]}:1`]);

    await state.loadScopeNextPage();
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([
      `gutendex:${PINNED_TERMS[1]}:1`,
      `gutendex:${PINNED_TERMS[1]}:2`,
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
          results: [book(`gutendex:${query}:${page}`)],
          nexoPage: page + 1,
          totalCount: 1_000_000,
        };
      },
    };
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    const before = requests.length;

    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[1],
      titleKey: PINNED_KEYS[1],
      sourceId: BUILTIN_GUTENDEX,
    });
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
    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
      sourceId: BUILTIN_GUTENDEX,
    });
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
            results: [book(`gutendex:${query}:1`)],
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
    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
      sourceId: BUILTIN_GUTENDEX,
    });
    const before = requests.length;

    const first = state.loadScopeNextPage();
    const second = state.loadScopeNextPage();
    await flushMicrotasks();
    // One threshold crossing, one request: the second call joins the in-flight page.
    expect(requests.length).toBe(before + 1);

    releasePage2({ results: [book('gutendex:term:2')], nexoPage: null, totalCount: 2 });
    await first;
    await second;
    expect(requests.length).toBe(before + 1);
    expect(state.scopeBooks.map((scoped) => scoped.id)).toEqual([
      `gutendex:${PINNED_TERMS[0]}:1`,
      'gutendex:term:2',
    ]);
    expect(state.scopeExhausted).toBe(true);
    expect(state.scopeAtDepthCap).toBe(false);
  });

  it('captures a scope failure instead of throwing and clears it on retry', async () => {
    const behaviour: FakeBehaviour = { searching: () => [book('gutendex:term')] };
    const { provider } = recordingProvider(behaviour);
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    behaviour.searchSourceError = catalogError('NETWORK_ERROR', 'offline');
    await state.openScope({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
      sourceId: BUILTIN_GUTENDEX,
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
    const { provider } = recordingProvider({ searching: () => [book('gutendex:2')] });
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });
    await state.ensureRailsLoaded();
    state.setQuery('dune');

    await state.openRailScope({
      kind: 'term',
      term: PINNED_TERMS[0],
      titleKey: PINNED_KEYS[0],
      sourceId: BUILTIN_GUTENDEX,
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
    // "Ver todo" only exists for a shelf with a term to page.
    expect(source).toContain("spec.kind !== 'topic'");
    // The depth cap truncates the scope instead of inventing results, so the
    // retired single-page copy is rendered again with the loaded count.
    expect(source).toContain('discover.railScope.singlePage');
    expect(source).toContain('scopeAtDepthCap');
    expect(source).not.toContain('railViews');
  });
});
