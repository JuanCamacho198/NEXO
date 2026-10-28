/**
 * Grouped rail failure notice (both platforms share this rule).
 *
 * A rail that fails used to hide itself, so a page whose whole source group was
 * down silently got shorter and the reader could not tell "this app has four
 * shelves" from "four shelves are down". The rule pinned here:
 *
 *   1. every rail of a source failing  -> exactly ONE notice for that source
 *   2. one of two rails failing        -> NO notice (the rail keeps its error)
 *   3. rails still loading             -> no notice
 *   4. retry re-resolves only the group
 *   5. dismiss hides it for the session
 *
 * NOTE on wiring: the composite's `searchSource(sourceId, ...)` resolves the
 * owning provider and calls that provider's `searchSource()`, so the fake below
 * scripts `searchSource` (the two Gutendex topic rails) and `featured` (the
 * Gutendex popular shelf).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DiscoverRailsDomainState,
  failedRailGroupsOf,
  type DiscoverRailState,
} from '$lib/features/discover/DiscoverRailsDomainState.svelte';
import { railSourceOf, type DiscoverRailSpec } from '$lib/features/discover/railPlan';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import { InMemoryDiscoverCache } from '$lib/shared/services/catalog/DiscoverCache';
import {
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_GUTENDEX,
  BUILTIN_STANDARD_EBOOKS,
  catalogError,
  type CatalogBook,
  type CatalogProvider,
  type PagedResult,
} from '$lib/shared/services/catalog';

/**
 * 2026-06-10 ⇒ day-of-year 161 ⇒ rotation start index 0 ⇒ the two leading
 * topics, `science fiction` and `adventure`. The fixture scripts those terms.
 */
const PINNED_DAY = new Date(2026, 5, 10, 12);
const pinnedNow = (): Date => PINNED_DAY;
const FAILED_TERM = 'science fiction';
const SIBLING_TERM = 'adventure';

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

/**
 * Recording provider backed by the real composite, so a test asserts on the
 * PROVIDER calls a retry actually issued — the only honest way to prove a
 * working group was not refetched.
 */
function recordingProvider(options: {
  source: (sourceId: string, query: string) => Promise<PagedResult>;
  featured?: () => Promise<PagedResult>;
}) {
  const calls: string[] = [];
  const inner: CatalogProvider = {
    async search(query): Promise<PagedResult> {
      calls.push(`search:${query}`);
      return options.source(BUILTIN_GUTENDEX, query);
    },
    async getDetails(id: string): Promise<CatalogBook> {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    },
    async featured(): Promise<PagedResult> {
      calls.push('featured');
      return options.featured ? options.featured() : paged([]);
    },
    supportsFeatured(): boolean {
      return true;
    },
    async topic(term): Promise<PagedResult> {
      calls.push(`topic:${term}`);
      return options.source(BUILTIN_GUTENDEX, term);
    },
    supportsTopic(): boolean {
      return true;
    },
    resolveDownloadUrl(): string {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    async searchSource(sourceId, query): Promise<PagedResult> {
      if (sourceId === BUILTIN_STANDARD_EBOOKS) {
        calls.push('standard-ebooks');
        return paged([book('se:1', ['Wilde'])]);
      }
      calls.push(`${sourceId === BUILTIN_GUTENDEX ? 'gutendex' : 'googlebooks'}:${query}`);
      return options.source(sourceId, query);
    },
    listSources() {
      return [
        { sourceId: BUILTIN_GOOGLEBOOKS, name: 'Google Books', kind: 'builtin' as const },
        { sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' as const },
        { sourceId: BUILTIN_STANDARD_EBOOKS, name: 'Standard Ebooks', kind: 'builtin' as const },
      ];
    },
  };
  const provider = new CompositeCatalogProvider([inner], {
    debounceMs: 0,
    cache: new InMemoryDiscoverCache(),
  });
  return { provider, calls };
}

/** The rate-limited state measured live: Google Books answers 429, others 200. */
const rateLimited = (): Promise<PagedResult> =>
  Promise.reject(catalogError('RATE_LIMITED', 'upstream status 429'));

describe('failedRailGroupsOf — the grouping rule', () => {
  const topic: DiscoverRailSpec = { kind: 'topic', term: FAILED_TERM, titleKey: 'x' as never };
  const topicTwo: DiscoverRailSpec = { kind: 'topic', term: SIBLING_TERM, titleKey: 'x' as never };
  const popular: DiscoverRailSpec = { kind: 'popular', titleKey: 'x' as never };
  const opds: DiscoverRailSpec = { kind: 'opds', titleKey: 'x' as never };
  const specs = [topic, topicTwo, popular, opds];

  const loaded = (): DiscoverRailState => ({ kind: 'Loaded', books: [], totalCount: 0 });
  const failed = (): DiscoverRailState => ({
    kind: 'Error',
    code: 'RATE_LIMITED',
    offline: false,
  });

  it('names a source only when every one of its rails failed', () => {
    // The two topic rails AND the popular rail are all Gutendex: all three dead.
    const rails = [failed(), failed(), failed(), loaded()];
    expect(failedRailGroupsOf(specs, rails)).toEqual(['gutendex']);
    // With the popular shelf alive, the source still serves books: no notice.
    expect(failedRailGroupsOf(specs, [failed(), failed(), loaded(), loaded()])).toEqual([]);
  });

  it('reports nothing when a single rail of a two-rail group failed', () => {
    const rails = [failed(), loaded(), loaded(), loaded()];
    expect(failedRailGroupsOf(specs, rails)).toEqual([]);
  });

  it('reports nothing while a rail of the group is still loading', () => {
    const rails: DiscoverRailState[] = [failed(), failed(), { kind: 'Loading' }, loaded()];
    expect(failedRailGroupsOf(specs, rails)).toEqual([]);
  });

  it('maps each spec kind to its upstream', () => {
    expect([topic, popular, opds].map(railSourceOf)).toEqual([
      'gutendex',
      'gutendex',
      'standard-ebooks',
    ]);
  });
});

describe('DiscoverRailsDomainState — grouped failure notice', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  /** Fails the topic shelves and the popular shelf together; SE stays healthy. */
  const wholeGutendexDown = recordingProvider({
    source: rateLimited,
    featured: rateLimited,
  });

  it('shows exactly ONE notice when the whole Gutendex source fails', async () => {
    const { provider } = wholeGutendexDown;
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();

    expect(state.failedGroups).toEqual(['gutendex']);
    expect(state.visibleFailedGroups).toEqual(['gutendex']);
    state.dispose();
  });

  it('shows NO notice when only one rail of the pair fails', async () => {
    const { provider } = recordingProvider({
      source: (_sourceId, query) =>
        query === FAILED_TERM ? rateLimited() : Promise.resolve(paged([book('gd:1', ['Twain'])])),
      featured: () => Promise.resolve(paged([book('gd:pop', ['Verne'])])),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();

    // The failing rail keeps its own inline error…
    expect(state.rails[0]?.kind).toBe('Error');
    // …and the source is NOT reported as a dead group.
    expect(state.failedGroups).toEqual([]);
    state.dispose();
  });

  it('shows no notice while the rails are still loading', async () => {
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const { provider } = recordingProvider({
      source: async () => {
        await gate;
        return rateLimited();
      },
      featured: async () => {
        await gate;
        return rateLimited();
      },
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    const loading = state.refreshRails();
    // Give the microtask queue a chance: every rail is published Loading, none
    // has failed, so the notice must not exist yet.
    await Promise.resolve();
    expect(state.rails.every((rail) => rail.kind === 'Loading')).toBe(true);
    expect(state.failedGroups).toEqual([]);

    release?.();
    await loading;
    expect(state.failedGroups).toEqual(['gutendex']);
    state.dispose();
  });

  it('retries only the failed group, never the group that worked', async () => {
    let gutendexFails = true;
    const { provider, calls } = recordingProvider({
      source: () => {
        if (gutendexFails) return rateLimited();
        return Promise.resolve(paged([book('gd:1', ['London'])]));
      },
      featured: () => {
        if (gutendexFails) return rateLimited();
        return Promise.resolve(paged([book('gd:pop', ['Verne'])]));
      },
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.failedGroups).toEqual(['gutendex']);

    // The upstream recovers; the retry must issue the three Gutendex requests
    // ONLY (two topic rails + the popular shelf).
    gutendexFails = false;
    calls.length = 0;
    await state.retryRailGroup('gutendex');

    expect(calls).toHaveLength(3);
    expect(calls.every((call) => !call.startsWith('googlebooks:'))).toBe(true);
    // The working Standard Ebooks shelf was never asked again on the retry.
    expect(calls).not.toContain('standard-ebooks');
    expect(state.rails[3]?.kind).toBe('Loaded');
    expect(state.failedGroups).toEqual([]);
    state.dispose();
  });

  it('dismiss hides the notice for the session', async () => {
    const { provider } = wholeGutendexDown;
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.visibleFailedGroups).toEqual(['gutendex']);

    state.dismissRailGroup('gutendex');
    expect(state.visibleFailedGroups).toEqual([]);
    // The failure itself is still real: dismissal hides the notice, not the state.
    expect(state.failedGroups).toEqual(['gutendex']);
    state.dispose();
  });

  it('retries ONLY the failed rail of a group, never its working sibling', async () => {
    const termsSeen: string[] = [];
    const { provider, calls } = recordingProvider({
      source: (_sourceId, query) => {
        termsSeen.push(query);
        return query === FAILED_TERM
          ? rateLimited()
          : Promise.resolve(paged([book('gd:1', ['Verne'])]));
      },
      featured: () => Promise.resolve(paged([book('gd:pop', ['Twain'])])),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.rails[0]?.kind).toBe('Error');
    expect(state.rails[1]?.kind).toBe('Loaded');
    // One sibling still works, so the source is not a dead group.
    expect(state.failedGroups).toEqual([]);

    const siblingCallsBefore = termsSeen.filter((term) => term === SIBLING_TERM).length;
    calls.length = 0;
    await state.retryRailGroup('gutendex');

    // Exactly one request, and the working sibling's rail was NOT the one asked.
    // The topic rail asks the `?topic=` door, never the free-text one.
    expect(calls).toEqual([`topic:${FAILED_TERM}`]);
    expect(termsSeen.filter((term) => term === SIBLING_TERM)).toHaveLength(siblingCallsBefore);
    expect(state.rails[1]?.kind).toBe('Loaded');
    expect(state.rails[0]?.kind).toBe('Error');
    state.dispose();
  });

  it('a rail retry re-resolves that rail only, leaving its sibling untouched', async () => {
    const termsSeen: string[] = [];
    const { provider, calls } = recordingProvider({
      source: (_sourceId, query) => {
        termsSeen.push(query);
        return query === FAILED_TERM
          ? rateLimited()
          : Promise.resolve(paged([book('gd:1', ['Verne'])]));
      },
      featured: () => Promise.resolve(paged([book('gd:pop', ['Twain'])])),
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.rails[0]?.kind).toBe('Error');
    expect(state.rails[1]?.kind).toBe('Loaded');

    const siblingCallsBefore = termsSeen.filter((term) => term === SIBLING_TERM).length;
    calls.length = 0;
    await state.retryRail(0);

    // Exactly one request, and it was the failing rail's own query.
    expect(calls).toEqual([`topic:${FAILED_TERM}`]);
    // The sibling's fetch count is unchanged.
    expect(termsSeen.filter((term) => term === SIBLING_TERM)).toHaveLength(siblingCallsBefore);
    expect(state.rails[1]?.kind).toBe('Loaded');
    expect(state.rails[0]?.kind).toBe('Error');
    // The other sources were never contacted either.
    expect(calls).not.toContain('featured');
    expect(calls).not.toContain('standard-ebooks');
    state.dispose();
  });

  it('grouped retry clears the notice once every rail of the group recovers', async () => {
    let gutendexFails = true;
    const { provider } = recordingProvider({
      source: () => {
        if (gutendexFails) return rateLimited();
        return Promise.resolve(paged([book('gd:1', ['Verne'])]));
      },
      featured: () => {
        if (gutendexFails) return rateLimited();
        return Promise.resolve(paged([book('gd:pop', ['Twain'])]));
      },
    });
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.visibleFailedGroups).toEqual(['gutendex']);

    gutendexFails = false;
    await state.retryRailGroup('gutendex');

    expect(state.visibleFailedGroups).toEqual([]);
    expect(state.failedGroups).toEqual([]);
    expect(state.rails.slice(0, 3).every((rail) => rail.kind === 'Loaded')).toBe(true);
    state.dispose();
  });

  it('a rail that still fails keeps its own error after the group retry', async () => {
    const { provider } = wholeGutendexDown;
    const state = new DiscoverRailsDomainState({ provider, now: pinnedNow });

    await state.refreshRails();
    expect(state.visibleFailedGroups).toEqual(['gutendex']);

    await state.retryRailGroup('gutendex');

    // Still throttled: the failure is real, so the rail keeps its inline error
    // and its retry, and the group keeps speaking for the source.
    expect(state.rails[0]).toMatchObject({ kind: 'Error', code: 'RATE_LIMITED' });
    expect(state.rails[1]).toMatchObject({ kind: 'Error', code: 'RATE_LIMITED' });
    expect(state.visibleFailedGroups).toEqual(['gutendex']);
    state.dispose();
  });
});
