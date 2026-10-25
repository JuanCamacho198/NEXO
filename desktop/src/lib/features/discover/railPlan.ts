import {
  BUILTIN_GOOGLEBOOKS,
  BUILTIN_STANDARD_EBOOKS,
  catalogError,
  type CatalogBook,
  type CatalogProvider,
  type PagedResult,
} from '$lib/shared/services/catalog';
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import { authorEntriesFor } from './railRotation';

/**
 * One rail, as a discriminated union so each source is reached through the
 * resolution its upstream actually supports:
 *
 * - `author` — a Google Books term search of a curated author surname.
 * - `popular` — the Gutendex all-time download ordering (`?sort=popular`).
 * - `opds` — the Standard Ebooks newest-releases OPDS/Atom shelf.
 *
 * The union is the reason `loadRail` needs no capability probing at the call
 * site: the spec already names the source that can answer it.
 */
export type DiscoverRailSpec =
  | { kind: 'author'; term: string; titleKey: MessageKey }
  | { kind: 'popular'; titleKey: MessageKey }
  | { kind: 'opds'; titleKey: MessageKey };

/** The two rotating author shelves' share of the plan. */
export const DISCOVER_AUTHOR_RAIL_COUNT = 2;
/**
 * Four rails: two rotating authors, one Gutendex popular, one Standard Ebooks.
 * Fixed count so the plan's length never depends on which sources happen to
 * answer — a failing source hides its own section, it never renumbers the row.
 */
export const DISCOVER_RAIL_COUNT = 4;
/**
 * Fallback render count used only until the rail container's width is
 * measured. Once measured, the visible count comes from
 * `deriveVisibleRailCount(containerWidth)` so the row is filled at any width.
 */
export const DISCOVER_RAIL_LIMIT = 6;
/**
 * "Ver todo" scope page size. Kept as the historical first-page bound; the
 * Google Books datasource already clamps its own page to `MIN_PAGE_SIZE`.
 */
export const RAIL_SCOPE_LIMIT = 24;

/**
 * The rail grid renders `repeat(auto-fill, minmax(160px, 1fr))` with `gap-3`
 * (0.75rem at a 16px root = 12px). Both numbers are mirrored here so the
 * derived card count matches the columns the browser actually creates.
 */
export const RAIL_CARD_MIN_WIDTH_PX = 160;
export const RAIL_CARD_GAP_PX = 12;
/** A very narrow window still shows a useful handful of cards, never one. */
export const MIN_VISIBLE_RAIL_CARDS = 2;
/** Upper bound per rail, so an ultra-wide monitor cannot fan out unbounded. */
export const MAX_VISIBLE_RAIL_CARDS = 24;
/** Fetched per rail: the widest row any supported viewport can render. */
export const DISCOVER_RAIL_FETCH_LIMIT = MAX_VISIBLE_RAIL_CARDS;

/**
 * Columns that fit in `containerWidth` for a one-row rail, floored at
 * `MIN_VISIBLE_RAIL_CARDS` and capped at `MAX_VISIBLE_RAIL_CARDS`. An
 * unmeasured container (0, negative or non-finite) falls back to
 * `DISCOVER_RAIL_LIMIT` instead of collapsing the rail.
 */
export function deriveVisibleRailCount(containerWidth: number): number {
  if (!Number.isFinite(containerWidth) || containerWidth <= 0) {
    return DISCOVER_RAIL_LIMIT;
  }
  const columns = Math.floor(
    (containerWidth + RAIL_CARD_GAP_PX) / (RAIL_CARD_MIN_WIDTH_PX + RAIL_CARD_GAP_PX),
  );
  return Math.max(MIN_VISIBLE_RAIL_CARDS, Math.min(MAX_VISIBLE_RAIL_CARDS, columns));
}

/**
 * Index-stable rail plan: the day's two rotating author shelves first, then the
 * two fixed-source shelves. Rebuilt per refresh, so crossing local midnight
 * rolls the author window forward; the selection formula lives in
 * `railRotation.ts`. The fixed rails never move, so a refresh can only change
 * positions 0–1 and a settled rail is never renumbered by a neighbour's failure.
 */
export function buildRailSpecs(now: Date = new Date()): readonly DiscoverRailSpec[] {
  const authorRails: DiscoverRailSpec[] = authorEntriesFor(now, DISCOVER_AUTHOR_RAIL_COUNT).map(
    (entry) => ({
      kind: 'author',
      term: entry.term,
      titleKey: entry.titleKey,
    }),
  );
  return [
    ...authorRails,
    { kind: 'popular', titleKey: 'discover.rail.gutendexPopular' },
    { kind: 'opds', titleKey: 'discover.rail.standardEbooks' },
  ];
}

/**
 * Resolve one rail through the provider named by its own kind. The `limit` is
 * used by the fixed-source rails, which ask for exactly one row; the author
 * path keeps passing it for call-site compatibility, because the Google Books
 * datasource owns its own page size and the state layer slices.
 */
export function loadRail(
  provider: CatalogProvider,
  spec: DiscoverRailSpec,
  limit: number = DISCOVER_RAIL_FETCH_LIMIT,
): Promise<PagedResult> {
  switch (spec.kind) {
    case 'author':
      // Non-ordering author shelf: a per-source search of a non-empty term.
      // `searchSource` read-through-caches the page, so a cold fan-out stays
      // within the shared page cache and never multiplies quota pressure.
      return provider.searchSource(BUILTIN_GOOGLEBOOKS, spec.term, 1);
    case 'popular':
      // The featured POPULAR ordering, never an empty term search: measured
      // against the live API, `?search=&page=1` is an unfiltered full scan that
      // took 64s, while `?sort=popular` answers in ~250ms. The rail deadline is
      // 15s, so the search shape would time out every time.
      return provider.featured('POPULAR', limit);
    case 'opds':
      // Standard Ebooks publishes one newest-releases feed; the provider maps
      // that shelf to a page and slices it to the requested row width.
      return provider.searchSource(BUILTIN_STANDARD_EBOOKS, STANDARD_EBOOKS_RAIL_QUERY, 1);
  }
}

/** The Standard Ebooks feed has no query parameter; the term is ignored upstream. */
const STANDARD_EBOOKS_RAIL_QUERY = '';

/**
 * Rails fetch one row, but a source may return its whole feed. Bound the page
 * to `limit` so the state layer never holds more cards than the widest
 * supported viewport can draw.
 */
export function boundRailPage(page: PagedResult, limit: number): PagedResult {
  if (page.results.length <= limit) return page;
  return { ...page, results: page.results.slice(0, limit) };
}

/**
 * Accent- and case-insensitive fold used for author comparison. NFD splits a
 * precomposed character into base + combining mark, and the `\u0300`–`\u036f`
 * block is exactly Unicode's combining diacritical marks, so stripping it
 * degrades "Pérez" to "perez" without touching ASCII letters.
 */
function foldAuthorText(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}

/** Whole words of an author string, so "melvilles" never matches "melville". */
function authorWords(author: string): readonly string[] {
  return foldAuthorText(author)
    .split(/[^a-z0-9]+/)
    .filter(Boolean);
}

/**
 * True iff `book` is attributed to the rail term's author. The surname is the
 * LAST whitespace-separated token of the term (`herman melville` → `melville`,
 * `arthur conan doyle` → `doyle`, `h g wells` → `wells`), and it must appear as
 * a whole word in one of the book's authors after case folding and accent
 * stripping. Free-text rail search ranks criticism, study guides and
 * biographies above the works themselves; this is the client-side guarantee
 * that a `Loaded` rail never carries a foreign book. A book with no authors, or
 * a term with no surname, never passes. Pure: no I/O, total over any input.
 */
export function matchesAuthorQuery(book: CatalogBook, term: string): boolean {
  const surname = term.trim().split(/\s+/).pop();
  if (surname === undefined || surname === '') return false;
  const needle = foldAuthorText(surname);
  return book.authors.some((author) => authorWords(author).includes(needle));
}

/**
 * Bound a rail attempt by `ms` so a hung request settles instead of staying
 * `Loading` forever. Deliberately a manual timer (not `AbortSignal.timeout`) so
 * fake timers can drive it under jsdom; expiry is a slow-upstream timeout and
 * maps to UPSTREAM_TIMEOUT (retryable, never reported as offline).
 */
export async function withDeadline<T>(work: Promise<T>, ms: number): Promise<T> {
  let handle: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      work,
      new Promise<never>((_, reject) => {
        handle = setTimeout(
          () => reject(catalogError('UPSTREAM_TIMEOUT', `rail deadline of ${ms}ms elapsed`)),
          ms,
        );
      }),
    ]);
  } finally {
    if (handle !== undefined) clearTimeout(handle);
  }
}
