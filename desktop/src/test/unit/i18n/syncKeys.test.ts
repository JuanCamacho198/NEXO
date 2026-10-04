import { describe, expect, it } from 'vitest';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

// The simplified sync surface must render only translated copy, never raw
// engineering strings. Every key the view relies on must exist in both locales
// and be a real translation, never the English fallback.
const syncKeys = [
  'sync.status.syncing',
  'sync.status.failed',
  'sync.status.pending',
  'sync.status.upToDate',
  'sync.status.upToDateAt',
  'sync.relative.now',
  'sync.relative.minutes',
  'sync.relative.hours',
  'sync.relative.days',
  'sync.advanced.title',
  'sync.scope.title',
  'sync.scope.hint',
  'sync.scope.group.annotations',
  'sync.scope.progress',
  'sync.scope.bookmarks',
  'sync.scope.highlights',
  'sync.scope.sessions',
  'sync.scope.catalog',
  'sync.scope.dictionary',
  'sync.raw.title',
  'sync.raw.lastSync',
  'sync.raw.pending',
  'sync.raw.realtime',
  'sync.raw.lastError',
  'sync.raw.never',
  'sync.raw.none',
] as const;

describe('sync simplification i18n keys', () => {
  it('exposes every new sync key in both locales as a real translation', () => {
    for (const key of syncKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
      expect(messagesEs[key]).not.toBe(messagesEn[key]);
    }
  });

  it('keeps the interpolation placeholder the status line relies on', () => {
    expect(messagesEn['sync.status.upToDateAt']).toContain('{{when}}');
    expect(messagesEs['sync.status.upToDateAt']).toContain('{{when}}');
    expect(messagesEn['sync.relative.minutes']).toContain('{{count}}');
    expect(messagesEs['sync.relative.minutes']).toContain('{{count}}');
  });

  it('keeps the English hardcoded literals out of the sync copy', () => {
    const banned = ['Sync Health', 'outbox depth', 'Keep local', 'Keep remote', 'Sync scopes'];
    for (const key of syncKeys) {
      for (const literal of banned) {
        expect(messagesEn[key]).not.toContain(literal);
        expect(messagesEs[key]).not.toContain(literal);
      }
    }
  });
});
