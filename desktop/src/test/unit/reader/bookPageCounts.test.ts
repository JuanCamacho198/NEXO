import { beforeEach, describe, expect, it } from 'vitest';
import { bookPageCounts } from '$lib/features/reader/viewer-epub/bookPageCounts.svelte';

describe('bookPageCounts', () => {
  beforeEach(() => {
    bookPageCounts.clearAll();
  });

  it('accumulates a chapter at a time, so a chapter change keeps the others', () => {
    bookPageCounts.record('book-1', 0, 12);
    bookPageCounts.record('book-1', 1, 20);
    expect(bookPageCounts.countsFor('book-1')).toEqual([12, 20]);
  });

  it('survives a reopen — the cache is module scope, not viewer scope', () => {
    bookPageCounts.record('book-1', 4, 25);
    // A second viewer for the same book reads the same numbers.
    expect(bookPageCounts.countsFor('book-1')[4]).toBe(25);
  });

  it('resets on a book change: another book sees nothing', () => {
    bookPageCounts.record('book-1', 0, 12);
    expect(bookPageCounts.countsFor('book-2')).toEqual([]);
  });

  it('resets on a mode change: the clear is what stops columns showing in scroll mode', () => {
    bookPageCounts.record('book-1', 0, 12);
    bookPageCounts.record('book-1', 1, 20);
    // Seen from the field: `/ 32` is about to be printed over a document count
    // unless the mode flip drops it.
    bookPageCounts.clear('book-1');
    expect(bookPageCounts.countsFor('book-1')).toEqual([]);
  });

  it('replaces a recounted chapter instead of adding to it', () => {
    bookPageCounts.record('book-1', 0, 12);
    bookPageCounts.record('book-1', 0, 9);
    expect(bookPageCounts.countsFor('book-1')).toEqual([9]);
  });

  it('ignores values that cannot be a page count', () => {
    bookPageCounts.record('', 0, 12);
    bookPageCounts.record('book-1', -1, 12);
    bookPageCounts.record('book-1', 0, 0);
    bookPageCounts.record('book-1', 0, -4);
    bookPageCounts.record('book-1', 0, Number.NaN);
    expect(bookPageCounts.countsFor('book-1')).toEqual([]);
    expect(bookPageCounts.countsFor('')).toEqual([]);
  });

  it('keeps measuring an untouched position at 0 so a gap never reads as a page', () => {
    bookPageCounts.record('book-1', 2, 9);
    expect(bookPageCounts.countsFor('book-1')).toEqual([0, 0, 9]);
  });
});
