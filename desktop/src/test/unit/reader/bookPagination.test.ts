import { describe, expect, it } from 'vitest';
import {
  bookPagePosition,
  type ChapterPageCounts,
} from '$lib/features/reader/viewer-epub/bookPagination';

const counts = (entries: Array<[number, number]>): ChapterPageCounts => new Map(entries);

describe('bookPagePosition', () => {
  it('sums every measured chapter into the total', () => {
    expect(
      bookPagePosition(
        counts([
          [0, 3],
          [1, 4],
          [2, 5],
        ]),
        0,
        1,
      ),
    ).toEqual({ page: 1, total: 12 });
  });

  it('reports the first page of the first chapter', () => {
    expect(
      bookPagePosition(
        counts([
          [0, 3],
          [1, 4],
        ]),
        0,
        1,
      ),
    ).toEqual({ page: 1, total: 7 });
  });

  it('offsets by every earlier chapter and counts the page within the current one', () => {
    // Chapters 0 and 1 hold 3 + 4 pages, so the third page of chapter 2 is the
    // tenth page of the book (3 + 4 + 3).
    expect(
      bookPagePosition(
        counts([
          [0, 3],
          [1, 4],
          [2, 5],
        ]),
        2,
        3,
      ),
    ).toEqual({
      page: 10,
      total: 12,
    });
  });

  it('reports the last page of the last measured chapter as the total', () => {
    expect(
      bookPagePosition(
        counts([
          [0, 3],
          [1, 4],
          [2, 5],
        ]),
        2,
        5,
      ),
    ).toEqual({
      page: 12,
      total: 12,
    });
  });

  it('returns an empty position for an empty cache rather than dividing by zero', () => {
    expect(bookPagePosition(counts([]), 0, 1)).toEqual({ page: 1, total: 0 });
  });

  it('ignores unmeasured chapters: the total never claims pages nobody counted', () => {
    // Chapter 3 is ahead and unvisited, so it contributes nothing — and the
    // total is the sum of what was actually measured, not a guess. The position
    // is the first page after chapter 0, which is where the reader starts.
    expect(bookPagePosition(counts([[0, 3]]), 3, 1)).toEqual({ page: 3, total: 3 });
  });

  it('does not add the page index of a chapter that has no count yet', () => {
    // Landing on chapter 2 before it is measured must not answer "page 7 of 7"
    // by adding a page index to an offset with no chapter in it.
    expect(
      bookPagePosition(
        counts([
          [0, 3],
          [1, 4],
        ]),
        2,
        4,
      ),
    ).toEqual({ page: 7, total: 7 });
  });

  it('follows a chapter recounted smaller after a zoom change', () => {
    const wide = bookPagePosition(
      counts([
        [0, 3],
        [1, 9],
      ]),
      1,
      9,
    );
    const narrow = bookPagePosition(
      counts([
        [0, 3],
        [1, 4],
      ]),
      1,
      4,
    );
    expect(wide).toEqual({ page: 12, total: 12 });
    expect(narrow).toEqual({ page: 7, total: 7 });
    // The page index carried over from the wider layout is clamped, never
    // printed as "page 12 of 7".
    expect(
      bookPagePosition(
        counts([
          [0, 3],
          [1, 4],
        ]),
        1,
        9,
      ),
    ).toEqual({ page: 7, total: 7 });
  });

  it('never decreases the total while walking forward through measured chapters', () => {
    const cache = counts([
      [0, 3],
      [1, 4],
      [2, 5],
      [3, 2],
    ]);
    let previousTotal = 0;
    for (let chapter = 0; chapter < 4; chapter++) {
      for (let page = 1; page <= cache.get(chapter)!; page++) {
        const position = bookPagePosition(cache, chapter, page);
        expect(position.total).toBeGreaterThanOrEqual(previousTotal);
        expect(position.page).toBeLessThanOrEqual(position.total);
        expect(position.page).toBeGreaterThanOrEqual(1);
        previousTotal = position.total;
      }
    }
    expect(previousTotal).toBe(14);
  });

  it('never decreases the page while walking forward through measured chapters', () => {
    const cache = counts([
      [0, 3],
      [1, 4],
      [2, 5],
    ]);
    let previousPage = 0;
    for (let chapter = 0; chapter < 3; chapter++) {
      for (let page = 1; page <= cache.get(chapter)!; page++) {
        const position = bookPagePosition(cache, chapter, page);
        expect(position.page).toBeGreaterThan(previousPage);
        previousPage = position.page;
      }
    }
  });

  it('floors a nonsense chapter count at one page instead of adding zero', () => {
    expect(
      bookPagePosition(
        counts([
          [0, 0],
          [1, 4],
        ]),
        1,
        1,
      ),
    ).toEqual({ page: 2, total: 5 });
  });

  it('floors a non-finite page index at the chapter start', () => {
    expect(
      bookPagePosition(
        counts([
          [0, 3],
          [1, 4],
        ]),
        1,
        Number.NaN,
      ),
    ).toEqual({ page: 4, total: 7 });
  });
});
