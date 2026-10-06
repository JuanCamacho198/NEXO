/**
 * Tray behavior tests (NOTIF-04 deep links; NOTIF-06 i18n at render,
 * grouping, keyboard operability and empty state).
 *
 * Rows render from `i18nKey` + `i18nParams` at render time: the same stored
 * rows re-render in another language without touching the store. Repeats
 * collapse into a presentational group with a count and expand back to the
 * stored rows; activating one row marks exactly that row read.
 */
import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { tick } from 'svelte';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import NotificationCenter from '$lib/shared/ui/feedback/NotificationCenter.svelte';
import {
  notificationCenter,
  notify,
  clearNotifications,
  setNotificationPort,
} from '$lib/shared/stores/notificationCenter.svelte';
import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
import { MockNotificationAdapter } from '$lib/shared/ports/adapters/mock/MockNotificationAdapter';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

const t = (key: string): string => key;

/** Translator over the real dictionaries with a switchable locale. */
function dictT(locale: 'es' | 'en') {
  const dict = (locale === 'es' ? messagesEs : messagesEn) as Record<string, string>;
  return (key: string, params?: Record<string, string | number>): string => {
    let template = dict[key] ?? key;
    if (params) {
      template = template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, name: string) =>
        params[name] === undefined ? '' : String(params[name]),
      );
    }
    return template;
  };
}

/** Reading-silenced context: the tray records, no toast leaks into the queue. */
const readingContext = { isWindowFocused: true, isReading: true };

async function renderCenter(translate: unknown = t): Promise<{ unmount: () => void }> {
  const view = render(NotificationCenter, { props: { open: true, t: translate as never } });
  await tick();
  return view;
}

function itemButton(title: string): HTMLButtonElement {
  const button = screen.getByText(title).closest('button');
  expect(button).not.toBeNull();
  return button as HTMLButtonElement;
}

/**
 * Lets stale bits-ui dialog layers finish teardown. Unmounted dialogs leave
 * document-level handlers alive for a macrotask; without this a later test's
 * keydown is hijacked back to a dead dialog root (observed: focus yanked to
 * `role=dialog` on Enter/Space, so keyboard activation never reaches the
 * row). Mirrors the `settle` helper in Modal.test.ts.
 */
function settleBitsUi(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 20));
}

function emitSyncFailure(detail: string, withTarget: boolean = true): void {
  notify(
    {
      source: 'sync',
      severity: 'error',
      i18nKey: 'notifications.kind.syncFailure',
      i18nParams: { detail },
      ...(withTarget ? { target: { kind: 'route', route: 'sync' as const } } : {}),
    },
    readingContext,
  );
}

describe('NotificationCenter deep links', () => {
  let routeSnapshot: { route: string; previewBookId: string | null; shelfDetails: string | null };

  beforeEach(() => {
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
    routeSnapshot = {
      route: navigationState.route,
      previewBookId: navigationState.previewBookId,
      shelfDetails: navigationState.shelfDetailsBookId,
    };
  });

  afterEach(async () => {
    navigationState.route = routeSnapshot.route as typeof navigationState.route;
    navigationState.previewBookId = routeSnapshot.previewBookId;
    navigationState.shelfDetailsBookId = routeSnapshot.shelfDetails;
    clearNotifications();
    cleanup();
    await settleBitsUi();
  });

  it('clicking a book notification marks it read and opens the book details', async () => {
    notify(
      {
        source: 'import',
        severity: 'error',
        i18nKey: 'notifications.kind.importFailure',
        i18nParams: { name: 'my-book', detail: 'Unsupported format' },
        target: { kind: 'book', bookId: 'book-7' },
      },
      readingContext,
    );
    expect(notificationCenter.unreadCount).toBe(1);

    await renderCenter();
    await fireEvent.click(itemButton('my-book'));

    expect(notificationCenter.unreadCount).toBe(0);
    expect(navigationState.route).toBe('library');
    expect(navigationState.shelfDetailsBookId).toBe('book-7');
  });

  it('an unknown target marks read but stays on the tray without navigating', async () => {
    notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.kind.syncFailure',
        i18nParams: { detail: 'Offline' },
        target: { kind: 'route', route: 'reader' as 'library' },
      },
      readingContext,
    );

    await renderCenter();
    await fireEvent.click(itemButton('Offline'));

    expect(notificationCenter.unreadCount).toBe(0);
    expect(navigationState.route).toBe(routeSnapshot.route);
    // The center stays open: the entry is still listed.
    expect(screen.getByText('Offline')).toBeInTheDocument();
  });
});

describe('NotificationCenter i18n at render (NOTIF-06)', () => {
  beforeEach(() => {
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
  });

  afterEach(async () => {
    clearNotifications();
    cleanup();
    await settleBitsUi();
  });

  it('re-renders the same stored rows when the UI language changes', async () => {
    notify(
      {
        source: 'sync',
        severity: 'error',
        i18nKey: 'notifications.auth.required',
      },
      readingContext,
    );
    notify(
      {
        source: 'import',
        severity: 'success',
        i18nKey: 'notifications.kind.importSuccess',
        i18nParams: { name: 'my-book' },
      },
      readingContext,
    );

    const spanish = await renderCenter(dictT('es'));
    expect(screen.getByText('Se requiere iniciar sesion para sincronizar')).toBeInTheDocument();
    expect(screen.getByText('my-book')).toBeInTheDocument();
    spanish.unmount();

    await renderCenter(dictT('en'));
    expect(screen.getByText('Sign-in required to sync')).toBeInTheDocument();
    expect(screen.getByText('my-book')).toBeInTheDocument();
    // The rows themselves never changed — only the render language did.
    expect(notificationCenter.items).toHaveLength(2);
  });
});

describe('NotificationCenter grouping (NOTIF-06)', () => {
  beforeEach(() => {
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
  });

  afterEach(async () => {
    clearNotifications();
    cleanup();
    await settleBitsUi();
  });

  it('collapses repeats with a count and expands back to the stored rows', async () => {
    emitSyncFailure('err-one');
    emitSyncFailure('err-two');
    emitSyncFailure('err-three');
    notify(
      {
        source: 'import',
        severity: 'success',
        i18nKey: 'notifications.kind.importSuccess',
        i18nParams: { name: 'solo-book' },
        target: { kind: 'route', route: 'library' },
      },
      readingContext,
    );

    await renderCenter(dictT('en'));

    const toggle = screen.getByRole('button', { name: 'Show 3 notifications' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText('×3')).toBeInTheDocument();
    expect(screen.queryByText('err-one')).not.toBeInTheDocument();
    // Singletons render flat, outside any group.
    expect(screen.getByText('solo-book')).toBeInTheDocument();

    await fireEvent.click(toggle);
    await tick();
    expect(screen.getByRole('button', { name: 'Hide grouped notifications' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(screen.getByText('err-one')).toBeInTheDocument();
    expect(screen.getByText('err-two')).toBeInTheDocument();
    expect(screen.getByText('err-three')).toBeInTheDocument();

    await fireEvent.click(screen.getByRole('button', { name: 'Hide grouped notifications' }));
    await tick();
    expect(screen.queryByText('err-one')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show 3 notifications' })).toBeInTheDocument();
  });

  it('marks one grouped row read without merging the stored rows', async () => {
    emitSyncFailure('err-one', false);
    emitSyncFailure('err-two', false);
    expect(notificationCenter.unreadCount).toBe(2);

    await renderCenter(dictT('en'));
    await fireEvent.click(screen.getByRole('button', { name: 'Show 2 notifications' }));
    await tick();

    await fireEvent.click(itemButton('err-one'));
    await tick();

    expect(notificationCenter.unreadCount).toBe(1);
    // Grouping is presentational: both stored rows are still listed.
    expect(screen.getByText('err-one')).toBeInTheDocument();
    expect(screen.getByText('err-two')).toBeInTheDocument();
    expect(screen.getByText('×2')).toBeInTheDocument();
  });
});

describe('NotificationCenter keyboard and empty state (NOTIF-06)', () => {
  beforeEach(() => {
    setNotificationPort(new MockNotificationAdapter());
    clearNotifications();
  });

  afterEach(async () => {
    clearNotifications();
    cleanup();
    await settleBitsUi();
  });

  it('keeps a kind empty state when there is nothing to show', async () => {
    await renderCenter();

    expect(screen.getByText('notifications.center.emptyTitle')).toBeInTheDocument();
    expect(screen.getByText('notifications.center.emptyDescription')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('makes every row and group toggle keyboard-focusable', async () => {
    emitSyncFailure('err-one', false);
    emitSyncFailure('err-two', false);

    await renderCenter(dictT('en'));
    await fireEvent.click(screen.getByRole('button', { name: 'Show 2 notifications' }));
    await tick();

    for (const button of screen.getAllByRole('button')) {
      expect((button as HTMLElement).tabIndex).toBeGreaterThanOrEqual(0);
    }
    const row = itemButton('err-one');
    row.focus();
    expect(document.activeElement).toBe(row);
  });

  it('activates the focused row with Enter and marks it read', async () => {
    const user = userEvent.setup();
    emitSyncFailure('kb-err', false);

    await renderCenter(dictT('en'));
    const row = itemButton('kb-err');
    row.focus();
    expect(document.activeElement).toBe(row);

    await user.keyboard('{Enter}');
    await tick();

    expect(notificationCenter.unreadCount).toBe(0);
  });

  it('closes on Escape and returns focus to the opener', async () => {
    const opener = document.createElement('button');
    opener.textContent = 'opener';
    document.body.appendChild(opener);
    opener.focus();

    await renderCenter(dictT('en'));
    // Keyboard close starts with focus inside the dialog, as it would be
    // after Tab-navigating the list (bits-ui autofocus is async in jsdom, and
    // the facade's own close label comes from its internal translator).
    const dialog = screen.getByRole('dialog');
    (dialog.querySelector('button') as HTMLElement).focus();
    expect(document.activeElement).not.toBe(opener);
    expect(dialog.contains(document.activeElement)).toBe(true);

    await fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(opener));
    opener.remove();
  });
});
