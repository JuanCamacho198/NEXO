/**
 * Arrival announcement tests (NOTIF-06).
 *
 * The tray dialog only exists while open, so an always-mounted polite live
 * region announces each new arrival once. History that predates the mount and
 * read-state changes (opening the tray marks everything read) stay silent.
 */
import { render, screen, cleanup } from '@testing-library/svelte';
import { tick } from 'svelte';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import NotificationArrivalAnnouncer from '$lib/shared/ui/feedback/NotificationArrivalAnnouncer.svelte';
import {
  notify,
  markAllRead,
  clearNotifications,
  setNotificationPort,
} from '$lib/shared/stores/notificationCenter.svelte';
import { MockNotificationAdapter } from '$lib/shared/ports/adapters/mock/MockNotificationAdapter';

const t = (key: string, params?: Record<string, string | number>): string => {
  if (key === 'notifications.center.newArrival') return `ANNOUNCE:${params?.message ?? ''}`;
  return key;
};

const readingContext = { isWindowFocused: true, isReading: true };

function liveRegion(): HTMLElement {
  const region = screen.getByRole('status');
  expect(region).toHaveAttribute('aria-live', 'polite');
  return region;
}

describe('NotificationArrivalAnnouncer', () => {
  beforeEach(() => {
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
  });

  afterEach(() => {
    clearNotifications();
    cleanup();
  });

  it('announces a new arrival rendered from its key', async () => {
    render(NotificationArrivalAnnouncer, { props: { t: t as never } });
    await tick();
    expect(liveRegion().textContent).toBe('');

    notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.kind.syncFailure',
        i18nParams: { detail: 'Offline' },
      },
      readingContext,
    );
    await tick();

    expect(liveRegion().textContent).toContain('ANNOUNCE:notifications.kind.syncFailure');
  });

  it('stays silent for history that predates the mount', async () => {
    notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.kind.syncFailure',
        i18nParams: { detail: 'Offline' },
      },
      readingContext,
    );

    render(NotificationArrivalAnnouncer, { props: { t: t as never } });
    await tick();

    expect(liveRegion().textContent).toBe('');
  });

  it('does not announce read-state changes', async () => {
    render(NotificationArrivalAnnouncer, { props: { t: t as never } });
    await tick();

    notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.kind.syncFailure',
        i18nParams: { detail: 'Offline' },
      },
      readingContext,
    );
    await tick();
    const announced = liveRegion().textContent;
    expect(announced).toContain('ANNOUNCE:');

    markAllRead();
    await tick();

    expect(liveRegion().textContent).toBe(announced);
  });
});
