/**
 * bookPagePosition — where the reader is in the WHOLE book, in one unit.
 *
 * The page field shows `<current> / <total>`, and both sides of that slash must
 * be the same kind of thing. Without this module the total was the chapter's
 * (columns in paginated mode, documents in scrolling mode) while the position
 * was the chapter's too — coherent, but it never answered "how long is this
 * book". The reader asked for the book's total instead, and the price of that
 * is that the two numbers have to be added up across chapters.
 *
 * Chapters are measured lazily: a column count only exists once a chapter has
 * been laid out, and paginating the whole book up front was rejected (measured
 * as a multi-second open). So the counts arrive one chapter at a time and the
 * total GROWS as the reader walks. That is the designed behaviour, not a bug:
 * on first open the field reads the chapters measured so far.
 *
 * One rule is absolute and lives here rather than in the view: a total of 0 is
 * never reported. "of 0" and "0 of 43" are the two ways a partial cache would
 * print nonsense, so an unmeasurable position returns `null` and the caller
 * falls back to the chapter's own numbers.
 */

/**
 * Pages per reading-order position. Index = position, value 0 (or absent) =
 * not measured yet.
 */
export type ChapterPageCounts = readonly number[];

/** A position in the book, 1-based on both sides. */
export type BookPagePosition = {
  page: number;
  total: number;
};

/** A position resolved back into a chapter and a page inside it. */
export type BookChapterPage = {
  chapter: number;
  page: number;
};

/** A count that can participate in a sum: finite and positive, else 0. */
function usableCount(value: number | undefined): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return 0;
  return Math.trunc(value);
}

/**
 * The book position for `pageInChapter` of `chapter`.
 *
 * `chapter` is a reading-order position (the space turns, the TOC and the
 * renderer all use), and `pageInChapter` is 1-based inside it.
 *
 * Returns `null` when the chapter on screen has no measured page count: the
 * numbers would then be pure invention, and the caller must fall back to the
 * chapter's totals instead of printing a zero.
 *
 * The total is the sum of every measured chapter, not just the ones up to the
 * current position. A reader who jumped to chapter 12 and then walked back
 * would otherwise watch the total shrink, and a total that moves backwards
 * while reading forwards is indistinguishable from a bug.
 */
export function bookPagePosition(
  counts: ChapterPageCounts,
  chapter: number,
  pageInChapter: number,
): BookPagePosition | null {
  const at = Number.isFinite(chapter) ? Math.trunc(chapter) : -1;
  if (at < 0) return null;
  const own = usableCount(counts[at]);
  if (own <= 0) return null;

  let total = 0;
  let before = 0;
  for (let position = 0; position < counts.length; position++) {
    const count = usableCount(counts[position]);
    if (count <= 0) continue;
    total += count;
    if (position < at) before += count;
  }
  if (total <= 0) return null;

  const local = Math.max(1, Math.trunc(pageInChapter) || 1);
  // Clamped against `own` and then against `total`: a stale page index from a
  // previous recount must never address a page this chapter does not have.
  return { page: Math.min(before + Math.min(local, own), total), total };
}

/**
 * The inverse of `bookPagePosition`: which chapter and page a book page number
 * names.
 *
 * "Go to page" has to speak the same unit as the field, so a number typed while
 * the field shows book pages must resolve to a chapter plus a page inside it —
 * not to a reading-order position, which is what the number meant before.
 *
 * Returns `null` past the measured part of the book. Chapters that have never
 * been laid out cannot be addressed, and inventing a position for them would
 * land the reader somewhere the field did not name.
 */
export function chapterPageForBookPage(
  counts: ChapterPageCounts,
  bookPage: number,
): BookChapterPage | null {
  if (!Number.isFinite(bookPage)) return null;
  const target = Math.trunc(bookPage);
  if (target < 1) return null;

  let consumed = 0;
  for (let position = 0; position < counts.length; position++) {
    const count = usableCount(counts[position]);
    if (count <= 0) continue;
    if (target <= consumed + count) return { chapter: position, page: target - consumed };
    consumed += count;
  }
  return null;
}
