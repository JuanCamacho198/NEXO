/**
 * notificationCenter — local-only in-memory notification tray (FR-DN1/FR-DN2).
 *
 * Fed only by existing import/sync outcome events. No persistence (the tray
 * starts empty every launch with an explicit empty state), no OS-level push,
 * no network delivery — pushes are synchronous in-memory appends.
 *
 * Bounded at MAX_NOTIFICATIONS entries: pushing past the cap evicts the
 * oldest entry first (FIFO). Unbounded history was explicitly rejected.
 */
export type NotificationKind =
  'import-success' | 'import-failure' | 'sync-success' | 'sync-failure';

export interface NotificationEntry {
  id: number;
  kind: NotificationKind;
  title: string;
  message: string;
  at: number;
  read: boolean;
}

/** Tray cap — oldest entry is evicted first once the tray holds this many. */
export const MAX_NOTIFICATIONS = 50;

// ─── Reactive State ───────────────────────────────────────────────────

let entries: NotificationEntry[] = $state([]);
let nextId = 1;

// ─── Public API ───────────────────────────────────────────────────────

/**
 * Record one tray entry per outcome event. Synchronous, in-memory only:
 * no timers, no storage writes, no network, no OS notification.
 */
export function pushNotification(
  entry: Omit<NotificationEntry, 'id' | 'at' | 'read'>,
): NotificationEntry {
  const item: NotificationEntry = { ...entry, id: nextId++, at: Date.now(), read: false };
  entries.push(item);
  while (entries.length > MAX_NOTIFICATIONS) {
    entries.shift();
  }
  return item;
}

/** Feed helper for import outcomes — called from BulkImportDomainState only. */
export function reportImportOutcome(
  ok: boolean,
  title: string,
  message: string,
): NotificationEntry {
  return pushNotification({ kind: ok ? 'import-success' : 'import-failure', title, message });
}

/** Feed helper for sync-run outcomes — called from SyncService only. */
export function reportSyncOutcome(ok: boolean, title: string, message: string): NotificationEntry {
  return pushNotification({ kind: ok ? 'sync-success' : 'sync-failure', title, message });
}

/** Clear the unread indicator — called when the center is opened. */
export function markAllRead(): void {
  for (const entry of entries) {
    entry.read = true;
  }
}

/** Empty the tray (tests, sign-out reset). */
export function clearNotifications(): void {
  entries = [];
}

export const notificationCenter = {
  get items(): NotificationEntry[] {
    return entries;
  },
  get unreadCount(): number {
    return entries.filter((entry) => !entry.read).length;
  },
};
