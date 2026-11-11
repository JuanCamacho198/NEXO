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
import { resolveTranslationSourceWithDetection } from '$lib/shared/services/translation/translationLanguageResolution';

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

/**
 * Defect A: the `en -> en` case must surface as the honest `same-language`
 * refusal, never as a transport failure.
 *
 * These tests reproduce the READER's own wiring (`handleTranslate` in
 * `ReaderWorkspace.svelte`) against a stub port, so they measure the path rather
 * than the resolver alone: the resolver is called with the same five arguments,
 * the SAME branch decides the outcome, and the port is asserted not to be
 * called. That last assertion is the defect itself — an `en -> en` pair reaching
 * the service is what made the reader see "Servicio de traducción no
 * disponible", which is false: nothing was unavailable.
 */
describe('reader handleTranslate — en -> en never reaches the service', () => {
  /** The reader's exact resolution call, with the port stubbed. */
  async function runHandleTranslate(input: {
    bookLanguage: string | null | undefined;
    locale: string;
    text: string;
    preferenceSource?: string | null;
    bookSourceLanguage?: string | null;
  }) {
    const translate = vi.fn().mockResolvedValue('SHOULD NOT BE REACHED');
    const resolution = resolveTranslationSourceWithDetection(
      input.preferenceSource ?? null,
      input.bookLanguage,
      input.text,
      input.locale,
      input.bookSourceLanguage ?? null,
    );

    const outcome =
      resolution.kind === 'no-translation'
        ? {
            status: 'refused' as const,
            reason: resolution.reason,
            source: resolution.source,
            target: resolution.target,
            sourceOrigin: resolution.sourceOrigin,
          }
        : await (async () => {
            const text = await translate({
              text: input.text,
              sourceLang: resolution.source,
              targetLang: resolution.target,
            });
            return {
              status: 'ok' as const,
              text,
              source: resolution.source,
              target: resolution.target,
            };
          })();

    return { outcome, translate };
  }

  it('refuses an English book on an English app instead of calling the service', async () => {
    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: 'en',
      locale: 'en',
      text: 'the quick brown fox jumps over the lazy dog',
    });

    expect(outcome).toEqual({
      status: 'refused',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'book',
    });
    // The whole point: no request was made, so the reader cannot be shown a
    // transport failure for a pair the app already knows is degenerate.
    expect(translate).not.toHaveBeenCalled();
  });

  it('refuses a detected English source on an English app too', async () => {
    // No book language at all: the source is DETECTED. The B1 correction cannot
    // help because falling back to English would return the same `en -> en`, so
    // this stays a `same-language` refusal.
    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: undefined,
      locale: 'en',
      text: 'the quick brown fox jumps over the lazy dog',
    });

    expect(outcome).toEqual({
      status: 'refused',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'detected',
    });
    expect(translate).not.toHaveBeenCalled();
  });

  it('refuses rather than reaching the service for a chosen en -> en', async () => {
    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: 'en',
      locale: 'en',
      text: 'the quick brown fox jumps over the lazy dog',
      preferenceSource: 'en',
    });

    expect(outcome).toMatchObject({ status: 'refused', reason: 'same-language' });
    expect(translate).not.toHaveBeenCalled();
  });

  it('still translates a pair that is not degenerate', async () => {
    // The guard must not swallow the ordinary case.
    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: 'es',
      locale: 'en',
      text: 'la casa es bonita y grande',
    });

    expect(translate).toHaveBeenCalledWith({
      text: 'la casa es bonita y grande',
      sourceLang: 'es',
      targetLang: 'en',
    });
    expect(outcome).toMatchObject({ status: 'ok', source: 'es', target: 'en' });
  });
});
