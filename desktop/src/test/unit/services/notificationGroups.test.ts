/**
 * Unit tests for the presentational tray grouping (NOTIF-06).
 *
 * Grouping collapses stored rows sharing one `source` + `i18nKey` so repeated
 * events read as one; it never merges rows — every entry keeps its identity,
 * read state and target.
 */
import { describe, it, expect } from 'vitest';
import { groupNotifications } from '$lib/shared/services/notificationGroups';
import type { Notification } from '$lib/shared/types/notification';

function row(partial: Partial<Notification> & { id: string }): Notification {
  return {
    createdAt: 1,
    source: 'sync',
    category: 'system',
    severity: 'error',
    interruption: 'in-app',
    i18nKey: 'notifications.kind.syncFailure',
    readAt: null,
    ...partial,
  };
}

describe('groupNotifications', () => {
  it('returns one group per entry when nothing repeats', () => {
    const groups = groupNotifications([
      row({ id: 'a', i18nKey: 'notifications.kind.syncSuccess', severity: 'success' }),
      row({ id: 'b', i18nKey: 'notifications.kind.syncFailure' }),
    ]);

    expect(groups).toHaveLength(2);
    expect(groups.map((g) => g.entries.map((e) => e.id))).toEqual([['b'], ['a']]);
  });

  it('collapses repeats on source + key with entries newest first', () => {
    const groups = groupNotifications([
      row({ id: 'old', createdAt: 1, i18nParams: { detail: 'first' } }),
      row({ id: 'mid', createdAt: 2, i18nParams: { detail: 'second' } }),
      row({ id: 'new', createdAt: 3, i18nParams: { detail: 'third' } }),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBe('sync::notifications.kind.syncFailure');
    expect(groups[0].entries.map((e) => e.id)).toEqual(['new', 'mid', 'old']);
  });

  it('keeps per-book failures as one group without merging rows', () => {
    const groups = groupNotifications([
      row({
        id: 'book-a',
        i18nKey: 'notifications.catalog.failed',
        target: { kind: 'book', bookId: 'a' },
      }),
      row({
        id: 'book-b',
        i18nKey: 'notifications.catalog.failed',
        target: { kind: 'book', bookId: 'b' },
      }),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].entries).toHaveLength(2);
    expect(groups[0].entries.map((e) => e.target)).toEqual([
      { kind: 'book', bookId: 'b' },
      { kind: 'book', bookId: 'a' },
    ]);
  });

  it('orders groups by latest activity first', () => {
    const groups = groupNotifications([
      row({ id: 'sync-old', createdAt: 1 }),
      row({
        id: 'import-new',
        createdAt: 5,
        source: 'import',
        i18nKey: 'notifications.kind.importSuccess',
        severity: 'success',
      }),
      row({ id: 'sync-new', createdAt: 9 }),
    ]);

    expect(groups.map((g) => g.key)).toEqual([
      'sync::notifications.kind.syncFailure',
      'import::notifications.kind.importSuccess',
    ]);
    expect(groups[0].entries.map((e) => e.id)).toEqual(['sync-new', 'sync-old']);
  });

  it('returns no groups for an empty history', () => {
    expect(groupNotifications([])).toEqual([]);
  });
});
