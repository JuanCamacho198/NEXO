/**
 * bookPageCounts — the per-book cache of measured chapter page counts.
 *
 * The book total is a sum of chapters, and a chapter's page count only exists
 * once it has been laid out. Re-measuring on every chapter change would make
 * the total collapse to the chapter on screen each time, so the numbers are
 * kept here instead of in the viewer: a chapter change replaces one entry, it
 * does not throw the others away.
 *
 * MODULE scope, not component scope, on purpose: a book that is closed and
 * reopened must not have to re-measure from zero, and viewer state dies with
 * the viewer.
 *
 * TWO resets, both structural rather than housekeeping:
 *  - a BOOK change is a different key, so nothing can carry between books;
 *  - a MODE change must drop the counts outright. A column total (`/ 345`)
 *    printed over 43 documents is the exact contradiction this feature exists
 *    to avoid, so the viewer clears on the mode flip and never reads this cache
 *    while scrolling.
 *
 * Only columns are cached. A "document count" per chapter would be 1 for every
 * reading-order position, and the reading order already names all of them the
 * moment the book opens — caching those one at a time would replace an exact
 * total with a partial one.
 */

import type { ChapterPageCounts } from '$lib/features/reader/viewer-epub/bookPagePosition';

type BookEntry = {
  /** Pages per reading-order position; index = position, 0 = not measured. */
  counts: number[];
};

const NO_COUNTS: readonly number[] = Object.freeze([]);

class BookPageCountCache {
  /** bookId → its measured chapters. `$state` so the page field re-derives. */
  entries = $state<Record<string, BookEntry>>({});

  /** Measured counts for `bookId`, or an empty list before anything is measured. */
  countsFor(bookId: string): ChapterPageCounts {
    return this.entries[bookId]?.counts ?? NO_COUNTS;
  }

  /**
   * Remember `count` pages for reading-order `position`.
   *
   * Immutable replacement of the entry, not an in-place index write: the whole
   * object is what the page field derives from, and an in-place write would only
   * be noticed if Svelte happened to track that exact index.
   *
   * An unchanged count is not written at all. `record` runs from an effect that
   * follows every measurement, and a no-op write would re-invalidate the same
   * graph that asked for the measurement.
   */
  record(bookId: string, position: number, count: number): void {
    if (!bookId) return;
    const at = Math.trunc(position);
    const pages = Math.trunc(count);
    if (!Number.isFinite(at) || at < 0 || !Number.isFinite(pages) || pages <= 0) return;
    const current = this.entries[bookId];
    const counts = current ? current.counts.slice() : [];
    while (counts.length <= at) counts.push(0);
    if (counts[at] === pages) return;
    counts[at] = pages;
    this.entries = { ...this.entries, [bookId]: { counts } };
  }

  /** Forget one book — the mode-change reset, and the book-change reset. */
  clear(bookId: string): void {
    if (!this.entries[bookId]) return;
    const next = { ...this.entries };
    delete next[bookId];
    this.entries = next;
  }

  /** Forget everything. Tests only: production always has a book to name. */
  clearAll(): void {
    this.entries = {};
  }
}

export const bookPageCounts = new BookPageCountCache();
