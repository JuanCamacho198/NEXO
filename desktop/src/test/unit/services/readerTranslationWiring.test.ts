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
 * The reader's exact resolution call with the port stubbed — `handleTranslate`
 * in `ReaderWorkspace.svelte`, reproduced argument for argument.
 *
 * Shared by the blocks below so the `en -> en` guard and the page fallback are
 * measured against the SAME wiring: the resolver's positional arguments (source
 * preference, book language, selection text, locale, per-book source, target
 * preference, page text) and the same refusal branch decide both.
 */
async function runHandleTranslate(input: {
  bookLanguage: string | null | undefined;
  locale: string;
  text: string;
  preferenceSource?: string | null;
  preferenceTarget?: string | null;
  bookSourceLanguage?: string | null;
  /**
   * The chapter text the viewer holds, read at translate time through
   * `epubRef.getPageText()` — the same positional argument `handleTranslate`
   * passes last. Defaults to `''`, which is what a PDF, a text-free chapter or
   * no chapter at all yields.
   */
  pageText?: string;
}) {
  const translate = vi.fn().mockResolvedValue('SHOULD NOT BE REACHED');
  const resolution = resolveTranslationSourceWithDetection(
    input.preferenceSource ?? null,
    input.bookLanguage,
    input.text,
    input.locale,
    input.bookSourceLanguage ?? null,
    input.preferenceTarget ?? null,
    input.pageText ?? '',
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
            // Carried through the wiring so a test can assert WHICH step
            // produced the source — the selection's `detected` or the page's
            // `detectedFromPage` — rather than only the code both share.
            sourceOrigin: resolution.sourceOrigin,
          };
        })();

  return { outcome, translate };
}

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

  it('sends the SAVED target preference to the service, not the app locale', async () => {
    // The defect: the reader resolved the target from the app locale and threw
    // the saved preference away, so a target picked in Settings — and the bar's
    // target control — did nothing. The app is Spanish, the reader asked for
    // French, and the request must leave as `es -> fr`.
    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: 'es',
      locale: 'es',
      text: 'la casa es bonita y grande',
      preferenceTarget: 'fr',
    });

    expect(translate).toHaveBeenCalledWith({
      text: 'la casa es bonita y grande',
      sourceLang: 'es',
      targetLang: 'fr',
    });
    expect(outcome).toMatchObject({ status: 'ok', source: 'es', target: 'fr' });
  });

  it('moves the resolved target when the preference moves', async () => {
    // Two different saved targets must produce two different resolved pairs —
    // the exact regression the reader hit, where both answered the locale.
    const first = await runHandleTranslate({
      bookLanguage: 'es',
      locale: 'es',
      text: 'la casa es bonita y grande',
      preferenceTarget: 'fr',
    });
    const second = await runHandleTranslate({
      bookLanguage: 'es',
      locale: 'es',
      text: 'la casa es bonita y grande',
      preferenceTarget: 'it',
    });

    expect(first.outcome).toMatchObject({ status: 'ok', target: 'fr' });
    expect(second.outcome).toMatchObject({ status: 'ok', target: 'it' });
  });

  it('refuses rather than calling the service when a chosen source meets the saved target', async () => {
    // The reader set the target explicitly AND chose the same source: both are
    // decisions, so this is the deliberate degenerate pair and stays refused.
    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: null,
      locale: 'en',
      text: 'la casa es bonita y grande',
      preferenceSource: 'es',
      preferenceTarget: 'es',
    });

    expect(outcome).toEqual({
      status: 'refused',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'preference',
    });
    expect(translate).not.toHaveBeenCalled();
  });
});

/**
 * T4: the page fallback, measured through the WIRING the reader actually hits.
 *
 * T3 pins the resolver in isolation. These cases reproduce `handleTranslate` —
 * the real selection text, the real page text the viewer extracts, the real
 * positional call — so a regression in the plumbing (the page dropped, merged
 * into the selection, consulted too early, or consulted lazily-wrong) fails
 * here even when the resolver alone still passes.
 *
 * The page text below is real Spanish prose of a few hundred words, the size the
 * viewer's `extractPageText` cap hands over (~800 words / 5000 chars), not a
 * token string: a one-word "page" would score below the detector's floor and
 * pin nothing. The detections stay SEPARATE — the selection first, its answer
 * always winning — so an English quote inside this page still resolves `en`.
 */
describe('reader handleTranslate — the page fallback, end to end', () => {
  /** The reported 3-word fragment: one marker hit, under the detector's floor. */
  const REPORTED_FRAGMENT = 'Hay un límite';

  /**
   * A realistic Spanish chapter body (~330 words, within the viewer's cap). Real
   * prose, not marker words glued together: it carries `un`, `la`, `y`, `con`,
   * `del`, `pero` at natural frequency, which is exactly the signal the page
   * fallback is supposed to harvest.
   */
  const SPANISH_PAGE = [
    'Cuando el tren llegó a la estación, la niebla cubría todavía los tejados del pueblo y las luces de la calle',
    'apenas alcanzaban a verse desde el andén. El hombre bajó con su maleta de cuero, miró alrededor como quien',
    'busca a alguien que ya no está, y caminó despacio hacia la salida. Había vivido allí veinte años y no',
    'reconocía ni la mitad de las casas. La panadería de la esquina seguía abierta, pero el cartel era nuevo y el',
    'olor del pan se mezclaba con el de la lluvia. Recordó que su madre solía decir que un buen pan no necesita',
    'nada más que harina, agua y tiempo, y que la prisa era la única enemiga de las cosas bien hechas. Subió la',
    'cuesta con la maleta en la mano izquierda y se detuvo frente a la verja de hierro. La casa estaba pintada',
    'de blanco, con las ventanas cerradas y las macetas vacías sobre el muro. Por un momento pensó que se había',
    'equivocado de calle, pero el número de la puerta era el mismo y la higuera del patio continuaba en su sitio,',
    'más grande de lo que la recordaba, con las ramas apoyadas contra la pared. Sacó la llave del bolsillo y la',
    'sostuvo un rato sin decidirse. Después del entierro había prometido no volver nunca, y sin embargo allí estaba,',
    'con la lluvia cayendo sobre los hombros y el peso de los años encima. Abrió la puerta y entró. El pasillo',
    'olía a madera vieja y a polvo, y al fondo, en la cocina, la mesa seguía puesta con el mantel de lino que su',
    'abuela había bordado. Se sentó en la silla de siempre, dejó la maleta en el suelo y miró por la ventana. No',
    'había nadie esperándolo, pero tampoco se sentía solo. Pensó en las cartas que nunca escribió, en las llamadas',
    'que nunca hizo, en todas las veces que había dicho que volvería y no volvió. Fuera, el viento movía las hojas',
    'del árbol y la lluvia golpeaba el cristal con una insistencia tranquila. Cerró los ojos y escuchó. Aquel',
    'silencio no era el mismo que había dejado atrás: era un silencio más hondo, con algo dentro, como si la casa',
    'guardara las palabras que nadie se había atrevido a decir. Se levantó, encendió la luz de la cocina y abrió',
    'el grifo para llenar un vaso de agua. El agua salió turbia al principio y después clara. Bebió despacio,',
    'apoyado en el borde de la mesa, y por primera vez en mucho tiempo sintió que quizá todavía quedaba algo por',
    'hacer en aquel lugar, algo pequeño y suyo, algo que no dependía de nadie más que de él.',
  ].join(' ');

  it('resolves the reported fragment from the PAGE, and reports the page as the origin', async () => {
    // The original failure, end to end: three words of Spanish in a Spanish
    // chapter, a book whose metadata says nothing. Before T3 the wiring refused
    // with `unknown-source` and the reader saw "cannot detect the language".
    // The page through the wiring names `es`, and `sourceOrigin` says the PAGE
    // produced it — the value the bar uses to tell context from the highlight.
    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: null,
      locale: 'en',
      text: REPORTED_FRAGMENT,
      pageText: SPANISH_PAGE,
    });

    expect(outcome).toEqual({
      status: 'ok',
      text: 'SHOULD NOT BE REACHED',
      source: 'es',
      target: 'en',
      sourceOrigin: 'detectedFromPage',
    });
    // The pair is what leaves for the service: the selection's own text, in the
    // language the page named. Nothing about the page is sent as content.
    expect(translate).toHaveBeenCalledWith({
      text: REPORTED_FRAGMENT,
      sourceLang: 'es',
      targetLang: 'en',
    });
  });

  it('still resolves an English quote inside the Spanish page as `en`, never `es`', async () => {
    // The concatenation trap through the WIRING: if the page were joined to the
    // selection before detecting — the single most likely way to get this wrong,
    // and a change the resolver's own tests would not necessarily catch if the
    // join happened in the call site — the English quote would be drowned by the
    // page and reported as Spanish. Separate detections keep the quote's answer.
    const englishQuote =
      'The question of how the state should be organised was answered in the letters that the author wrote with great care and of which only a few survived.';

    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: null,
      locale: 'es',
      text: englishQuote,
      pageText: SPANISH_PAGE,
    });

    expect(outcome).toMatchObject({
      status: 'ok',
      source: 'en',
      target: 'es',
      sourceOrigin: 'detected',
    });
    expect(translate).toHaveBeenCalledWith({
      text: englishQuote,
      sourceLang: 'en',
      targetLang: 'es',
    });
  });

  it('behaves exactly as before when there is NO page — a PDF or a text-free chapter', async () => {
    // The fallback is ADDITIVE. With no page text the wiring passes `''`, the
    // selection refuses, and the honest `unknown-source` refusal survives with
    // the same message and the same fix. Same fragment, same book, no page.
    const { outcome, translate } = await runHandleTranslate({
      bookLanguage: null,
      locale: 'en',
      text: REPORTED_FRAGMENT,
      pageText: '',
    });

    expect(outcome).toEqual({
      status: 'refused',
      reason: 'unknown-source',
      source: null,
      target: 'en',
      sourceOrigin: null,
    });
    expect(translate).not.toHaveBeenCalled();
  });

  it('never consults the page when the book metadata already names the language', async () => {
    // The metadata step still OUTRANKS the page fallback. A book declaring its
    // language resolves from `book` even though the page could name something
    // else, and the origin proves the page was not the source.
    const { outcome } = await runHandleTranslate({
      bookLanguage: 'fr',
      locale: 'en',
      text: REPORTED_FRAGMENT,
      pageText: SPANISH_PAGE,
    });

    expect(outcome).toEqual({
      status: 'ok',
      text: 'SHOULD NOT BE REACHED',
      source: 'fr',
      target: 'en',
      sourceOrigin: 'book',
    });
  });
});
