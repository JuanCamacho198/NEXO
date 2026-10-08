/**
 * Google Books datasource (WU3) — keyed volume search/detail by ISBN or
 * title-author. Plain `fetch` + identified UA; `connect-src` already
 * allowlists https://www.googleapis.com in tauri.conf.json.
 *
 * Fail-closed on the key: the provider is only constructed when
 * `VITE_GOOGLE_BOOKS_KEY` is non-blank (see `googleBooksProviderOrNull`). The
 * datasource takes the key as a constructor arg so it stays fully testable
 * offline; the key is appended as `&key=` only when non-blank and is never
 * logged, never echoed into an error message.
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
import {
  mapGoogleBooksVolume,
  type GoogleBooksSearchResponse,
  type GoogleBooksVolumeItem,
} from './mappers';
import type { CatalogBook } from './CatalogProvider';

export const GOOGLE_BOOKS_BASE_URL = 'https://www.googleapis.com/books/v1';

/**
 * `fields` whitelist for the detail endpoint: keeps the payload small while
 * carrying everything the mapper needs. `industryIdentifiers` is included so a
 * keyed detail can surface ISBN-10/13 enrichment (spec scenario "Keyed
 * enrichment"); unknown/missing fields still map leniently.
 */
export const GOOGLE_BOOKS_VOLUME_FIELDS =
  'id,volumeInfo(title,authors,description,language,categories,imageLinks,industryIdentifiers)';

/**
 * `fields` whitelist for the search endpoint. Wraps the volume whitelist in the
 * list-response envelope: `totalItems` must be requested explicitly or the
 * partial response omits it and pagination degrades to a per-page count.
 */
export const GOOGLE_BOOKS_SEARCH_FIELDS = `totalItems,items(${GOOGLE_BOOKS_VOLUME_FIELDS})`;

/** A bare `isbn:`-eligible identifier: 9 digits + digit/X check digit, or 13 digits. */
const ISBN10_RE = /^\d{9}[\dX]$/;
const ISBN13_RE = /^\d{13}$/;

/**
 * Build the `q` param: a bare ISBN-10/13 (digits, optional `X` check digit,
 * dashes/spaces tolerated) is compacted to its plain digits; everything else
 * passes through as a title/author query the API ranks itself.
 *
 * The `isbn:` operator is deliberately NOT used. Measured against the live API
 * with the app's own key, sending the URL verbatim: `isbn:9780141439518` and
 * `isbn%3A9780141439518` both answer `totalItems: 0`, while the plain digits
 * `9780141439518` answer with real volumes. Every colon operator (`isbn:`,
 * `subject:`, `intitle:`, `inauthor:`) behaved the same way, while quoted
 * phrases worked, so the operator — not the encoding — is what fails here. The
 * root cause is unexplained (a key restriction or endpoint behaviour); do not
 * "restore" the operator without re-measuring it against the live API.
 */
export function buildGoogleBooksQuery(rawQuery: string): string {
  const trimmed = rawQuery.trim();
  const compact = trimmed.replace(/[-\s]/g, '').toUpperCase();
  return ISBN10_RE.test(compact) || ISBN13_RE.test(compact) ? compact : trimmed;
}

export class GoogleBooksDataSource {
  constructor(
    private readonly fetchFn: typeof fetch = fetch,
    private readonly apiKey: string = '',
    private readonly userAgent: string = DESKTOP_USER_AGENT,
  ) {}

  /** `&key=…` only when the key is non-blank; the key is never logged. */
  private keyParam(): string {
    const trimmed = this.apiKey.trim();
    return trimmed === '' ? '' : `&key=${encodeURIComponent(trimmed)}`;
  }

  private async getJson(url: string, deadlineMs: number): Promise<unknown> {
    try {
      const res = await fetchWithRetry(
        url,
        { headers: { 'User-Agent': this.userAgent, Accept: 'application/json' } },
        this.fetchFn,
        deadlineMs,
      );
      return await res.json();
    } catch (err) {
      if (isCatalogError(err)) throw err;
      toCatalogError(err, 'googlebooks request failed');
    }
  }

  /** Search volumes; a bare ISBN is sent as plain digits (never a colon operator). */
  async search(
    query: string,
    page: number,
    pageSize = 24,
  ): Promise<{
    books: CatalogBook[];
    totalCount: number;
  }> {
    const size = clampPageSize(pageSize);
    const startIndex = (page - 1) * size;
    const q = encodeURIComponent(buildGoogleBooksQuery(query));
    const url = `${GOOGLE_BOOKS_BASE_URL}/volumes?q=${q}&startIndex=${startIndex}&maxResults=${size}&fields=${GOOGLE_BOOKS_SEARCH_FIELDS}${this.keyParam()}`;
    const data = (await this.getJson(url, SEARCH_DEADLINE_MS)) as GoogleBooksSearchResponse;
    const books = (data.items ?? [])
      .map(mapGoogleBooksVolume)
      .filter((b): b is CatalogBook => b !== null)
      .slice(0, size);
    return {
      books,
      totalCount: typeof data.totalItems === 'number' ? data.totalItems : books.length,
    };
  }

  /** Fetch one volume by id; unknown ids surface NOT_FOUND. */
  async getById(volumeId: string): Promise<CatalogBook> {
    const url = `${GOOGLE_BOOKS_BASE_URL}/volumes/${encodeURIComponent(volumeId)}?fields=${GOOGLE_BOOKS_VOLUME_FIELDS}${this.keyParam()}`;
    const data = (await this.getJson(url, DETAIL_DEADLINE_MS)) as GoogleBooksVolumeItem;
    const book = mapGoogleBooksVolume(data);
    if (!book) throw catalogError('NOT_FOUND', 'googlebooks volume unavailable');
    return book;
  }
}
