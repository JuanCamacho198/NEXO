/**
 * G1 — bounded automatic retry for failed rails, and G2 — the connectivity pill
 * reflects reality again (a successful retry or the `online` event clears it).
 * DISC-04b: the rails are Google Books author shelves, so every rail is one
 * `searchSource` call keyed by its day's term.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AUTO_RETRY_DELAYS_MS,
  DiscoverRailsDomainState,
} from '$lib/features/discover/DiscoverRailsDomainState.svelte';
import { DiscoverDomainState } from '$lib/features/discover/DiscoverDomainState.svelte';
import {
  BUILTIN_GOOGLEBOOKS,
  catalogError,
  type CatalogBook,
  type CatalogProvider,
  type PagedResult,
} from '$lib/shared/services/catalog';

/** 2026-06-10 ⇒ the doyle / poe / wilde author shelves. */
const PINNED_DAY = new Date(2026, 5, 10, 12);
const pinnedNow = (): Date => PINNED_DAY;
const TERM = ['arthur conan doyle', 'edgar allan poe', 'oscar wilde'] as const;

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

/** Let already-resolved promises publish without advancing timers. */
const flushMicrotasks = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

type Outcome = CatalogBook[] | Error | Promise<CatalogBook[]>;

interface Behaviour {
  searchSource?: (query: string) => Outcome;
}

type RailRequest = { kind: 'searchSource'; query: string };

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
      return paged([]);
    },
    supportsFeatured(): boolean {
      return false;
    },
    async searchSource(_sourceId, query, _page): Promise<PagedResult> {
      requests.push({ kind: 'searchSource', query });
      const outcome = behaviour.searchSource?.(query) ?? [];
      if (outcome instanceof Error) throw outcome;
      return paged(await outcome);
    },
    resolveDownloadUrl(): string {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    listSources() {
      return [{ sourceId: BUILTIN_GOOGLEBOOKS, name: 'Google Books', kind: 'builtin' as const }];
    },
  };
  return { provider, requests };
}

describe('discover rails — bounded automatic retry (G1)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('retries a retryable failure at 2s, 5s and 15s, then stops permanently', async () => {
    vi.useFakeTimers();
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider, requests } = recordingProvider({ searchSource: () => offline });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(requests).toHaveLength(3);
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Error', 'Error', 'Error']);

    for (let i = 0; i < AUTO_RETRY_DELAYS_MS.length; i++) {
      await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[i]!);
      expect(requests).toHaveLength(3 + (i + 1) * 3);
    }

    // Budget spent: no further automatic attempt, ever.
    expect(state.autoRetryCount(0)).toBe(3);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(requests).toHaveLength(12);
    state.dispose();
  });

  it('never auto-retries a non-retryable failure', async () => {
    vi.useFakeTimers();
    const upstream = catalogError('UPSTREAM_ERROR', 'boom');
    const { provider, requests } = recordingProvider({ searchSource: () => upstream });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    await vi.advanceTimersByTimeAsync(60_000);
    expect(requests).toHaveLength(3);
    expect(state.autoRetryCount(0)).toBe(0);
    state.dispose();
  });

  it('treats RATE_LIMITED as retryable (the errors.ts classification)', async () => {
    vi.useFakeTimers();
    const limited = catalogError('RATE_LIMITED', 'slow down');
    const { provider, requests } = recordingProvider({ searchSource: () => limited });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();

    await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[0]!);
    expect(requests).toHaveLength(6);
    state.dispose();
  });

  it('resets the automatic budget after a successful load', async () => {
    vi.useFakeTimers();
    let failing = true;
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider } = recordingProvider({
      searchSource: () => (failing ? offline : [book('googlebooks:ok')]),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.autoRetryCount(0)).toBe(1);

    failing = false;
    await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[0]!);
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded']);
    expect(state.autoRetryCount(0)).toBe(0);
    state.dispose();
  });

  it('cancels the pending timer and resets the budget on a manual retry', async () => {
    vi.useFakeTimers();
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider, requests } = recordingProvider({
      searchSource: (query) => (query === TERM[1] ? offline : [book('googlebooks:ok')]),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(requests).toHaveLength(3);

    // One tick before the automatic attempt, a manual retry supersedes it.
    await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[0]! - 1);
    expect(requests).toHaveLength(3);
    await state.retryRail(1);
    expect(requests).toHaveLength(4);
    // The superseded timer is gone: t+1ms adds nothing.
    await vi.advanceTimersByTimeAsync(1);
    expect(requests).toHaveLength(4);
    // The manual retry restarted the budget at the first delay.
    await vi.advanceTimersByTimeAsync(AUTO_RETRY_DELAYS_MS[0]! - 1);
    expect(requests).toHaveLength(5);
    state.dispose();
  });

  it('dispose cancels pending timers and detaches the online listener', async () => {
    vi.useFakeTimers();
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider, requests } = recordingProvider({ searchSource: () => offline });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(requests).toHaveLength(3);

    state.dispose();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(requests).toHaveLength(3);

    window.dispatchEvent(new Event('online'));
    expect(requests).toHaveLength(3);
    expect(state.isOnline).toBe(false);
  });
});

describe('discover rails — connectivity recovery and the online event (G1+G2)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('re-triggers only rails in Error on the online event, exactly once', async () => {
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider, requests } = recordingProvider({
      searchSource: (query) => (query === TERM[1] ? offline : [book('googlebooks:ok')]),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Error', 'Loaded']);

    const before = requests.length;
    window.dispatchEvent(new Event('online'));
    await flushMicrotasks();

    // One event → exactly one request, for the only Error rail (no duplicate
    // listeners); the Loaded rails are never re-fetched.
    expect(requests.slice(before)).toEqual([{ kind: 'searchSource', query: TERM[1] }]);
    state.dispose();
  });

  it('clears the pill via the online event and recovers every failed rail', async () => {
    let failing = true;
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider } = recordingProvider({
      searchSource: () => (failing ? offline : [book('googlebooks:ok')]),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.isOnline).toBe(false);

    failing = false;
    window.dispatchEvent(new Event('online'));
    await flushMicrotasks();

    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loaded', 'Loaded']);
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
      searchSource: (query) => {
        if (query === TERM[1]) return gated;
        if (query === TERM[2]) return offline;
        return [book('googlebooks:ok')];
      },
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    const pending = state.refreshRails();
    await flushMicrotasks();
    expect(state.rails.map((rail) => rail.kind)).toEqual(['Loaded', 'Loading', 'Error']);

    const before = requests.length;
    window.dispatchEvent(new Event('online'));
    await flushMicrotasks();

    expect(requests.slice(before)).toEqual([{ kind: 'searchSource', query: TERM[2] }]);

    release([book('googlebooks:2')]);
    await pending;
    state.dispose();
  });

  it('keeps the pill offline while any rail is still offline after a per-rail retry', async () => {
    let failing = true;
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const { provider } = recordingProvider({
      searchSource: () => (failing ? offline : [book('googlebooks:ok')]),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.isOnline).toBe(false);

    // Only rail 0 recovers: rails 1 and 2 are still offline, so the pill stays
    // offline instead of being cleared by the rail that settled last.
    failing = false;
    await state.retryRail(0);
    expect(state.rails[0]?.kind).toBe('Loaded');
    expect(state.isOnline).toBe(false);

    // Every rail recovered ⇒ the pill clears.
    await state.retryRail(1);
    await state.retryRail(2);
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
      searchSource: (query) => {
        if (query === TERM[1]) return gated;
        if (query === TERM[0]) return offline;
        return [book('googlebooks:ok')];
      },
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    const pending = state.refreshRails();
    await flushMicrotasks();

    // Rail 0 (offline) already settled while rail 1 is still Loading.
    expect(state.rails[0]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    expect(state.isOnline).toBe(false);

    // A later Loaded settle must not clear the pill while rail 0 is offline.
    releasePopular([book('googlebooks:loaded')]);
    await pending;
    expect(state.rails[1]?.kind).toBe('Loaded');
    expect(state.rails[2]?.kind).toBe('Loaded');
    expect(state.isOnline).toBe(false);
    state.dispose();
  });

  it('clears the pill when a retry decays an offline error to a non-offline one', async () => {
    let firstAttempt = true;
    const offline = catalogError('NETWORK_ERROR', 'offline');
    const upstream = catalogError('UPSTREAM_ERROR', 'boom');
    const { provider } = recordingProvider({
      searchSource: (query) => {
        if (query !== TERM[1]) return [book('googlebooks:ok')];
        return firstAttempt ? offline : upstream;
      },
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });
    await state.refreshRails();
    expect(state.rails[1]).toEqual({ kind: 'Error', code: 'NETWORK_ERROR', offline: true });
    expect(state.isOnline).toBe(false);

    firstAttempt = false;
    await state.retryRail(1);

    // No rail is offline any more: NETWORK_ERROR decayed to UPSTREAM_ERROR.
    expect(state.rails[1]).toEqual({ kind: 'Error', code: 'UPSTREAM_ERROR', offline: false });
    expect(state.isOnline).toBe(true);
    state.dispose();
  });

  it('exposes the dispose path through the DiscoverDomainState facade', async () => {
    const { provider } = recordingProvider({ searchSource: () => [book('googlebooks:ok')] });
    const state = new DiscoverDomainState(provider, {}, { now: pinnedNow });
    await state.refreshRails();
    expect(() => state.dispose()).not.toThrow();
  });
});
