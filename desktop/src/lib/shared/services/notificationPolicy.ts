/**
 * notificationPolicy — the pure delivery policy for the notification system
 * (NOTIF-01). No UI, no DOM, no timers, no storage: given a draft and a
 * snapshot of current state it returns the resolved category, interruption and
 * the delivery decision.
 *
 * The six rules (notification-system task doc):
 *  1. Every accepted notification is a tray entry; unread is the badge.
 *  2. OS fires only when `interruption === 'system'` AND the window is unfocused.
 *  3. Total silence while reading: no toast, no OS, regardless of severity; the
 *     tray still records.
 *  4. Dedup + rate limit: an already-known `dedupKey` is not re-emitted; a
 *     `nudge` is capped at one per `dedupKey` per day.
 *  5. Per-category preferences gate sound (NOTIF-05): a disabled category
 *     produces no toast and no OS — the tray still records. A nudge reaches
 *     the OS only when its category switch is on (plus rules 2–3 and quiet
 *     hours below).
 *  6. The OS permission is a single global grant; per-category preferences
 *     (NOTIF-05, `NotificationPreferences`) are what make OS-on-nudges safe.
 *  7. Quiet hours suppress toast + OS like reading silence does (rule 3
 *     semantics on a user schedule); the tray still records.
 */
import type {
  Category,
  Interruption,
  NotificationSource,
  Severity,
} from '$lib/shared/types/notification';
import {
  DEFAULT_NOTIFICATION_PREFERENCES,
  isQuietHoursActive,
  type NotificationPreferences,
} from '$lib/shared/services/notificationPreferences';

/** Nudge sources — engagement events the reader should be able to switch off. */
const NUDGE_SOURCES: ReadonlySet<NotificationSource> = new Set(['streak', 'goal']);

/** Sources whose notifications are about the reader's engagement, not the app. */
export function defaultCategory(source: NotificationSource): Category {
  return NUDGE_SOURCES.has(source) ? 'nudge' : 'system';
}

const DEFAULT_INTERRUPTION: Partial<Record<`${NotificationSource}:${Severity}`, Interruption>> = {
  'import:success': 'silent',
  'import:error': 'in-app',
  'sync:success': 'silent',
  'sync:error': 'in-app',
  'addons:success': 'silent',
  'addons:error': 'in-app',
  'update:info': 'silent',
  'library:success': 'silent',
  'streak:warning': 'system',
  'streak:error': 'system',
  'streak:success': 'system',
  'goal:info': 'system',
  'goal:warning': 'system',
  'goal:error': 'system',
  'goal:success': 'in-app',
};

/**
 * Resolve the default interruption for a draft from the table, falling back by
 * category: nudges default to `system`, system events default to `in-app` on
 * error and `silent` otherwise.
 */
export function defaultInterruption(
  source: NotificationSource,
  severity: Severity,
  category: Category,
): Interruption {
  const explicit = DEFAULT_INTERRUPTION[`${source}:${severity}`];
  if (explicit) return explicit;
  if (category === 'nudge') return 'system';
  return severity === 'error' ? 'in-app' : 'silent';
}

/**
 * Tray + rate-limit state the caller passes in. Deliberately a plain snapshot
 * so the policy stays pure and testable without the store.
 */
export interface PolicySnapshot {
  /** Dedup keys currently present in the tray. */
  dedupKeys: ReadonlySet<string>;
  /** Nudge emissions already recorded, for the daily per-key cap. */
  nudges: ReadonlyArray<{ dedupKey: string; createdAt: number }>;
}

export interface PolicyInput {
  source: NotificationSource;
  severity: Severity;
  dedupKey?: string;
  /** Explicit overrides; when omitted the default table resolves them. */
  category?: Category;
  interruption?: Interruption;
  isWindowFocused: boolean;
  isReading: boolean;
  /** Injectable clock (tests). Defaults to `Date.now()`. */
  now?: number;
  snapshot?: PolicySnapshot;
  /**
   * Per-category switches + quiet hours (NOTIF-05). Omitted means the
   * defaults: every category enabled, quiet hours off.
   */
  preferences?: NotificationPreferences;
}

export interface DeliveryDecision {
  /** Rule 1 — every accepted notification lands in the tray. */
  tray: boolean;
  /** In-app toast for `in-app` and `system`, suppressed while reading (rule 3). */
  toast: boolean;
  /** OS delivery per rules 2, 3, 5 and 7. */
  os: boolean;
}

export type PolicyRejection = 'duplicate' | 'nudge-daily-cap';

export interface PolicyResolution {
  accepted: boolean;
  reason: PolicyRejection | null;
  category: Category;
  interruption: Interruption;
  delivery: DeliveryDecision;
}

/** True when both timestamps fall on the same calendar day (local time). */
export function isSameDay(a: number, b: number): boolean {
  const dateA = new Date(a);
  const dateB = new Date(b);
  return (
    dateA.getFullYear() === dateB.getFullYear() &&
    dateA.getMonth() === dateB.getMonth() &&
    dateA.getDate() === dateB.getDate()
  );
}

export function notificationPolicy(input: PolicyInput): PolicyResolution {
  const now = input.now ?? Date.now();
  const category = input.category ?? defaultCategory(input.source);
  const interruption =
    input.interruption ?? defaultInterruption(input.source, input.severity, category);

  const snapshot = input.snapshot;
  // Rule 4a: an identical event already known is never re-emitted.
  const isDuplicate = Boolean(input.dedupKey && snapshot?.dedupKeys.has(input.dedupKey));
  // Rule 4b: a nudge is capped at one emission per dedupKey per day.
  const nudgeDailyCapReached =
    category === 'nudge' &&
    Boolean(input.dedupKey) &&
    Boolean(
      snapshot?.nudges.some(
        (record) => record.dedupKey === input.dedupKey && isSameDay(record.createdAt, now),
      ),
    );

  if (isDuplicate || nudgeDailyCapReached) {
    return {
      accepted: false,
      reason: isDuplicate ? 'duplicate' : 'nudge-daily-cap',
      category,
      interruption,
      delivery: { tray: false, toast: false, os: false },
    };
  }

  // Rule 3: total silence while reading — no toast, no OS; the tray still records.
  // Rule 7: quiet hours silence exactly the same surfaces on a user schedule.
  const prefs = input.preferences ?? DEFAULT_NOTIFICATION_PREFERENCES;
  const quiet = isQuietHoursActive(prefs, now);
  // Rule 5: a disabled category produces no toast and no OS; the tray (rule 1)
  // still records. Nudge OS is unlocked by the nudge switch, not hardcoded.
  const categoryEnabled = category === 'nudge' ? prefs.nudge : prefs.system;
  const toast = interruption !== 'silent' && !input.isReading && !quiet && categoryEnabled;
  // Rule 2 (system interruption + unfocused) with rules 3, 5 and 7 applied.
  const os =
    interruption === 'system' &&
    !input.isWindowFocused &&
    !input.isReading &&
    !quiet &&
    categoryEnabled;

  return {
    accepted: true,
    reason: null,
    category,
    interruption,
    delivery: { tray: true, toast, os },
  };
}
