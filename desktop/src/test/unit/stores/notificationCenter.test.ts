/**
 * Unit tests for the notificationCenter tray store (NOTIF-01, extended by
 * NOTIF-02, projection removed by NOTIF-06). Covers the `notify` emit API on
 * the canonical model, the canonical `items` rows the center renders from
 * `i18nKey` + params, unreadCount/markAllRead/markRead, the 50-entry view
 * cap, clear, and the persistence contract through the notification port.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  notificationCenter,
  notify,
  markRead,
  markAllRead,
  clearNotifications,
  setNotificationPort,
  MAX_NOTIFICATIONS,
} from '$lib/shared/stores/notificationCenter.svelte';
import { MockNotificationAdapter } from '$lib/shared/ports/adapters/mock/MockNotificationAdapter';

describe('notificationCenter', () => {
  beforeEach(() => {
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('records one canonical entry per import outcome with timestamp and unread state', () => {
    const before = Date.now();
    const { notification } = notify({
      source: 'import',
      severity: 'success',
      i18nKey: 'notifications.kind.importSuccess',
      i18nParams: { name: 'my-book' },
    });

    expect(notification).not.toBeNull();
    expect(notification!.source).toBe('import');
    expect(notification!.severity).toBe('success');
    expect(notification!.category).toBe('system');
    expect(notification!.interruption).toBe('silent');
    expect(notification!.readAt).toBeNull();
    expect(notification!.createdAt).toBeGreaterThanOrEqual(before);
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('stores the canonical key + params the center renders (no resolved strings)', () => {
    notify({
      source: 'import',
      severity: 'success',
      i18nKey: 'notifications.kind.importSuccess',
      i18nParams: { name: 'my-book' },
    });
    notify({
      source: 'sync',
      severity: 'error',
      i18nKey: 'notifications.kind.syncFailure',
      i18nParams: { detail: 'Offline' },
    });

    const [imported, sync] = notificationCenter.items;
    expect(imported.i18nKey).toBe('notifications.kind.importSuccess');
    expect(imported.i18nParams).toEqual({ name: 'my-book' });
    expect(imported.readAt).toBeNull();
    expect(sync.i18nKey).toBe('notifications.kind.syncFailure');
    expect(sync.i18nParams).toEqual({ detail: 'Offline' });
    expect(sync.createdAt).toBe(sync.createdAt);
  });

  it('records import failures and sync outcomes with source and severity', () => {
    notify({
      source: 'import',
      severity: 'error',
      i18nKey: 'notifications.kind.importFailure',
      i18nParams: { name: 'bad-book', detail: 'Unsupported format' },
    });
    notify({ source: 'sync', severity: 'success', i18nKey: 'notifications.kind.syncSuccess' });
    notify({
      source: 'sync',
      severity: 'error',
      i18nKey: 'notifications.kind.syncFailure',
      i18nParams: { detail: 'Offline' },
    });

    expect(notificationCenter.items.map((e) => [e.source, e.severity])).toEqual([
      ['import', 'error'],
      ['sync', 'success'],
      ['sync', 'error'],
    ]);
  });

  it('derives unreadCount and clears it when the center is opened', () => {
    notify({ source: 'sync', severity: 'success', i18nKey: 'notifications.kind.syncSuccess' });
    notify({
      source: 'import',
      severity: 'success',
      i18nKey: 'notifications.kind.importSuccess',
      i18nParams: { name: 'book' },
    });

    expect(notificationCenter.unreadCount).toBe(2);

    markAllRead();

    expect(notificationCenter.unreadCount).toBe(0);
    expect(notificationCenter.items).toHaveLength(2);
    expect(notificationCenter.items.every((e) => e.readAt !== null)).toBe(true);
  });

  it('markRead lowers the badge by exactly one item', () => {
    const first = notify({
      source: 'import',
      severity: 'success',
      i18nKey: 'notifications.kind.importSuccess',
      i18nParams: { name: 'one' },
    }).notification!;
    notify({
      source: 'import',
      severity: 'success',
      i18nKey: 'notifications.kind.importSuccess',
      i18nParams: { name: 'two' },
    });

    expect(notificationCenter.unreadCount).toBe(2);

    markRead(first.id);

    expect(notificationCenter.unreadCount).toBe(1);
    const read = notificationCenter.items.find((e) => e.id === first.id);
    const unread = notificationCenter.items.find((e) => e.id !== first.id);
    expect(read!.readAt).not.toBeNull();
    expect(unread!.readAt).toBeNull();
  });

  it('does not re-emit a notification whose dedupKey is already in the tray', () => {
    const draft = {
      source: 'sync' as const,
      severity: 'error' as const,
      i18nKey: 'notifications.kind.syncFailure' as const,
      dedupKey: 'sync-auth:auth_required',
    };

    const first = notify(draft);
    const second = notify(draft);

    expect(first.notification).not.toBeNull();
    expect(second.notification).toBeNull();
    expect(second.resolution.reason).toBe('duplicate');
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('evicts the oldest entry first past the 50-entry view cap (FIFO)', () => {
    expect(MAX_NOTIFICATIONS).toBe(50);

    for (let i = 0; i < MAX_NOTIFICATIONS + 1; i++) {
      notify({
        source: 'sync',
        severity: 'success',
        i18nKey: 'notifications.kind.syncSuccess',
        i18nParams: { name: `entry-${i}` },
      });
    }

    expect(notificationCenter.items).toHaveLength(MAX_NOTIFICATIONS);
    const names = notificationCenter.items.map((e) => e.i18nParams?.name);
    expect(names).not.toContain('entry-0');
    expect(names).toContain(`entry-${MAX_NOTIFICATIONS}`);
  });

  it('clear empties the tray (empty state)', () => {
    notify({
      source: 'import',
      severity: 'success',
      i18nKey: 'notifications.kind.importSuccess',
      i18nParams: { name: 'book' },
    });
    clearNotifications();

    expect(notificationCenter.items).toHaveLength(0);
    expect(notificationCenter.unreadCount).toBe(0);
  });

  it('persists an accepted notification through the port, never over fetch or localStorage', () => {
    const adapter = new MockNotificationAdapter();
    setNotificationPort(adapter);
    const saveSpy = vi.spyOn(adapter, 'save');
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}'));

    const { notification } = notify({
      source: 'sync',
      severity: 'error',
      i18nKey: 'notifications.kind.syncFailure',
      i18nParams: { detail: 'Offline' },
    });

    expect(saveSpy).toHaveBeenCalledTimes(1);
    expect(saveSpy.mock.calls[0][0]).toEqual(notification);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
  });
});
