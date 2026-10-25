/**
 * Standard Ebooks datasource — a real OPDS/Atom acquisition feed.
 *
 * Endpoint reality (measured 2026-10, see the module's own note below): the
 * `/feeds/opds*` family answers HTTP 401 for every Accept variant. Its
 * `WWW-Authenticate` header asks for Basic credentials with the Patrons Circle
 * email, i.e. that feed is a members-only endpoint and NOT a public catalog.
 * Requesting `application/opds+json` therefore never yields OPDS 2.0 JSON for
 * an anonymous client, so the JSON-first path is not implementable here.
 *
 * The public equivalent is `https://standardebooks.org/feeds/atom/new-releases`:
 * HTTP 200, `application/atom+xml`, 15 entries, each carrying a title, author,
 * cover thumbnail and an EPUB enclosure link. It is parsed with the platform
 * `DOMParser` — deliberately no XML package is added for one feed.
 *
 * Transport: the site sends NO `Access-Control-Allow-Origin`, so a webview
 * `fetch` of this URL is blocked by CORS regardless of how the rail is routed.
 * The default transport is therefore the Rust `fetchCatalogFeed` command
 * (`catalogFeedTransport`), which fetches without a browser origin. Tests and
 * scripts inject their own `fetch`, which is why the parameter is still there.
 *
 * The feed is a single unpaged, date-ordered page of the newest releases, so
 * `page > 1` is an empty page rather than an invented slice of a nonexistent
 * catalog.
 */
import { catalogError, isCatalogError } from './errors';
import { DESKTOP_USER_AGENT, SEARCH_DEADLINE_MS, fetchWithRetry, toCatalogError } from './policy';
import { mapStandardEbooksAtomFeed } from './mappers';
import { catalogFeedFetch } from './catalogFeedTransport';
import type { CatalogBook } from './CatalogProvider';

export const STANDARD_EBOOKS_BASE_URL = 'https://standardebooks.org';

/**
 * The one public feed. `/feeds/opds` and `/feeds/opds/new-releases` both 401;
 * `/feeds/atom/popular` does not exist (404).
 */
export const STANDARD_EBOOKS_ATOM_FEED_URL = `${STANDARD_EBOOKS_BASE_URL}/feeds/atom/new-releases`;

/** Stable inquiry key for the "newest releases" shelf; the only feed offered. */
export const STANDARD_EBOOKS_NEW_RELEASES = 'new-releases';

/**
 * `DOMParser` from the host realm. Read lazily so importing this module in a
 * non-DOM context (node during a build step) never throws at module load.
 */
export function standardEbooksXmlParser(): DOMParser {
  const Parser = (globalThis as { DOMParser?: typeof DOMParser }).DOMParser;
  if (typeof Parser !== 'function') {
    throw catalogError('UPSTREAM_ERROR', 'DOMParser unavailable for the atom feed');
  }
  return new Parser();
}

export class StandardEbooksDataSource {
  constructor(
    // Rust-backed by default: the feed has no CORS header, so the ambient
    // webview `fetch` cannot read it. Injected transports keep the retry and
    // deadline behaviour below, they only replace the wire.
    private readonly fetchFn: typeof fetch = catalogFeedFetch,
    private readonly userAgent: string = DESKTOP_USER_AGENT,
  ) {}

  /**
   * Fetch and map the newest-releases feed. The Atom media type is the one
   * measured to answer 200; asking for OPDS JSON here would only confirm the
   * 401 again.
   */
  private async getFeed(): Promise<CatalogBook[]> {
    try {
      const res = await fetchWithRetry(
        STANDARD_EBOOKS_ATOM_FEED_URL,
        {
          headers: { 'User-Agent': this.userAgent, Accept: 'application/atom+xml' },
        },
        this.fetchFn,
        SEARCH_DEADLINE_MS,
      );
      return mapStandardEbooksAtomFeed(await res.text(), standardEbooksXmlParser());
    } catch (err) {
      if (isCatalogError(err)) throw err;
      // A malformed feed is an upstream fault, never a "no books" answer: a rail
      // that silently hid would look like an empty catalog rather than a break.
      toCatalogError(err, 'standardebooks feed request failed');
    }
  }

  /** Newest releases, page 1 only; deeper pages are empty without I/O. */
  async newest(page: number): Promise<{ books: CatalogBook[]; totalCount: number }> {
    if (page !== 1) return { books: [], totalCount: 0 };
    const books = await this.getFeed();
    return { books, totalCount: books.length };
  }

  /** One book by its namespaced id; anything absent from the feed is unknown. */
  async getById(id: string): Promise<CatalogBook> {
    const book = (await this.getFeed()).find((entry) => entry.id === id);
    if (!book) throw catalogError('NOT_FOUND', `standardebooks book ${id} unavailable`);
    return book;
  }
}
