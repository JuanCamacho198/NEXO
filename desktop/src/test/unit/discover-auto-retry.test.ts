/**
 * G1 — bounded automatic retry for failed rails, and G2 — the connectivity pill
 * reflects reality again (a successful retry or the `online` event clears it).
 * The plan is four rails: two Gutendex topic shelves (keyed by the day's term),
 * one Gutendex popular shelf (keyed by `featured`) and one Standard Ebooks shelf
 * (keyed by source alone). The fake routes on source id and the assertions stay
 * about retry/connectivity, not content.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AUTO_RETRY_DELAYS_MS,
  DiscoverRailsDomainState,
} from '$lib/features/discover/DiscoverRailsDomainState.svelte';
import { DiscoverDomainState } from '$lib/features/discover/DiscoverDomainState.svelte';
import { DISCOVER_RAIL_COUNT } from '$lib/features/discover/railPlan';
import {
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_GUTENDEX,
  BUILTIN_STANDARD_EBOOKS,
  catalogError,
  type CatalogBook,
  type CatalogProvider,
  type PagedResult,
} from '$lib/shared/services/catalog';

/** 2026-06-10 ⇒ the science-fiction / adventure topic shelves, then popular and opds. */
const PINNED_DAY = new Date(2026, 5, 10, 12);
const pinnedNow = (): Date => PINNED_DAY;
const TERM = ['science fiction', 'adventure'] as const;
/** The two fixed-source rails' shared key, since neither carries a term. */
const FIXED_KEY = '';

/** A rail-capable book (coverless books render a text card instead). */
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

/** Let already-resolved promises publish without advancing timers. */
const flushMicrotasks = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

type Outcome = CatalogBook[] | Error | Promise<CatalogBook[]>;

interface Behaviour {
  /** Routes one topic rail through the `?topic=` door. */
  topic?: (term: string) => Outcome;
  /** Routes one opds rail; `sourceId` distinguishes the sources. */
  searchSource?: (sourceId: string, query: string) => Outcome;
  /** Routes the popular rail, which resolves through `featured`. */
  featured?: (sourceId: string) => Outcome;
}

type RailRequest =
  { kind: 'topic'; term: string } | { kind: 'searchSource'; sourceId: string; query: string };

/** Recording fake provider: outcomes are per-call, so tests can flip failure on. */
function recordingProvider(behaviour: Behaviour = {}): {
  provider: CatalogProvider;
  requests: RailRequest[];
} {
  const requests: RailRequest[] = [];
  const provider: CatalogProvider = {
    async search(): Promise<PagedResult> {
      return paged([]);
    },
    async getDetails(id: string): Promise<CatalogBook> {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    },
    async featured(): Promise<PagedResult> {
      const outcome = behaviour.featured?.(BUILTIN_GUTENDEX) ?? [];
      if (outcome instanceof Error) throw outcome;
      return paged(await outcome);
    },
    supportsFeatured(): boolean {
      return true;
    },
    async topic(term, _page): Promise<PagedResult> {
      requests.push({ kind: 'topic', term });
      const outcome = behaviour.topic?.(term) ?? [];
      if (outcome instanceof Error) throw outcome;
      return paged(await outcome);
    },
    supportsTopic(): boolean {
      return true;
    },
    async searchSource(sourceId, query, _page): Promise<PagedResult> {
      requests.push({ kind: 'searchSource', sourceId, query });
      const outcome = behaviour.searchSource?.(sourceId, query) ?? [];
      if (outcome instanceof Error) throw outcome;
      return paged(await outcome);
    },
    resolveDownloadUrl(): string {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    listSources() {
      return [
        { sourceId: BUILTIN_GOOGLEBOOKS, name: 'Google Books', kind: 'builtin' as const },
        { sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' as const },
        { sourceId: BUILTIN_STANDARD_EBOOKS, name: 'Standard Ebooks', kind: 'builtin' as const },
      ];
    },
  };
  return { provider, requests };
}

/** Every rail settles Loaded, so only the retry machinery is under test. */
function alwaysLoaded(sourceId: string, query: string): CatalogBook[] {
  return [book(`${sourceId}:${query}`, ['Author'])];
}

/** Topic rails record through their own door; keep the same loaded shape. */
function topicLoaded(term: string): CatalogBook[] {
  return [book(`gutendex:topic-${term}`, ['Author'])];
}

/** The popular rail answers a covered book, so it only fails when told to. */
const POPULAR_LOADED = (): CatalogBook[] => [book('gutendex:1342', ['Author'])];

describe('discover rails — bounded automatic retry (G1)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('retries a retryable failure at 2s, 5s and 15s, then stops permanently', async () => {
    vi.useFakeTimers();
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider, requests } = recordingProvider({
      topic: () => offline,
      searchSource: () => offline,
      featured: () => offline,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    // Three rails resolve via topic/searchSource; the popular rail via featured.
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT - 1);
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Error', 'Error', 'Error', 'Error']);

    for (let i = 0; i < AUTO_RETRY_DELAYS_MS.length; i++) {
      await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[i]!);
      expect(requests).toHaveLength((DISCOVER_RAIL_COUNT - 1) * (i + 2));
    }

    // Budget spent: no further automatic attempt, ever.
    expect(state.autoRetryCount(0)).toBe(3);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(requests).toHaveLength((DISCOVER_RAIL_COUNT - 1) * 4);
    state.dispose();
  });

  it('never auto-retries a non-retryable failure', async () => {
    vi.useFakeTimers();
    const upstream = catalogError('UPSTREAM_ERROR', 'boom');
    const { provider, requests } = recordingProvider({
      topic: () => upstream,
      searchSource: () => upstream,
      featured: () => upstream,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    await vi.advanceTimersByTimeAsync(60_000);
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT - 1);
    expect(state.autoRetryCount(0)).toBe(0);
    state.dispose();
  });

  it('treats RATE_LIMITED as retryable (the errors.ts classification)', async () => {
    vi.useFakeTimers();
    const limited = catalogError('RATE_LIMITED', 'slow down');
    const { provider, requests } = recordingProvider({
      topic: () => limited,
      searchSource: () => limited,
      featured: () => limited,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[0]!);
    expect(requests).toHaveLength((DISCOVER_RAIL_COUNT - 1) * 2);
    state.dispose();
  });

  it('resets the automatic budget after a successful load', async () => {
    vi.useFakeTimers();
    let failing = true;
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider } = recordingProvider({
      topic: (term) => (failing ? offline : topicLoaded(term)),
      searchSource: (sourceId, query) => (failing ? offline : alwaysLoaded(sourceId, query)),
      featured: () => (failing ? offline : POPULAR_LOADED()),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.autoRetryCount(0)).toBe(1);

    failing = false;
    await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[0]!);
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded', 'Loaded']);
    expect(state.autoRetryCount(0)).toBe(0);
    state.dispose();
  });

  it('cancels the pending timer and resets the budget on a manual retry', async () => {
    vi.useFakeTimers();
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const failingSource = BUILTIN_STANDARD_EBOOKS;
    const { provider, requests } = recordingProvider({
      topic: (term) => topicLoaded(term),
      searchSource: (sourceId, query) =>
        sourceId === failingSource ? offline : alwaysLoaded(sourceId, query),
      featured: POPULAR_LOADED,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    // Two topic rails (their own door) + the opds rail issue requests; popular uses featured.
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT - 1);

    // One tick before the automatic attempt, a manual retry supersedes it.
    await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[0]! - 1);
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT - 1);
    await state.retryRail(3);
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT);
    // The superseded timer is gone: t+1ms adds nothing.
    await vi.advanceTimersByTimeAsync(1);
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT);
    // The manual retry restarted the budget at the first delay.
    await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[0]! - 1);
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT + 1);
    state.dispose();
  });

  it('dispose cancels pending timers and detaches the online listener', async () => {
    vi.useFakeTimers();
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider, requests } = recordingProvider({
      topic: () => offline,
      searchSource: () => offline,
      featured: () => offline,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT - 1);

    state.dispose();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT - 1);

    window.dispatchEvent(new Event('online'));
    expect(requests).toHaveLength(DISCOVER_RAIL_COUNT - 1);
    expect(state.isOnline).toBe(false);
  });
});

describe('discover rails — connectivity recovery and the online event (G1+G2)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('re-triggers only rails in Error on the online event, exactly once', async () => {
    const offline = catalogError('NETWORK_ERROR', 'offline');
    // Only the popular rail fails, through its featured path.
    const { provider, requests } = recordingProvider({
      topic: (term) => topicLoaded(term),
      searchSource: alwaysLoaded,
      featured: () => offline,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Error', 'Loaded']);

    const before = requests.length;
    window.dispatchEvent(new Event('online'));
    await flushMicrotasks();

    // Nothing on the topic/searchSource path moves: the failing rail resolves
    // through featured, so an `online` re-trigger adds no source request at all.
    // The rails that were Loaded are never re-fetched.
    expect(requests.slice(before)).toEqual([]);
    state.dispose();
  });

  it('clears the pill via the online event and recovers every failed rail', async () => {
    let failing = true;
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider } = recordingProvider({
      topic: (term) => (failing ? offline : topicLoaded(term)),
      searchSource: (sourceId, query) => (failing ? offline : alwaysLoaded(sourceId, query)),
      featured: () => (failing ? offline : POPULAR_LOADED()),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.isOnline).toBe(false);

    failing = false;
    window.dispatchEvent(new Event('online'));
    await flushMicrotasks();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded', 'Loaded']);
    expect(state.isOnline).toBe(true);
    state.dispose();
  });

  it('never re-triggers a Loading or Loaded rail on the online event', async () => {
    let release!: (books: CatalogBook[]) => void;
    const gated = new Promise<CatalogBook[]>((resolve) => {
      release = resolve;
    });
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider, requests } = recordingProvider({
      // Popular is still in flight; the opds rail is the one in Error.
      featured: () => gated,
      topic: (term) => topicLoaded(term),
      searchSource: (sourceId, query) =>
        sourceId === BUILTIN_STANDARD_EBOOKS ? offline : alwaysLoaded(sourceId, query),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    const pending = state.refreshRails();
    await flushMicrotasks();
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loading', 'Error']);

    const before = requests.length;
    window.dispatchEvent(new Event('online'));
    await flushMicrotasks();

    expect(requests.slice(before)).toEqual([
      { kind: 'searchSource', sourceId: BUILTIN_STANDARD_EBOOKS, query: FIXED_KEY },
    ]);

    release([book('gutendex:1342', ['Author'])]);
    await pending;
    state.dispose();
  });

  it('keeps the pill offline while any rail is still offline after a per-rail retry', async () => {
    let failing = true;
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider } = recordingProvider({
      topic: (term) => (failing ? offline : topicLoaded(term)),
      searchSource: (sourceId, query) => (failing ? offline : alwaysLoaded(sourceId, query)),
      featured: () => (failing ? offline : POPULAR_LOADED()),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.isOnline).toBe(false);

    // Only rail 0 recovers: the other rails are still offline, so the pill stays
    // offline instead of being cleared by the rail that settled last.
    failing = false;
    await state.retryRail(0);
    expect(state.rails[0]?.kind).toBe('Loaded');
    expect(state.isOnline).toBe(false);

    // Every rail recovered ⇒ the pill clears.
    await state.retryRail(1);
    await state.retryRail(2);
    await state.retryRail(3);
    expect(state.isOnline).toBe(true);
    state.dispose();
  });

  it('stays offline when an offline Error rail settles before a Loaded rail', async () => {
    let releasePopular!: (books: CatalogBook[]) => void;
    const gated = new Promise<CatalogBook[]>((resolve) => {
      releasePopular = resolve;
    });
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider } = recordingProvider({
      // The popular rail is slow; the first topic rail is offline.
      featured: () => gated,
      topic: (term) => (term === TERM[0] ? offline : topicLoaded(term)),
      searchSource: alwaysLoaded,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    const pending = state.refreshRails();
    await flushMicrotasks();

    // Rail 0 (offline) already settled while the popular rail is still Loading.
    expect(state.rails[0]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    expect(state.isOnline).toBe(false);

    // A later Loaded settle must not clear the pill while rail 0 is offline.
    releasePopular(POPULAR_LOADED());
    await pending;
    expect(state.rails[2]?.kind).toBe('Loaded');
    expect(state.rails[3]?.kind).toBe('Loaded');
    expect(state.isOnline).toBe(false);
    state.dispose();
  });

  it('clears the pill when a retry decays an offline error to a non-offline one', async () => {
    let firstAttempt = true;
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const upstream = catalogError('UPSTREAM_ERROR', 'boom');
    const { provider } = recordingProvider({
      topic: topicLoaded,
      searchSource: (sourceId, query) =>
        sourceId === BUILTIN_STANDARD_EBOOKS
          ? firstAttempt
            ? offline
            : upstream
          : alwaysLoaded(sourceId, query),
      featured: POPULAR_LOADED,
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.rails[3]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    expect(state.isOnline).toBe(false);

    firstAttempt = false;
    await state.retryRail(3);

    // No rail is offline any more: NETWORK_ERROR decayed to UPSTREAM_ERROR.
    expect(state.rails[3]).toEqual({ kind: 'Error', code: 'UPSTREAM_ERROR', offline: false });
    expect(state.isOnline).toBe(true);
    state.dispose();
  });

  it('exposes the dispose path through the DiscoverDomainState facade', async () => {
    const { provider } = recordingProvider({
      topic: topicLoaded,
      searchSource: alwaysLoaded,
      featured: POPULAR_LOADED,
    });
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });
    await state.refreshRails();
    expect(() => state.dispose()).not.toThrow();
  });
});
