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
  resolveTranslationSourceWithDetection,
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

/**
 * The per-book level (T14). The BOOK wins over the account, and the two are
 * reported with DIFFERENT origins because they are different promises: one is a
 * choice scoped to this title, the other the account's default for every title
 * the reader never spoke about. A UI that could not tell them apart would offer
 * to clear a choice that was never made for the book.
 */
describe('resolveLanguagePreferencePair — the per-book source', () => {
  it('lets the book choice win over the account preference', () => {
    expect(resolveLanguagePreferencePair(prefs('de', 'en'), 'fr', 'en', 'es')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'bookPreference',
    });
  });

  it('falls through to the account preference when the book has no choice', () => {
    // `null` at the book level means "no choice for THIS book", NOT "automatic":
    // automatic would be a decision for the book that stops the cascade here and
    // leaves no source at all. Both spellings below are the same absence.
    expect(resolveLanguagePreferencePair(prefs('de', 'en'), 'fr', 'en', null)).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'en',
      sourceOrigin: 'preference',
    });
    expect(resolveLanguagePreferencePair(prefs('de', 'en'), 'fr', 'en', undefined)).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'en',
      sourceOrigin: 'preference',
    });
    // And with no account preference either, the cascade continues to the book's
    // metadata language — the book level does not swallow the inference.
    expect(resolveLanguagePreferencePair(prefs(null, 'en'), 'fr', 'en', null)).toEqual({
      kind: 'translate',
      source: 'fr',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('normalises the book choice like every other tag', () => {
    expect(resolveLanguagePreferencePair(prefs(null, 'en'), null, 'en', 'PT-BR')).toEqual({
      kind: 'translate',
      source: 'pt',
      target: 'en',
      sourceOrigin: 'bookPreference',
    });
  });

  it('refuses a book choice that collides with the target instead of correcting it', () => {
    // B1 (the English fallback) exists for a pair the APP inferred, where the
    // collision was the inference's own doing. A per-book choice is the reader's
    // decision about this title, so rewriting it to English would answer a
    // question they did not ask — the same reasoning that already refuses an
    // explicit source preference. The refusal names the pair so they can revise
    // it.
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), 'fr', 'es', 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'bookPreference',
    });
    // The `en -> en` edge case is not special here either: it is the same
    // collision, refused for the same reason.
    expect(resolveLanguagePreferencePair(prefs(null, 'en'), 'fr', 'en', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'bookPreference',
    });
  });

  it('refuses an unusable book choice rather than treating it as absent', () => {
    // The book level is only ever `null` when the reader made no choice, so a
    // choice that is present but unusable is a user error, not a fall-through —
    // exactly like the account preference. `normalize`/Rust collapse a blank to
    // NULL before it is stored, so this is the defensive path.
    expect(resolveLanguagePreferencePair(prefs('de', 'en'), 'fr', 'en', '  ')).toEqual({
      kind: 'no-translation',
      reason: 'invalid-source',
      source: null,
      target: 'en',
      sourceOrigin: null,
    });
  });

  it('never takes the target from the book, even when the book has a choice', () => {
    // Only the SOURCE is per book. The target is a property of the reader and
    // the same answer in every book, so a book choice with no account target
    // still resolves the target from the app locale.
    expect(resolveLanguagePreferencePair(prefs(null, null), 'de', 'it', 'de')).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'it',
      sourceOrigin: 'bookPreference',
    });
    // And a degeneracy the book level created is NOT corrected by B1 when the
    // target came from the app locale: the choice is still the reader's.
    expect(resolveLanguagePreferencePair(prefs(null, null), 'de', 'de', 'de')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'de',
      target: 'de',
      sourceOrigin: 'bookPreference',
    });
  });
});

/**
 * The two resolvers must agree on the TARGET side, and on the degenerate-pair
 * rule that the target feeds.
 *
 * They differ only in HOW the source is found (the detection variant adds a
 * local detection step); the destination is the same decision either way, and
 * the collision rule is one rule. They were allowed to drift once — the
 * detection variant took no target preference at all — and the reader's target
 * became a dead control. These cases pin the parity.
 */
describe('the target side is the same on both resolvers', () => {
  const ENGLISH = 'The man is in the house with the dog and the cat of the neighbour.';
  const SPANISH = 'El hombre está en la casa con el perro y el gato del vecino.';

  it('resolves the same explicit target on both paths', () => {
    const viaPreference = resolveLanguagePreferencePair(prefs('de', 'fr'), 'it', 'es');
    const viaDetection = resolveTranslationSourceWithDetection(
      'de',
      'it',
      ENGLISH,
      'es',
      null,
      'fr',
    );

    expect(viaPreference).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'fr',
      sourceOrigin: 'preference',
    });
    expect(viaDetection).toEqual({
      kind: 'translate',
      source: 'de',
      target: 'fr',
      sourceOrigin: 'preference',
    });
  });

  it('refuses an explicit target that equals a chosen source on both paths', () => {
    // Book choice (bookPreference) on each side, colliding with the target.
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), 'fr', 'es', 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'bookPreference',
    });
    expect(resolveTranslationSourceWithDetection(null, 'fr', SPANISH, 'en', 'es', 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'bookPreference',
    });
  });

  it('never rewrites an explicit target that equals an inferred source on both paths', () => {
    expect(resolveLanguagePreferencePair(prefs(null, 'es'), 'es', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'book',
    });
    expect(resolveTranslationSourceWithDetection(null, 'es', SPANISH, 'en', null, 'es')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'es',
      target: 'es',
      sourceOrigin: 'book',
    });
  });

  it('corrects an inferred collision with an AUTOMATIC target on both paths', () => {
    expect(resolveLanguagePreferencePair(prefs(null, null), 'es', 'es')).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'book',
    });
    expect(resolveTranslationSourceWithDetection(null, 'es', SPANISH, 'es', null, null)).toEqual({
      kind: 'translate',
      source: 'es',
      target: 'en',
      sourceOrigin: 'book',
    });
  });

  it('keeps the en -> en refusal on both paths', () => {
    expect(resolveLanguagePreferencePair(prefs(null, null), 'en', 'en')).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'book',
    });
    expect(resolveTranslationSourceWithDetection(null, 'en', ENGLISH, 'en', null, null)).toEqual({
      kind: 'no-translation',
      reason: 'same-language',
      source: 'en',
      target: 'en',
      sourceOrigin: 'book',
    });
  });
});
