import {
  BUILTIN_GOOGLEBOOKS,
  catalogError,
  type CatalogProvider,
  type PagedResult,
} from '$lib/shared/services/catalog';
import type { MessageKey } from '$lib/shared/i18n/messages.en';
import { authorEntriesFor } from './railRotation';

/**
 * The rail set is exactly three curated author shelves. Every spec carries a
 * non-empty Google Books term, so no rail can ever issue an unsorted query and
 * no rail depends on an upstream ordering Google Books does not expose.
 */
export interface DiscoverRailSpec {
  kind: 'author';
  term: string;
  titleKey: MessageKey;
}

export const DISCOVER_RAIL_COUNT = 3;
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
 * Index-stable rail plan: the day's three curated author shelves, best-vetted
 * first. Rebuilt per refresh, so crossing local midnight rolls the window
 * forward one author; the selection formula lives in `railRotation.ts`.
 */
export function buildRailSpecs(now: Date = new Date()): readonly DiscoverRailSpec[] {
  return authorEntriesFor(now, DISCOVER_RAIL_COUNT).map((entry) => ({
    kind: 'author',
    term: entry.term,
    titleKey: entry.titleKey,
  }));
}

/**
 * Resolve one rail through Google Books. Rails are non-ordering author shelves,
 * so this is always a per-source search of a non-empty term; `searchSource`
 * read-through-caches the page, so a cold fan-out of three rails stays within
 * the shared page cache and never gratuitously multiplies quota pressure.
 * The `limit` parameter is retained for call-site/compatibility stability: the
 * Google Books datasource owns its own page size and the state layer slices.
 */
export function loadRail(
  provider: CatalogProvider,
  spec: DiscoverRailSpec,
  _limit: number = DISCOVER_RAIL_FETCH_LIMIT,
): Promise<PagedResult> {
  return provider.searchSource(BUILTIN_GOOGLEBOOKS, spec.term, 1);
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
