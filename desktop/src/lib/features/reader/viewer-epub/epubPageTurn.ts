/**
 * epubPageTurn — what the page field counts, and when a press leaves the
 * chapter.
 *
 * Paginated mode puts two different counts in front of the reader and they must
 * not be confused:
 *
 *  - COLUMNS are the pages the reader turns through inside one document. Next,
 *    the wheel, the arrow keys and the page field all move through them.
 *  - DOCUMENTS are the spine items the chapter's own columns cannot reach. A
 *    chapter in an EPUB is often several documents — a title page the outline
 *    names, then an image spread and a body it does not — and each one is a full
 *    screen of its own. Measured on *Prisioneros de la geografía*: spine 7 is
 *    "1. Rusia" (60 characters), spine 8 two maps and no text, spine 9 the
 *    57 356-character body.
 *
 * The defect this module exists for: paginated mode answered the page field
 * from the columns ALONE, whichever document was on screen. On the 60-character
 * title page that measured one column, so the field read "1 / 1" and the Next
 * control disabled itself — and the body, three turns away in the reading order,
 * could not be reached from the title page at all. Scrolling mode had never had
 * the problem because it counts documents and nothing else.
 *
 * The rule is therefore: the field counts the columns the chapter on screen
 * really has, PLUS one page for every later document of the chapter — the ones
 * the reader reaches by turning past the last column. Nothing is added for a
 * chapter that is a single document, so the common case is unchanged.
 */

/** One chapter's worth of pagination, as the page field must report it. */
export interface ChapterPageSpan {
  /** Pages the chapter on screen has, 1 when the layout is not usable. */
  columnPages: number;
  /**
   * Documents after the one on screen that belong to the same chapter — every
   * later document up to the next one the outline names.
   */
  laterDocuments: number;
}

/**
 * The chapter's total page count.
 *
 * `columnPages` is floored at 1: an unmeasured layout is not a chapter with zero
 * pages, and a total of 0 would be printed as "of 0". `laterDocuments` is
 * floored at 0 so a bad count can never subtract pages.
 */
export function chapterPageTotal(span: ChapterPageSpan): number {
  const columns = Math.max(1, Math.floor(span.columnPages) || 1);
  const later = Math.max(0, Math.floor(span.laterDocuments) || 0);
  return columns + later;
}

/** Everything a press needs to decide whether it stays in the chapter. */
export interface PageTurnState {
  /** The page the reader is on, 1-based, in the chapter's own numbering. */
  page: number;
  /** Pages the chapter on screen has. */
  columnPages: number;
  /** The chapter's total, as `chapterPageTotal` reports it. */
  chapterTotal: number;
}

/**
 * True when a turn of `delta` stays inside the chapter, and false when it
 * leaves it.
 *
 * The boundary that matters is the END of the chapter's documents, not the end
 * of the columns: `page < columnPages` is an ordinary column turn, and once the
 * columns run out there are still `chapterTotal - columnPages` document turns
 * before the chapter itself ends. Reading stops when the page reaches
 * `chapterTotal`, so Next is disabled there and only there.
 */
export function turnStaysInChapter(state: PageTurnState, delta: 1 | -1): boolean {
  const total = Math.max(1, state.chapterTotal);
  if (delta > 0) return state.page < total;
  // A backward turn at the chapter's first page is what crosses backwards.
  return state.page > 1;
}

/**
 * How many later documents of a chapter start AT document `position`.
 *
 * `isChapterStart` answers "does the outline name this document as a chapter?"
 * for a reading-order position `[0, length)`, and returns false past the end.
 * The run stops at the next named document, which is where the next chapter
 * begins — the same rule the reading order itself uses to decide that an
 * unnamed document belongs to the entry before it.
 */
export function documentsAfterInChapter(
  position: number,
  orderLength: number,
  isChapterStart: (position: number) => boolean,
): number {
  if (!Number.isFinite(position) || position < 0 || position >= orderLength) return 0;
  let count = 0;
  for (let next = Math.floor(position) + 1; next < orderLength; next++) {
    if (isChapterStart(next)) break;
    count++;
  }
  return count;
}
