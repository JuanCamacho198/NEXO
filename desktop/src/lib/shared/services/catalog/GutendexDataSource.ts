/**
 * Gutendex datasource — metadata/download authority (`copyright=false`).
 * Plain `fetch` + identified UA; `connect-src` allowlisted in tauri.conf.json.
 */
import { catalogError, isCatalogError } from './errors';
import {
  DESKTOP_USER_AGENT,
  DETAIL_DEADLINE_MS,
  SEARCH_DEADLINE_MS,
  clampPageSize,
  fetchWithRetry,
  toCatalogError,
} from './policy';
import { mapGutendexBook, type GutendexRecord } from './mappers';
import type { CatalogBook, CatalogFeaturedSort } from './CatalogProvider';

export const GUTENDEX_BASE_URL = 'https://gutendex.com';

export interface GutendexSearchResponse {
  count: number;
  results: GutendexRecord[];
}

export class GutendexDataSource {
  constructor(
    private readonly fetchFn: typeof fetch = fetch,
    private readonly userAgent: string = DESKTOP_USER_AGENT,
  ) {}

  private async getJson(path: string, deadlineMs: number): Promise<unknown> {
    try {
      const res = await fetchWithRetry(
        `${GUTENDEX_BASE_URL}${path}`,
        { headers: { 'User-Agent': this.userAgent, Accept: 'application/json' } },
        this.fetchFn,
        deadlineMs,
      );
      return await res.json();
    } catch (err) {
      if (isCatalogError(err)) throw err;
      toCatalogError(err, 'gutendex request failed');
    }
  }

  /** Search PD books; in-copyright records are excluded by the mapper. */
  async search(
    query: string,
    page: number,
    pageSize = 24,
  ): Promise<{
    books: CatalogBook[];
    totalCount: number;
  }> {
    const size = clampPageSize(pageSize);
    const params = new URLSearchParams({ search: query, page: String(page) });
    const data = (await this.getJson(
      `/books/?${params.toString()}`,
      SEARCH_DEADLINE_MS,
    )) as GutendexSearchResponse;
    const books = (data.results ?? [])
      .map(mapGutendexBook)
      .filter((b): b is CatalogBook => b !== null)
      .slice(0, size);
    return { books, totalCount: typeof data.count === 'number' ? data.count : books.length };
  }

  /** Fetch one book by numeric id; unknown ids surface NOT_FOUND. */
  async getById(numericId: number): Promise<CatalogBook> {
    const data = (await this.getJson(`/books/${numericId}/`, DETAIL_DEADLINE_MS)) as GutendexRecord;
    const book = mapGutendexBook(data);
    if (!book) throw catalogError('NOT_FOUND', `gutendex book ${numericId} unavailable`);
    return book;
  }

  /**
   * Featured rail page. The ordering is a closed enum because an invalid
   * upstream `sort` value hangs the call; only verified literals are emitted.
   * Mirrors Android `GutendexDataSource.featured`.
   */
  async featured(
    sort: CatalogFeaturedSort,
    page: number,
    pageSize = 24,
  ): Promise<{
    books: CatalogBook[];
    totalCount: number;
  }> {
    const size = clampPageSize(pageSize);
    const sortParam = sort === 'POPULAR' ? 'sort=popular' : 'sort=descending';
    const data = (await this.getJson(
      `/books/?${sortParam}&page=${page}`,
      SEARCH_DEADLINE_MS,
    )) as GutendexSearchResponse;
    const books = (data.results ?? [])
      .map(mapGutendexBook)
      .filter((b): b is CatalogBook => b !== null)
      .slice(0, size);
    return { books, totalCount: typeof data.count === 'number' ? data.count : books.length };
  }
}
