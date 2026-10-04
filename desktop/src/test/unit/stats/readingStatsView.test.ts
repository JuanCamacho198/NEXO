import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  periodWindow,
  previousWindow,
  computeDelta,
  formatDelta,
  periodDeltaLabels,
} from '$lib/features/stats/components/readingStatsState.svelte';
import type { MessageKey } from '$lib/shared/i18n';

const deltaDictionary: Partial<Record<MessageKey, string>> = {
  'stats.noChange': 'Sin cambios',
  'stats.firstPeriod': 'Nuevo',
  'stats.deltaMonth': 'vs. mes anterior',
};

const deltaT = (key: MessageKey): string => deltaDictionary[key] ?? key;

describe('periodWindow', () => {
  const freeze = '2026-06-30T12:00:00.000Z';

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(freeze));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns a week-long window for "week"', () => {
    const result = periodWindow('week');
    const from = new Date(result.from);
    const to = new Date(result.to);
    const diffMs = to.getTime() - from.getTime();
    // ~7 days in ms
    expect(diffMs).toBeGreaterThanOrEqual(6 * 24 * 60 * 60 * 1000);
    expect(diffMs).toBeLessThanOrEqual(7 * 24 * 60 * 60 * 1000 + 100);
  });

  it('returns a month-long window for "month"', () => {
    const result = periodWindow('month');
    const from = new Date(result.from);
    const to = new Date(result.to);
    const diffMs = to.getTime() - from.getTime();
    expect(diffMs).toBeGreaterThanOrEqual(29 * 24 * 60 * 60 * 1000);
    expect(diffMs).toBeLessThanOrEqual(30 * 24 * 60 * 60 * 1000 + 100);
  });

  it('returns a year-long window for "year"', () => {
    const result = periodWindow('year');
    const from = new Date(result.from);
    const to = new Date(result.to);
    const diffMs = to.getTime() - from.getTime();
    expect(diffMs).toBeGreaterThanOrEqual(364 * 24 * 60 * 60 * 1000);
    expect(diffMs).toBeLessThanOrEqual(365 * 24 * 60 * 60 * 1000 + 100);
  });

  it('returns epoch start for "all"', () => {
    const result = periodWindow('all');
    expect(result.from).toBe('1970-01-01T00:00:00Z');
    expect(result.to).toBe(freeze);
  });

  it('to is always now (frozen)', () => {
    const result = periodWindow('month');
    expect(result.to).toBe(freeze);
  });
});

describe('previousWindow', () => {
  const freeze = '2026-06-30T12:00:00.000Z';

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(freeze));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns a non-overlapping window of equal length for "week"', () => {
    const current = periodWindow('week');
    const previous = previousWindow('week');

    // Previous window ends where current begins (no overlap, exact boundary)
    expect(new Date(previous.to).getTime()).toBeLessThanOrEqual(
      new Date(current.from).getTime() + 100,
    );

    // Equal length
    const currentLen = new Date(current.to).getTime() - new Date(current.from).getTime();
    const prevLen = new Date(previous.to).getTime() - new Date(previous.from).getTime();
    expect(Math.abs(currentLen - prevLen)).toBeLessThan(100);
  });

  it('returns a non-overlapping window of equal length for "month"', () => {
    const current = periodWindow('month');
    const previous = previousWindow('month');

    expect(new Date(previous.to).getTime()).toBeLessThanOrEqual(
      new Date(current.from).getTime() + 100,
    );

    const currentLen = new Date(current.to).getTime() - new Date(current.from).getTime();
    const prevLen = new Date(previous.to).getTime() - new Date(previous.from).getTime();
    expect(Math.abs(currentLen - prevLen)).toBeLessThan(100);
  });

  it('returns a non-overlapping window of equal length for "year"', () => {
    const current = periodWindow('year');
    const previous = previousWindow('year');

    expect(new Date(previous.to).getTime()).toBeLessThanOrEqual(
      new Date(current.from).getTime() + 100,
    );

    const currentLen = new Date(current.to).getTime() - new Date(current.from).getTime();
    const prevLen = new Date(previous.to).getTime() - new Date(previous.from).getTime();
    expect(Math.abs(currentLen - prevLen)).toBeLessThan(100);
  });

  it('returns epoch start for "all"', () => {
    const result = previousWindow('all');
    expect(result.from).toBe('1970-01-01T00:00:00Z');
    expect(result.to).toBe(freeze);
  });
});

describe('computeDelta', () => {
  it('returns null when previous is 0', () => {
    expect(computeDelta(100, 0)).toBeNull();
  });

  it('returns null when previous is negative', () => {
    expect(computeDelta(100, -10)).toBeNull();
  });

  it('returns 0 when current equals previous', () => {
    expect(computeDelta(50, 50)).toBe(0);
  });

  it('returns positive percent when current > previous', () => {
    const result = computeDelta(150, 100);
    expect(result).toBe(50);
  });

  it('returns negative percent when current < previous', () => {
    const result = computeDelta(80, 100);
    expect(result).toBe(-20);
  });

  it('rounds to one decimal place', () => {
    const result = computeDelta(33, 100);
    // (33 - 100) / 100 * 100 = -67.0 → -67
    expect(result).toBe(-67);
  });

  it('handles fractional deltas', () => {
    const result = computeDelta(125, 200);
    // (125 - 200) / 200 * 100 = -37.5
    expect(result).toBe(-37.5);
  });
});

describe('formatDelta', () => {
  it('is neutral and unsinged when the change is exactly zero', () => {
    const result = formatDelta(50, 50, 'month', deltaT);
    expect(result.tone).toBe('neutral');
    expect(result.text.startsWith('+')).toBe(false);
    expect(result.text.startsWith('-')).toBe(false);
    expect(result.text).toContain('Sin cambios');
  });

  it('reports a first period instead of inventing a comparison when there is no baseline', () => {
    const result = formatDelta(120, 0, 'month', deltaT);
    expect(result.tone).toBe('none');
    expect(result.text).toBe('Nuevo');
    expect(result.text).not.toContain('%');
  });

  it('emits nothing when neither the current nor the previous period has activity', () => {
    expect(formatDelta(0, 0, 'month', deltaT)).toEqual({ text: '', tone: 'none' });
    expect(formatDelta(undefined, undefined, 'month', deltaT)).toEqual({ text: '', tone: 'none' });
  });

  it('shows a signed positive delta with a growth tone', () => {
    const result = formatDelta(150, 100, 'month', deltaT);
    expect(result.tone).toBe('positive');
    expect(result.text).toBe('+50% vs. mes anterior');
  });

  it('shows a signed negative delta with a decline tone', () => {
    const result = formatDelta(80, 100, 'month', deltaT);
    expect(result.tone).toBe('negative');
    expect(result.text).toBe('-20% vs. mes anterior');
  });

  it('does not hide a drop to zero behind a dash', () => {
    const result = formatDelta(0, 100, 'month', deltaT);
    expect(result.tone).toBe('negative');
    expect(result.text).toBe('-100% vs. mes anterior');
  });
});

describe('periodDeltaLabels', () => {
  it('has all four period keys', () => {
    expect(periodDeltaLabels).toMatchObject({
      week: expect.any(String),
      month: expect.any(String),
      year: expect.any(String),
      all: expect.any(String),
    });
  });

  it('contains Spanish labels', () => {
    expect(periodDeltaLabels.week).toContain('semana');
    expect(periodDeltaLabels.month).toContain('mes');
    expect(periodDeltaLabels.year).toContain('año');
    expect(periodDeltaLabels.all).toContain('periodo');
  });
});
