/**
 * T2 wiring: the reader refuses to translate a degenerate pair.
 *
 * The regression this pins: the old resolver fell back to the target when the
 * book had no language, so an English book with the app in English produced
 * `en -> en`, the service answered 403, and the reader saw a bland "no
 * translation" with no way to understand or fix it. Now the pair is resolved
 * with `resolveLanguagePreferencePair` and a refusal NEVER reaches the port.
 *
 * `sourceOrigin` travels with the pair because the reader's own choice and the
 * app's inference are shown differently: only an inferred language is labelled
 * automatic.
 */
import { describe, expect, it, vi } from 'vitest';
import { resolveLanguagePreferencePair } from '$lib/shared/services/translation/translationLanguageResolution';

describe('reader translation pair resolution', () => {
  it('refuses instead of guessing when the book has no language and no source is set', () => {
    const resolution = resolveLanguagePreferencePair(
      { sourceLanguage: null, targetLanguage: null },
      undefined,
      'en',
    );

    expect(resolution).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'en',
      sourceOrigin: null,
    });
  });

  it('resolves from the book when it carries a language and the target is inferred', () => {
    const resolution = resolveLanguagePreferencePair(
      { sourceLanguage: null, targetLanguage: null },
      'es',
      'en',
    );

    expect(resolution).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('lets an explicit source preference win over a silent book', () => {
    const resolution = resolveLanguagePreferencePair(
      { sourceLanguage: 'fr', targetLanguage: null },
      undefined,
      'en',
    );

    expect(resolution).toEqual({
      kind: 'translate',
      source: 'fr',
      target: 'en',
      sourceOrigin: 'preference',
    });
  });

  it('refuses a pair that resolves to one language', () => {
    // The book says English and the app is English: the source is inferred but
    // the fallback is English too, so the pair is the genuine `en -> en` trap
    // and stays refused.
    const resolution = resolveLanguagePreferencePair({}, 'en', 'en');

    expect(resolution).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('never calls the port for a refusal', () => {
    const translate = vi.fn();
    const resolution = resolveLanguagePreferencePair({}, undefined, 'en');

    // Mirrors the reader's branch: a refusal short-circuits before the port.
    if (resolution.kind === 'translate') {
      translate({ sourceLang: resolution.source, targetLang: resolution.target });
    }

    expect(translate).not.toHaveBeenCalled();
  });
});

/**
 * T15: the reader resolves with the BOOK's own choice first, and reports it as
 * `bookPreference` so the bar labels it as the reader's choice rather than as an
 * inference. The book's choice is a separate input from the account pair; the
 * destination is never taken from the book.
 */
describe('reader translation pair resolution — per-book source', () => {
  const prefs = (sourceLanguage: string | null = null, targetLanguage: string | null = null) => ({
    sourceLanguage,
    targetLanguage,
  });

  it('lets the book choice win over the account pair', () => {
    const resolution = resolveLanguagePreferencePair(prefs('de', 'en'), 'fr', 'en', 'es');

    expect(resolution).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'bookPreference',
    });
  });

  it('falls through to the account pair when the book has no choice', () => {
    const resolution = resolveLanguagePreferencePair(prefs('de', 'en'), 'fr', 'en', null);

    expect(resolution).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'en',
      sourceOrigin: 'preference',
    });
  });

  it('never takes the destination from the book', () => {
    // A book scoped to Spanish read with the app in English: the pair is
    // `es -> en`, and the account's target is untouched by the book's language.
    const resolution = resolveLanguagePreferencePair(prefs(null, null), 'de', 'en', 'es');

    expect(resolution).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'bookPreference',
    });
  });

  it('refuses a per-book choice that collides with the target instead of falling back', () => {
    // The reader chose Spanish for this book while reading with a Spanish
    // target: the collision is their own doing, so it is refused with the pair
    // so the panel can name it as a choice they can revise.
    const resolution = resolveLanguagePreferencePair(prefs(null, null), 'fr', 'es', 'es');

    expect(resolution).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'bookPreference',
    });
  });
});
