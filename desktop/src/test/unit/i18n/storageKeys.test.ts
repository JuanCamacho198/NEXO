import { describe, expect, it } from 'vitest';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

// BUG-2/BUG-3: the storage surface used to push raw codes and English literals
// straight to the UI. Every key the simplified view renders must exist in both
// locales and be a real translation, never the English fallback.
const storageKeys = [
  'storage.usageSummary',
  'storage.freeSpace',
  'storage.freeingSpace',
  'storage.freedToast',
  'storage.breakdown.temporary',
  'storage.breakdown.bookFiles',
  'storage.advanced',
  'storage.loading',
  'storage.retry',
  'storage.permissionDenied',
  'storage.perBook.title',
  'storage.perBook.count',
  'storage.perBook.remove',
  'storage.perBook.removeConfirm',
  'storage.perBook.removedToast',
  'storage.perBook.refresh',
  'storage.perBook.empty',
  'storage.perBook.loading',
  'storage.confirm',
  'storage.cancel',
  'storage.drive.onDrive',
] as const;

describe('storage simplification i18n keys', () => {
  it('exposes every new storage key in both locales as a real translation', () => {
    for (const key of storageKeys) {
      expect(messagesEn[key]).toBeDefined();
      expect(messagesEs[key]).toBeDefined();
      expect(messagesEs[key]).not.toBe(messagesEn[key]);
    }
  });

  it('keeps the interpolated placeholders the view relies on', () => {
    expect(messagesEn['storage.usageSummary']).toContain('{{size}}');
    expect(messagesEs['storage.usageSummary']).toContain('{{size}}');
    expect(messagesEn['storage.freedToast']).toContain('{{size}}');
    expect(messagesEn['storage.drive.onDrive']).toContain('{{size}}');
    expect(messagesEn['storage.perBook.count']).toContain('{{count}}');
    expect(messagesEn['storage.perBook.removeConfirm']).toContain('{{title}}');
  });
});
