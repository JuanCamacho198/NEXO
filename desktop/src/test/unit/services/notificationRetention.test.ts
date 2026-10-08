/**
 * Pure retention policy for the notification history (NOTIF-02). The bounds
 * must match `src-tauri/src/repository/notifications.rs`; this test pins them.
 */
import { describe, it, expect } from 'vitest';
import {
  applyNotificationRetention,
  NOTIFICATION_RETENTION_MAX_AGE_MS,
  NOTIFICATION_RETENTION_MAX_COUNT,
} from '$lib/shared/services/notificationRetention';
import type { Notification } from '$lib/shared/types/notification';

const DAY = 24 * 60 * 60 * 1000;
const NOW = Date.UTC(2026, 9, 5, 12, 0, 0);

function make(id: string, createdAt: number): Notification {
  return {
    id,
    createdAt,
    source: 'sync',
    category: 'system',
    severity: 'error',
    interruption: 'in-app',
    i18nKey: 'notifications.kind.syncFailure',
    readAt: null,
  };
}

describe('applyNotificationRetention', () => {
  it('pins the documented bounds (must match the Rust repository)', () => {
    expect(NOTIFICATION_RETENTION_MAX_COUNT).toBe(200);
    expect(NOTIFICATION_RETENTION_MAX_AGE_MS).toBe(30 * DAY);
  });

  it('drops entries older than the age bound', () => {
    const kept = applyNotificationRetention(
      [make('old', NOW - 31 * DAY), make('fresh', NOW - 1 * DAY)],
      NOW,
    );
    expect(kept.map((n) => n.id)).toEqual(['fresh']);
  });

  it('keeps only the newest maxCount entries', () => {
    const extra = 5;
    const items = Array.from({ length: NOTIFICATION_RETENTION_MAX_COUNT + extra }, (_, i) =>
      make(`n${i}`, NOW - (NOTIFICATION_RETENTION_MAX_COUNT + extra - i) * 1000),
    );

    const kept = applyNotificationRetention(items, NOW);

    expect(kept).toHaveLength(NOTIFICATION_RETENTION_MAX_COUNT);
    expect(kept[0].id).toBe(`n${extra}`);
    expect(kept[kept.length - 1].id).toBe(`n${NOTIFICATION_RETENTION_MAX_COUNT + extra - 1}`);
  });

  it('returns a chronological list regardless of input order', () => {
    const kept = applyNotificationRetention(
      [make('b', NOW - 1 * DAY), make('a', NOW - 2 * DAY)],
      NOW,
    );
    expect(kept.map((n) => n.id)).toEqual(['a', 'b']);
  });

  it('applies both bounds together', () => {
    const items = [
      make('stale', NOW - 40 * DAY),
      ...Array.from({ length: NOTIFICATION_RETENTION_MAX_COUNT + 1 }, (_, i) =>
        make(`fresh${i}`, NOW - i * 1000),
      ),
    ];

    const kept = applyNotificationRetention(items, NOW);

    expect(kept).toHaveLength(NOTIFICATION_RETENTION_MAX_COUNT);
    expect(kept.some((n) => n.id === 'stale')).toBe(false);
    expect(kept.some((n) => n.id === 'fresh0')).toBe(true);
    expect(kept.some((n) => n.id === `fresh${NOTIFICATION_RETENTION_MAX_COUNT}`)).toBe(false);
  });
});
