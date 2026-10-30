/**
 * The `?topic=` seam on the desktop composite: opt-in fan-out over providers that
 * declare `supportsTopic`, per-source caching under its own `t:` namespace, and a
 * fail-closed default for every provider that does not opt in.
 *
 * The namespace split is the part that matters: a topic page and a free-text
 * search page are different upstream answers for the same words, so sharing a key
 * is exactly how a topic page could be served to a search (or the reverse) once
 * both doors exist. Mirrors the Android `CatalogTopicSeamTest`.
 */
import { describe, expect, it } from 'vitest';
import { CompositeCatalogProvider } from '$lib/shared/services/catalog/CompositeCatalogProvider';
import {
  InMemoryDiscoverCache,
  pageCacheKey,
  topicCacheKey,
} from '$lib/shared/services/catalog/DiscoverCache';
import {
  BUILTIN_GUTENDEX,
  BUILTIN_OPENLIBRARY,
  catalogError,
  type CatalogBook,
  type CatalogProvider,
  type CatalogSource,
  type CatalogSourceInfo,
  type PagedResult,
} from '$lib/shared/services/catalog';

function book(id: string, title: string): CatalogBook {
  return {
    id,
    provider: BUILTIN_GUTENDEX,
    title,
    authors: [`Author ${id}`],
    coverUrl: null,
    languages: ['en'],
    subjects: [],
    downloadUrl: null,
  };
}

function paged(books: CatalogBook[]): PagedResult {
  return { results: books, nexoPage: null, totalCount: books.length };
}

interface FakeOptions {
  sourceId: CatalogSource;
  topicCapable?: boolean;
  topicPage?: (term: string, page: number) => PagedResult;
  searchPage?: PagedResult;
}

class FakeTopicProvider implements CatalogProvider {
  readonly topicCalls: { term: string; page: number }[] = [];
  readonly searchCalls: { query: string; page: number }[] = [];

  constructor(private readonly opts: FakeOptions) {}

  listSources(): CatalogSourceInfo[] {
    return [{ sourceId: this.opts.sourceId, name: 'Fake', kind: 'builtin' }];
  }

  async search(query: string, page: number): Promise<PagedResult> {
    this.searchCalls.push({ query, page });
    return this.opts.searchPage ?? paged([]);
  }

  async featured(): Promise<PagedResult> {
    return paged([]);
  }

  supportsFeatured(): boolean {
    return false;
  }

  async topic(term: string, page: number): Promise<PagedResult> {
    this.topicCalls.push({ term, page });
    return this.opts.topicPage?.(term, page) ?? paged([]);
  }

  supportsTopic(): boolean {
    return this.opts.topicCapable ?? false;
  }

  async searchSource(_sourceId: CatalogSource, query: string, page: number): Promise<PagedResult> {
    return this.search(query, page);
  }

  async getDetails(id: string): Promise<CatalogBook> {
    throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
  }

  resolveDownloadUrl(): string {
    return 'n/a';
  }
}

describe('CompositeCatalogProvider.topic — the ?topic= seam', () => {
  it('fans out only over providers that opt in', async () => {
    const capable = new FakeTopicProvider({
      sourceId: BUILTIN_GUTENDEX,
      topicCapable: true,
      topicPage: (term) => ({
        results: [book('gutendex:1', `About ${term}`)],
        nexoPage: null,
        totalCount: 3_530,
      }),
    });
    const refuses = new FakeTopicProvider({ sourceId: BUILTIN_OPENLIBRARY, topicCapable: false });
    const composite = new CompositeCatalogProvider([capable, refuses], { debounceMs: 0 });

    const page = await composite.topic('science fiction', 1);

    expect(capable.topicCalls).toEqual([{ term: 'science fiction', page: 1 }]);
    expect(refuses.topicCalls).toEqual([]);
    // The topic total is the authority, not the search total.
    expect(page.totalCount).toBe(3_530);
    expect(composite.supportsTopic()).toBe(true);
  });

  it('rejects page < 1 before any provider call', async () => {
    const capable = new FakeTopicProvider({ sourceId: BUILTIN_GUTENDEX, topicCapable: true });
    const composite = new CompositeCatalogProvider([capable], { debounceMs: 0 });

    await expect(composite.topic('adventure', 0)).rejects.toMatchObject({
      code: 'INVALID_PAGE',
    });
    expect(capable.topicCalls).toEqual([]);
  });

  /**
   * The cache is what lets this bug come back silently: a topic page stored under
   * the search namespace would be served to a search (and the reverse).
   */
  it('never shares a cache key with search', async () => {
    const provider = new FakeTopicProvider({
      sourceId: BUILTIN_GUTENDEX,
      topicCapable: true,
      topicPage: (term) => ({
        results: [book('gutendex:1', `Topic ${term}`)],
        nexoPage: null,
        totalCount: 3_530,
      }),
      searchPage: paged([book('gutendex:2', 'Search')]),
    });
    const cache = new InMemoryDiscoverCache();
    const composite = new CompositeCatalogProvider([provider], { debounceMs: 0, cache });

    await composite.topic('science fiction', 1);
    await composite.search('science fiction', 1);

    const topicRow = cache.get(topicCacheKey(BUILTIN_GUTENDEX, 'science fiction', 1), 0);
    const searchRow = cache.get(pageCacheKey(BUILTIN_GUTENDEX, 'science fiction', 1), 0);
    expect(topicRow).toContain('Topic science fiction');
    expect(searchRow).toContain('Search');
    expect(topicRow).not.toContain('Search');
    expect(searchRow).not.toContain('Topic science fiction');
  });

  it('caches a topic page per source under the t: namespace and serves it with no I/O', async () => {
    const provider = new FakeTopicProvider({
      sourceId: BUILTIN_GUTENDEX,
      topicCapable: true,
      topicPage: () => paged([book('gutendex:1', 'Cached')]),
    });
    const cache = new InMemoryDiscoverCache();
    const composite = new CompositeCatalogProvider([provider], { debounceMs: 0, cache });

    await composite.topic('adventure', 1);
    await composite.topic('adventure', 1);

    // One upstream request, one cached row, under `t:` and not `p:`.
    expect(provider.topicCalls).toHaveLength(1);
    expect(cache.get(topicCacheKey(BUILTIN_GUTENDEX, 'adventure', 1), 0)).not.toBeNull();
    expect(cache.get(pageCacheKey(BUILTIN_GUTENDEX, 'adventure', 1), 0)).toBeNull();
  });

  it('never caches an empty topic page', async () => {
    const provider = new FakeTopicProvider({
      sourceId: BUILTIN_GUTENDEX,
      topicCapable: true,
      topicPage: () => paged([]),
    });
    const cache = new InMemoryDiscoverCache();
    const composite = new CompositeCatalogProvider([provider], { debounceMs: 0, cache });

    const page = await composite.topic('horror', 1);

    expect(page.results).toEqual([]);
    expect(cache.get(topicCacheKey(BUILTIN_GUTENDEX, 'horror', 1), 0)).toBeNull();
  });

  it('propagates a provider failure so the rail can settle Error', async () => {
    const throwing = new FakeTopicProvider({
      sourceId: BUILTIN_GUTENDEX,
      topicCapable: true,
      topicPage: () => {
        throw catalogError('NETWORK_ERROR', 'socket closed');
      },
    });
    const composite = new CompositeCatalogProvider([throwing], { debounceMs: 0 });

    await expect(composite.topic('adventure', 1)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    });
  });

  it('reports false when no provider opts in', () => {
    const quiet = new FakeTopicProvider({ sourceId: BUILTIN_GUTENDEX, topicCapable: false });
    expect(new CompositeCatalogProvider([quiet], { debounceMs: 0 }).supportsTopic()).toBe(false);
  });
});
