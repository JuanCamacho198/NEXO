import { describe, expect, it } from 'vitest';
import { messagesEn, type UiKey } from '@/i18n/ui.en';
import { messagesEs } from '@/i18n/ui.es';

describe('i18n dictionary parity', () => {
  it('ES and EN dictionaries expose the same key set', () => {
    const enKeys = Object.keys(messagesEn).sort() as UiKey[];
    const esKeys = Object.keys(messagesEs).sort() as UiKey[];
    expect(esKeys).toEqual(enKeys);
  });

  it('every ES string is non-empty', () => {
    for (const value of Object.values(messagesEs)) {
      expect(typeof value).toBe('string');
      expect(value.length).toBeGreaterThan(0);
    }
  });
});
