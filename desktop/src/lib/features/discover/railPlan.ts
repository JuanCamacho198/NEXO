import {
  BUILTIN_STANDARD_EBOOKS,
  catalogError,
  type CatalogProvider,
  type PagedResult,
} from '$lib/shared/services/catalog';
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import { topicEntriesFor } from './railRotation';

/**
 * One rail, as a discriminated union so each source is reached through the
 * resolution its upstream actually supports:
 *
 * - `topic` — a Gutendex `?topic=` search of a curated, closed topic term.
 * - `popular` — the Gutendex all-time download ordering (`?sort=popular`).
 * - `opds` — the Standard Ebooks newest-releases OPDS/Atom shelf.
 *
 * The union is the reason `loadRail` needs no capability probing at the call
 * site: the spec already names the source that can answer it.
 *
 * Google Books LEFT the rail plan (its `author` kind is gone) but stays a
 * registered provider: it is still a search source, still detail-routable, and
 * still what a rail scope browses. Deleting the provider would break search.
 */
export type DiscoverRailSpec =
  | { kind: 'topic'; term: string; titleKey: MessageKey }
  | { kind: 'popular'; titleKey: MessageKey }
  | { kind: 'opds'; titleKey: MessageKey };

/**
 * The upstream a rail is served from. Rails of one source share that source's
 * quota and outage, so they are reported together: when every rail of a source
 * fails the page shows ONE notice naming it, instead of one identical sentence
 * per shelf.
 */
export type DiscoverRailSource = 'googlebooks' | 'gutendex' | 'standard-ebooks';

/** Source of a rail spec; the fixed-source shelves are one rail each. */
export function railSourceOf(spec: DiscoverRailSpec): DiscoverRailSource {
  switch (spec.kind) {
    case 'topic':
      return 'gutendex';
    case 'popular':
      return 'gutendex';
    case 'opds':
      return 'standard-ebooks';
  }
}

/**
 * The source a rail is fetched THROUGH. It is the same as `railSourceOf`
 * except on a degraded build: when Google Books is absent, the topic and
 * popular shelves are served by the provider's in-memory Gutendex fallback,
 * whose identifier carries that fallback's own tag. Reporting it honestly keeps
 * a grouped notice from naming a source the build did not actually call.
 */
export function railServingSourceOf(spec: DiscoverRailSpec): DiscoverRailSource {
  return railSourceOf(spec);
}

/** i18n key naming the source in a grouped failure notice. */
export const RAIL_SOURCE_LABEL_KEYS: Record<DiscoverRailSource, MessageKey> = {
  googlebooks: 'discover.rail.group.googlebooks',
  gutendex: 'discover.rail.group.gutendex',
  'standard-ebooks': 'discover.rail.group.standardEbooks',
};

/** The two rotating topic shelves' share of the plan. */
export const DISCOVER_TOPIC_RAIL_COUNT = 2;
/**
 * Four rails: two rotating Gutendex topics, one Gutendex popular, one Standard
 * Ebooks. Fixed count so the plan's length never depends on which sources
 * happen to answer — a failing source hides its own section, it never
 * renumbers the row.
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
 * Index-stable rail plan: the day's two rotating topic shelves first, then the
 * two fixed-source shelves. Rebuilt per refresh, so crossing local midnight
 * rolls the topic window forward; the selection formula lives in
 * `railRotation.ts`. The fixed rails never move, so a refresh can only change
 * positions 0–1 and a settled rail is never renumbered by a neighbour's failure.
 */
export function buildRailSpecs(now: Date = new Date()): readonly DiscoverRailSpec[] {
  const topicRails: DiscoverRailSpec[] = topicEntriesFor(now, DISCOVER_TOPIC_RAIL_COUNT).map(
    (entry) => ({
      kind: 'topic',
      term: entry.term,
      titleKey: entry.titleKey,
    }),
  );
  return [
    ...topicRails,
    { kind: 'popular', titleKey: 'discover.rail.gutendexPopular' },
    { kind: 'opds', titleKey: 'discover.rail.standardEbooks' },
  ];
}

/**
 * Resolve one rail through the provider named by its own kind. The `limit` is
 * used by the fixed-source rails, which ask for exactly one row; the topic
 * path keeps passing it for call-site compatibility, because the Gutendex page
 * is fetched at the courtesy size and the state layer slices.
 *
 * A provider rejection propagates, so the caller settles that rail `Error`. An
 * empty successful page stays an empty page: the caller hides the shelf.
 */
export function loadRail(
  provider: CatalogProvider,
  spec: DiscoverRailSpec,
  limit: number = DISCOVER_RAIL_FETCH_LIMIT,
): Promise<PagedResult> {
  switch (spec.kind) {
    case 'topic':
      // The Gutendex `?topic=` selector of a term from the closed table in
      // `railRotation.ts` — NEVER `searchSource`. Measured live, the same words
      // through `?search=` answer 6 books where `?topic=` answers 3 530, so the
      // free-text door turns a full shelf into an empty one. `topic()` caches
      // read-through under its own `t:` namespace, so a cold fan-out of two topic
      // rails never multiplies upstream pressure.
      return provider.topic(spec.term, 1);
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
