/**
 * epubReachablePages — how the reader numbers a book's spine.
 *
 * Two shapes of book look the same from the reader's side:
 *
 *   spine == toc   every document has an outline entry (almost every book in a
 *                  normal library). With `spine === toc`, the TOC index and the
 *                  spine index are the same number for every page, so nothing
 *                  changes for these books.
 *
 *   spine  > toc   the publisher shipped a partial NCX. *Prisioneros de la
 *                  geografía* is the measured case: 21 navPoints over 43 spine
 *                  documents. Each chapter is three documents — a title page
 *                  (`1_Rusia_0005_0000.htm`, 60 chars), an image-only page
 *                  (`0005_0001.htm`, 0 chars), and the body (`0005_0002.htm`,
 *                  57 356 chars). The NCX names only the title page, so the
 *                  reader never reaches the body.
 *
 * THE NUMBERING RULE: a spine document is a page. All of them, including one
 * that renders an image and no prose, and including the cover that measures
 * zero characters of text. That is what a printed book does — a blank page is
 * still page 84 — and it is the only rule that keeps three surfaces agreeing:
 * the page field, the footer percentage, and the reader's own sense of how far
 * they are. It also makes "N of M" equal the spine length, and the footer is
 * already computed from the spine.
 *
 * An earlier revision counted only documents containing text. Measured against
 * the real book it gave M = 32 against a footer that says 43, and it skipped
 * the cover and eleven image spreads — a numbering the reader cannot reconcile
 * with the page they are looking at. Rejected by the reader, correctly.
 *
 * So this module answers one question, in one place: which numbered page a
 * spine index is, and which spine index a page number is. Nothing here decides
 * whether a page is "worth counting"; it is reachable, so it counts.
 */

/** A numbered page is 1-based; 0 is the "not a page" signal. */
export const NOT_A_PAGE = 0;

/**
 * Numbered page for a 0-based spine index.
 *
 * With every spine document counted, this is simply `spineIndex + 1`. It exists
 * as a named function so the rule has one definition and a test that pins it:
 * the bug this fixes was three different surfaces each doing the arithmetic
 * slightly differently.
 */
export function pageNumberForSpine(spineIndex: number, spineLength: number): number {
  if (!Number.isFinite(spineIndex) || spineIndex < 0) return NOT_A_PAGE;
  if (Number.isFinite(spineLength) && spineLength > 0 && spineIndex >= spineLength) {
    return NOT_A_PAGE;
  }
  return Math.floor(spineIndex) + 1;
}

/**
 * Total pages in "page N of M": the reachable spine, which is the same number
 * the footer percentages. Zero spine (metadata still loading) is 0, not 1.
 */
export function countPageTotal(spineLength: number): number {
  if (!Number.isFinite(spineLength) || spineLength <= 0) return 0;
  return Math.floor(spineLength);
}

/**
 * Spine index that owns a numbered page, or -1 when the page is out of reach.
 * The page field's "go to page" resolves through this, so typing "9" lands on
 * the ninth document — the body of chapter 1 — and not on a TOC entry that
 * happens to sit at the same number.
 */
export function spineForPageNumber(page: number, spineLength: number): number {
  if (!Number.isFinite(page) || page < 1) return -1;
  const total = countPageTotal(spineLength);
  if (total === 0 || page > total) return -1;
  return Math.floor(page) - 1;
}

/**
 * True when the book's outline covers every spine document.
 *
 * Used to decide whether the TOC can still answer "which page is this": when the
 * two counts match, TOC index and spine index agree and the existing mapping is
 * correct. When the spine is longer, the TOC is a partial outline and only the
 * spine can answer.
 */
export function tocCoversSpine(tocLength: number, spineLength: number): boolean {
  if (!Number.isFinite(spineLength) || spineLength <= 0) return false;
  if (!Number.isFinite(tocLength) || tocLength <= 0) return false;
  return tocLength >= spineLength;
}
