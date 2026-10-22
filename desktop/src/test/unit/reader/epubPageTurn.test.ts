import { describe, expect, it } from 'vitest';
import {
  chapterPageTotal,
  documentsAfterInChapter,
  turnStaysInChapter,
} from '$lib/features/reader/viewer-epub/epubPageTurn';

/** Outline positions of the measured book, in reading order. */
const ORDER = [
  0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
  27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42,
];
/** Reading-order positions the NCX names (spine indexes above, resolved). */
const NAMED_ORDERS = new Set([
  0, 3, 4, 5, 6, 7, 10, 13, 16, 19, 22, 25, 28, 31, 34, 37, 38, 39, 40, 41, 42,
]);

const isChapterStart = (position: number): boolean => NAMED_ORDERS.has(position);

describe('chapterPageTotal', () => {
  it('adds the later documents of the chapter to its columns', () => {
    // The body of "1. Rusia": dozens of columns, and it is the chapter's last
    // document, so nothing is added.
    expect(chapterPageTotal({ columnPages: 52, laterDocuments: 0 })).toBe(52);
    // The image spread: one column, then the body.
    expect(chapterPageTotal({ columnPages: 1, laterDocuments: 1 })).toBe(2);
  });

  it('never reports fewer than one page, and never subtracts', () => {
    // An unmeasured layout is not "of 0"; `derivePageCount` returns 0 on purpose.
    expect(chapterPageTotal({ columnPages: 0, laterDocuments: 0 })).toBe(1);
    expect(chapterPageTotal({ columnPages: 0, laterDocuments: 2 })).toBe(3);
    // A bad count cannot shrink the total below the columns on screen.
    expect(chapterPageTotal({ columnPages: 4, laterDocuments: -3 })).toBe(4);
  });
});

describe('documentsAfterInChapter', () => {
  it('counts the unnamed documents that follow a named title page', () => {
    // "1. Rusia" (spine 7, order 7) is followed by the image spread (8) and the
    // body (9) before "2. China" (10) names the next chapter.
    expect(documentsAfterInChapter(7, ORDER.length, isChapterStart)).toBe(2);
    // The image spread has the body after it only.
    expect(documentsAfterInChapter(8, ORDER.length, isChapterStart)).toBe(1);
    // The body is the chapter's last document: a turn past it leaves the chapter.
    expect(documentsAfterInChapter(9, ORDER.length, isChapterStart)).toBe(0);
  });

  it('is 0 for a chapter made of one document, so the common case is untouched', () => {
    // The Introducción (order 6) is followed by "1. Rusia" (7), which the outline names.
    expect(documentsAfterInChapter(6, ORDER.length, isChapterStart)).toBe(0);
  });

  it('is 0 past the end of the book', () => {
    expect(documentsAfterInChapter(ORDER.length, ORDER.length, isChapterStart)).toBe(0);
    expect(documentsAfterInChapter(-1, ORDER.length, isChapterStart)).toBe(0);
  });

  it('does not run past the end when the last chapter is split', () => {
    // A book whose final documents are unnamed: the count stops at the end.
    expect(documentsAfterInChapter(41, ORDER.length, () => false)).toBe(1);
    expect(documentsAfterInChapter(42, ORDER.length, () => false)).toBe(0);
  });
});

describe('turnStaysInChapter', () => {
  it('turns columns while there are columns left', () => {
    expect(turnStaysInChapter({ page: 1, columnPages: 3, chapterTotal: 3 }, 1)).toBe(true);
    expect(turnStaysInChapter({ page: 2, columnPages: 3, chapterTotal: 3 }, 1)).toBe(true);
  });

  it('keeps turning through the later documents of the chapter', () => {
    // The 1-column title page with two documents after it: pages 1, 2 and 3.
    const state = { page: 1, columnPages: 1, chapterTotal: 3 };
    expect(turnStaysInChapter(state, 1)).toBe(true);
    expect(turnStaysInChapter({ ...state, page: 2 }, 1)).toBe(true);
    expect(turnStaysInChapter({ ...state, page: 3 }, 1)).toBe(false);
  });

  it('a one-column chapter is still one page, and Next is disabled there', () => {
    // A genuinely one-page chapter has nothing after it: disabled, as before.
    expect(turnStaysInChapter({ page: 1, columnPages: 1, chapterTotal: 1 }, 1)).toBe(false);
  });

  it('stops turning back at the chapter first page', () => {
    expect(turnStaysInChapter({ page: 2, columnPages: 3, chapterTotal: 3 }, -1)).toBe(true);
    expect(turnStaysInChapter({ page: 1, columnPages: 3, chapterTotal: 3 }, -1)).toBe(false);
  });
});
