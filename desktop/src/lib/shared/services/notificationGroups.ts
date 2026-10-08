/**
 * notificationGroups — presentational grouping for the tray (NOTIF-06).
 *
 * Repeated events read as one entry with a count: every stored row sharing
 * the same `source` + `i18nKey` collapses into a group (e.g. repeated sync
 * failures, or one `notifications.catalog.failed` per book). Grouping never
 * merges stored rows — each entry keeps its own identity, read state and
 * target; the tray only renders them together, expandable to the full list.
 *
 * Pure (no stores, no DOM) so the rule is unit-testable.
 */
import type { Notification } from '$lib/shared/types/notification';

export interface NotificationGroup {
  /** Presentational identity: `source` + rendered event, stable per event type. */
  key: string;
  /** Newest first, so the group header shows the latest activity. */
  entries: Notification[];
}

/**
 * Groups a chronological (oldest-first) history for display. Groups follow
 * latest activity first; entries inside a group are newest first. A group
 * with a single entry renders as a plain row.
 */
export function groupNotifications(chronological: readonly Notification[]): NotificationGroup[] {
  const ordered = new Map<string, Notification[]>();
  for (let index = chronological.length - 1; index >= 0; index--) {
    const entry = chronological[index];
    const key = `${entry.source}::${entry.i18nKey}`;
    const list = ordered.get(key);
    if (list) {
      list.push(entry);
    } else {
      ordered.set(key, [entry]);
    }
  }
  return [...ordered.entries()].map(([key, entries]) => ({ key, entries }));
}
