/**
 * Page numbers for a whole CHAPTER in paginated mode.
 *
 * A chapter is often several spine documents — a title page, a map plate, the
 * body — and only the one on screen has columns. Counting that document alone
 * made the field restart inside one chapter (measured on *Prisioneros de la
 * geografía*, chapter 4: 1/2, then 1/3, then 1/51) and the "pages left" line
 * count each later document as a single page. Here the chapter is the run of
 * reading-order documents from the one the outline names to the next one it
 * names, and its pages are the sum of every document's columns.
 *
 * Documents not on screen are measured off-screen by the viewer; until a count
 * arrives a document is taken as one page and the result is marked inexact, so
 * the caller can say "at least" instead of printing a number it cannot stand
 * behind.
 */

/** Reading-order positions `start..end`, inclusive. */
export type ChapterSpan = { start: number; end: number };

/** A page count for a position, or null when it has not been measured. */
export type PageCountLookup = (position: number) => number | null;

export type ChapterPagePosition = {
  /** 1-based page in the chapter. */
  page: number;
  total: number;
  /** False while any document of the chapter is unmeasured (counted as 1). */
  exact: boolean;
};

/**
 * The chapter containing `position`. `isChapterStart` says whether the outline
 * names a reading-order position; position 0 always starts a run, so a book
 * whose outline begins late still has a first chapter.
 */
export function chapterSpanAt(
  position: number,
  orderLength: number,
  isChapterStart: (position: number) => boolean,
): ChapterSpan | null {
  if (!Number.isFinite(position) || position < 0 || position >= orderLength) return null;
  const at = Math.floor(position);
  let start = at;
  while (start > 0 && !isChapterStart(start)) start--;
  let end = at;
  while (end + 1 < orderLength && !isChapterStart(end + 1)) end++;
  return { start, end };
}

function countOf(position: number, lookup: PageCountLookup): { pages: number; known: boolean } {
  const value = lookup(position);
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return { pages: Math.floor(value), known: true };
  }
  return { pages: 1, known: false };
}

/**
 * Where the reader is in the chapter: the pages of every earlier document of
 * the chapter, plus the page inside the one on screen.
 */
export function chapterPagePosition(
  span: ChapterSpan,
  onScreen: number,
  pageOnScreen: number,
  lookup: PageCountLookup,
): ChapterPagePosition {
  let before = 0;
  let total = 0;
  let exact = true;
  for (let position = span.start; position <= span.end; position++) {
    const { pages, known } = countOf(position, lookup);
    if (!known) exact = false;
    total += pages;
    if (position < onScreen) before += pages;
  }
  const own = countOf(onScreen, lookup).pages;
  const local = Math.min(Math.max(1, Math.floor(pageOnScreen) || 1), own);
  return { page: Math.min(before + local, total), total, exact };
}

/**
 * The document and the page inside it that chapter page `page` names, or null
 * outside the chapter. The inverse of `chapterPagePosition`, with the same
 * one-page stand-in for an unmeasured document.
 */
export function chapterPageTarget(
  span: ChapterSpan,
  page: number,
  lookup: PageCountLookup,
): { position: number; page: number } | null {
  if (!Number.isFinite(page) || page < 1) return null;
  const target = Math.floor(page);
  let consumed = 0;
  for (let position = span.start; position <= span.end; position++) {
    const { pages } = countOf(position, lookup);
    if (target <= consumed + pages) return { position, page: target - consumed };
    consumed += pages;
  }
  return null;
}
