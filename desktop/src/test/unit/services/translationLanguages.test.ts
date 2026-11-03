/**
 * Translation language resolution.
 *
 * Source language is optional in `LibraryBookDto`, so the pair must still form
 * when the book is silent. Normalisation covers the real shapes seen in book
 * metadata (`en`, `en-US`, `pt_BR`, padded whitespace) — a wrong source code
 * makes MyMemory answer nonsense or nothing.
 */
import { describe, expect, it } from 'vitest';
import {
  normalizeLanguageTag,
  resolveTranslationLanguages,
} from '$lib/shared/services/translation/translationLanguages';
import { translationFailureOutcome } from '$lib/shared/services/translation/translationOutcome';

describe('normalizeLanguageTag', () => {
  it('keeps a bare two-letter code', () => {
    expect(normalizeLanguageTag('en')).toBe('en');
    expect(normalizeLanguageTag('ES')).toBe('es');
  });

  it('reduces a region tag to its primary subtag', () => {
    expect(normalizeLanguageTag('pt-BR')).toBe('pt');
    expect(normalizeLanguageTag('en_US')).toBe('en');
    expect(normalizeLanguageTag('zh-Hans-CN')).toBe('zh');
  });

  it('trims surrounding whitespace', () => {
    expect(normalizeLanguageTag('  fr  ')).toBe('fr');
  });

  it('returns null for missing, empty or unusable tags', () => {
    expect(normalizeLanguageTag(null)).toBeNull();
    expect(normalizeLanguageTag(undefined)).toBeNull();
    expect(normalizeLanguageTag('')).toBeNull();
    expect(normalizeLanguageTag('   ')).toBeNull();
    expect(normalizeLanguageTag('1')).toBeNull();
    expect(normalizeLanguageTag('e')).toBeNull();
  });
});

describe('resolveTranslationLanguages', () => {
  it('uses the book language as source and the UI locale as target', () => {
    expect(resolveTranslationLanguages('es', 'en')).toEqual({
      sourceLang: 'es',
      targetLang: 'en',
    });
  });

  it('falls back to the UI locale as source when the book has no language', () => {
    expect(resolveTranslationLanguages(undefined, 'en')).toEqual({
      sourceLang: 'en',
      targetLang: 'en',
    });
    expect(resolveTranslationLanguages(null, 'es')).toEqual({
      sourceLang: 'es',
      targetLang: 'es',
    });
  });

  it('normalises both sides of the pair', () => {
    expect(resolveTranslationLanguages('pt-BR', 'EN')).toEqual({
      sourceLang: 'pt',
      targetLang: 'en',
    });
  });

  it('falls back to English when the UI locale is unusable', () => {
    expect(resolveTranslationLanguages('fr', '')).toEqual({
      sourceLang: 'fr',
      targetLang: 'en',
    });
  });
});

describe('translationFailureOutcome', () => {
  const withCode = (code: string) => {
    const e = new Error('x') as Error & { commandError: { code: string } };
    e.commandError = { code };
    return e;
  };

  it('maps a no-usable-translation code to no-result', () => {
    expect(translationFailureOutcome(withCode('TRANSLATE_NO_TRANSLATION'))).toEqual({
      status: 'no-result',
    });
  });

  it('maps untranslatable input to no-result', () => {
    expect(translationFailureOutcome(withCode('TRANSLATE_UNTRANSLATABLE_INPUT'))).toEqual({
      status: 'no-result',
    });
  });

  it('maps network, quota and HTTP codes to unavailable', () => {
    expect(translationFailureOutcome(withCode('TRANSLATE_NETWORK'))).toEqual({
      status: 'unavailable',
    });
    expect(translationFailureOutcome(withCode('TRANSLATE_QUOTA_EXCEEDED'))).toEqual({
      status: 'unavailable',
    });
    expect(translationFailureOutcome(withCode('TRANSLATE_HTTP_500'))).toEqual({
      status: 'unavailable',
    });
  });

  it('treats a code-less error as unavailable, never as no-result', () => {
    // An unknown failure must not masquerade as a normal "no translation".
    expect(translationFailureOutcome(new Error('boom'))).toEqual({ status: 'unavailable' });
    expect(translationFailureOutcome(undefined)).toEqual({ status: 'unavailable' });
  });
});
