/**
 * Source-language resolution cascade with local detection as the fallback.
 *
 * The book's `language` is optional in practice, and when it is missing the
 * translation used to be refused outright — the reader saw "This book has no
 * language. Choose a source language in Settings › Translation." even for a
 * selection whose language is obvious. The fallback is a LOCAL detection over
 * the selected text (`detectLanguage`), because MyMemory rejects `auto` as a
 * source language with a 403.
 *
 * The order is: book metadata, then detection, then `unknown-source`. The BOOK
 * WINS when it exists, even when it contradicts detection — its metadata is
 * declared, detection is a guess.
 *
 * Every result also carries `sourceOrigin`: `preference` for an explicit source,
 * `book` for the book's metadata, `detected` for the detector. The UI labels a
 * language as automatic only when it was inferred, so the origin must name the
 * step that actually produced the code — not just re-state the code.
 */
import { describe, expect, it } from 'vitest';
import { resolveTranslationSourceWithDetection } from '$lib/shared/services/translation/translationLanguageResolution';

describe('resolveTranslationSourceWithDetection', () => {
  it('uses the book language when the book declares one', () => {
    expect(
      resolveTranslationSourceWithDetection(
        null,
        'fr',
        'The man is in the house with the dog and the cat of the neighbour.',
        'es',
      ),
    ).toEqual({ kind: 'translate', source: 'fr', target: 'es', sourceOrigin: 'book' });
  });

  it('falls back to local detection when the book carries no language', () => {
    expect(
      resolveTranslationSourceWithDetection(
        null,
        null,
        'The man is in the house with the dog and the cat of the neighbour.',
        'es',
      ),
    ).toEqual({ kind: 'translate', source: 'en', target: 'es', sourceOrigin: 'detected' });
  });

  it('reports a preference origin when an explicit source is set', () => {
    // The reader's own choice beats both the book and the detector, and the
    // origin must say so: a language the reader picked is never "automatic".
    expect(
      resolveTranslationSourceWithDetection(
        'de',
        'fr',
        'The man is in the house with the dog and the cat of the neighbour.',
        'es',
      ),
    ).toEqual({ kind: 'translate', source: 'de', target: 'es', sourceOrigin: 'preference' });
  });

  it('lets the BOOK win over a detection that contradicts it', () => {
    // The text is English, the metadata says Spanish. The metadata is declared,
    // the heuristic is a guess; a wrong guess is silently fluent, so the
    // declared value is the honest one to trust and the detection is ignored.
    // The origin is `book`, not `detected`.
    expect(
      resolveTranslationSourceWithDetection(
        null,
        'es',
        'The man is in the house with the dog and the cat of the neighbour.',
        'en',
      ),
    ).toEqual({ kind: 'translate', source: 'es', target: 'en', sourceOrigin: 'book' });
  });

  it('refuses when neither the book nor detection can name the source', () => {
    expect(resolveTranslationSourceWithDetection(null, null, 'give up', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'en',
      sourceOrigin: null,
    });
  });

  it('reports the resolved target when only the source is unknown', () => {
    // The refusal is what the panel turns into a question, and the target it
    // already resolved is part of the answer: the picker prefills from this
    // value, so a null here would make the reader re-pick a side that worked.
    expect(resolveTranslationSourceWithDetection(null, null, 'give up', 'pt-BR')).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'pt',
      sourceOrigin: null,
    });
  });

  it('falls back to detection when the book language is garbage', () => {
    // `'  '` and `'1'` normalise to null; that is "the book said nothing", not a
    // hard refusal, so the detection step still gets its chance — and the origin
    // is `detected`, because detection is what produced the code.
    const text = 'The man is in the house with the dog and the cat of the neighbour.';
    expect(resolveTranslationSourceWithDetection(null, '  ', text, 'es')).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
      sourceOrigin: 'detected',
    });
    expect(resolveTranslationSourceWithDetection(null, '1', text, 'es')).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
      sourceOrigin: 'detected',
    });
  });

  it('corrects an inferred source that equals the target to English', () => {
    // A Spanish book read with the app in Spanish: the app put that pair
    // together, so it corrects the pair to English rather than refusing — the
    // reader asked for a translation and gets one. The same holds when the
    // source came from detection instead of the book's metadata.
    const spanish = 'El hombre está en la casa con el perro y el gato del vecino.';
    expect(resolveTranslationSourceWithDetection(null, null, spanish, 'es')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'detected',
    });
    expect(resolveTranslationSourceWithDetection(null, 'es', spanish, 'es')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('still refuses when source and target resolve to English', () => {
    // The detection must not bypass the `en -> en` trap: the fallback target is
    // English, so an English source cannot be corrected by it. The pair is
    // genuinely degenerate and stays refused.
    expect(
      resolveTranslationSourceWithDetection(
        null,
        null,
        'The man is in the house with the dog and the cat of the neighbour.',
        'en',
      ),
    ).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'detected',
    });
    // Same when the book declares English and the app is English.
    expect(
      resolveTranslationSourceWithDetection(
        null,
        'en',
        'The man is in the house with the dog and the cat of the neighbour.',
        'en',
      ),
    ).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('keeps refusing when an explicit source equals the target', () => {
    // The reader picked Spanish and the app is in Spanish. Unlike an inferred
    // collision, this one is corrected by nobody: the explicit source is theirs.
    expect(
      resolveTranslationSourceWithDetection(
        'es',
        'es',
        'El hombre está en la casa con el perro y el gato del vecino.',
        'es',
      ),
    ).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'preference',
    });
  });
});

/**
 * The per-book level (T14), on the detection path. It is the FIRST step — ahead
 * of the account preference — and it is reported as `bookPreference` so the UI
 * can tell a choice made for this book from the account's default.
 */
describe('resolveTranslationSourceWithDetection — the per-book source', () => {
  const ENGLISH = 'The man is in the house with the dog and the cat of the neighbour.';

  it('lets the book choice win over the account preference and over detection', () => {
    expect(resolveTranslationSourceWithDetection('de', 'fr', ENGLISH, 'en', 'es')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'bookPreference',
    });
  });

  it('falls through to the account preference when the book has no choice', () => {
    // `null` is "no choice for this book" and the cascade continues — it is not
    // the value "automatic", which would stop the resolution with no source.
    expect(resolveTranslationSourceWithDetection('de', 'fr', ENGLISH, 'en', null)).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'en',
      sourceOrigin: 'preference',
    });
    expect(resolveTranslationSourceWithDetection('de', 'fr', ENGLISH, 'en', undefined)).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'en',
      sourceOrigin: 'preference',
    });
  });

  it('carries on to the book metadata and to detection when nothing was chosen', () => {
    expect(resolveTranslationSourceWithDetection(null, 'fr', ENGLISH, 'en', null)).toEqual({
      kind: 'translate',
      source: 'fr',
      target: 'en',
      sourceOrigin: 'book',
    });
    expect(resolveTranslationSourceWithDetection(null, null, ENGLISH, 'es', null)).toEqual({
      kind: 'translate',
      source: 'en',
      target: 'es',
      sourceOrigin: 'detected',
    });
  });

  it('refuses a book choice that equals the target, without the English fallback', () => {
    // B1 corrects a pair the APP inferred. A per-book choice is the reader's own
    // decision for this title, so it is refused exactly like an explicit account
    // preference — the panel then says the source is a choice they can revise.
    expect(
      resolveTranslationSourceWithDetection(
        null,
        'fr',
        'El hombre está en la casa con el perro y el gato del vecino.',
        'es',
        'es',
      ),
    ).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'bookPreference',
    });
    // The `en -> en` shape is the same collision and the same refusal: here the
    // book choice is what made the pair degenerate, so it is not "correctable".
    expect(resolveTranslationSourceWithDetection(null, null, ENGLISH, 'en', 'en-US')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'bookPreference',
    });
  });

  it('refuses an unusable book choice instead of falling through', () => {
    // The step ran and produced no usable code: falling through would silently
    // ignore the reader's choice and translate in some other language.
    expect(resolveTranslationSourceWithDetection(null, null, ENGLISH, 'es', '1')).toEqual({
      kind: 'no-translation',
      reason: 'invalid-source',
      source: null,
      target: 'es',
      sourceOrigin: null,
    });
  });

  it('never takes the target from the book', () => {
    // The book level is source-only, by design: which language the reader wants
    // translations IN is the same answer in every book.
    expect(resolveTranslationSourceWithDetection(null, null, ENGLISH, 'it', 'de')).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'it',
      sourceOrigin: 'bookPreference',
    });
  });
});
