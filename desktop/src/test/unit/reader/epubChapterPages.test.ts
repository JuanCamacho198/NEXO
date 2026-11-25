import { describe, expect, it } from 'vitest';
import {
  chapterPagePosition,
  chapterPageTarget,
  chapterSpanAt,
} from '$lib/features/reader/viewer-epub/epubChapterPages';

// Chapter 4 of the measured book: a 2-page title (position 10), a 3-page map
// plate (11) and the 51-page body (12); chapter 5 starts at 13.
const named = new Set([0, 4, 10, 13]);
const isStart = (p: number): boolean => named.has(p);
const counts: Record<number, number> = { 10: 2, 11: 3, 12: 51 };
const all = (p: number): number | null => counts[p] ?? null;

describe('chapterSpanAt', () => {
  it('runs from the named document to the one before the next named one', () => {
    expect(chapterSpanAt(10, 20, isStart)).toEqual({ start: 10, end: 12 });
    expect(chapterSpanAt(12, 20, isStart)).toEqual({ start: 10, end: 12 });
    expect(chapterSpanAt(13, 20, isStart)).toEqual({ start: 13, end: 19 });
  });

  it('starts the first run at 0 even when the outline names it late', () => {
    expect(chapterSpanAt(2, 20, (p) => p === 5)).toEqual({ start: 0, end: 4 });
  });

  it('refuses a position outside the book', () => {
    expect(chapterSpanAt(-1, 20, isStart)).toBeNull();
    expect(chapterSpanAt(20, 20, isStart)).toBeNull();
  });
});

describe('chapterPagePosition', () => {
  const span = { start: 10, end: 12 };

  it('counts the whole chapter continuously across its documents', () => {
    expect(chapterPagePosition(span, 10, 1, all)).toEqual({ page: 1, total: 56, exact: true });
    expect(chapterPagePosition(span, 11, 3, all)).toEqual({ page: 5, total: 56, exact: true });
    expect(chapterPagePosition(span, 12, 1, all)).toEqual({ page: 6, total: 56, exact: true });
    expect(chapterPagePosition(span, 12, 51, all)).toEqual({ page: 56, total: 56, exact: true });
  });

  it('is inexact while a document is unmeasured, counting it as one page', () => {
    const partial = (p: number): number | null => (p === 12 ? null : all(p));
    expect(chapterPagePosition(span, 10, 2, partial)).toEqual({
      page: 2,
      total: 6,
      exact: false,
    });
  });
});

describe('chapterPageTarget', () => {
  const span = { start: 10, end: 12 };

  it('resolves a chapter page to its document and page', () => {
    expect(chapterPageTarget(span, 1, all)).toEqual({ position: 10, page: 1 });
    expect(chapterPageTarget(span, 4, all)).toEqual({ position: 11, page: 2 });
    expect(chapterPageTarget(span, 6, all)).toEqual({ position: 12, page: 1 });
    expect(chapterPageTarget(span, 56, all)).toEqual({ position: 12, page: 51 });
  });

  it('refuses a page outside the chapter', () => {
    expect(chapterPageTarget(span, 0, all)).toBeNull();
    expect(chapterPageTarget(span, 57, all)).toBeNull();
  });
});
