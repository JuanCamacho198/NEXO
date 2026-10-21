import { describe, expect, it } from 'vitest';
import {
  buildReadingOrder,
  readingOrderIndexFor,
} from '$lib/features/reader/viewer-epub/epubReadingOrder';

/** Outline entries from bare spine indexes, which is all the order needs. */
function tocAt(indexes: number[]): Array<{ index: number; href: string }> {
  return indexes.map((index) => ({ index, href: `OEBPS/Text/ch-${index}.xhtml` }));
}

function assertPermutation(order: number[], spineLength: number): void {
  expect(order).toHaveLength(spineLength);
  const seen = new Set(order);
  expect(seen.size, `duplicates in ${order.join(',')}`).toBe(spineLength);
  const sorted = [...order].sort((a, b) => a - b);
  expect(sorted).toEqual(Array.from({ length: spineLength }, (_, i) => i));
}

/** True when `run` appears as a contiguous, in-order subsequence of `order`. */
function hasContiguousRun(order: number[], run: number[]): boolean {
  for (let start = 0; start + run.length <= order.length; start++) {
    if (run.every((value, offset) => order[start + offset] === value)) return true;
  }
  return false;
}

describe('buildReadingOrder', () => {
  it('is the identity when the outline names every document in spine order', () => {
    const spineLength = 24;
    const order = buildReadingOrder(tocAt([...Array(spineLength).keys()]), spineLength);
    expect(order).toEqual(Array.from({ length: spineLength }, (_, i) => i));
  });

  describe('the measured shape: 43 spine documents, 21 navPoints', () => {
    // Read off the real file (2026-10-21): each chapter is a title page (named),
    // an image spread and a body (both unnamed).
    const spineLength = 43;
    const tocIndexes = [
      0, 3, 4, 5, 6, 7, 10, 13, 16, 19, 22, 25, 28, 31, 34, 37, 38, 39, 40, 41, 42,
    ];

    it('is a permutation of the whole spine', () => {
      const order = buildReadingOrder(tocAt(tocIndexes), spineLength);
      expect(order).toHaveLength(43);
      assertPermutation(order, spineLength);
    });

    it('keeps a chapter title, its image spread and its body together', () => {
      const order = buildReadingOrder(tocAt(tocIndexes), spineLength);
      // Chapter 5 of the book is spine 7 (title) + 8 (image spread) + 9 (body).
      expect(hasContiguousRun(order, [7, 8, 9])).toBe(true);
      // And the body is never read before its own title.
      expect(order.indexOf(9)).toBe(order.indexOf(8) + 1);
      expect(order.indexOf(8)).toBe(order.indexOf(7) + 1);
    });

    it('does not put the book’s back matter before the chapters it follows', () => {
      const order = buildReadingOrder(tocAt(tocIndexes), spineLength);
      // 37..42 is the outline's tail (conclusión, bibliografía, …) and 34 is the
      // last regular chapter; the tail must come after it, not before.
      expect(order.indexOf(37)).toBeGreaterThan(order.indexOf(34));
      // Spine 10 and 13 name chapters after that tail in the file, so their
      // unnamed continuations must stay with them, not jump to the end.
      expect(order.indexOf(11)).toBe(order.indexOf(10) + 1);
      expect(order.indexOf(12)).toBe(order.indexOf(11) + 1);
    });
  });

  it('handles an outline with more entries than documents (Don Quijote: 4 spine, 14 toc)', () => {
    // Several outline entries point at the same document; nothing may repeat or
    // go missing even though the outline is longer than the book's spine.
    const order = buildReadingOrder(tocAt([0, 0, 1, 1, 1, 2, 2, 3, 3, 0, 2, 1, 3, 3]), 4);
    assertPermutation(order, 4);
    expect(order).toEqual([0, 1, 2, 3]);
  });

  it('returns the whole spine ascending when the outline is empty', () => {
    expect(buildReadingOrder([], 5)).toEqual([0, 1, 2, 3, 4]);
  });

  it('contains an outline heading whose fragment differs from an earlier one', () => {
    const toc = [
      { index: 2, href: 'a.xhtml#top' },
      { index: 2, href: 'a.xhtml#middle' },
      { index: 5, href: 'b.xhtml' },
    ];
    const order = buildReadingOrder(toc, 8);
    assertPermutation(order, 8);
    // 2 is named twice and pushed once; its run stops at 5 because 5 has an
    // outline entry of its own, and the unnamed 0 and 1 are appended last.
    expect(order.slice(0, 4)).toEqual([2, 3, 4, 5]);
    expect(order.slice(4)).toEqual([6, 7, 0, 1]);
  });

  it('survives an unordered outline and stays a permutation', () => {
    const order = buildReadingOrder(tocAt([6, 1, 4]), 8);
    assertPermutation(order, 8);
    // 4's run stops at 6, which the outline names next; 0 is never named, so it
    // is appended after the entire outline.
    expect(order).toEqual([6, 7, 1, 2, 3, 4, 5, 0]);
  });

  it('survives out-of-range and garbage outline indexes without crashing', () => {
    const toc = [
      { index: -3, href: 'x.xhtml' },
      { index: 99, href: 'y.xhtml' },
      { index: Number.NaN, href: 'z.xhtml' },
      { index: 2.7, href: 'w.xhtml' },
      { index: 1, href: 'v.xhtml' },
    ];
    const order = buildReadingOrder(toc, 4);
    assertPermutation(order, 4);
    // Truncation, not rounding: 2.7 is document 2, and 1 was named after it, so
    // it stays where the outline put it.
    expect(order.indexOf(2)).toBeLessThan(order.indexOf(1));
  });

  it('returns an empty order for a book with no spine', () => {
    expect(buildReadingOrder(tocAt([0]), 0)).toEqual([]);
    expect(buildReadingOrder(tocAt([0]), -1)).toEqual([]);
  });
});

describe('readingOrderIndexFor', () => {
  it('maps a spine index to its reading position, and -1 when absent', () => {
    const order = buildReadingOrder(tocAt([0, 3]), 6);
    expect(order).toEqual([0, 1, 2, 3, 4, 5]);
    expect(readingOrderIndexFor(order, 4)).toBe(4);
    const shuffled = buildReadingOrder(tocAt([4, 0]), 6);
    expect(readingOrderIndexFor(shuffled, 4)).toBe(0);
    expect(readingOrderIndexFor(shuffled, 99)).toBe(-1);
  });
});
