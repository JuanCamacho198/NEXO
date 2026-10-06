/**
 * Target resolution and activation tests (NOTIF-04): every target kind
 * navigates somewhere real, unknown/missing targets degrade safely (no
 * navigation, no throw), and opening an item always marks it read.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  activateNotificationTarget,
  openNotification,
  resolveNotificationTarget,
  type NotificationNavigator,
} from '$lib/shared/services/notificationNavigation';
import type { NotificationRouteTarget } from '$lib/shared/types/notification';

function fakeNavigator(): NotificationNavigator & Record<string, ReturnType<typeof vi.fn>> {
  return {
    navigateToHome: vi.fn(),
    navigateToLibrary: vi.fn(),
    navigateToSync: vi.fn(),
    navigateToStorage: vi.fn(),
    navigateToAddons: vi.fn(),
    navigateToSettings: vi.fn(),
    openShelfDetails: vi.fn(),
  };
}

function calledOnce(navigator: Record<string, ReturnType<typeof vi.fn>>, name: string): void {
  expect(navigator[name]).toHaveBeenCalledTimes(1);
  for (const [key, fn] of Object.entries(navigator)) {
    if (key !== name) expect(fn).not.toHaveBeenCalled();
  }
}

describe('resolveNotificationTarget', () => {
  it('resolves a book target with a non-empty id', () => {
    expect(resolveNotificationTarget({ kind: 'book', bookId: 'book-1' })).toEqual({
      type: 'book',
      bookId: 'book-1',
    });
  });

  it('resolves every known route target', () => {
    const routes: NotificationRouteTarget[] = [
      'home',
      'library',
      'sync',
      'storage',
      'addons',
      'settings',
    ];
    for (const route of routes) {
      expect(resolveNotificationTarget({ kind: 'route', route })).toEqual({ type: 'route', route });
    }
  });

  it('accepts the legacy slash-prefixed route form from persisted rows', () => {
    expect(resolveNotificationTarget({ kind: 'route', route: '/library' as 'library' })).toEqual({
      type: 'route',
      route: 'library',
    });
  });

  it.each([undefined, null, 'route', 42])('degrades safely for target %p', (target) => {
    expect(
      resolveNotificationTarget(target as Parameters<typeof resolveNotificationTarget>[0]),
    ).toBeNull();
  });

  it('degrades safely for an unknown kind', () => {
    expect(
      resolveNotificationTarget({ kind: 'deep-link', url: 'nexo://x' } as unknown as Parameters<
        typeof resolveNotificationTarget
      >[0]),
    ).toBeNull();
  });

  it('degrades safely for an unknown route', () => {
    expect(resolveNotificationTarget({ kind: 'route', route: 'reader' as 'library' })).toBeNull();
  });

  it.each(['', '   '])('degrades safely for a blank book id %p', (bookId) => {
    expect(resolveNotificationTarget({ kind: 'book', bookId })).toBeNull();
  });
});

describe('activateNotificationTarget', () => {
  let navigator: NotificationNavigator & Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(() => {
    navigator = fakeNavigator();
  });

  it('opens a book target in the library details', () => {
    const navigated = activateNotificationTarget({ kind: 'book', bookId: 'book-9' }, navigator);

    expect(navigated).toBe(true);
    expect(navigator.navigateToLibrary).toHaveBeenCalledTimes(1);
    expect(navigator.openShelfDetails).toHaveBeenCalledWith('book-9');
  });

  it.each([
    ['home', 'navigateToHome'],
    ['library', 'navigateToLibrary'],
    ['sync', 'navigateToSync'],
    ['storage', 'navigateToStorage'],
    ['addons', 'navigateToAddons'],
    ['settings', 'navigateToSettings'],
  ] as const)('navigates route %s via %s', (route, method) => {
    const navigated = activateNotificationTarget({ kind: 'route', route }, navigator);

    expect(navigated).toBe(true);
    calledOnce(navigator, method);
  });

  it('stays put for a missing target', () => {
    expect(activateNotificationTarget(undefined, navigator)).toBe(false);
    for (const fn of Object.values(navigator)) expect(fn).not.toHaveBeenCalled();
  });

  it('stays put for an unknown target without throwing', () => {
    const unknown = { kind: 'deep-link', url: 'nexo://x' } as unknown as Parameters<
      typeof activateNotificationTarget
    >[0];

    expect(activateNotificationTarget(unknown, navigator)).toBe(false);
    for (const fn of Object.values(navigator)) expect(fn).not.toHaveBeenCalled();
  });
});

describe('openNotification', () => {
  it('marks read and navigates for a known target', () => {
    const navigator = fakeNavigator();
    const markAsRead = vi.fn();

    const navigated = openNotification(
      { id: 'n-1', target: { kind: 'route', route: 'sync' } },
      { markAsRead, navigator },
    );

    expect(navigated).toBe(true);
    expect(markAsRead).toHaveBeenCalledWith('n-1');
    expect(navigator.navigateToSync).toHaveBeenCalledTimes(1);
  });

  it('still marks read but stays put for an unknown target', () => {
    const navigator = fakeNavigator();
    const markAsRead = vi.fn();
    const unknown = { kind: 'deep-link' } as unknown as Parameters<typeof openNotification>[0];

    const navigated = openNotification(unknown, { markAsRead, navigator });

    expect(navigated).toBe(false);
    expect(markAsRead).toHaveBeenCalledWith(unknown.id);
    for (const fn of Object.values(navigator)) expect(fn).not.toHaveBeenCalled();
  });
});
