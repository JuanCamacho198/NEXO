/**
 * The order a reader actually reads a book in.
 *
 * An EPUB's spine is the complete, ordered list of documents the book is made
 * of, and the NCX is only an *outline* of it: it names the documents a
 * publisher bothered to title, which is usually a subset. Walking the spine
 * alone therefore reaches every document but not in the book's order — measured
 * on *Prisioneros de la geografía* (43 spine documents, 21 navPoints, each
 * chapter split into a title page, an image spread and a body), a plain spine
 * walk showed the book's conclusion after chapter 7 because that is where the
 * conclusion's file happens to sit.
 *
 * Walking the outline alone is the opposite failure: the bodies of the chapters
 * are documents the outline never names, so 57 356 characters of prose become
 * unreachable by turning pages.
 *
 * Neither list is the reading order. The order is the outline, with each named
 * document's unnamed successors trailing it, then whatever the outline never
 * reached. That is this function, and its result is the single index space the
 * reader navigates in.
 */

/** The only part of an outline entry `buildReadingOrder` reads. */
export type ReadingOrderTocEntry = { index: number; href: string };

/**
 * Spine indices (0-based) in the order a reader turns through them.
 *
 * The return value is always a permutation of `0..spineLength-1`: every spine
 * document appears exactly once, for any input the caller can produce —
 * including duplicate, unordered, and out-of-range outline indexes.
 */
export function buildReadingOrder(
  toc: ReadonlyArray<ReadingOrderTocEntry>,
  spineLength: number,
): number[] {
  if (!Number.isFinite(spineLength) || spineLength <= 0) return [];
  const total = Math.floor(spineLength);

  // Every valid outline target is "claimed". The trailing run after an entry
  // must stop at a claimed document, because that document already has a
  // position of its own in the outline and pushing it here would duplicate it.
  // A target that is duplicated across outline entries is still claimed.
  const claimed = new Set<number>();
  for (const entry of toc) {
    const target = Math.trunc(entry?.index ?? Number.NaN);
    if (Number.isFinite(target) && target >= 0 && target < total) claimed.add(target);
  }

  const order: number[] = [];
  const placed = new Set<number>();

  for (const entry of toc) {
    const target = Math.trunc(entry?.index ?? Number.NaN);
    if (!Number.isFinite(target) || target < 0 || target >= total) continue;
    // A document named twice (a chapter with two headings in one file) gets the
    // position of its first mention only.
    if (placed.has(target)) continue;
    placed.add(target);
    order.push(target);

    // The documents that follow a named one without being named themselves are
    // that named document's continuation — the image spread and the body of the
    // chapter — so they are read right here, not at the end of the book.
    for (let next = target + 1; next < total && !claimed.has(next); next++) {
      if (placed.has(next)) break;
      placed.add(next);
      order.push(next);
    }
  }

  for (let spineIndex = 0; spineIndex < total; spineIndex++) {
    if (!placed.has(spineIndex)) order.push(spineIndex);
  }

  return order;
}

/**
 * Where in `readingOrder` a spine document is read, or `-1` when the order does
 * not contain it.
 *
 * A linear scan rather than a cached map: the order is at most a few thousand
 * entries and is rebuilt whenever the book's metadata changes, so a map would
 * be a second copy of the truth that can disagree with the first.
 */
export function readingOrderIndexFor(
  readingOrder: ReadonlyArray<number>,
  spineIndex: number,
): number {
  return readingOrder.indexOf(spineIndex);
}
