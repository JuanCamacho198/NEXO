import { describe, expect, it } from 'vitest';
import { BULK_IMPORT_STATUS, getBulkImportStatusKey } from '$lib/features/library/utils';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

describe('getBulkImportStatusKey', () => {
  // 'importing' pointed at a key that never existed (`status.ingesting`), so a
  // file being imported showed the raw key in the bulk import list.
  it('maps every status to a key both locales define', () => {
    for (const status of Object.values(BULK_IMPORT_STATUS)) {
      const key = getBulkImportStatusKey(status) as keyof typeof messagesEn;
      expect(messagesEn[key], `${status} → ${key}`).toBeDefined();
      expect(messagesEs[key as keyof typeof messagesEs], `${status} → ${key}`).toBeDefined();
    }
  });
});
