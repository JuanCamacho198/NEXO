/**
 * Focused unit tests for the pure notification policy (NOTIF-01).
 *
 * The policy takes a draft + a state snapshot and returns the resolved category,
 * interruption and delivery decision. No UI, no DOM, no store. Every one of the
 * six task rules is exercised here; the tray store tests cover integration.
 */
import { describe, it, expect } from 'vitest';
import {
  notificationPolicy,
  defaultCategory,
  defaultInterruption,
  isSameDay,
  type PolicySnapshot,
} from '$lib/shared/services/notificationPolicy';
import type { NotificationSource, Severity } from '$lib/shared/types/notification';

const DAY = 24 * 60 * 60 * 1000;
// 2026-10-05T12:00:00Z — a fixed reference so day-boundary tests are stable.
const NOW = Date.UTC(2026, 9, 5, 12, 0, 0);

const EMPTY: PolicySnapshot = { dedupKeys: new Set(), nudges: [] };

function resolve(
  source: NotificationSource,
  severity: Severity,
  overrides: Partial<Parameters<typeof notificationPolicy>[0]> = {},
) {
  return notificationPolicy({
    source,
    severity,
    isWindowFocused: true,
    isReading: false,
    now: NOW,
    snapshot: EMPTY,
    ...overrides,
  });
}

describe('notificationPolicy — default mapping', () => {
  // Event → category / interruption, straight from the task doc.
  const table: Array<[NotificationSource, Severity, 'system' | 'nudge', string]> = [
    ['import', 'success', 'system', 'silent'],
    ['import', 'error', 'system', 'in-app'],
    ['sync', 'success', 'system', 'silent'],
    ['sync', 'error', 'system', 'in-app'],
    ['addons', 'success', 'system', 'silent'],
    ['addons', 'error', 'system', 'in-app'],
    ['update', 'info', 'system', 'silent'],
    ['library', 'success', 'system', 'silent'],
    ['streak', 'warning', 'nudge', 'system'],
    ['streak', 'success', 'nudge', 'system'],
    ['goal', 'warning', 'nudge', 'system'],
    ['goal', 'success', 'nudge', 'in-app'],
  ];

  it.each(table)('%s/%s → %s + %s', (source, severity, category, interruption) => {
    const result = resolve(source, severity);
    expect(result.category).toBe(category);
    expect(result.interruption).toBe(interruption);
  });

  it('derives category and interruption helpers independently', () => {
    expect(defaultCategory('streak')).toBe('nudge');
    expect(defaultCategory('goal')).toBe('nudge');
    expect(defaultCategory('import')).toBe('system');
    expect(defaultInterruption('import', 'error', 'system')).toBe('in-app');
    expect(defaultInterruption('import', 'success', 'system')).toBe('silent');
    // Unknown nudge severity still defaults off the app (system).
    expect(defaultInterruption('streak', 'info', 'nudge')).toBe('system');
  });
});

describe('rule 1 — every accepted notification is a tray entry', () => {
  it('accepts a routine silent event and routes it to the tray only', () => {
    const result = resolve('import', 'success');
    expect(result.accepted).toBe(true);
    expect(result.delivery.tray).toBe(true);
    expect(result.delivery.toast).toBe(false);
    expect(result.delivery.os).toBe(false);
  });

  it('trays an in-app event and also requests an in-app toast', () => {
    const result = resolve('sync', 'error');
    expect(result.delivery).toEqual({ tray: true, toast: true, os: false });
  });
});

describe('rule 2 — OS only for system interruption while unfocused', () => {
  // No default event is system+system today (all OS candidates are nudges), so
  // the mechanism is exercised with an explicit override.
  const systemEvent = { interruption: 'system' as const };

  it('fires OS when the window is unfocused', () => {
    const result = resolve('library', 'success', {
      ...systemEvent,
      isWindowFocused: false,
    });
    expect(result.delivery.os).toBe(true);
    expect(result.delivery.toast).toBe(true);
    expect(result.delivery.tray).toBe(true);
  });

  it('never fires OS while the window is focused', () => {
    const result = resolve('library', 'success', {
      ...systemEvent,
      isWindowFocused: true,
    });
    expect(result.delivery.os).toBe(false);
  });

  it('never fires OS for an in-app interruption even when unfocused', () => {
    const result = resolve('sync', 'error', { isWindowFocused: false });
    expect(result.delivery.os).toBe(false);
  });
});

describe('rule 3 — total silence while reading', () => {
  it('suppresses toast and OS for an in-app event, but still trays it', () => {
    const result = resolve('sync', 'error', { isReading: true, isWindowFocused: false });
    expect(result.accepted).toBe(true);
    expect(result.delivery).toEqual({ tray: true, toast: false, os: false });
  });

  it('suppresses OS for a system event regardless of severity while reading', () => {
    const result = resolve('library', 'success', {
      interruption: 'system',
      isReading: true,
      isWindowFocused: false,
    });
    expect(result.delivery.os).toBe(false);
    expect(result.delivery.toast).toBe(false);
    expect(result.delivery.tray).toBe(true);
  });
});

describe('rule 4 — dedup + nudge daily cap', () => {
  it('rejects an already-present dedupKey', () => {
    const result = resolve('sync', 'error', {
      dedupKey: 'sync:book-1',
      snapshot: { dedupKeys: new Set(['sync:book-1']), nudges: [] },
    });
    expect(result.accepted).toBe(false);
    expect(result.reason).toBe('duplicate');
    expect(result.delivery).toEqual({ tray: false, toast: false, os: false });
  });

  it('emits when the dedupKey is new', () => {
    const result = resolve('sync', 'error', {
      dedupKey: 'sync:book-1',
      snapshot: { dedupKeys: new Set(['sync:other']), nudges: [] },
    });
    expect(result.accepted).toBe(true);
  });

  it('caps a nudge at one emission per dedupKey per day', () => {
    const result = resolve('streak', 'warning', {
      dedupKey: 'streak:at-risk',
      snapshot: {
        dedupKeys: new Set(),
        nudges: [{ dedupKey: 'streak:at-risk', createdAt: NOW - 2 * 60 * 60 * 1000 }],
      },
    });
    expect(result.accepted).toBe(false);
    expect(result.reason).toBe('nudge-daily-cap');
  });

  it('allows the same nudge key again on a different day', () => {
    const result = resolve('streak', 'warning', {
      dedupKey: 'streak:at-risk',
      snapshot: {
        dedupKeys: new Set(),
        nudges: [{ dedupKey: 'streak:at-risk', createdAt: NOW - DAY }],
      },
    });
    expect(result.accepted).toBe(true);
  });

  it('does not apply the daily cap to system notifications', () => {
    const result = resolve('sync', 'error', {
      dedupKey: 'sync:book-1',
      snapshot: {
        dedupKeys: new Set(),
        nudges: [{ dedupKey: 'sync:book-1', createdAt: NOW }],
      },
    });
    expect(result.accepted).toBe(true);
  });

  it('compares timestamps by calendar day', () => {
    expect(isSameDay(NOW, NOW + 60 * 1000)).toBe(true);
    expect(isSameDay(NOW, NOW + DAY)).toBe(false);
  });
});

describe('rule 5 — per-category preferences gate sound (NOTIF-05)', () => {
  it('lets a nudge reach the OS when its category switch is on and unfocused', () => {
    const result = resolve('streak', 'warning', { isWindowFocused: false });
    expect(result.category).toBe('nudge');
    expect(result.interruption).toBe('system');
    expect(result.delivery).toEqual({ tray: true, toast: true, os: true });
  });

  it('stops nudge OS delivery when nudges are off, without losing the channel', () => {
    const nudge = resolve('streak', 'warning', {
      isWindowFocused: false,
      preferences: {
        system: true,
        nudge: false,
        quietHours: { enabled: false, start: '22:00', end: '07:00' },
      },
    });
    const system = resolve('library', 'success', {
      interruption: 'system',
      isWindowFocused: false,
      preferences: {
        system: true,
        nudge: false,
        quietHours: { enabled: false, start: '22:00', end: '07:00' },
      },
    });
    expect(nudge.accepted).toBe(true);
    expect(nudge.delivery).toEqual({ tray: true, toast: false, os: false });
    expect(system.delivery.os).toBe(true);
  });

  it('stops system toast and OS when the system category is off, tray still records', () => {
    const result = resolve('sync', 'error', {
      isWindowFocused: false,
      preferences: {
        system: false,
        nudge: true,
        quietHours: { enabled: false, start: '22:00', end: '07:00' },
      },
    });
    expect(result.accepted).toBe(true);
    expect(result.delivery).toEqual({ tray: true, toast: false, os: false });
  });

  it('restores sound when the category is re-enabled', () => {
    const off = resolve('goal', 'warning', {
      isWindowFocused: false,
      preferences: {
        system: true,
        nudge: false,
        quietHours: { enabled: false, start: '22:00', end: '07:00' },
      },
    });
    const on = resolve('goal', 'warning', { isWindowFocused: false });
    expect(off.delivery.os).toBe(false);
    expect(on.delivery.os).toBe(true);
  });
});

describe('rule 7 — quiet hours silence like reading does', () => {
  // NOW is 2026-10-05T12:00:00Z; a 11:00–13:00 local window only matches when
  // the runner shares that offset, so build the window around the clock.
  function quietAroundNow(): { start: string; end: string } {
    const at = new Date(NOW);
    const minutes = at.getHours() * 60 + at.getMinutes();
    const pad = (n: number): string => String(n).padStart(2, '0');
    const toClock = (total: number): string => {
      const wrapped = ((total % 1440) + 1440) % 1440;
      return `${pad(Math.floor(wrapped / 60))}:${pad(wrapped % 60)}`;
    };
    return { start: toClock(minutes - 30), end: toClock(minutes + 30) };
  }

  it('suppresses toast and OS but keeps the tray during quiet hours', () => {
    const { start, end } = quietAroundNow();
    const result = resolve('sync', 'error', {
      isWindowFocused: false,
      preferences: { system: true, nudge: true, quietHours: { enabled: true, start, end } },
    });
    expect(result.accepted).toBe(true);
    expect(result.delivery).toEqual({ tray: true, toast: false, os: false });
  });

  it('sounds normally outside quiet hours', () => {
    const result = resolve('sync', 'error', {
      isWindowFocused: false,
      preferences: {
        system: true,
        nudge: true,
        quietHours: { enabled: true, start: '03:00', end: '03:01' },
      },
    });
    expect(result.delivery).toEqual({ tray: true, toast: true, os: false });
  });
});

describe('rule 6 — the OS grant is global, preferences gate it', () => {
  it('reaches OS for a nudge only through its category switch', () => {
    // Same interruption, different category: each side follows its own
    // switch, so nudges are off without losing the channel for the rest.
    const systemOff = resolve('library', 'success', {
      interruption: 'system',
      isWindowFocused: false,
      preferences: {
        system: false,
        nudge: true,
        quietHours: { enabled: false, start: '22:00', end: '07:00' },
      },
    });
    const nudgeOn = resolve('streak', 'warning', {
      interruption: 'system',
      isWindowFocused: false,
    });
    expect(systemOff.delivery.os).toBe(false);
    expect(nudgeOn.delivery.os).toBe(true);
  });
});
