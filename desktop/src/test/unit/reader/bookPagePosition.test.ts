import { describe, expect, it } from 'vitest';
import {
  bookPagePosition,
  chapterPageForBookPage,
} from '$lib/features/reader/viewer-epub/bookPagePosition';

describe('bookPagePosition', () => {
  it('sums every measured chapter into the book total', () => {
    const counts = [12, 20, 9];
    // First chapter, first page: the position is the page itself.
    expect(bookPagePosition(counts, 0, 1)).toEqual({ page: 1, total: 41 });
    // A later chapter starts after the columns already read.
    expect(bookPagePosition(counts, 1, 1)).toEqual({ page: 13, total: 41 });
    expect(bookPagePosition(counts, 1, 5)).toEqual({ page: 17, total: 41 });
  });

  it('places the last chapter at the end of the book', () => {
    const counts = [12, 20, 9];
    expect(bookPagePosition(counts, 2, 9)).toEqual({ page: 41, total: 41 });
    // Never past the total, even if the page index is stale.
    expect(bookPagePosition(counts, 2, 40)).toEqual({ page: 41, total: 41 });
  });

  it('returns null when nothing is measured, so no zero is ever printed', () => {
    expect(bookPagePosition([], 0, 1)).toBeNull();
    // The chapter on screen is unmeasured even though others are not: reporting
    // the others' sum as "page 1 of 20" would name a position it cannot address.
    expect(bookPagePosition([0, 20], 0, 1)).toBeNull();
    expect(bookPagePosition([12], 7, 1)).toBeNull();
    expect(bookPagePosition([0, 0, 0], 2, 1)).toBeNull();
  });

  it('follows a chapter that is recounted smaller', () => {
    // A zoom or font change legitimately changes a chapter's columns; the total
    // moves with it and the position stays inside the new total.
    const before = bookPagePosition([10, 20], 1, 5);
    expect(before).toEqual({ page: 15, total: 30 });
    const after = bookPagePosition([4, 20], 1, 5);
    expect(after).toEqual({ page: 9, total: 24 });
    expect(after!.page).toBeLessThanOrEqual(after!.total);
  });

  it('never lets the total drop while walking forward', () => {
    const chapters = [12, 20, 9, 31, 7, 18];
    const counts: number[] = [];
    let previousTotal = 0;
    let previousPage = 0;
    for (let chapter = 0; chapter < chapters.length; chapter++) {
      // Only the chapters read so far have been measured.
      counts[chapter] = chapters[chapter]!;
      for (let page = 1; page <= chapters[chapter]!; page++) {
        const position = bookPagePosition(counts, chapter, page);
        expect(position).not.toBeNull();
        expect(position!.total).toBeGreaterThanOrEqual(previousTotal);
        expect(position!.page).toBeGreaterThanOrEqual(previousPage);
        expect(position!.page).toBeLessThanOrEqual(position!.total);
        previousTotal = position!.total;
        previousPage = position!.page;
      }
    }
    expect(previousTotal).toBe(97);
    expect(previousPage).toBe(97);
  });

  it('keeps the total when the reader walks backwards into a measured chapter', () => {
    // Both numbers must stay in the same unit, so the position is cumulative
    // from the book's start, not from wherever the reader happens to stand.
    const counts = [12, 20, 9];
    expect(bookPagePosition(counts, 2, 1)).toEqual({ page: 33, total: 41 });
    expect(bookPagePosition(counts, 0, 2)).toEqual({ page: 2, total: 41 });
  });

  it('ignores counts that cannot be pages', () => {
    const counts = [12, Number.NaN, -3, 9];
    // Neither a NaN nor a negative count adds a page to the total, and neither
    // shifts the position of the chapter the reader is in.
    expect(bookPagePosition(counts, 3, 1)).toEqual({ page: 13, total: 21 });
  });
});

describe('chapterPageForBookPage', () => {
  it('resolves a book page back to its chapter and the page inside it', () => {
    const counts = [12, 20, 9];
    expect(chapterPageForBookPage(counts, 1)).toEqual({ chapter: 0, page: 1 });
    expect(chapterPageForBookPage(counts, 12)).toEqual({ chapter: 0, page: 12 });
    // The first page of the next chapter is the page after the previous one's
    // last — the two sides of the slash share a unit.
    expect(chapterPageForBookPage(counts, 13)).toEqual({ chapter: 1, page: 1 });
    expect(chapterPageForBookPage(counts, 41)).toEqual({ chapter: 2, page: 9 });
  });

  it('is the inverse of bookPagePosition', () => {
    const counts = [12, 20, 9];
    for (let chapter = 0; chapter < counts.length; chapter++) {
      for (let page = 1; page <= counts[chapter]!; page++) {
        const book = bookPagePosition(counts, chapter, page)!;
        expect(chapterPageForBookPage(counts, book.page)).toEqual({ chapter, page });
      }
    }
  });

  it('refuses pages past the measured part of the book', () => {
    expect(chapterPageForBookPage([12, 20], 33)).toBeNull();
    expect(chapterPageForBookPage([], 1)).toBeNull();
    expect(chapterPageForBookPage([12], 0)).toBeNull();
    expect(chapterPageForBookPage([12], Number.NaN)).toBeNull();
  });
});
