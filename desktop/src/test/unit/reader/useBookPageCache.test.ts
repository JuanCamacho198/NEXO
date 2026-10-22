import { describe, expect, it } from 'vitest';
import { createBookPageCache } from '$lib/features/reader/viewer-epub/useBookPageCache.svelte';

describe('createBookPageCache', () => {
  it('keeps a chapter count across a chapter change', () => {
    const cache = createBookPageCache();
    cache.record(0, 3);
    cache.record(1, 4);
    // The whole point of a cache: chapter 1 knows what chapter 0 was worth.
    expect(cache.position(1, 2)).toEqual({ page: 5, total: 7 });
    expect(cache.measuredChapters).toBe(2);
  });

  it('reports an empty position before anything is measured', () => {
    const cache = createBookPageCache();
    expect(cache.position(0, 1)).toEqual({ page: 1, total: 0 });
  });

  it('ignores an unmeasured chapter: a 0 count is not a chapter with no pages', () => {
    const cache = createBookPageCache();
    cache.record(0, 0);
    cache.record(1, Number.NaN);
    expect(cache.measuredChapters).toBe(0);
    expect(cache.position(0, 1)).toEqual({ page: 1, total: 0 });
  });

  it('follows a chapter recounted smaller at a different zoom', () => {
    const cache = createBookPageCache();
    cache.record(0, 3);
    cache.record(1, 9);
    expect(cache.position(1, 5)).toEqual({ page: 8, total: 12 });
    cache.record(1, 4);
    expect(cache.position(1, 5)).toEqual({ page: 7, total: 7 });
  });

  it('keeps the total non-decreasing while walking forward', () => {
    const cache = createBookPageCache();
    const counts = [3, 4, 5, 2];
    let previous = 0;
    counts.forEach((count, chapter) => {
      cache.record(chapter, count);
      for (let page = 1; page <= count; page++) {
        const position = cache.position(chapter, page);
        expect(position.total).toBeGreaterThanOrEqual(previous);
        previous = position.total;
      }
    });
    expect(previous).toBe(14);
  });

  it('drops everything on reset (a different book, or a different unit)', () => {
    const cache = createBookPageCache();
    cache.record(0, 3);
    cache.record(1, 4);
    cache.reset();
    expect(cache.measuredChapters).toBe(0);
    expect(cache.position(1, 1)).toEqual({ page: 1, total: 0 });
  });

  it('floors a fractional count instead of carrying it into the total', () => {
    const cache = createBookPageCache();
    cache.record(0, 2.9);
    expect(cache.position(0, 1)).toEqual({ page: 1, total: 2 });
  });
});
