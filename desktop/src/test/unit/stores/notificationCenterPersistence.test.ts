/**
 * Persistence integration for the notificationCenter store (NOTIF-02).
 *
 * A "restart" is simulated with `vi.resetModules()` plus a shared
 * `MockNotificationAdapter`, which mirrors the Rust repository semantics. This
 * proves the acceptance criteria: history survives a restart as unread, a
 * failed sync does not vanish with the toast, per-item read state round-trips,
 * clear deletes, and retention bounds the store.
 */
import { describe, it, expect, vi } from 'vitest';
import { MockNotificationAdapter } from '$lib/shared/ports/adapters/mock/MockNotificationAdapter';
import { NOTIFICATION_RETENTION_MAX_COUNT } from '$lib/shared/services/notificationRetention';
import type { Notification } from '$lib/shared/types/notification';

const FAILED_SYNC = {
  source: 'sync' as const,
  severity: 'error' as const,
  i18nKey: 'notifications.kind.syncFailure' as const,
  i18nParams: { detail: 'Offline' },
};

async function freshStore() {
  vi.resetModules();
  return import('$lib/shared/stores/notificationCenter.svelte');
}

describe('notificationCenter persistence (NOTIF-02)', () => {
  it('restores an unread entry after a restart', async () => {
    const storage = new MockNotificationAdapter();

    const first = await freshStore();
    first.setNotificationPort(storage);
    const emitted = first.notify(FAILED_SYNC);
    expect(emitted.notification).not.toBeNull();
    await vi.waitFor(async () => {
      expect(await storage.list()).toHaveLength(1);
    });

    const second = await freshStore();
    second.setNotificationPort(storage);
    await second.loadNotifications();

    expect(second.notificationCenter.items).toHaveLength(1);
    expect(second.notificationCenter.unreadCount).toBe(1);
    expect(second.notificationCenter.items[0].readAt).toBeNull();
  });

  it('persists per-item read state across a restart', async () => {
    const storage = new MockNotificationAdapter();

    const first = await freshStore();
    first.setNotificationPort(storage);
    const readOne = first.notify(FAILED_SYNC).notification!;
    first.notify({ ...FAILED_SYNC, dedupKey: 'sync:other' });
    first.markRead(readOne.id);
    await vi.waitFor(async () => {
      const rows = await storage.list();
      expect(rows.find((row) => row.id === readOne.id)?.readAt).not.toBeNull();
    });

    const second = await freshStore();
    second.setNotificationPort(storage);
    await second.loadNotifications();

    expect(second.notificationCenter.unreadCount).toBe(1);
    expect(second.notificationCenter.items.find((e) => e.id === readOne.id)!.readAt).not.toBeNull();
  });

  it('markAllRead persists and survives a restart', async () => {
    const storage = new MockNotificationAdapter();

    const first = await freshStore();
    first.setNotificationPort(storage);
    first.notify(FAILED_SYNC);
    first.notify({ ...FAILED_SYNC, dedupKey: 'sync:other' });
    first.markAllRead();
    await vi.waitFor(async () => {
      const rows = await storage.list();
      expect(rows.every((row) => row.readAt !== null)).toBe(true);
    });

    const second = await freshStore();
    second.setNotificationPort(storage);
    await second.loadNotifications();

    expect(second.notificationCenter.unreadCount).toBe(0);
  });

  it('clearNotifications deletes the persisted history', async () => {
    const storage = new MockNotificationAdapter();

    const store = await freshStore();
    store.setNotificationPort(storage);
    store.notify(FAILED_SYNC);
    await vi.waitFor(async () => {
      expect(await storage.list()).toHaveLength(1);
    });

    store.clearNotifications();
    await vi.waitFor(async () => {
      expect(await storage.list()).toHaveLength(0);
    });
    expect(store.notificationCenter.unreadCount).toBe(0);
  });

  it('load applies retention so the store is bounded', async () => {
    const DAY = 24 * 60 * 60 * 1000;
    const now = Date.now();
    const rows: Notification[] = [
      {
        id: 'stale',
        createdAt: now - 40 * DAY,
        source: 'import',
        category: 'system',
        severity: 'success',
        interruption: 'silent',
        i18nKey: 'notifications.kind.importSuccess',
        readAt: null,
      },
      ...Array.from({ length: NOTIFICATION_RETENTION_MAX_COUNT + 3 }, (_, i) => ({
        id: `fresh-${i}`,
        createdAt: now - i * 1000,
        source: 'import' as const,
        category: 'system' as const,
        severity: 'success' as const,
        interruption: 'silent' as const,
        i18nKey: 'notifications.kind.importSuccess' as const,
        readAt: null,
      })),
    ];
    const storage = new MockNotificationAdapter();
    storage.seed(rows);

    const store = await freshStore();
    store.setNotificationPort(storage);
    await store.loadNotifications();

    expect(store.notificationCenter.unreadCount).toBe(NOTIFICATION_RETENTION_MAX_COUNT);
    expect(store.notificationCenter.items.some((e) => e.id === 'stale')).toBe(false);
  });

  it('load failures leave the in-memory tray untouched', async () => {
    const store = await freshStore();
    store.setNotificationPort({
      list: async () => {
        throw new Error('database unavailable');
      },
      save: async () => {},
      markRead: async () => false,
      markAllRead: async () => 0,
      clear: async () => 0,
      prune: async () => 0,
    });

    store.notify(FAILED_SYNC);
    await expect(store.loadNotifications()).resolves.toBeUndefined();
    expect(store.notificationCenter.items).toHaveLength(1);
  });
});
