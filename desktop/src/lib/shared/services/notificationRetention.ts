/**
 * Durable notification-retention policy (NOTIF-02).
 *
 * The tray is bounded in memory AND on disk. This module owns the frontend
 * mirror of the bounds enforced by the Rust repository
 * (`src-tauri/src/repository/notifications.rs`, `apply_notification_retention`);
 * both sides are pinned by tests, so the two definitions must change together.
 *
 * Why these values:
 * - `MAX_COUNT = 200`: far more than a heavy week of events can produce, yet
 *   only a few tens of KB on disk.
 * - `MAX_AGE = 30 days`: matches the app-wide tombstone window
 *   (`retention::RETENTION_DAYS`), so there is one retention story in the app.
 */
import type { Notification } from '$lib/shared/types/notification';

export const NOTIFICATION_RETENTION_MAX_COUNT = 200;
export const NOTIFICATION_RETENTION_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * Applies both bounds to any list of notifications (any input order) and
 * returns a chronological (oldest-first) list whose newest entries are kept.
 */
export function applyNotificationRetention(
  items: readonly Notification[],
  now: number,
): Notification[] {
  const cutoff = now - NOTIFICATION_RETENTION_MAX_AGE_MS;
  const kept = items
    .filter((item) => item.createdAt >= cutoff)
    .sort((a, b) => a.createdAt - b.createdAt);

  if (kept.length <= NOTIFICATION_RETENTION_MAX_COUNT) {
    return kept;
  }
  return kept.slice(kept.length - NOTIFICATION_RETENTION_MAX_COUNT);
}
