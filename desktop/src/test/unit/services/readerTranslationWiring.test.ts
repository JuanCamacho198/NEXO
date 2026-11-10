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
