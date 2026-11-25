import { describe, expect, it } from 'vitest';
import {
  READING_IDLE_GRACE_MS,
  createReadingSessionTracker,
} from '$lib/shared/stores/readingSessionTracker';

const T0 = Date.parse('2026-11-25T10:00:00.000Z');

function clock(): { now: () => number; advance: (ms: number) => void } {
  let at = T0;
  return { now: () => at, advance: (ms) => (at += ms) };
}

describe('readingSessionTracker', () => {
  it('turns one reading visit into one session, however many pages were turned', () => {
    const c = clock();
    const tracker = createReadingSessionTracker(c.now);
    expect(tracker.start('book', 10)).toBeNull();
    for (let i = 1; i <= 20; i += 1) {
      c.advance(60_000);
      expect(tracker.activity('book', 10 + i)).toBeNull();
    }
    const session = tracker.end();
    expect(session).toEqual({
      bookId: 'book',
      startedAt: '2026-11-25T10:00:00.000Z',
      endedAt: '2026-11-25T10:20:00.000Z',
      durationSeconds: 1200,
      startPercentage: 10,
      endPercentage: 30,
    });
    expect(tracker.end()).toBeNull();
    expect(tracker.activeBookId).toBeNull();
  });

  it('does not count a window left open: the session stops at the last turn plus the grace', () => {
    const c = clock();
    const tracker = createReadingSessionTracker(c.now);
    tracker.start('book', 0);
    c.advance(2 * 60_000);
    tracker.activity('book', 5);
    c.advance(3 * 60 * 60_000);
    const session = tracker.end();
    expect(session?.durationSeconds).toBe((2 * 60_000 + READING_IDLE_GRACE_MS) / 1000);
  });

  it('splits at an idle gap: the turn after it closes the old session and opens a new one', () => {
    const c = clock();
    const tracker = createReadingSessionTracker(c.now);
    tracker.start('book', 0);
    c.advance(60_000);
    tracker.activity('book', 2);
    c.advance(READING_IDLE_GRACE_MS + 1);
    const closed = tracker.activity('book', 3);
    expect(closed?.durationSeconds).toBe((60_000 + READING_IDLE_GRACE_MS) / 1000);
    expect(closed?.endPercentage).toBe(2);
    c.advance(60_000);
    const next = tracker.end();
    expect(next?.startPercentage).toBe(3);
    expect(next?.durationSeconds).toBe(60);
  });

  it('closes the previous book when another one is opened', () => {
    const c = clock();
    const tracker = createReadingSessionTracker(c.now);
    tracker.start('a', 0);
    c.advance(90_000);
    const closed = tracker.start('b', 50);
    expect(closed?.bookId).toBe('a');
    expect(closed?.durationSeconds).toBe(90);
    expect(tracker.activeBookId).toBe('b');
  });

  it('ignores position changes for a book it is not tracking', () => {
    const c = clock();
    const tracker = createReadingSessionTracker(c.now);
    expect(tracker.activity('book', 10)).toBeNull();
    tracker.start('a', 0);
    c.advance(60_000);
    expect(tracker.activity('other', 99)).toBeNull();
    expect(tracker.end()?.endPercentage).toBe(0);
  });

  it('clamps percentages the validator would reject', () => {
    const c = clock();
    const tracker = createReadingSessionTracker(c.now);
    tracker.start('a', Number.NaN);
    c.advance(60_000);
    tracker.activity('a', 140);
    const session = tracker.end();
    expect(session?.startPercentage).toBe(0);
    expect(session?.endPercentage).toBe(100);
  });
});
