/**
 * Open Library datasource — relevance enrichment + cover fallback.
 * Enforces the 1s anonymous courtesy gap between calls via a rate limiter.
 */
import { isCatalogError } from './errors';
import {
  DESKTOP_USER_AGENT,
  OL_MIN_GAP_MS,
  SEARCH_DEADLINE_MS,
  clampPageSize,
  createRateLimiter,
  fetchWithRetry,
  toCatalogError,
} from './policy';
import { mapOpenLibraryDoc, type OpenLibraryDoc } from './mappers';
import type { CatalogBook } from './CatalogProvider';

export const OPEN_LIBRARY_BASE_URL = 'https://openlibrary.org';

/**
 * `fields` projection for the ISBN-scoped identity lookup (DISC-04c). The
 * unprojected `search.json` response measured 23–386 KB; request only the
 * fields the lazy resolver reads. `ebook_access` is requested alongside the
 * identity fields as the documented minimum projection.
 */
export const OPEN_LIBRARY_ISBN_FIELDS = 'key,ia,ebook_access';

export interface OpenLibrarySearchResponse {
  numFound: number;
  docs: OpenLibraryDoc[];
}

export class OpenLibraryDataSource {
  private readonly limiter = createRateLimiter(OL_MIN_GAP_MS);

  constructor(
    private readonly fetchFn: typeof fetch = fetch,
    private readonly userAgent: string = DESKTOP_USER_AGENT,
  ) {}

  /** Search usable-public docs only; borrow-restricted docs are dropped. */
  async search(
    query: string,
    page: number,
    pageSize = 24,
  ): Promise<{
    books: CatalogBook[];
    totalCount: number;
  }> {
    const size = clampPageSize(pageSize);
    await this.limiter.waitForSlot();
    try {
      const params = new URLSearchParams({ q: query, page: String(page), limit: String(size) });
      const res = await fetchWithRetry(
        `${OPEN_LIBRARY_BASE_URL}/search.json?${params.toString()}`,
        { headers: { 'User-Agent': this.userAgent, Accept: 'application/json' } },
        this.fetchFn,
        SEARCH_DEADLINE_MS,
      );
      const data = (await res.json()) as OpenLibrarySearchResponse;
      const books = (data.docs ?? [])
        .map(mapOpenLibraryDoc)
        .filter((b): b is CatalogBook => b !== null)
        .slice(0, size);
      return {
        books,
        totalCount: typeof data.numFound === 'number' ? data.numFound : books.length,
      };
    } catch (err) {
      if (isCatalogError(err)) throw err;
      toCatalogError(err, 'openlibrary request failed');
    }
  }

  /**
   * ISBN-scoped identity lookup (DISC-04c): a single `search.json` doc under
   * the `isbn:<isbn>` query, with a `fields` projection. The percent-encoded
   * colon `URLSearchParams` produces is accepted by Open Library (`isbn%3A…`
   * and `isbn:…` return the same `ia` list) — the opposite of Google Books,
   * where every colon operator answers `totalItems: 0`.
   */
  async searchByIsbn(isbn: string): Promise<OpenLibraryDoc[]> {
    await this.limiter.waitForSlot();
    try {
      const params = new URLSearchParams({
        q: `isbn:${isbn}`,
        limit: '1',
        fields: OPEN_LIBRARY_ISBN_FIELDS,
      });
      const res = await fetchWithRetry(
        `${OPEN_LIBRARY_BASE_URL}/search.json?${params.toString()}`,
        { headers: { 'User-Agent': this.userAgent, Accept: 'application/json' } },
        this.fetchFn,
        SEARCH_DEADLINE_MS,
      );
      const data = (await res.json()) as OpenLibrarySearchResponse;
      return data.docs ?? [];
    } catch (err) {
      if (isCatalogError(err)) throw err;
      toCatalogError(err, 'openlibrary request failed');
    }
  }
}
