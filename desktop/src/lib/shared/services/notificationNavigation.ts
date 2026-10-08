/**
 * notificationNavigation — target resolution and activation for the
 * notification system (NOTIF-04).
 *
 * Every `NotificationTarget` kind navigates somewhere real: `book` opens the
 * book's details in the library, `route` navigates to a known AppRouter
 * destination. Anything else (missing target, unknown kind, unknown route,
 * empty book id — including legacy persisted rows) resolves to null and the
 * activation is a safe no-op: the entry stays in the tray, nothing throws, no
 * dead navigation happens.
 *
 * `resolveNotificationTarget` is pure (no stores, no DOM) so the contract is
 * unit-testable. `activateNotificationTarget` drives the real
 * `navigationState` unless a test navigator is passed. `openNotification`
 * keeps markRead on click: opening an item always marks it read, and returns
 * whether navigation happened so callers know whether to leave the tray open.
 */
import type {
  Notification,
  NotificationRouteTarget,
  NotificationTarget,
} from '$lib/shared/types/notification';
import {
  navigationState,
  type NavigationDomainState,
} from '$lib/shared/stores/NavigationDomainState.svelte';
import { markRead } from '$lib/shared/stores/notificationCenter.svelte';

/** Route destinations a notification may point at (mirrors the target type). */
export const NOTIFICATION_ROUTES: readonly NotificationRouteTarget[] = [
  'home',
  'library',
  'sync',
  'storage',
  'addons',
  'settings',
];

/** Minimal navigation surface activation needs (real state or a test fake). */
export type NotificationNavigator = Pick<
  NavigationDomainState,
  | 'navigateToHome'
  | 'navigateToLibrary'
  | 'navigateToSync'
  | 'navigateToStorage'
  | 'navigateToAddons'
  | 'navigateToSettings'
  | 'openShelfDetails'
>;

export type ResolvedNotificationTarget =
  { type: 'book'; bookId: string } | { type: 'route'; route: NotificationRouteTarget };

function normalizeRoute(value: unknown): NotificationRouteTarget | null {
  if (typeof value !== 'string') return null;
  const name = value.startsWith('/') ? value.slice(1) : value;
  return (NOTIFICATION_ROUTES as readonly string[]).includes(name)
    ? (name as NotificationRouteTarget)
    : null;
}

/**
 * Validates a stored target (which may be any persisted JSON shape) into an
 * actionable destination, or null when it must degrade safely.
 */
export function resolveNotificationTarget(
  target: NotificationTarget | undefined | null,
): ResolvedNotificationTarget | null {
  if (!target || typeof target !== 'object') return null;
  if (target.kind === 'book') {
    const bookId = (target as { bookId?: unknown }).bookId;
    if (typeof bookId !== 'string' || bookId.trim().length === 0) return null;
    return { type: 'book', bookId };
  }
  if (target.kind === 'route') {
    const route = normalizeRoute((target as { route?: unknown }).route);
    return route ? { type: 'route', route } : null;
  }
  return null;
}

/**
 * Navigates to a stored target. Returns true when navigation happened, false
 * for a missing/unknown target (safe degrade: no navigation, no throw).
 */
export function activateNotificationTarget(
  target: NotificationTarget | undefined | null,
  navigator: NotificationNavigator = navigationState,
): boolean {
  const resolved = resolveNotificationTarget(target);
  if (!resolved) return false;
  if (resolved.type === 'book') {
    navigator.navigateToLibrary();
    navigator.openShelfDetails(resolved.bookId);
    return true;
  }
  switch (resolved.route) {
    case 'home':
      navigator.navigateToHome();
      return true;
    case 'library':
      navigator.navigateToLibrary();
      return true;
    case 'sync':
      navigator.navigateToSync();
      return true;
    case 'storage':
      navigator.navigateToStorage();
      return true;
    case 'addons':
      navigator.navigateToAddons();
      return true;
    case 'settings':
      navigator.navigateToSettings();
      return true;
    default:
      return false;
  }
}

/**
 * Opens one notification: always marks it read, then navigates when its
 * target resolves. Returns whether navigation happened.
 */
export function openNotification(
  notification: Pick<Notification, 'id' | 'target'>,
  deps: { markAsRead?: (id: string) => void; navigator?: NotificationNavigator } = {},
): boolean {
  try {
    (deps.markAsRead ?? markRead)(notification.id);
  } catch {
    // Read state is advisory next to navigation; never block the activation.
  }
  return activateNotificationTarget(notification.target, deps.navigator);
}
