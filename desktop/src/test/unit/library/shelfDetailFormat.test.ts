import { describe, it, expect } from 'vitest';
import {
  getCurrentStatus,
  formatMinutes,
  getCollectionNames,
  formatRelativeDate,
  getLanguageName,
  formatPublicationDate,
  LANGUAGE_KEY_MAP,
} from '$lib/features/library/shelfDetailFormat';
import type { MessageKey } from '$lib/shared/i18n';
import type { CollectionDto, LibraryBookDto } from '$lib/shared/types';

const t = (key: MessageKey, params?: Record<string, string | number>): string => {
  if (params) return `${key} ${JSON.stringify(params)}`;
  return key;
};

describe('shelfDetailFormat pure helpers', () => {
  it('LANGUAGE_KEY_MAP has 17 entries', () => {
    expect(Object.keys(LANGUAGE_KEY_MAP).length).toBe(17);
    expect(LANGUAGE_KEY_MAP.es).toBe('shelf.langSpanish');
    expect(LANGUAGE_KEY_MAP.vi).toBe('shelf.langVietnamese');
  });

  it('getCurrentStatus determinism', () => {
    expect(getCurrentStatus(null)).toBe('reading');
    expect(getCurrentStatus({ readingStatus: 'completed' } as LibraryBookDto)).toBe('completed');
    expect(getCurrentStatus({ readingStatus: 'to_read' } as LibraryBookDto)).toBe('to_read');
    expect(getCurrentStatus({ readingStatus: 'reading' } as LibraryBookDto)).toBe('reading');
    expect(getCurrentStatus({ readingStatus: null } as unknown as LibraryBookDto)).toBe('reading');
  });

  it('formatMinutes branches <1min/h+m/h/m', () => {
    expect(formatMinutes(0, t)).toBe('shelf.lessThanMinute');
    expect(formatMinutes(0.5, t)).toBe('shelf.lessThanMinute');
    expect(formatMinutes(5, t)).toContain('shelf.formatMins');
    expect(formatMinutes(60, t)).toContain('shelf.formatHours');
    expect(formatMinutes(90, t)).toContain('shelf.formatHoursMinutes');
    expect(formatMinutes(125, t)).toBe('shelf.formatHoursMinutes {"h":2,"m":5}');
  });

  it('getCollectionNames filters correctly', () => {
    const cols: CollectionDto[] = [
      { id: 1, name: 'Fav', color: null, isSystem: true, createdAt: '' },
      { id: 2, name: 'SciFi', color: null, isSystem: false, createdAt: '' },
    ];
    expect(getCollectionNames(undefined, cols)).toEqual([]);
    expect(getCollectionNames([], cols)).toEqual([]);
    expect(getCollectionNames([1, 2, 99], cols)).toEqual(['Fav', 'SciFi']);
    expect(getCollectionNames([99], cols)).toEqual([]);
  });

  it('formatRelativeDate 8 branches with fixed now', () => {
    const now = new Date('2026-08-26T12:00:00Z');
    expect(formatRelativeDate(now.toISOString(), now, t)).toBe('shelf.now');
    expect(formatRelativeDate(new Date(now.getTime() - 5 * 60 * 1000).toISOString(), now, t)).toBe(
      'shelf.minutesAgo {"n":5}',
    );
    expect(
      formatRelativeDate(new Date(now.getTime() - 2 * 60 * 60 * 1000).toISOString(), now, t),
    ).toBe('shelf.hoursAgo {"n":2}');
    expect(
      formatRelativeDate(new Date(now.getTime() - 24 * 60 * 60 * 1000).toISOString(), now, t),
    ).toBe('shelf.yesterday');
    expect(
      formatRelativeDate(new Date(now.getTime() - 3 * 24 * 60 * 60 * 1000).toISOString(), now, t),
    ).toBe('shelf.daysAgo {"n":3}');
    expect(
      formatRelativeDate(new Date(now.getTime() - 14 * 24 * 60 * 60 * 1000).toISOString(), now, t),
    ).toBe('shelf.weeksAgo {"n":2}');
    expect(
      formatRelativeDate(new Date(now.getTime() - 60 * 24 * 60 * 60 * 1000).toISOString(), now, t),
    ).toBe('shelf.monthsAgo {"n":2}');
    expect(
      formatRelativeDate(new Date(now.getTime() - 400 * 24 * 60 * 60 * 1000).toISOString(), now, t),
    ).toBe('shelf.yearAgo');
    // One of a unit is singular: "Hace 1 mes", never "Hace 1 meses".
    expect(
      formatRelativeDate(new Date(now.getTime() - 35 * 24 * 60 * 60 * 1000).toISOString(), now, t),
    ).toBe('shelf.monthAgo');
    expect(
      formatRelativeDate(new Date(now.getTime() - 800 * 24 * 60 * 60 * 1000).toISOString(), now, t),
    ).toBe('shelf.yearsAgo {"n":2}');
  });

  it('getLanguageName fallback toUpperCase and map', () => {
    expect(getLanguageName('es', t)).toBe('shelf.langSpanish');
    expect(getLanguageName('ES', t)).toBe('shelf.langSpanish');
    expect(getLanguageName('xx', t)).toBe('XX');
    expect(getLanguageName('zz', t)).toBe('ZZ');
  });

  it('formatPublicationDate es-ES month short and fallback', () => {
    expect(formatPublicationDate('')).toBe('');
    expect(formatPublicationDate('not-a-date')).toBe('not-a-date');
    const out = formatPublicationDate('2024-03-15T00:00:00Z');
    // es-ES short month contains mar or numeric year
    expect(out).toContain('2024');
    // should not be raw iso when valid
    expect(out).not.toBe('2024-03-15T00:00:00Z');
  });

  it('has 0 $state imports', async () => {
    const src = await import('node:fs').then((fs) =>
      fs.readFileSync('src/lib/features/library/shelfDetailFormat.ts', 'utf8'),
    );
    expect(src).not.toContain('$state');
    expect(src).not.toContain('$derived');
    expect(src).not.toContain('$effect');
  });
});
