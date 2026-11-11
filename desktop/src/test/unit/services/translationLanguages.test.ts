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
import { parseCommandError } from '$lib/shared/api/tauriClient';

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

/**
 * Defect B: the code is recovered from the BARE STRING the Rust command emits.
 *
 * `translateSelection` is `Result<String, String>` and stringifies its typed
 * error with `.map_err(|e| e.to_string())`, so the rejection that reaches the
 * frontend is the literal `"{code}: {detail}"` the `Display` impl writes — not
 * JSON. `parseCommandError` used to JSON-parse it, fail, and return `null`, so
 * `commandError.code` was always absent and EVERY failure collapsed to
 * `unavailable`: the `no-result` branch was unreachable on this path.
 *
 * These strings are copied from `commands/translate.rs` (`error(code, detail)`
 * call sites), not invented.
 */
describe('translationFailureOutcome — bare-string command errors', () => {
  it('recovers TRANSLATE_NO_TRANSLATION from the raw rejection string', () => {
    const rejection = 'TRANSLATE_NO_TRANSLATION: no usable translation was returned';
    const error = new Error(rejection);

    expect(parseCommandError(error)?.code).toBe('TRANSLATE_NO_TRANSLATION');
    expect(translationFailureOutcome(error)).toEqual({ status: 'no-result' });
  });

  it('recovers the same-language 403 as a no-result, which is what it is', () => {
    // Measured live: a same-language pair answers this text. Nothing is
    // unavailable — there is simply nothing to translate.
    const rejection =
      'TRANSLATE_NO_TRANSLATION: translation service requires two distinct languages';

    expect(translationFailureOutcome(new Error(rejection))).toEqual({ status: 'no-result' });
  });

  it('recovers TRANSLATE_UNTRANSLATABLE_INPUT as a no-result', () => {
    const rejection = 'TRANSLATE_UNTRANSLATABLE_INPUT: selection is not translatable';
    expect(translationFailureOutcome(new Error(rejection))).toEqual({ status: 'no-result' });
  });

  it('keeps a network rejection unavailable', () => {
    const rejection = 'TRANSLATE_NETWORK: translation request failed: connection refused';
    expect(translationFailureOutcome(new Error(rejection))).toEqual({ status: 'unavailable' });
  });

  it('recovers the `${prefix}${status}` HTTP code the transport builds', () => {
    const rejection = 'TRANSLATE_HTTP_503: translation service responded 503 Service Unavailable';

    expect(parseCommandError(new Error(rejection))?.code).toBe('TRANSLATE_HTTP_503');
    expect(translationFailureOutcome(new Error(rejection))).toEqual({ status: 'unavailable' });
  });

  it('degrades to unavailable for a message that does not match the shape', () => {
    // The pre-change behaviour, and the guarantee: an unrecognised string is
    // never reclaimed as a "no translation".
    const unmatchable = [
      new Error('boom'),
      new Error('TRANSLATE: missing code'),
      new Error('TRANSLATE_NO_TRANSLATION'),
      new Error('something TRANSLATE_NETWORK: not at the start'),
      // A structured JSON error must still take the JSON path, not this one.
      new Error(JSON.stringify({ code: 'X', message: 'y', recoverable: false })),
    ];

    for (const error of unmatchable) {
      expect(translationFailureOutcome(error)).toEqual({ status: 'unavailable' });
    }
  });
});
