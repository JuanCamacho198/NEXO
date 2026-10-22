import { bookPagePosition, type BookPagePosition, type ChapterPageCounts } from './bookPagination';

/**
 * Per-book page counts, one map per chapter of the book being read.
 *
 * The cache is the reason the total can GROW instead of being computed up front:
 * a chapter's count is recorded when the reader actually opens it, and every
 * chapter already visited keeps its count. Measuring the book eagerly would mean
 * paginating all of it before the first page appears — multi-second work on a
 * real EPUB — so the honest trade is a total that fills in as the book is read.
 *
 * Two lifetimes, deliberately:
 *  - a chapter CHANGE keeps the map (that is the whole point: chapter 3 must know
 *    what chapters 1 and 2 were worth);
 *  - a different BOOK starts a fresh map, because a chapter page count is a
 *    property of that book's layout, not of the reader.
 *
 * The viewer owns one instance for its whole life, so reopening the same book
 * keeps it too — only `reset()` on a book change clears it.
 */
export function createBookPageCache(): {
  /** Record what a chapter counts as. A non-positive count is ignored. */
  record(chapter: number, pageCount: number): void;
  /** The shared position for `chapter` at `page`, in the cached unit. */
  position(chapter: number, page: number): BookPagePosition;
  /** Drop everything: a different book, or a different counting unit. */
  reset(): void;
  /** Reading-order chapters counted so far. */
  readonly measuredChapters: number;
} {
  // Not `$state`: the cache is written from layout measurement callbacks and read
  // by a `$derived` through `position`, which is invoked from tracked code. A
  // reactive write from an observer callback would make the observer a dependency
  // of the graph it feeds, which is the re-entrancy the pagination composable
  // already documents for `pageBox`. The reactive numbers the footer reads are
  // the viewer's own `$state` page and total; this is the history behind them.
  let chapters: Map<number, number> = new Map();

  function record(chapter: number, pageCount: number): void {
    if (!Number.isFinite(chapter)) return;
    const count = Math.floor(Number(pageCount));
    // A chapter measured at 0 is an unmeasured chapter (`derivePageCount` says so
    // on purpose), not a chapter with no pages. Storing it would let the total
    // claim a page the reader can never reach.
    if (!Number.isFinite(count) || count <= 0) return;
    const at = Math.trunc(chapter);
    if (chapters.get(at) === count) return;
    chapters.set(at, count);
  }

  function position(chapter: number, page: number): BookPagePosition {
    return bookPagePosition(chapters as ChapterPageCounts, chapter, page);
  }

  function reset(): void {
    chapters = new Map();
  }

  return {
    record,
    position,
    reset,
    get measuredChapters(): number {
      return chapters.size;
    },
  };
}

export type BookPageCache = ReturnType<typeof createBookPageCache>;
