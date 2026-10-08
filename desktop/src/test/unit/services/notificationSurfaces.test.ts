/**
 * Surface routing tests (NOTIF-03): one accepted event produces exactly the
 * surfaces its policy resolution allows — toast iff `delivery.toast`, OS
 * iff `delivery.os` — and never the same surface twice.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  notify,
  clearNotifications,
  setNotificationPort,
  notificationCenter,
} from '$lib/shared/stores/notificationCenter.svelte';
import {
  dispatchSurfaces,
  resetNotificationSurfacesForTests,
  setToastSink,
  type ToastSink,
} from '$lib/shared/services/notificationSurfaces';
import {
  resetNotificationOsForTests,
  setOsNotificationSink,
} from '$lib/shared/services/notificationOs';
import { MockNotificationAdapter } from '$lib/shared/ports/adapters/mock/MockNotificationAdapter';
import {
  resetNotificationPreferencesForTests,
  updateNotificationPreferences,
  setNotificationPreferencesPort,
} from '$lib/shared/services/notificationPreferences';
import { MockSettingsAdapter } from '$lib/shared/ports/adapters/mock/MockSettingsAdapter';
import type { ToastType } from '$lib/shared/stores/ToastQueue.svelte';
import { dismiss, toastQueue } from '$lib/shared/stores/ToastQueue.svelte';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('notification surface routing', () => {
  const toasts: Array<{ type: ToastType; message: string }> = [];
  const osCalls: Array<{ title: string; body: string }> = [];
  const toastSink: ToastSink = {
    push: (type, message) => {
      toasts.push({ type, message });
    },
  };

  beforeEach(() => {
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
    resetNotificationSurfacesForTests();
    resetNotificationOsForTests();
    resetNotificationPreferencesForTests();
    setNotificationPreferencesPort(new MockSettingsAdapter());
    toasts.length = 0;
    osCalls.length = 0;
    setToastSink(toastSink);
    setOsNotificationSink({
      send: (title, body) => {
        osCalls.push({ title, body });
      },
    });
  });

  it('shows one toast for in-app interruption and no OS while focused', async () => {
    const { notification, resolution } = notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.kind.syncFailure',
        i18nParams: { detail: 'Offline' },
      },
      { isWindowFocused: true, isReading: false },
    );

    expect(notification).not.toBeNull();
    expect(resolution.delivery).toEqual({ tray: true, toast: true, os: false });
    await flush();

    expect(toasts).toHaveLength(1);
    expect(toasts[0].type).toBe('error');
    expect(osCalls).toHaveLength(0);
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('shows no toast for silent interruption (tray only)', async () => {
    const { notification, resolution } = notify(
      { source: 'sync', severity: 'success', i18nKey: 'notifications.kind.syncSuccess' },
      { isWindowFocused: true, isReading: false },
    );

    expect(notification).not.toBeNull();
    expect(resolution.delivery.toast).toBe(false);
    await flush();

    expect(toasts).toHaveLength(0);
    expect(osCalls).toHaveLength(0);
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('sends OS only for system interruption while unfocused, plus toast', async () => {
    const { notification, resolution } = notify(
      {
        source: 'sync',
        severity: 'error',
        interruption: 'system',
        i18nKey: 'notifications.kind.syncFailure',
        i18nParams: { detail: 'Offline' },
      },
      { isWindowFocused: false, isReading: false },
    );

    expect(notification).not.toBeNull();
    expect(resolution.delivery).toEqual({ tray: true, toast: true, os: true });
    await flush();

    expect(toasts).toHaveLength(1);
    expect(osCalls).toHaveLength(1);
  });

  it('sends no OS while focused even for system interruption', async () => {
    const { resolution } = notify(
      {
        source: 'sync',
        severity: 'error',
        interruption: 'system',
        i18nKey: 'notifications.kind.syncFailure',
      },
      { isWindowFocused: true, isReading: false },
    );

    expect(resolution.delivery).toEqual({ tray: true, toast: true, os: false });
    await flush();

    expect(toasts).toHaveLength(1);
    expect(osCalls).toHaveLength(0);
  });

  it('stays totally silent while reading: tray records, no toast, no OS', async () => {
    const { notification, resolution } = notify(
      {
        source: 'sync',
        severity: 'error',
        interruption: 'system',
        i18nKey: 'notifications.kind.syncFailure',
      },
      { isWindowFocused: false, isReading: true },
    );

    expect(notification).not.toBeNull();
    expect(resolution.delivery).toEqual({ tray: true, toast: false, os: false });
    await flush();

    expect(toasts).toHaveLength(0);
    expect(osCalls).toHaveLength(0);
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('sends OS for a nudge when its category switch is on (NOTIF-05 unlock)', async () => {
    const { notification, resolution } = notify(
      {
        source: 'streak',
        severity: 'warning',
        i18nKey: 'notifications.kind.syncFailure',
        dedupKey: 'streak:at-risk',
      },
      { isWindowFocused: false, isReading: false },
    );

    expect(notification).not.toBeNull();
    expect(resolution.category).toBe('nudge');
    expect(resolution.delivery.os).toBe(true);
    await flush();

    expect(osCalls).toHaveLength(1);
    expect(toasts).toHaveLength(1);
  });

  it('stops nudge toast and OS when nudges are off, but the tray still records', async () => {
    await updateNotificationPreferences({ nudge: false });
    const { notification, resolution } = notify(
      {
        source: 'streak',
        severity: 'warning',
        i18nKey: 'notifications.kind.syncFailure',
        dedupKey: 'streak:at-risk-off',
      },
      { isWindowFocused: false, isReading: false },
    );

    expect(notification).not.toBeNull();
    expect(resolution.delivery).toEqual({ tray: true, toast: false, os: false });
    await flush();

    expect(osCalls).toHaveLength(0);
    expect(toasts).toHaveLength(0);
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('never dispatches any surface for a rejected (duplicate) event', async () => {
    const draft = {
      source: 'sync' as const,
      severity: 'error' as const,
      i18nKey: 'notifications.kind.syncFailure' as const,
      dedupKey: 'routing:dup',
    };
    const context = { isWindowFocused: true, isReading: false };

    expect(notify(draft, context).notification).not.toBeNull();
    const second = notify(draft, context);

    expect(second.notification).toBeNull();
    expect(second.resolution.accepted).toBe(false);
    await flush();

    expect(toasts).toHaveLength(1);
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('routes through the real ToastQueue when no test sink is set', async () => {
    setToastSink(null);
    const before = toastQueue.items.length;

    const { notification } = notify(
      {
        source: 'import',
        severity: 'error',
        i18nKey: 'notifications.kind.importFailure',
        i18nParams: { name: 'broken-book', detail: 'Unsupported format' },
      },
      { isWindowFocused: true, isReading: false },
    );

    expect(notification).not.toBeNull();
    expect(toastQueue.items.length).toBe(before + 1);
    expect(toastQueue.items[toastQueue.items.length - 1].type).toBe('error');
    expect(notificationCenter.items).toHaveLength(1);
    dismiss(toastQueue.items[toastQueue.items.length - 1].id);
    expect(toastQueue.items.length).toBe(before);
    await flush();
    expect(osCalls).toHaveLength(0);
  });

  it('delivers each surface exactly once even when dispatch is re-entered', async () => {
    const { notification, resolution } = notify(
      {
        source: 'sync',
        severity: 'error',
        interruption: 'system',
        i18nKey: 'notifications.kind.syncFailure',
      },
      { isWindowFocused: false, isReading: false },
    );

    expect(notification).not.toBeNull();
    dispatchSurfaces(notification!, resolution);
    dispatchSurfaces(notification!, resolution);
    await flush();

    expect(toasts).toHaveLength(1);
    expect(osCalls).toHaveLength(1);
  });
});

describe('notification toast deep links (NOTIF-04)', () => {
  beforeEach(() => {
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
    resetNotificationSurfacesForTests();
    resetNotificationOsForTests();
    setToastSink(null);
    for (const item of [...toastQueue.items]) dismiss(item.id);
  });

  function lastToast(): (typeof toastQueue.items)[number] {
    const items = toastQueue.items;
    expect(items.length).toBeGreaterThan(0);
    return items[items.length - 1];
  }

  it('carries a deep-link action when the target resolves to a real destination', () => {
    const { notification } = notify(
      {
        source: 'import',
        severity: 'error',
        i18nKey: 'notifications.kind.importFailure',
        i18nParams: { name: 'my-book', detail: 'Unsupported format' },
        target: { kind: 'book', bookId: 'book-1' },
      },
      { isWindowFocused: true, isReading: false },
    );

    expect(notification).not.toBeNull();
    const toast = lastToast();
    expect(toast.action?.notificationId).toBe(notification!.id);
    expect(toast.action?.target).toEqual({ kind: 'book', bookId: 'book-1' });
    expect(typeof toast.action?.label).toBe('string');
    expect(toast.action!.label.length).toBeGreaterThan(0);
    dismiss(toast.id);
  });

  it('carries no action when there is no target', () => {
    notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.kind.syncFailure',
        i18nParams: { detail: 'Offline' },
      },
      { isWindowFocused: true, isReading: false },
    );

    expect(lastToast().action).toBeUndefined();
    for (const item of [...toastQueue.items]) dismiss(item.id);
  });

  it('carries no action for an unknown target (tray-only safe degrade)', () => {
    notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.kind.syncFailure',
        target: { kind: 'route', route: 'reader' as 'library' },
      },
      { isWindowFocused: true, isReading: false },
    );

    expect(lastToast().action).toBeUndefined();
    for (const item of [...toastQueue.items]) dismiss(item.id);
  });
});
