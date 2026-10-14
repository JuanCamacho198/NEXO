/**
 * Discover source filter — the second client-side dimension beside the genre
 * chips: loaded books are narrowed by catalog source instead of subject.
 *
 * Mirrors the Android `DiscoverSourceFilter` sealed interface: the selection is
 * either all sources or one concrete source id. Pure, synchronous, and derived
 * over already-loaded books — selecting a source never triggers a catalog call,
 * never changes a rail's `Loading`/`Error` state, and hides a rail that has no
 * book of that source (a rail filtered to zero renders no bare header).
 */
import type { CatalogBook, CatalogSourceInfo } from '$lib/shared/services/catalog';

/** Selected catalog source; `null` means every source is in scope. */
export type DiscoverSourceFilter = string | null;

/** Sentinel for "all sources": the label is i18n, this value is not a source id. */
export const ALL_SOURCES: DiscoverSourceFilter = null;

/** True when a book belongs to the selected source; "all" matches every book. */
export function matchesSource(book: CatalogBook, filter: DiscoverSourceFilter): boolean {
  if (filter === ALL_SOURCES) return true;
  return book.provider === filter;
}

/** Client-side source filter; an "all" selection returns the slice untouched. */
export function filterBooksBySource(
  books: CatalogBook[],
  filter: DiscoverSourceFilter,
): CatalogBook[] {
  if (filter === ALL_SOURCES) return books;
  return books.filter((book) => matchesSource(book, filter));
}

/**
 * Degrade honestly: the source set is build-dependent (Google Books is
 * key-gated, addons register at runtime), so a selection that no longer appears
 * in `listSources()` falls back to "all" instead of rendering a stuck chip row
 * or an empty grid. An "all" selection is already valid.
 */
export function reconcileSourceFilter(
  filter: DiscoverSourceFilter,
  sources: readonly CatalogSourceInfo[],
): DiscoverSourceFilter {
  if (filter === ALL_SOURCES) return ALL_SOURCES;
  return sources.some((source) => source.sourceId === filter) ? filter : ALL_SOURCES;
}
