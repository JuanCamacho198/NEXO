/**
 * Canonical notification model (NOTIF-01).
 *
 * One model for every user-visible event the reader could have missed. The
 * tray, the delivery policy, and (in later work units) persistence and OS
 * delivery all speak this shape.
 *
 * Localization is stored as a key plus params — never a resolved string — so a
 * later language switch re-renders history correctly (NOTIF-06 renders it).
 */
import type { MessageKey } from '$lib/shared/i18n';

/** What a notification is about: routine system events vs. engagement nudges. */
export type Category = 'system' | 'nudge';

/** How important a notification is (drives nothing by itself; category + interruption do). */
export type Severity = 'success' | 'info' | 'warning' | 'error';

/** How much a notification may interrupt the reader. */
export type Interruption = 'silent' | 'in-app' | 'system';

/** The subsystem that produced the notification. */
export type NotificationSource =
  'import' | 'sync' | 'addons' | 'update' | 'library' | 'streak' | 'goal';

/** Interpolation params for `i18nKey` (values are data, never translated). */
export type NotificationI18nParams = Record<string, string | number>;

/**
 * Destinations a notification can navigate to, each reachable in the
 * AppRouter: `library` (imports land here), `sync` / `storage` (the settings
 * panels sync failures point at), `addons` (catalog flows), plus `home` and
 * `settings` for future emitters. Anything else is unknown by definition and
 * must degrade safely (no navigation, no crash — NOTIF-04).
 */
export type NotificationRouteTarget =
  'home' | 'library' | 'sync' | 'storage' | 'addons' | 'settings';

/**
 * Where activating a notification navigates to (NOTIF-04 final shape).
 * `book` opens the book's details; `route` navigates to a known destination.
 * Persisted rows with any other shape are unknown targets and never navigate.
 */
export type NotificationTarget =
  { kind: 'book'; bookId: string } | { kind: 'route'; route: NotificationRouteTarget };

export interface Notification {
  /** Stable identity, unique for the lifetime of the store. */
  id: string;
  /** Creation time, epoch milliseconds. */
  createdAt: number;
  source: NotificationSource;
  category: Category;
  severity: Severity;
  interruption: Interruption;
  /** Message key rendered at display time — never a resolved string. */
  i18nKey: MessageKey;
  i18nParams?: NotificationI18nParams;
  target?: NotificationTarget;
  /** Null while unread; epoch milliseconds once read. */
  readAt: number | null;
  /** Suppresses re-emission while an identical event is already known (rule 4). */
  dedupKey?: string;
}
