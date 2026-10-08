/**
 * notificationCenter — the local tray and the single `notify(...)` emit API
 * (NOTIF-01), now backed by a durable local history (NOTIF-02).
 *
 * Every accepted event is one tray entry, unread until read. History is loaded
 * from the `NotificationPort` on startup (newest first, unread state restored),
 * an accepted `notify` is persisted immediately, and per-item read state is
 * written through `markRead` / `markAllRead`; `clearNotifications` deletes the
 * stored history. Retention (count + age) bounds both the persisted table and
 * the in-memory list via `notificationRetention`.
 *
 * The public surface stays compatible with the existing UI
 * (`items` / `unreadCount` / `markAllRead` / `clearNotifications`). `items`
 * keeps its chronological (oldest-first) order because the current center
 * reverses it; it is capped at `MAX_NOTIFICATIONS` as a view bound, while the
 * durable bound is the retention policy. `unreadCount` is the real per-item
 * count over the retained history, never a global flag.
 *
 * Rendering (NOTIF-06) reads the canonical rows directly: the center renders
 * `i18nKey` + `i18nParams` through the active translator, so a language
 * switch re-renders history without touching the store.
 */
import type {
  Category,
  Interruption,
  Notification,
  NotificationI18nParams,
  NotificationSource,
  NotificationTarget,
  Severity,
} from '$lib/shared/types/notification';
import type { MessageKey } from '$lib/shared/i18n';
import {
  notificationPolicy,
  type PolicyResolution,
  type PolicySnapshot,
} from '$lib/shared/services/notificationPolicy';
import { getCachedNotificationPreferences } from '$lib/shared/services/notificationPreferences';
import { applyNotificationRetention } from '$lib/shared/services/notificationRetention';
import {
  dispatchSurfaces,
  isReadingLive,
  isWindowFocusedLive,
} from '$lib/shared/services/notificationSurfaces';
import type { NotificationPort } from '$lib/shared/ports/NotificationPort';
import { TauriNotificationAdapter } from '$lib/shared/ports/adapters/tauri/TauriNotificationAdapter';

/**
 * View cap for `items` (the legacy in-memory tray size). Retention is the
 * durable bound; this only limits how many entries the current center renders.
 */
export const MAX_NOTIFICATIONS = 50;

/** Nudge rate-limit records are pruned past this window. */
const NUDGE_LOG_TTL_MS = 48 * 60 * 60 * 1000;

// ─── Persistence seam ─────────────────────────────────────────────────

let port: NotificationPort = new TauriNotificationAdapter();

/** Replaces the persistence adapter (tests, non-Tauri hosts). */
export function setNotificationPort(next: NotificationPort): void {
  port = next;
}

// ─── Reactive State ───────────────────────────────────────────────────

let notifications: Notification[] = $state([]);
let nudgeLog: Array<{ dedupKey: string; createdAt: number }> = [];
let fallbackId = 1;

// ─── Persistence ──────────────────────────────────────────────────────

/**
 * Runs a best-effort persistence call. A port that rejects — or, under a
 * partial module mock, throws synchronously — must never break the
 * synchronous emit path.
 */
function safePersistence(run: () => Promise<unknown>): void {
  try {
    void run().catch(() => {});
  } catch {
    // Ignore: persistence is durable history, not a delivery guarantee.
  }
}

function persistNotification(notification: Notification, now: number): void {
  safePersistence(() => port.save(notification).then(() => port.prune(now)));
}

/**
 * Loads the persisted history, restores unread state and applies retention.
 * The port returns newest-first; `applyNotificationRetention` restores a
 * chronological (oldest-first) list, which is the order `items` projects.
 * Failures leave the current in-memory tray untouched.
 */
export async function loadNotifications(): Promise<void> {
  try {
    const persisted = await port.list();
    const now = Date.now();
    notifications = applyNotificationRetention(persisted, now);
    safePersistence(() => port.prune(now));
  } catch {
    // No persisted history available; keep whatever is already in memory.
  }
}

// ─── Emit API ─────────────────────────────────────────────────────────

export interface NotifyDraft {
  source: NotificationSource;
  severity: Severity;
  i18nKey: MessageKey;
  i18nParams?: NotificationI18nParams;
  target?: NotificationTarget;
  dedupKey?: string;
  /** Explicit overrides; omitted values resolve from the policy default table. */
  category?: Category;
  interruption?: Interruption;
}

/** Context overrides for the policy (window focus, reading state, clock). */
export interface NotifyContext {
  isWindowFocused?: boolean;
  isReading?: boolean;
  now?: number;
}

export interface NotifyOutcome {
  /** The stored entry, or null when the policy rejected the draft. */
  notification: Notification | null;
  resolution: PolicyResolution;
}

function nextNotificationId(): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  return uuid ?? `notification-${fallbackId++}`;
}

function snapshotFor(now: number): PolicySnapshot {
  nudgeLog = nudgeLog.filter((record) => now - record.createdAt < NUDGE_LOG_TTL_MS);
  const dedupKeys = new Set<string>();
  for (const item of notifications) {
    if (item.dedupKey) dedupKeys.add(item.dedupKey);
  }
  return { dedupKeys, nudges: nudgeLog };
}

/**
 * Record one notification. The policy resolves category + interruption and
 * applies dedup, the nudge daily cap, reading silence and window focus before
 * anything is stored. Focus defaults to the live window focus state and
 * reading defaults to the canonical reading state
 * (`readerState.activeReadingBookId`); both are overridable per call.
 * Returns the stored entry (or null) plus the resolution. An accepted entry
 * is persisted (and retention re-applied) synchronously for the caller,
 * asynchronously on disk, and dispatched exactly once to its
 * policy-allowed surfaces (toast/OS; the tray is the stored entry itself).
 */
export function notify(draft: NotifyDraft, context: NotifyContext = {}): NotifyOutcome {
  const now = context.now ?? Date.now();
  const resolution = notificationPolicy({
    source: draft.source,
    severity: draft.severity,
    dedupKey: draft.dedupKey,
    category: draft.category,
    interruption: draft.interruption,
    isWindowFocused: context.isWindowFocused ?? isWindowFocusedLive(),
    isReading: context.isReading ?? isReadingLive(),
    now,
    snapshot: snapshotFor(now),
    preferences: getCachedNotificationPreferences(),
  });

  if (!resolution.accepted) {
    return { notification: null, resolution };
  }

  const notification: Notification = {
    id: nextNotificationId(),
    createdAt: now,
    source: draft.source,
    category: resolution.category,
    severity: draft.severity,
    interruption: resolution.interruption,
    i18nKey: draft.i18nKey,
    readAt: null,
    ...(draft.i18nParams ? { i18nParams: draft.i18nParams } : {}),
    ...(draft.target ? { target: draft.target } : {}),
    ...(draft.dedupKey ? { dedupKey: draft.dedupKey } : {}),
  };

  notifications.push(notification);
  const retained = applyNotificationRetention(notifications, now);
  if (retained.length !== notifications.length) {
    notifications = retained;
  }
  if (resolution.category === 'nudge' && draft.dedupKey) {
    nudgeLog.push({ dedupKey: draft.dedupKey, createdAt: now });
  }

  persistNotification(notification, now);
  dispatchSurfaces(notification, resolution);
  return { notification, resolution };
}

/** Mark one entry read (per-item state; the badge is its unread count). */
export function markRead(id: string): void {
  const item = notifications.find((entry) => entry.id === id);
  if (!item || item.readAt !== null) return;
  const now = Date.now();
  item.readAt = now;
  safePersistence(() => port.markRead(id, now));
}

/** Mark every entry read — called when the center is opened. */
export function markAllRead(): void {
  const now = Date.now();
  let changed = false;
  for (const item of notifications) {
    if (item.readAt === null) {
      item.readAt = now;
      changed = true;
    }
  }
  if (changed) safePersistence(() => port.markAllRead(now));
}

/** Delete the persisted history and empty the tray (clear action, tests). */
export function clearNotifications(): void {
  notifications = [];
  nudgeLog = [];
  safePersistence(() => port.clear());
}

// ─── Tray view ────────────────────────────────────────────────────────

export const notificationCenter = {
  get items(): Notification[] {
    return notifications.length > MAX_NOTIFICATIONS
      ? notifications.slice(notifications.length - MAX_NOTIFICATIONS)
      : notifications;
  },
  get unreadCount(): number {
    return notifications.reduce((count, item) => (item.readAt === null ? count + 1 : count), 0);
  },
};
