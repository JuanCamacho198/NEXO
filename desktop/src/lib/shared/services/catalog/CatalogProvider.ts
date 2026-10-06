/**
 * CatalogProvider port — identical contract on desktop (TS) and Android (Kotlin).
 * Gutendex is metadata/download authority; Open Library enriches + cover fallback.
 */
import { catalogError, type CatalogErrorCode } from './errors';
import type { AccessOption } from './accessResolver';

/**
 * Strict catalog source ids: 'builtin:<name>' for first-party sources,
 * 'addon:<addonId>' for registry addons (addonId = sha256(url)[0..16]).
 * Exact-prefix parsing only — no tolerant parsing, malformed ids rejected.
 */
export type CatalogSource = string & { readonly __catalogSource: true };

export const BUILTIN_GUTENDEX = 'builtin:gutendex' as CatalogSource;
export const BUILTIN_OPENLIBRARY = 'builtin:openlibrary' as CatalogSource;
/**
 * Key-gated Google Books source. Registered in the closed built-in registry so
 * `parseCatalogSource('builtin:googlebooks')` accepts it, but the provider is
 * only constructed when `VITE_GOOGLE_BOOKS_KEY` is non-blank (fail-closed).
 */
export const BUILTIN_GOOGLEBOOKS = 'builtin:googlebooks' as CatalogSource;

/** Closed registry of first-party built-in source names (curated bundle included). */
const KNOWN_BUILTIN_NAMES = [
  'gutendex',
  'openlibrary',
  'googlebooks',
  'standard-ebooks',
  'librivox',
  'wikisource',
  'faded-page',
] as const;

/** addonId = sha256(url) first 16 hex chars (design A6) — lowercase only. */
const ADDON_ID_RE = /^[0-9a-f]{16}$/;

export function addonSource(addonId: string): CatalogSource {
  return `addon:${addonId}` as CatalogSource;
}

/** Extract the addonId from an addon source id; null for non-addon sources. */
export function addonSourceIdOf(source: string): string | null {
  if (!source.startsWith('addon:')) return null;
  const id = source.slice('addon:'.length);
  return ADDON_ID_RE.test(id) ? id : null;
}

/** Exact-prefix parse: built-ins are a closed registry; addons require 16-hex ids. */
export function parseCatalogSource(raw: string): CatalogSource {
  if (raw.startsWith('builtin:')) {
    const name = raw.slice('builtin:'.length);
    if ((KNOWN_BUILTIN_NAMES as readonly string[]).includes(name)) {
      return raw as CatalogSource;
    }
  } else if (raw.startsWith('addon:') && ADDON_ID_RE.test(raw.slice('addon:'.length))) {
    return raw as CatalogSource;
  }
  throw catalogError('NOT_FOUND', `unknown catalog source ${raw}`);
}

export type CatalogSourceKind = 'builtin' | 'curated' | 'addon';

export interface CatalogSourceInfo {
  sourceId: CatalogSource;
  name: string;
  kind: CatalogSourceKind;
}

export interface CatalogBook {
  id: string;
  provider: string;
  title: string;
  authors: string[];
  coverUrl: string | null;
  languages: string[];
  subjects: string[];
  downloadUrl: string | null;
  /**
   * WU1 additive-optional enrichment (Android parity). All optional so
   * existing providers, fixtures, and cached payloads keep compiling.
   */
  description?: string | null;
  formats?: Record<string, string>;
  isbn10?: string | null;
  isbn13?: string | null;
  isPublicDomain?: boolean | null;
  openLibraryWorkId?: string | null;
  internetArchiveId?: string | null;
  googleBooksId?: string | null;
}

export interface PagedResult {
  results: CatalogBook[];
  /** Next 1-based page, or null when the last page was reached. */
  nexoPage: number | null;
  totalCount: number;
}

/**
 * Addon access resolution (slice 9): the in-app download gate plus the
 * external-open options. `downloadUrl` is non-null only when
 * `canDownloadInApp` is true. `options` reuses the `AccessOption` shape with
 * `titleKey: 'discover.accessOpen'` and `opensInApp: false`.
 */
export interface AddonAccessResolution {
  canDownloadInApp: boolean;
  downloadUrl: string | null;
  options: AccessOption[];
}

/**
 * Closed domain of featured orderings: every entry maps to a verified
 * upstream literal, so no caller can ever pass a raw `sort` string through
 * to Gutendex. Mirrors Android `CatalogFeaturedSort`.
 *
 * - `POPULAR` -> `sort=popular` (all-time download count, HTTP 200 verified).
 * - `NEWEST` -> `sort=descending` (newest-by-id, HTTP 200 verified). Note this
 *   is *not* week-scoped: Gutendex exposes no time-windowed ordering.
 */
export type CatalogFeaturedSort = 'POPULAR' | 'NEWEST';

export interface CatalogProvider {
  /** `page` is 1-based; `page < 1` rejects with INVALID_PAGE before any I/O. */
  search(query: string, page: number): Promise<PagedResult>;
  /** Unknown id rejects with NOT_FOUND. */
  getDetails(id: string): Promise<CatalogBook>;
  /**
   * Pure function (no I/O): pick a download URL from a Gutendex `formats` map.
   * Throws UNAVAILABLE_DOWNLOAD when no usable URL exists.
   */
  resolveDownloadUrl(formats: Record<string, string>, preferEpub: boolean): string;

  /**
   * Addon-owned access resolve (slice 9, additive + optional): render the
   * addon's `resolveUrl` from the book identity and resolve reading access.
   * Absent ⇒ the book has no addon resolution (empty result, zero I/O).
   * `resolveDownloadUrl` semantics are untouched.
   */
  resolveAddonAccess?(book: CatalogBook): Promise<AddonAccessResolution>;

  /** Pure function (no I/O): the sources this provider can serve, in order. */
  listSources(): CatalogSourceInfo[];

  /**
   * Featured rail page (first slice): `limit` books in `sort` order, page 1.
   * `limit` must be an integer >= 1, else rejects INVALID_PAGE before any I/O.
   *
   * Fail-closed default semantics: a provider with no featured capability
   * returns an empty page, so its rail auto-hides instead of surfacing an
   * error or a placeholder section.
   */
  featured(sort: CatalogFeaturedSort, limit: number): Promise<PagedResult>;

  /**
   * Fail-closed capability probe: false means "do not build a featured rail"
   * for `sort` with this provider.
   */
  supportsFeatured(sort: CatalogFeaturedSort): boolean;

  /**
   * Per-source search scoped to one `sourceId`. Fail-closed: an unsupported
   * id yields an empty page rather than a crash. `page` is 1-based and
   * rejects INVALID_PAGE before any I/O.
   */
  searchSource(sourceId: CatalogSource, query: string, page: number): Promise<PagedResult>;

  /**
   * Fail-closed capability probe: false ⇒ the composite's fan-out `search()`
   * skips this provider. Absent ⇒ true (backward compatible). Only the
   * composite keyword-search fan-out is gated: detail routing, per-source
   * `searchSource`, featured rails and download resolution still see every
   * registered provider.
   */
  supportsCompositeSearch?(): boolean;

  /**
   * Lazy per-book authority resolve (DISC-04c, additive + optional): resolve a
   * book's Gutendex download/public-domain and Open Library work/Internet
   * Archive identity, caching the merged result under the existing detail key
   * so it runs once per book. Absent ⇒ the caller skips the enrichment. The
   * resolve is best-effort: a rejection or an empty result never replaces the
   * seeded detail and is never surfaced as an error.
   */
  resolveBookAuthorities?(book: CatalogBook): Promise<CatalogBook>;
}

export type { CatalogErrorCode };
