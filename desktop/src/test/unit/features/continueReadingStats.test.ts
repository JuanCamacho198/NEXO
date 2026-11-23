import { describe, expect, it } from 'vitest';
import { buildContinueStats } from '$lib/features/home/continueReadingStats';
import type { LibraryBookDto } from '$lib/shared/types';

const t = (key: string, params?: Record<string, string | number>): string =>
  params ? `${key}:${JSON.stringify(params)}` : key;

const NOW = new Date('2026-11-22T12:00:00Z');

function book(overrides: Partial<LibraryBookDto> = {}): LibraryBookDto {
  return {
    id: 'b1',
    title: 'Book',
    author: 'A',
    format: 'epub',
    currentPage: 5,
    totalPages: 43,
    progressPercentage: 25,
    coverPath: null,
    minutesRead: 30,
    updatedAt: '2026-11-20T12:00:00Z',
    createdAt: '2026-11-01T12:00:00Z',
    ...overrides,
  };
}

describe('buildContinueStats', () => {
  it('shows the chapter the reader recorded and when the book was last read', () => {
    const stats = buildContinueStats(book(), 'Sinopsis', NOW, t as never);
    expect(stats.map((s) => [s.key, s.value])).toEqual([
      ['chapter', 'Sinopsis'],
      ['lastRead', 'shelf.daysAgo:{"n":2}'],
    ]);
  });

  it('prefers the progress timestamp over the row update for last read', () => {
    const stats = buildContinueStats(
      book({ progressUpdatedAt: '2026-11-21T12:00:00Z' }),
      null,
      NOW,
      t as never,
    );
    expect(stats.find((s) => s.key === 'lastRead')?.value).toBe('shelf.yesterday');
  });

  it('leaves out rows whose inputs are missing instead of inventing them', () => {
    expect(buildContinueStats(book({ updatedAt: 'not a date' }), null, NOW, t as never)).toEqual(
      [],
    );
  });
});
