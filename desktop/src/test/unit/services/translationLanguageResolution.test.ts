/**
 * Translation language preference resolution.
 *
 * User preferences are optional on both sides: a null source means "infer it
 * from the book", a null target means "use the app locale". The resolver turns
 * that into a concrete pair or a typed refusal.
 *
 * The refusal exists because of a real failure: an English book read with the
 * app in English used to ask the service for `en -> en`, which answers
 * `403 PLEASE SELECT TWO DISTINCT LANGUAGES`. The user saw "no translation"
 * with no explanation. The pair must never be degenerate.
 */
import { describe, expect, it } from 'vitest';
import {
  resolveLanguagePreferencePair,
  type TranslationLanguagePreferences,
} from '$lib/shared/services/translation/translationLanguageResolution';

const prefs = (
  sourceLanguage: string | null = null,
  targetLanguage: string | null = null,
): TranslationLanguagePreferences => ({ sourceLanguage, targetLanguage });

describe('resolveLanguagePreferencePair', () => {
  it('uses an explicit source and an explicit target as the pair', () => {
    expect(resolveLanguagePreferencePair(prefs('es', 'en'), 'fr', 'de')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
    });
  });

  it('falls back to the app locale when only the target preference is missing', () => {
    expect(resolveLanguagePreferencePair(prefs('fr', null), 'en', 'es')).toEqual({
      kind: 'translate',
      source: 'fr',
      target: 'es',
    });
  });

  it('infers the source from the book when only the source preference is missing', () => {
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), 'en', 'en')).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
    });
  });

  it('infers both sides from the book language and the app locale', () => {
    expect(resolveLanguagePreferencePair(prefs(null, null), 'en', 'es')).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
    });
  });

  it('refuses to guess the source when the book has no language', () => {
    // The old fallback used the target as the source, which is exactly the
    // `en -> en` pair the service rejects with a 403. Refusing is honest.
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), null, null)).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'es',
    });
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), undefined, 'en')).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'es',
    });
  });

  it('refuses when source and target resolve to the same language', () => {
    // This is THE failure that motivated the feature.
    expect(resolveLanguagePreferencePair(prefs(null, null), 'en', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
    });
    expect(resolveLanguagePreferencePair(prefs('en', 'en'), 'en', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
    });
    expect(resolveLanguagePreferencePair(prefs('zh-Hans-CN', 'zh-TW'), null, 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'zh',
      target: 'zh',
    });
  });

  it('normalises both sides before comparing them', () => {
    expect(resolveLanguagePreferencePair(prefs('pt-BR', 'EN'), null, 'en')).toEqual({
      kind: 'translate',
      source: 'pt',
      target: 'en',
    });
  });

  it('treats regional variants of one base language as the same language', () => {
    // `en-US -> en-GB` technically translates, but the reader asked for a
    // translation and would get back what they already read. The service is
    // also unreliable for same-base pairs, so it is refused up front.
    expect(resolveLanguagePreferencePair(prefs('en-US', 'en-GB'), null, 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
    });
    expect(resolveLanguagePreferencePair(prefs('es-419', 'es-ES'), null, 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
    });
  });

  it('refuses when the preference is unusable rather than treating it as inferred', () => {
    // An empty or malformed preference is a user error, not "infer me".
    expect(resolveLanguagePreferencePair(prefs('  ', 'en'), 'es', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'invalid-source',
      source: null,
      target: 'en',
    });
    expect(resolveLanguagePreferencePair(prefs('es', '1'), 'es', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'invalid-target',
      source: 'es',
      target: null,
    });
  });

  it('falls back to English when the app locale is unusable', () => {
    expect(resolveLanguagePreferencePair(prefs('fr', null), null, '')).toEqual({
      kind: 'translate',
      source: 'fr',
      target: 'en',
    });
  });

  it('prefers the explicit target over the book language for the target side', () => {
    // A book in Spanish, app in English, user asked for German: the book's
    // language must never leak into the target.
    expect(resolveLanguagePreferencePair(prefs(null, 'de'), 'es', 'en')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'de',
    });
  });

  it('accepts an empty preferences object as "infer everything"', () => {
    expect(resolveLanguagePreferencePair({}, 'it', 'fr')).toEqual({
      kind: 'translate',
      source: 'it',
      target: 'fr',
    });
  });
});
