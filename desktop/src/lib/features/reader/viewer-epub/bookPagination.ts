/**
 * Whole-book page numbers, built from per-chapter page counts.
 *
 * The reader already answers two questions in the footer: the page field counts
 * pages of the CHAPTER on screen, and the percentage weights text across the
 * spine. Neither says "page 412 of 907" — where the reader is in the BOOK — and
 * that is what this module computes.
 *
 * The unit is honest per reading mode and the caller owns that choice:
 *  - paginated mode, a "page" is a column of a chapter (what the page field
 *    counts), so a chapter contributes `chapterPageTotal` pages;
 *  - scrolling mode, a "page" is a spine document, so a chapter contributes the
 *    documents it spans.
 * N and M always share one unit, and the caller passes `currentPage` in that
 * same unit.
 *
 * The total GROWS as chapters are visited, by design. Measuring every chapter up
 * front is the tempting fix and the wrong one: it means paginating the whole
 * book on open, which is multi-second work on a real EPUB. Character-based
 * estimation is rejected too — it is not a page count, and the footer would be
 * printing a guess with page-number confidence.
 */

/** One chapter's page count, keyed by the chapter's reading-order position. */
export type ChapterPageCounts = ReadonlyMap<number, number>;

/** A page number and a total in one shared unit, both already floored at 1. */
export type BookPagePosition = {
  page: number;
  total: number;
};

/**
 * Where the reader is in the book: `page` counts from the first chapter's first
 * page, `total` sums every page of every MEASURED chapter.
 *
 * Only chapters whose count has been measured contribute. An unmeasured chapter
 * is not a zero-page chapter, so it is skipped rather than added — otherwise the
 * label would claim a total that shrinks as soon as the chapter is visited.
 *
 * The page is clamped into `[1, total]`, so a stale page index carried over from
 * a chapter measured at another zoom can never render "page 55 of 12" or
 * "page 0".
 */
export function bookPagePosition(
  chapterCounts: ChapterPageCounts,
  chapterIndex: number,
  currentPage: number,
): BookPagePosition {
  const chapters = [...chapterCounts.keys()].filter((key) => Number.isFinite(key));
  if (chapters.length === 0) return { page: 1, total: 0 };

  let total = 0;
  for (const key of chapters) total += pageCount(chapterCounts.get(key));
  if (total <= 0) return { page: 1, total: 0 };

  const at = Number.isFinite(chapterIndex) ? Math.trunc(chapterIndex) : 0;
  // Every measured chapter strictly before the one on screen is behind the
  // reader: its whole count belongs to the offset.
  let before = 0;
  for (const key of chapters) {
    if (key < at) before += pageCount(chapterCounts.get(key));
  }
  // The page within the chapter counts only when that chapter HAS a count: a
  // chapter the reader just landed on can be one frame away from its first
  // measurement, and there is no page index to add to an offset with no chapter
  // in it. The result then points at the chapter's own start, which is accurate —
  // it is the first unread page.
  const inChapter = chapterCounts.has(at) ? pageFrom(currentPage) : 0;
  const offset = before + inChapter;
  const page = offset === 0 ? 1 : Math.min(Math.max(Math.trunc(offset) || 1, 1), total);
  return { page, total };
}

/** A chapter's own page count, floored at one page. */
function pageCount(value: number | undefined): number {
  const count = Math.floor(Number(value ?? 0)) || 0;
  return Math.max(1, count);
}

/** The page within the chapter, floored at 1 for a non-finite or zero index. */
function pageFrom(value: number): number {
  const page = Math.floor(Number(value));
  return Number.isFinite(page) && page > 0 ? page : 1;
}
