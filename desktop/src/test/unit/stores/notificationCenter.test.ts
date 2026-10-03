/**
 * Unit tests for the notificationCenter tray store (WU3 / PR3, FR-DN1/FR-DN2).
 *
 * Covers push per kind (result + timestamp recorded), unreadCount derivation,
 * markAllRead on open, 50-entry FIFO eviction, clear, and the local-only
 * contract (no persistence, no network delivery, no OS push).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  notificationCenter,
  pushNotification,
  reportImportOutcome,
  reportSyncOutcome,
  markAllRead,
  clearNotifications,
  MAX_NOTIFICATIONS,
} from '$lib/shared/stores/notificationCenter.svelte';

describe('notificationCenter', () => {
  beforeEach(() => {
    clearNotifications();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('records one entry per import outcome with result and timestamp', () => {
    const before = Date.now();
    const entry = reportImportOutcome(true, 'my-book', '');

    expect(entry.kind).toBe('import-success');
    expect(entry.title).toBe('my-book');
    expect(entry.at).toBeGreaterThanOrEqual(before);
    expect(entry.read).toBe(false);
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('records import failures and sync outcomes per kind', () => {
    reportImportOutcome(false, 'bad-book', 'Unsupported format');
    reportSyncOutcome(true, 'Sync', 'Completed');
    reportSyncOutcome(false, 'Sync', 'Offline');

    expect(notificationCenter.items.map((e) => e.kind)).toEqual([
      'import-failure',
      'sync-success',
      'sync-failure',
    ]);
  });

  it('derives unreadCount and clears it when the center is opened', () => {
    pushNotification({ kind: 'sync-success', title: 'Sync', message: 'Completed' });
    pushNotification({ kind: 'import-success', title: 'book', message: '' });

    expect(notificationCenter.unreadCount).toBe(2);

    markAllRead();

    expect(notificationCenter.unreadCount).toBe(0);
    expect(notificationCenter.items).toHaveLength(2);
  });

  it('evicts the oldest entry first past the 50-entry cap (FIFO)', () => {
    expect(MAX_NOTIFICATIONS).toBe(50);

    for (let i = 0; i < MAX_NOTIFICATIONS + 1; i++) {
      pushNotification({ kind: 'sync-success', title: `entry-${i}`, message: '' });
    }

    expect(notificationCenter.items).toHaveLength(MAX_NOTIFICATIONS);
    const titles = notificationCenter.items.map((e) => e.title);
    expect(titles).not.toContain('entry-0');
    expect(titles).toContain(`entry-${MAX_NOTIFICATIONS}`);
  });

  it('clear empties the tray (empty state)', () => {
    pushNotification({ kind: 'import-success', title: 'book', message: '' });
    clearNotifications();

    expect(notificationCenter.items).toHaveLength(0);
    expect(notificationCenter.unreadCount).toBe(0);
  });

  it('is local-only: no persistence, no network delivery', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}'));

    pushNotification({ kind: 'import-success', title: 'book', message: '' });
    pushNotification({ kind: 'sync-failure', title: 'Sync', message: 'Offline' });

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
  });
});
