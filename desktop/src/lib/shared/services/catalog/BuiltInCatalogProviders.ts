/**
 * Built-in CatalogProvider adapters over the raw datasources.
 * Open Library enriches + cover fallback; Google Books is a key-gated metadata
 * enrichment source (never a download source). Gutendex is the download/metadata
 * authority and the composite-search fallback when Google Books is absent.
 * Standard Ebooks serves a real OPDS/Atom acquisition shelf.
 * Book-id formats stay byte-for-byte: `gutendex:<numericId>` /
 * `openlibrary:<key>` / `googlebooks:<volumeId>` / `standard-ebooks:<slug>`.
 */
import { catalogError } from './errors';
import {
  BUILTIN_GUTENDEX,
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_OPENLIBRARY,
  BUILTIN_STANDARD_EBOOKS,
} from './CatalogProvider';
import type {
  CatalogBook,
  CatalogFeaturedSort,
  CatalogProvider,
  CatalogSource,
  CatalogSourceInfo,
  PagedResult,
} from './CatalogProvider';
import { GutendexDataSource } from './GutendexDataSource';
import { GOOGLE_BOOKS_MAX_START_INDEX, GoogleBooksDataSource } from './GoogleBooksDataSource';
import { OpenLibraryDataSource } from './OpenLibraryDataSource';
import { StandardEbooksDataSource } from './StandardEbooksDataSource';
import { resolveDownloadUrl, toPagedResult } from './mappers';
import type { OpenLibraryIdentity } from './bookAuthorityResolver';
import { DEFAULT_PAGE_SIZE, MIN_PAGE_SIZE } from './policy';

const GUTENDEX_ID_PREFIX = 'gutendex:';
const GOOGLEBOOKS_ID_PREFIX = 'googlebooks:';
const STANDARD_EBOOKS_ID_PREFIX = 'standard-ebooks:';
const EMPTY_PAGE: PagedResult = { results: [], nexoPage: null, totalCount: 0 };

export class GutendexCatalogProvider implements CatalogProvider {
  constructor(
    private readonly ds: GutendexDataSource = new GutendexDataSource(),
    /**
     * Composite-search fallback flag. `defaultCatalogProviders` passes `true`
     * only when Google Books is not registered (keyless build): Gutendex then
     * stays in the fan-out so a keyless search still returns results. When
     * Google Books is registered this is `false`, and the slow Gutendex search
     * leaves the fan-out entirely (its detail routing, `searchSource` and
     * download resolution are untouched).
     */
    private readonly compositeSearchFallback = true,
  ) {}

  supportsCompositeSearch(): boolean {
    return this.compositeSearchFallback;
  }

  async search(query: string, page: number): Promise<PagedResult> {
    const { books, totalCount } = await this.ds.search(query, page);
    return toPagedResult(books, page, totalCount);
  }

  /** Gutendex detail by numeric id; malformed ids reject NOT_FOUND. */
  async getDetails(id: string): Promise<CatalogBook> {
    if (!id.startsWith(GUTENDEX_ID_PREFIX)) {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    }
    const numericId = Number(id.slice(GUTENDEX_ID_PREFIX.length));
    if (!Number.isInteger(numericId) || numericId < 1) {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    }
    return this.ds.getById(numericId);
  }

  /** Featured rails are served by Gutendex alone (POPULAR / NEWEST). */
  async featured(sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
    if (!Number.isInteger(limit) || limit < 1) {
      throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
    }
    // The courtesy window clamps small fetches up to MIN_PAGE_SIZE; slice
    // back to the requested rail size so rails never over-render.
    const { books, totalCount } = await this.ds.featured(sort, 1, Math.max(limit, MIN_PAGE_SIZE));
    const sliced = books.slice(0, limit);
    return toPagedResult(sliced, 1, totalCount);
  }

  supportsFeatured(_sort: CatalogFeaturedSort): boolean {
    return true;
  }

  /**
   * Gutendex owns exactly one source; anything else fails closed with an
   * empty page (never a crash, never a silent composite search).
   */
  async searchSource(sourceId: CatalogSource, query: string, page: number): Promise<PagedResult> {
    if (sourceId !== BUILTIN_GUTENDEX) {
      return { results: [], nexoPage: null, totalCount: 0 };
    }
    return this.search(query, page);
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }

  listSources(): CatalogSourceInfo[] {
    return [{ sourceId: BUILTIN_GUTENDEX, name: 'Gutendex', kind: 'builtin' }];
  }
}

export class OpenLibraryCatalogProvider implements CatalogProvider {
  constructor(private readonly ds: OpenLibraryDataSource = new OpenLibraryDataSource()) {}

  async search(query: string, page: number): Promise<PagedResult> {
    const { books, totalCount } = await this.ds.search(query, page);
    return toPagedResult(books, page, totalCount);
  }

  /** Open Library ids are not detail-resolvable (NOT_FOUND — preserved contract). */
  async getDetails(id: string): Promise<CatalogBook> {
    throw catalogError('NOT_FOUND', `openlibrary ids are not detail-resolvable: ${id}`);
  }

  /**
   * DISC-04c lazy identity enrichment: work + Internet Archive id for an ISBN,
   * read from a `fields`-projected `search.json` lookup. Returns null when the
   * lookup yields no identity — a no-match is not an error.
   */
  async enrichByIsbn(isbn: string): Promise<OpenLibraryIdentity | null> {
    const doc = (await this.ds.searchByIsbn(isbn))[0];
    if (!doc) return null;
    const workId = (doc.key ?? '').trim();
    const archiveId = ((doc.ia ?? [])[0] ?? '').trim();
    if (workId === '' && archiveId === '') return null;
    return {
      workId: workId === '' ? null : workId,
      archiveId: archiveId === '' ? null : archiveId,
    };
  }

  // `featured` / `supportsFeatured` are deliberately left fail-closed.
  // `/trending/*.json` returns 100 works but is dominated by
  // borrow-restricted in-copyright titles that mapOpenLibraryDoc drops, its
  // payloads are 274-319 KB, and the endpoint proved flaky — so no OL rail,
  // no new OL endpoint, and the rail simply auto-hides. Mirrors Android.
  async featured(_sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
    if (!Number.isInteger(limit) || limit < 1) {
      throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
    }
    return { results: [], nexoPage: null, totalCount: 0 };
  }

  supportsFeatured(_sort: CatalogFeaturedSort): boolean {
    return false;
  }

  /**
   * Open Library leaves the composite keyword-search fan-out: its
   * `search.json` latency (measured 0.7–8 s) makes it a poor primary source.
   * It stays registered so `searchSource('builtin:openlibrary', …)` and any
   * enrichment that consumes its docs keep working.
   */
  supportsCompositeSearch(): boolean {
    return false;
  }

  /**
   * Open Library owns exactly one source; anything else fails closed with
   * an empty page (never a crash, never a silent composite search).
   */
  async searchSource(sourceId: CatalogSource, query: string, page: number): Promise<PagedResult> {
    if (sourceId !== BUILTIN_OPENLIBRARY) {
      return { results: [], nexoPage: null, totalCount: 0 };
    }
    return this.search(query, page);
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }

  listSources(): CatalogSourceInfo[] {
    return [{ sourceId: BUILTIN_OPENLIBRARY, name: 'Open Library', kind: 'builtin' }];
  }
}

export class GoogleBooksCatalogProvider implements CatalogProvider {
  constructor(private readonly ds: GoogleBooksDataSource = new GoogleBooksDataSource()) {}

  /**
   * Pages past `GOOGLE_BOOKS_MAX_START_INDEX` never reach the network: an empty
   * page with `nexoPage: null` exhausts every consumer, and `totalCount` is the
   * addressable window (cap + one page), never an invented result count.
   */
  async search(query: string, page: number): Promise<PagedResult> {
    if ((page - 1) * DEFAULT_PAGE_SIZE > GOOGLE_BOOKS_MAX_START_INDEX) {
      return {
        results: [],
        nexoPage: null,
        totalCount: GOOGLE_BOOKS_MAX_START_INDEX + DEFAULT_PAGE_SIZE,
      };
    }
    const { books, totalCount } = await this.ds.search(query, page);
    return toPagedResult(books, page, totalCount);
  }

  /** Google Books detail by volume id; malformed ids reject NOT_FOUND, no I/O. */
  async getDetails(id: string): Promise<CatalogBook> {
    if (!id.startsWith(GOOGLEBOOKS_ID_PREFIX)) {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    }
    const volumeId = id.slice(GOOGLEBOOKS_ID_PREFIX.length);
    if (volumeId.trim() === '' || volumeId.includes('/') || volumeId.includes(' ')) {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    }
    return this.ds.getById(volumeId);
  }

  // `featured` / `supportsFeatured` are deliberately fail-closed: Google Books
  // exposes no verified ordered rail, so the rail auto-hides and the provider
  // never contributes to the featured fan-out.
  async featured(_sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
    if (!Number.isInteger(limit) || limit < 1) {
      throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
    }
    return { results: [], nexoPage: null, totalCount: 0 };
  }

  supportsFeatured(_sort: CatalogFeaturedSort): boolean {
    return false;
  }

  /**
   * Google Books owns exactly one source; anything else fails closed with an
   * empty page (never a crash, never a silent composite search).
   */
  async searchSource(sourceId: CatalogSource, query: string, page: number): Promise<PagedResult> {
    if (sourceId !== BUILTIN_GOOGLEBOOKS) {
      return { results: [], nexoPage: null, totalCount: 0 };
    }
    return this.search(query, page);
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }

  listSources(): CatalogSourceInfo[] {
    return [{ sourceId: BUILTIN_GOOGLEBOOKS, name: 'Google Books', kind: 'builtin' }];
  }
}

/**
 * Standard Ebooks: a real first-party catalog over the public Atom acquisition
 * feed. `opds` rails resolve to the newest-releases feed through `searchSource`,
 * because the site publishes exactly one public feed — there is no query
 * parameter to search it with. The two doors stay distinct on purpose:
 * `search` fails closed (a keyword search cannot be honored), `searchSource`
 * answers the shelf. Collapsing them is exactly the bug that left the rail
 * empty with zero I/O.
 */
export class StandardEbooksCatalogProvider implements CatalogProvider {
  constructor(private readonly ds: StandardEbooksDataSource = new StandardEbooksDataSource()) {}

  /**
   * The feed is a fixed newest-releases shelf, not a searchable index: a keyword
   * search cannot be honored, so this fails closed with an empty page rather
   * than returning unrelated books under the user's query.
   */
  async search(_query: string, _page: number): Promise<PagedResult> {
    return EMPTY_PAGE;
  }

  /** Detail by namespaced id; malformed or unknown ids reject NOT_FOUND. */
  async getDetails(id: string): Promise<CatalogBook> {
    if (!id.startsWith(STANDARD_EBOOKS_ID_PREFIX)) {
      throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
    }
    return this.ds.getById(id);
  }

  /** The newest-releases shelf behind the `opds` rail kind. */
  async newest(limit: number): Promise<PagedResult> {
    if (!Number.isInteger(limit) || limit < 1) {
      throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
    }
    const { books, totalCount } = await this.ds.newest(1);
    const sliced = books.slice(0, limit);
    return toPagedResult(sliced, 1, totalCount);
  }

  /**
   * This source has no `CatalogFeaturedSort` ordering; the rail reaches it
   * through `newest()` instead. Fail-closed so the featured fan-out never calls it.
   */
  async featured(_sort: CatalogFeaturedSort, limit: number): Promise<PagedResult> {
    if (!Number.isInteger(limit) || limit < 1) {
      throw catalogError('INVALID_PAGE', `limit must be >= 1, got ${limit}`);
    }
    return EMPTY_PAGE;
  }

  supportsFeatured(_sort: CatalogFeaturedSort): boolean {
    return false;
  }

  /**
   * Owns exactly one source; anything else fails closed with an empty page.
   * The feed is a single unpaged shelf, so page > 1 answers an empty page from
   * the datasource without I/O — the same asymmetry `newest(page)` documents.
   */
  async searchSource(sourceId: CatalogSource, _query: string, page: number): Promise<PagedResult> {
    if (sourceId !== BUILTIN_STANDARD_EBOOKS) return EMPTY_PAGE;
    if (!Number.isInteger(page) || page < 1) {
      throw catalogError('INVALID_PAGE', `page must be >= 1, got ${page}`);
    }
    const { books, totalCount } = await this.ds.newest(page);
    return toPagedResult(books, page, totalCount);
  }

  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string {
    return resolveDownloadUrl(formats, preferEpub);
  }

  listSources(): CatalogSourceInfo[] {
    return [{ sourceId: BUILTIN_STANDARD_EBOOKS, name: 'Standard Ebooks', kind: 'builtin' }];
  }
}

/**
 * Fail-closed factory: a blank/absent key yields null so the provider is
 * omitted from the composite fan-out entirely — never a provider that would
 * 400/403 every search. Surrounding whitespace is trimmed so a copy-pasted
 * trailing newline still enables the source. The key is never logged.
 */
export function googleBooksProviderOrNull(
  apiKey: string,
  fetchFn: typeof fetch = fetch,
): GoogleBooksCatalogProvider | null {
  const trimmed = apiKey.trim();
  if (trimmed === '') return null;
  return new GoogleBooksCatalogProvider(new GoogleBooksDataSource(fetchFn, trimmed));
}

/**
 * Build-time key read from `import.meta.env.VITE_GOOGLE_BOOKS_KEY`. Absent,
 * non-string, or blank resolves to `''`, which omits the provider. Kept as an
 * explicit parameter so tests never depend on the ambient environment.
 */
export function googleBooksKeyFromEnv(
  env: Record<string, unknown> = import.meta.env as unknown as Record<string, unknown>,
): string {
  const raw = env.VITE_GOOGLE_BOOKS_KEY;
  return typeof raw === 'string' ? raw.trim() : '';
}
