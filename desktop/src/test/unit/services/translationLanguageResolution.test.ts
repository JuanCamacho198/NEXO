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
 *
 * Every result also carries `sourceOrigin`, which says whether the source was
 * the reader's own choice (`preference`) or the app's inference (`book`). The UI
 * only labels an inferred language as automatic, so this value has to be right
 * even on the refusals.
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
      sourceOrigin: 'preference',
    });
  });

  it('falls back to the app locale when only the target preference is missing', () => {
    expect(resolveLanguagePreferencePair(prefs('fr', null), 'en', 'es')).toEqual({
      kind: 'translate',
      source: 'fr',
      target: 'es',
      sourceOrigin: 'preference',
    });
  });

  it('infers the source from the book when only the source preference is missing', () => {
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), 'en', 'en')).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
      sourceOrigin: 'book',
    });
  });

  it('infers both sides from the book language and the app locale', () => {
    expect(resolveLanguagePreferencePair(prefs(null, null), 'en', 'es')).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
      sourceOrigin: 'book',
    });
  });

  it('refuses to guess the source when the book has no language', () => {
    // The old fallback used the target as the source, which is exactly the
    // `en -> en` pair the service rejects with a 403. Refusing is honest.
    // No source resolved, so there is no provenance to report: null.
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), null, null)).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'es',
      sourceOrigin: null,
    });
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), undefined, 'en')).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'es',
      sourceOrigin: null,
    });
  });

  it('refuses when an explicit source and the target resolve to the same language', () => {
    // An explicit source is the reader's own word. They picked Spanish while the
    // app is in Spanish; translating their Spanish into English would answer a
    // question they did not ask. The refusal is the honest answer, and it must
    // survive the inferred-source fallback below.
    expect(resolveLanguagePreferencePair(prefs('es', 'es'), null, 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'preference',
    });
    expect(resolveLanguagePreferencePair(prefs('en', 'en'), 'en', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'preference',
    });
    expect(resolveLanguagePreferencePair(prefs('zh-Hans-CN', 'zh-TW'), null, 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'zh',
      target: 'zh',
      sourceOrigin: 'preference',
    });
  });

  it('falls back to English when an inferred source equals the target', () => {
    // The app put the pair together: the book says Spanish and the app is in
    // Spanish. That is not a decision the reader made, so correcting it to
    // English is a service — the reader asked for a translation and gets one.
    expect(resolveLanguagePreferencePair(prefs(null, null), 'es', 'es')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'book',
    });
    // A regional variant of the target is the same collision and is corrected
    // the same way, because the comparison is on the normalised base language.
    expect(resolveLanguagePreferencePair(prefs(null, null), 'es-419', 'es-ES')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('refuses an inferred English source against an English target', () => {
    // THE edge case. The source was inferred (`book`), and it collides with the
    // target — so the fallback applies — but the fallback is English and the
    // source is ALREADY English: `en -> en` is the same 403 trap, so it stays a
    // refusal. An English book read with the app in English is genuinely
    // degenerate.
    expect(resolveLanguagePreferencePair(prefs(null, null), 'en', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('never rewrites an explicit target that collides with an inferred source', () => {
    // The source is inferred, but the TARGET is a decision: the reader asked for
    // Spanish while the book is in Spanish. Silently shipping English would
    // ignore their choice, so the pair is refused instead.
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), 'es', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'book',
    });
    // Same when the collision is a regional variant of the explicit target.
    expect(resolveLanguagePreferencePair(prefs(null, 'es-ES'), 'es-419', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'book',
    });
  });

  it('normalises both sides before comparing them', () => {
    expect(resolveLanguagePreferencePair(prefs('pt-BR', 'EN'), null, 'en')).toEqual({
      kind: 'translate',
      source: 'pt',
      target: 'en',
      sourceOrigin: 'preference',
    });
  });

  it('treats regional variants of one base language chosen by the reader as the same language', () => {
    // `en-US -> en-GB` technically translates, but the reader asked for a
    // translation and would get back what they already read. The service is
    // also unreliable for same-base pairs, so it is refused up front. Both sides
    // are the reader's own choices, so the inferred-source fallback does not
    // apply.
    expect(resolveLanguagePreferencePair(prefs('en-US', 'en-GB'), null, 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'preference',
    });
    expect(resolveLanguagePreferencePair(prefs('es-419', 'es-ES'), null, 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'preference',
    });
  });

  it('refuses when the preference is unusable rather than treating it as inferred', () => {
    // An empty or malformed preference is a user error, not "infer me". Neither
    // step produced a code, so there is no origin to report.
    expect(resolveLanguagePreferencePair(prefs('  ', 'en'), 'es', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'invalid-source',
      source: null,
      target: 'en',
      sourceOrigin: null,
    });
    expect(resolveLanguagePreferencePair(prefs('es', '1'), 'es', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'invalid-target',
      source: 'es',
      target: null,
      sourceOrigin: 'preference',
    });
  });

  it('falls back to English when the app locale is unusable', () => {
    expect(resolveLanguagePreferencePair(prefs('fr', null), null, '')).toEqual({
      kind: 'translate',
      source: 'fr',
      target: 'en',
      sourceOrigin: 'preference',
    });
  });

  it('prefers the explicit target over the book language for the target side', () => {
    // A book in Spanish, app in English, user asked for German: the book's
    // language must never leak into the target.
    expect(resolveLanguagePreferencePair(prefs(null, 'de'), 'es', 'en')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'de',
      sourceOrigin: 'book',
    });
  });

  it('accepts an empty preferences object as "infer everything"', () => {
    expect(resolveLanguagePreferencePair({}, 'it', 'fr')).toEqual({
      kind: 'translate',
      source: 'it',
      target: 'fr',
      sourceOrigin: 'book',
    });
  });
});
