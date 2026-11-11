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
 * Detection has two SAMPLES, tried in order and never together: the selection,
 * then the page text the selection sits in. The selection is direct evidence
 * about what is being translated, so its answer always stands; the page runs
 * only when the selection alone yields nothing, and its result is reported as
 * `detectedFromPage` so the UI can tell the two apart. The page call is lazy.
 *
 * Every result also carries `sourceOrigin`: `preference` for an explicit source,
 * `book` for the book's metadata, `detected` for the detector over the selection,
 * `detectedFromPage` for the detector over the page. The UI labels a language as
 * automatic only when it was inferred, so the origin must name the step that
 * actually produced the code — not just re-state the code.
 */
import { describe, expect, it, vi } from 'vitest';
import * as detection from '$lib/shared/services/translation/translationLanguageDetection';
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

/**
 * The TARGET preference, on the detection path.
 *
 * This is the defect that shipped: the reader resolves with the detection
 * variant, which took no target preference at all and hard-coded the app locale
 * as the destination. A target chosen in Settings (or in the bar) was persisted
 * correctly and then DISCARDED — with the default locale `es` and a Spanish
 * book every translation resolved `es -> es`, and picking another target in the
 * bar snapped back to Español and read as a dead control. These cases fail on
 * that code and pass once the preference is threaded through.
 */
describe('resolveTranslationSourceWithDetection — the target preference', () => {
  const ENGLISH = 'The man is in the house with the dog and the cat of the neighbour.';
  const SPANISH = 'El hombre está en la casa con el perro y el gato del vecino.';

  it('USES an explicit target preference instead of the app locale', () => {
    // The app is in Spanish and the reader asked for French. The saved target
    // wins: this is the assertion that would have caught the dead control.
    expect(resolveTranslationSourceWithDetection(null, 'de', ENGLISH, 'es', null, 'fr')).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'fr',
      sourceOrigin: 'book',
    });
  });

  it('resolves two different explicit targets to two different destinations', () => {
    // The regression the reader hit, stated directly: changing the target twice
    // must change the resolved pair twice. A hard-coded locale answers `es` both
    // times and fails here.
    expect(resolveTranslationSourceWithDetection(null, 'de', ENGLISH, 'es', null, 'fr')).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'fr',
      sourceOrigin: 'book',
    });
    expect(resolveTranslationSourceWithDetection(null, 'de', ENGLISH, 'es', null, 'it')).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'it',
      sourceOrigin: 'book',
    });
  });

  it('falls back to the app locale when the target is automatic', () => {
    // `null` is a real value — "use the app locale" — not a missing one.
    expect(resolveTranslationSourceWithDetection(null, 'de', ENGLISH, 'es', null, null)).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'es',
      sourceOrigin: 'book',
    });
    expect(
      resolveTranslationSourceWithDetection(null, 'de', ENGLISH, 'es', null, undefined),
    ).toEqual({ kind: 'translate', source: 'de', target: 'es', sourceOrigin: 'book' });
  });

  it('refuses an explicit target that equals a CHOSEN source', () => {
    // The reader chose Spanish for this book and asked for Spanish. Like the
    // preference path, that deliberate pair is refused rather than corrected.
    expect(resolveTranslationSourceWithDetection(null, 'fr', SPANISH, 'en', 'es', 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'bookPreference',
    });
    // Same for an explicit ACCOUNT source preference.
    expect(resolveTranslationSourceWithDetection('es', 'fr', SPANISH, 'en', null, 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'preference',
    });
  });

  it('never rewrites an explicit target that equals an INFERRED source', () => {
    // The source was inferred (book metadata), but the TARGET is a decision: the
    // reader asked for Spanish while the book is in Spanish. The English
    // fallback does not apply, exactly as on the preference path.
    expect(resolveTranslationSourceWithDetection(null, 'es', SPANISH, 'en', null, 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'book',
    });
    // And when the source came from detection rather than the metadata.
    expect(resolveTranslationSourceWithDetection(null, null, SPANISH, 'en', null, 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'detected',
    });
  });

  it('still falls back to English when an inferred source collides with the LOCALE', () => {
    // The automatic target keeps the original behaviour: an inferred collision
    // with the app locale is corrected. The explicit-target guard must not
    // swallow it.
    expect(resolveTranslationSourceWithDetection(null, 'es', SPANISH, 'es', null, null)).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('refuses an unusable target preference', () => {
    // A target that is present but unusable is a user error, not "infer me":
    // the same `invalid-target` refusal the preference path reports.
    expect(resolveTranslationSourceWithDetection(null, 'de', ENGLISH, 'es', null, '1')).toEqual({
      kind: 'no-translation',
      reason: 'invalid-target',
      source: 'de',
      target: null,
      sourceOrigin: 'book',
    });
  });

  it('normalises the target preference like every other tag', () => {
    expect(resolveTranslationSourceWithDetection(null, 'de', ENGLISH, 'es', null, 'PT-BR')).toEqual(
      { kind: 'translate', source: 'de', target: 'pt', sourceOrigin: 'book' },
    );
  });
});

/**
 * The PAGE fallback (T3): the chapter text as a SECOND sample for detection.
 *
 * The detector's floor is four word tokens, and a highlighted phrase is usually
 * three or fewer, so a real Spanish selection in a real Spanish chapter refuses
 * today ("cannot detect the language"). The page around it carries the missing
 * signal, so it is offered as a fallback — the SELECTION is tried first and its
 * answer always stands, the page only runs when the selection yields nothing.
 *
 * The two are detected with SEPARATE calls. Concatenating them is the measured
 * trap: an English quote (34 words) alone detects `en`, the Spanish page alone
 * detects `es`, and the two JOINED detect `es` — the page drowns the selection
 * and the reader is told their English quote is Spanish. The cases below pin
 * both halves of that.
 *
 * The page call is LAZY: it must not run when the selection already resolved,
 * because the call site passes the page on EVERY translation and an eager call
 * would make every translation pay for a chapter-sized scan.
 */
describe('resolveTranslationSourceWithDetection — the page fallback', () => {
  /** A 3-word Spanish fragment: one marker hit, below the detector's floor. */
  const SHORT_SPANISH = 'Hay un límite';
  /** An English quote long enough to score on its own. */
  const ENGLISH_QUOTE =
    'The question of how the state should be organised was answered in the letters that the author wrote with great care and of which only a few survived.';
  const SPANISH_PAGE =
    'El hombre llegó a la casa de su madre por la tarde y la encontró con la puerta abierta. ' +
    'La mesa estaba puesta con un mantel de lino y las sillas de madera junto a la ventana. ' +
    'Después del almuerzo hablaron del viaje con los vecinos y de los años que habían pasado.';

  it('resolves a short Spanish selection from the PAGE, and says so', () => {
    // The reported failure: three words in a Spanish chapter. The selection
    // alone refuses; the page names `es`. The origin is `detectedFromPage`, a
    // value DISTINCT from `detected`, so the UI can tell the weaker evidence
    // (the context) from the direct one (the highlighted text).
    expect(
      resolveTranslationSourceWithDetection(
        null,
        null,
        SHORT_SPANISH,
        'en',
        null,
        null,
        SPANISH_PAGE,
      ),
    ).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'detectedFromPage',
    });
  });

  it('still refuses the same selection when there is NO page', () => {
    // The fallback is ADDITIVE: with nothing to fall back to, the old answer is
    // byte-identical, and `unknown-source` keeps meaning "nothing could name the
    // source". A PDF or a chapter that has not rendered passes `''` and lands
    // here.
    expect(
      resolveTranslationSourceWithDetection(null, null, SHORT_SPANISH, 'en', null, null, ''),
    ).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'en',
      sourceOrigin: null,
    });
  });

  it('lets an English SELECTION win inside a Spanish page — never concatenating', () => {
    // The concatenation trap, stated as a test. Joining the quote and the page
    // would detect `es` (measured) and silently translate the quote as Spanish.
    // Detected separately, the selection answers `en` and the page is never
    // consulted for its answer.
    expect(
      resolveTranslationSourceWithDetection(
        null,
        null,
        ENGLISH_QUOTE,
        'es',
        null,
        null,
        SPANISH_PAGE,
      ),
    ).toEqual({ kind: 'translate', source: 'en', target: 'es', sourceOrigin: 'detected' });
  });

  it('refuses when neither the selection nor the page yields a language', () => {
    expect(
      resolveTranslationSourceWithDetection(null, null, 'give up', 'en', null, null, 'la de'),
    ).toEqual({
      kind: 'no-translation',
      reason: 'unknown-source',
      source: null,
      target: 'en',
      sourceOrigin: null,
    });
  });

  it('does not consult the page when the book metadata already named the language', () => {
    // The metadata step (3) still OUTRANKS the page fallback (5): a declared
    // language is honest evidence and a guess over the chapter is not. The
    // origin stays `book`, never `detectedFromPage`.
    expect(
      resolveTranslationSourceWithDetection(
        null,
        'fr',
        SHORT_SPANISH,
        'en',
        null,
        null,
        SPANISH_PAGE,
      ),
    ).toEqual({ kind: 'translate', source: 'fr', target: 'en', sourceOrigin: 'book' });
  });

  it('does not consult the page when a chosen source already resolved', () => {
    // The same for the two CHOICE steps: the reader's word is not a sample, so
    // no detection runs at all.
    expect(
      resolveTranslationSourceWithDetection(
        'de',
        'fr',
        SHORT_SPANISH,
        'en',
        null,
        null,
        SPANISH_PAGE,
      ),
    ).toEqual({ kind: 'translate', source: 'de', target: 'en', sourceOrigin: 'preference' });
    expect(
      resolveTranslationSourceWithDetection(
        null,
        'fr',
        SHORT_SPANISH,
        'en',
        'it',
        null,
        SPANISH_PAGE,
      ),
    ).toEqual({ kind: 'translate', source: 'it', target: 'en', sourceOrigin: 'bookPreference' });
  });

  it('runs the page detection LAZILY — not at all when the selection resolves', () => {
    // The call site passes the page on EVERY translation, so an eager page
    // `detectLanguage` call would charge every translation for a chapter-sized
    // scan it does not need. This spies on the detector and asserts the call
    // count directly: one call when the selection resolves (the selection
    // only), two when the selection refuses and the page is consulted.
    const spy = vi.spyOn(detection, 'detectLanguage');
    try {
      spy.mockClear();
      resolveTranslationSourceWithDetection(
        null,
        null,
        ENGLISH_QUOTE,
        'es',
        null,
        null,
        SPANISH_PAGE,
      );
      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy).toHaveBeenCalledWith(ENGLISH_QUOTE);

      spy.mockClear();
      resolveTranslationSourceWithDetection(
        null,
        null,
        SHORT_SPANISH,
        'en',
        null,
        null,
        SPANISH_PAGE,
      );
      expect(spy).toHaveBeenCalledTimes(2);
      expect(spy).toHaveBeenNthCalledWith(1, SHORT_SPANISH);
      expect(spy).toHaveBeenNthCalledWith(2, SPANISH_PAGE);
    } finally {
      spy.mockRestore();
    }
  });

  it('runs the page detection lazily when a choice wins too — zero calls', () => {
    const spy = vi.spyOn(detection, 'detectLanguage');
    try {
      spy.mockClear();
      resolveTranslationSourceWithDetection(
        null,
        'fr',
        SHORT_SPANISH,
        'en',
        null,
        null,
        SPANISH_PAGE,
      );
      expect(spy).not.toHaveBeenCalled();
    } finally {
      spy.mockRestore();
    }
  });
});
