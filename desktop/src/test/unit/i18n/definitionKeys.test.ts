import { describe, expect, it } from 'vitest';
import { messagesEn } from '$lib/shared/i18n/messages.en';
import { messagesEs } from '$lib/shared/i18n/messages.es';

// The definition feature's user-facing copy. Both locales must carry a real
// translation, and the mark must read as a WARNING rather than a label: it is
// the only thing telling the reader a gloss was machine-translated, and
// "translated" alone would not communicate the uncertainty.
const definitionKeys = [
  'reader.define',
  'reader.defining',
  'reader.definitionNoResult',
  'reader.definitionUnavailable',
  'reader.definitionTranslated',
  'reader.dismissDefinition',
] as const;

describe('definition i18n keys', () => {
  it('exposes every definition key in both locales as a real translation', () => {
    for (const key of definitionKeys) {
      expect(messagesEn[key], `missing en: ${key}`).toBeDefined();
      expect(messagesEs[key], `missing es: ${key}`).toBeDefined();
      expect(messagesEs[key], `es is not translated: ${key}`).not.toBe(messagesEn[key]);
    }
  });

  it('keeps the copy free of engineering strings', () => {
    const banned = ['Wiktionary', 'null', 'undefined', 'C+A'];
    for (const key of definitionKeys) {
      for (const literal of banned) {
        expect(messagesEn[key]).not.toContain(literal);
        expect(messagesEs[key]).not.toContain(literal);
      }
    }
  });

  it('names the machine translation as such, not merely as a translation', () => {
    // A reader who sees only "Translated" learns nothing about reliability.
    expect(messagesEn['reader.definitionTranslated'].toLowerCase()).toContain('machine');
    expect(messagesEs['reader.definitionTranslated'].toLowerCase().length).toBeGreaterThan(0);
  });
});
