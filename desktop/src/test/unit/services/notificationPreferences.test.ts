/**
 * NOTIF-05 tests: per-category preferences + quiet hours.
 *
 * Covers the preference model (defaults, sanitizing unknown storage),
 * quiet-hours math (daytime, overnight, degenerate, malformed), persistence
 * across restart at the port level, and the notify-level contract: a disabled
 * category or active quiet hours silence toast/OS while the tray still
 * records, and the nudge daily cap still applies on top of preferences.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  DEFAULT_NOTIFICATION_PREFERENCES,
  getCachedNotificationPreferences,
  isQuietHoursActive,
  loadNotificationPreferences,
  parseNotificationPreferences,
  resetNotificationPreferencesForTests,
  setNotificationPreferencesPort,
  updateNotificationPreferences,
  type NotificationPreferences,
} from '$lib/shared/services/notificationPreferences';
import {
  clearNotifications,
  notify,
  notificationCenter,
  setNotificationPort,
} from '$lib/shared/stores/notificationCenter.svelte';
import {
  resetNotificationSurfacesForTests,
  setToastSink,
  type ToastSink,
} from '$lib/shared/services/notificationSurfaces';
import {
  resetNotificationOsForTests,
  setOsNotificationSink,
} from '$lib/shared/services/notificationOs';
import { MockNotificationAdapter } from '$lib/shared/ports/adapters/mock/MockNotificationAdapter';
import { MockSettingsAdapter } from '$lib/shared/ports/adapters/mock/MockSettingsAdapter';
import type { ToastType } from '$lib/shared/stores/ToastQueue.svelte';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('notificationPreferences — model', () => {
  it('defaults to every category enabled and quiet hours off', () => {
    expect(DEFAULT_NOTIFICATION_PREFERENCES).toEqual({
      system: true,
      nudge: true,
      quietHours: { enabled: false, start: '22:00', end: '07:00' },
    });
  });

  it('sanitizes unknown storage content back to valid defaults', () => {
    expect(parseNotificationPreferences(null)).toEqual(DEFAULT_NOTIFICATION_PREFERENCES);
    expect(parseNotificationPreferences('nope')).toEqual(DEFAULT_NOTIFICATION_PREFERENCES);
    expect(parseNotificationPreferences({ system: 'yes', nudge: 0 })).toEqual(
      DEFAULT_NOTIFICATION_PREFERENCES,
    );
    const partial = parseNotificationPreferences({ nudge: false });
    expect(partial).toEqual({ ...DEFAULT_NOTIFICATION_PREFERENCES, nudge: false });
  });
});

describe('notificationPreferences — quiet hours math', () => {
  // Fixed clock: 2026-10-05T12:00 local construction avoids TZ flakiness by
  // deriving the windows from the same local clock the helper reads.
  function clockAround(hour: number, minute: number): number {
    const at = new Date(2026, 9, 5, hour, minute, 0, 0);
    return at.getTime();
  }

  const prefsWith = (start: string, end: string): NotificationPreferences => ({
    system: true,
    nudge: true,
    quietHours: { enabled: true, start, end },
  });

  it('matches inside a daytime window and not outside it', () => {
    const prefs = prefsWith('09:00', '17:00');
    expect(isQuietHoursActive(prefs, clockAround(12, 0))).toBe(true);
    expect(isQuietHoursActive(prefs, clockAround(8, 59))).toBe(false);
    expect(isQuietHoursActive(prefs, clockAround(17, 0))).toBe(false);
  });

  it('wraps overnight ranges past midnight', () => {
    const prefs = prefsWith('22:00', '07:00');
    expect(isQuietHoursActive(prefs, clockAround(23, 30))).toBe(true);
    expect(isQuietHoursActive(prefs, clockAround(3, 0))).toBe(true);
    expect(isQuietHoursActive(prefs, clockAround(12, 0))).toBe(false);
  });

  it('never silences when disabled, degenerate or malformed', () => {
    expect(
      isQuietHoursActive(
        {
          ...prefsWith('09:00', '17:00'),
          quietHours: { enabled: false, start: '09:00', end: '17:00' },
        },
        clockAround(12, 0),
      ),
    ).toBe(false);
    expect(isQuietHoursActive(prefsWith('09:00', '09:00'), clockAround(9, 0))).toBe(false);
    expect(isQuietHoursActive(prefsWith('nope', '17:00'), clockAround(12, 0))).toBe(false);
  });
});

describe('notificationPreferences — persistence and notify integration', () => {
  const toasts: Array<{ type: ToastType; message: string }> = [];
  const osCalls: Array<{ title: string; body: string }> = [];
  const toastSink: ToastSink = {
    push: (type, message) => {
      toasts.push({ type, message });
    },
  };
  let settings: MockSettingsAdapter;

  beforeEach(() => {
    settings = new MockSettingsAdapter();
    resetNotificationPreferencesForTests();
    setNotificationPreferencesPort(settings);
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
    resetNotificationSurfacesForTests();
    resetNotificationOsForTests();
    toasts.length = 0;
    osCalls.length = 0;
    setToastSink(toastSink);
    setOsNotificationSink({
      send: (title, body) => {
        osCalls.push({ title, body });
      },
    });
  });

  it('persists preferences across restart at the port level', async () => {
    await updateNotificationPreferences({
      nudge: false,
      quietHours: { enabled: true, start: '23:00', end: '06:00' },
    });

    // A restart drops the in-memory cache; loading must restore the stored shape.
    resetNotificationPreferencesForTests();
    setNotificationPreferencesPort(settings);
    const restored = await loadNotificationPreferences();

    expect(restored).toEqual({
      system: true,
      nudge: false,
      quietHours: { enabled: true, start: '23:00', end: '06:00' },
    });
    expect(getCachedNotificationPreferences()).toEqual(restored);
  });

  it('disabled system category: tray records, no toast, no OS', async () => {
    await updateNotificationPreferences({ system: false });
    const { notification, resolution } = notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.kind.syncFailure',
        i18nParams: { detail: 'Offline' },
      },
      { isWindowFocused: false, isReading: false },
    );

    expect(notification).not.toBeNull();
    expect(resolution.delivery).toEqual({ tray: true, toast: false, os: false });
    await flush();

    expect(toasts).toHaveLength(0);
    expect(osCalls).toHaveLength(0);
    expect(notificationCenter.items).toHaveLength(1);
    expect(notificationCenter.unreadCount).toBe(1);
  });

  it('re-enabling restores toast and OS for the category', async () => {
    await updateNotificationPreferences({ system: false });
    await updateNotificationPreferences({ system: true });
    const { resolution } = notify(
      {
        source: 'sync',
        severity: 'error',
        interruption: 'system',
        i18nKey: 'notifications.kind.syncFailure',
      },
      { isWindowFocused: false, isReading: false },
    );

    expect(resolution.delivery).toEqual({ tray: true, toast: true, os: true });
  });

  it('quiet hours silence toast and OS through notify but keep the tray', async () => {
    const at = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    const clock = (d: Date): string => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
    const start = new Date(at.getTime() - 30 * 60 * 1000);
    const end = new Date(at.getTime() + 30 * 60 * 1000);
    await updateNotificationPreferences({
      quietHours: { enabled: true, start: clock(start), end: clock(end) },
    });

    const { notification, resolution } = notify(
      {
        source: 'goal',
        severity: 'warning',
        i18nKey: 'notifications.kind.syncFailure',
        dedupKey: 'goal:quiet-hours',
      },
      { isWindowFocused: false, isReading: false },
    );

    expect(notification).not.toBeNull();
    expect(resolution.delivery).toEqual({ tray: true, toast: false, os: false });
    await flush();

    expect(toasts).toHaveLength(0);
    expect(osCalls).toHaveLength(0);
    expect(notificationCenter.items).toHaveLength(1);
  });

  it('nudge daily cap still applies on top of preferences', async () => {
    const draft = {
      source: 'streak' as const,
      severity: 'warning' as const,
      i18nKey: 'notifications.kind.syncFailure' as const,
      dedupKey: 'streak:capped-with-prefs',
    };
    const context = { isWindowFocused: false, isReading: false };

    const first = notify(draft, context);
    expect(first.notification).not.toBeNull();
    expect(first.resolution.delivery.os).toBe(true);

    const second = notify(draft, context);
    expect(second.notification).toBeNull();
    expect(second.resolution.reason).toBe('duplicate');
  });

  it('uses defaults before any load, so the emit path never blocks on I/O', () => {
    expect(getCachedNotificationPreferences()).toEqual(DEFAULT_NOTIFICATION_PREFERENCES);
    const { resolution } = notify(
      { source: 'import', severity: 'success', i18nKey: 'notifications.kind.importSuccess' },
      { isWindowFocused: true, isReading: false },
    );
    expect(resolution.accepted).toBe(true);
    expect(resolution.delivery.tray).toBe(true);
  });
});
