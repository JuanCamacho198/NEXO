/**
 * Resolves the language pair for a translation from the user's preferences.
 *
 * Preferences are optional on both sides, mirroring the Android model:
 * `targetLanguage = null` means "use the app locale", `sourceLanguage = null`
 * means "infer it from the book". This module only decides; it performs no I/O
 * and holds no state, so it is testable without mocks.
 *
 * The source has TWO levels, and the BOOK wins: the reader's choice for the book
 * being read is consulted before the account's general preference. They are
 * passed as two separate inputs rather than one already-merged value because the
 * two are different promises (`bookPreference` vs `preference`) and the UI has to
 * tell them apart to know whether a choice is clearable for this book.
 *
 * The refusal path is the point of the feature. An English book read with the
 * app in English used to resolve to `en -> en`, and the service answers that
 * with `403 PLEASE SELECT TWO DISTINCT LANGUAGES`; the reader saw a bland
 * "no translation" and no way to understand or fix it. A degenerate pair the APP
 * inferred is now corrected to English instead (see `resolveLanguagePreferencePair`
 * and `resolveTranslationSourceWithDetection`), and every pair that is still
 * degenerate — one the reader chose, or an `en -> en` that English cannot fix —
 * returns a typed reason instead.
 *
 * The DESTINATION is never taken from the book: which language the reader wants
 * translations in is a property of the reader, the same answer in every book, so
 * it stays on the account (or the app locale). Only the source is per book.
 */
import { detectLanguage } from './translationLanguageDetection';
import { normalizeLanguageTag } from './translationLanguages';

/**
 * Language preference as stored per user.
 *
 * Both fields are `null` by default: the language of the book is optional in
 * library metadata, and the app locale is a fine target until the user says
 * otherwise.
 */
export interface TranslationLanguagePreferences {
  sourceLanguage?: string | null;
  targetLanguage?: string | null;
}

/**
 * Where a resolved SOURCE came from.
 *
 * The UI needs this to decide whether it may label a language as automatic: a
 * language the reader picked is theirs and must be shown as chosen, while one
 * this module inferred (from the book's metadata or from local detection over
 * the text) may be shown as "Auto (español)". The two are not interchangeable
 * to the reader — one is a promise the app is repeating back, the other is a
 * guess the app made on their behalf.
 *
 * - `bookPreference` — the reader's own `sourceLanguage` for THIS book resolved.
 *   A separate value from `preference` because the two are different promises:
 *   both are the reader's word, but this one is scoped to one book and the
 *   reader can clear it without touching what every other book does. Collapsing
 *   them would make the two indistinguishable to the UI, which has to know
 *   whether it may offer "use the general preference" — a language inherited
 *   from the account is not a book-level choice to clear.
 * - `preference` — an explicit `sourceLanguage` preference resolved.
 * - `book` — `Book.language` resolved.
 * - `detected` — `detectLanguage(text)` produced the code.
 */
export type SourceOrigin = 'bookPreference' | 'preference' | 'book' | 'detected';

/** Why no translation should be attempted. Each maps to its own user copy. */
export type NoTranslationReason =
  /** Source and target resolved to the same language: the 403 trap. */
  | 'same-language'
  /** Source was inferred from the book, and the book carries no language. */
  | 'unknown-source'
  /** A source preference was set but is unusable (`''`, `'1'`, padding only). */
  | 'invalid-source'
  /** A target preference was set but is unusable. */
  | 'invalid-target';

/**
 * Discriminated result: either a concrete pair to hand to the port, or a typed
 * refusal the UI can explain.
 *
 * A refusal carries the PARTIAL pair alongside the reason, so the reader is not
 * left with a bare "no translation": the selection panel can show which side
 * did resolve and prefill the picker from it to fix the other one. `null` means
 * that side could not be resolved at all — never "unknown", "auto" or a guess.
 *
 * Both variants also carry `sourceOrigin`, which says WHERE the source came
 * from — see {@link SourceOrigin}. On a refusal it is `null` when no source was
 * resolved at all: there is no provenance to report for a side that does not
 * exist.
 */
export type LanguageResolution =
  | { kind: 'translate'; source: string; target: string; sourceOrigin: SourceOrigin }
  | {
      kind: 'no-translation';
      reason: NoTranslationReason;
      source: string | null;
      target: string | null;
      sourceOrigin: SourceOrigin | null;
    };

/** Last-resort target when the app locale itself is unusable. */
const FALLBACK_TARGET = 'en';

/**
 * Resolves the pair to translate with.
 *
 * Resolution order, per side:
 * - The reader's per-book source choice wins; otherwise an explicit source
 *   preference; otherwise the book's language; otherwise the pair cannot be
 *   formed (`unknown-source`). The app locale is deliberately NOT used as the
 *   source: that fallback is what produced the `en -> en` pair the service
 *   rejects, and guessing the reader's language from the UI would produce a
 *   confident answer in the wrong language.
 * - Explicit target preference wins; otherwise the app locale; otherwise
 *   English. The target has no per-book level at all.
 *
 * Codes are normalised to their ISO 639-1 primary subtag (`pt-BR` -> `pt`,
 * `en_US` -> `en`, `zh-Hans-CN` -> `zh`) because MyMemory keys on the primary
 * subtag. Two variants of one base language (`en-US` vs `en-GB`) are therefore
 * the SAME language; whether that is refused or corrected depends on who chose
 * it — see the degenerate-pair rule below.
 *
 * The degenerate-pair rule. When both sides resolve to the same language:
 * - A source the reader CHOSE — the per-book choice or the account preference —
 *   is REFUSED (`same-language`). They deliberately picked it; silently
 *   translating their Spanish book into English when they asked for Spanish
 *   would answer a question they did not ask. The per-book choice is included
 *   here: it is a decision about one book, not a weaker kind of decision.
 * - An explicit `targetLanguage` preference is likewise never rewritten: the
 *   pair is refused instead.
 * - Otherwise the app INFERRED the source (from the book, or from detection),
 *   and it was the inference that produced the collision — so it is corrected
 *   to English (`FALLBACK_TARGET`) and the translation proceeds. The reader
 *   asked for a translation and gets one.
 * - EXCEPT when the inferred source is itself English: falling back to English
 *   would return `en -> en`, the same pair the service rejects. That stays a
 *   `same-language` refusal.
 */
export function resolveLanguagePreferencePair(
  preferences: TranslationLanguagePreferences,
  bookLanguage: string | null | undefined,
  appLocale: string | null | undefined,
  bookSourceLanguage: string | null | undefined = null,
): LanguageResolution {
  // Both sides are resolved before either is judged, so a refusal can report
  // the partial pair even when the source is the side that failed.
  const source = resolveSource(preferences.sourceLanguage, bookLanguage, bookSourceLanguage);
  const target = resolveTarget(preferences.targetLanguage, appLocale);
  const resolvedTarget = typeof target === 'string' ? target : null;

  if (!source.resolved) {
    return {
      kind: 'no-translation',
      reason: source.reason,
      source: null,
      target: resolvedTarget,
      sourceOrigin: null,
    };
  }

  if (typeof target !== 'string') {
    return {
      kind: 'no-translation',
      reason: target.reason,
      source: source.source,
      target: resolvedTarget,
      sourceOrigin: source.origin,
    };
  }

  if (source.source === target) {
    // An INFERRED source that equals the target is not the reader's doing: the
    // app guessed it, so correcting the guess is a service to the reader. Three
    // guards stop it from becoming a silent rewrite:
    // - a source the reader CHOSE (book or account) is a decision, not an
    //   inference, and a deliberate `es -> es` is exactly what they asked to be
    //   told about;
    // - an explicit `targetLanguage` preference is likewise a decision; the
    //   reader asked for that language and only a refusal can tell them why it
    //   cannot be honoured;
    // - falling back to English is pointless when English is ALREADY the source
    //   (`en -> en`), so that pair stays the genuinely degenerate one.
    const targetWasExplicit =
      preferences.targetLanguage !== null && preferences.targetLanguage !== undefined;
    const sourceWasChosen = source.origin === 'preference' || source.origin === 'bookPreference';
    if (!sourceWasChosen && !targetWasExplicit && source.source !== FALLBACK_TARGET) {
      return {
        kind: 'translate',
        source: source.source,
        target: FALLBACK_TARGET,
        sourceOrigin: source.origin,
      };
    }
    return {
      kind: 'no-translation',
      reason: 'same-language',
      source: source.source,
      target,
      sourceOrigin: source.origin,
    };
  }
  return {
    kind: 'translate',
    source: source.source,
    target,
    sourceOrigin: source.origin,
  };
}

/** A side is either a resolved code or the reason it could not be resolved. */
type SideResolution = string | { reason: NoTranslationReason };

/**
 * A source side, plus WHERE it came from.
 *
 * The origin is decided by the cascade step that produced the code, not derived
 * afterwards from the code: `'en'` from the book and `'en'` from a preference are
 * the same string and different promises, so re-deriving would be a guess.
 *
 * Modelled as a union keyed on `resolved` rather than `origin: SourceOrigin |
 * null`, so a resolved code always carries a non-null origin and a caller gets
 * one narrowing that covers both fields: there is no provenance to report for a
 * source that does not exist, and no resolved source without one.
 */
type SourceResolution =
  | { resolved: true; source: string; origin: SourceOrigin }
  | { resolved: false; reason: NoTranslationReason };

/**
 * The SOURCE cascade, in the order the feature defines.
 *
 * 1. The reader's choice for THIS book (`bookPreference`). It wins outright: it
 *    is the most specific thing anyone said.
 * 2. The account's explicit source preference. `null` at the book level means
 *    "no choice for this book" and falls through — it is NOT "automatic", which
 *    would be a decision for the book that stops the cascade here.
 * 3. `Book.language`, and (in the detection variant) local detection.
 *
 * The origins are attached by the step that produced the code, never re-derived
 * from the code afterwards: the same `'en'` from a book choice and from an
 * account preference is the same string and a different promise.
 */
function resolveSource(
  preferred: string | null | undefined,
  bookLanguage: string | null | undefined,
  bookSourceLanguage: string | null | undefined,
): SourceResolution {
  if (bookSourceLanguage !== null && bookSourceLanguage !== undefined) {
    return sourceOrReason(
      normalizeOrRefuse(bookSourceLanguage, 'invalid-source'),
      'bookPreference',
    );
  }
  if (preferred !== null && preferred !== undefined) {
    return sourceOrReason(normalizeOrRefuse(preferred, 'invalid-source'), 'preference');
  }
  return sourceOrReason(normalizeOrRefuse(bookLanguage, 'unknown-source'), 'book');
}

/**
 * Attaches the origin of the step that ran, or reports the step's refusal reason.
 * Shared so both cascades report provenance the same way.
 */
function sourceOrReason(resolution: SideResolution, origin: SourceOrigin): SourceResolution {
  return typeof resolution === 'string'
    ? { resolved: true, source: resolution, origin }
    : { resolved: false, reason: resolution.reason };
}

/**
 * The SOURCE cascade with a local detection step, for a caller that has the
 * selected text in hand.
 *
 * Same resolution as {@link resolveLanguagePreferencePair}, except that when
 * none of the choices, nor the book's language, can name the source, the
 * language is DETECTED locally from `text` before giving up. The detection
 * exists because MyMemory does not accept `auto` as a source
 * (`langpair=auto|es` answers `403 "'AUTO' IS AN INVALID SOURCE LANGUAGE..."`,
 * measured), so this is the only fallback available.
 *
 * Order, and why:
 * 1. The reader's choice for THIS book — the most specific thing anyone said.
 * 2. Explicit source preference — the reader's word beats every inference.
 * 3. `Book.language`, when usable. When it exists it WINS, even when detection
 *    would disagree: the metadata is declared by whoever produced the book, and
 *    detection is a heuristic guess over a handful of function words. A wrong
 *    guess is silently fluent, so the declared value is the honest one to trust.
 * 4. `detectLanguage(text)` — the real fallback for a book with no language.
 * 5. `unknown-source`, which the panel turns into a question.
 *
 * The target side is deliberately untouched here: its cascade (secondary
 * language, etc.) is a separate concern that layers on top by passing the
 * resolved pair through. Keeping the two sides apart means adding to the target
 * cascade does not require rewriting the source one — and it is also why the
 * target has no per-book level: the book is the wrong scope for it.
 *
 * `bookLanguage` that is present but unusable (`'  '`, `'1'`) normalises to
 * `null`, which is "the book said nothing" — NOT a hard refusal — so step 4
 * still runs. `bookSourceLanguage` is deliberately NOT treated that way: it is
 * only ever `null` when the reader made no choice, so a choice that is present
 * but unusable is an `invalid-source` refusal rather than a fall-through.
 *
 * The TARGET side is the reader's own, exactly as on the preference path:
 * `targetLanguage` wins, then `appLocale`, then English. Passing a hard-coded
 * `null` here used to discard the saved target and resolve every translation to
 * the app locale, so a target chosen in Settings (or in the bar) was stored and
 * then ignored. The two resolvers now take the target preference the same way.
 */
export function resolveTranslationSourceWithDetection(
  sourceLanguage: string | null | undefined,
  bookLanguage: string | null | undefined,
  text: string,
  appLocale: string | null | undefined,
  bookSourceLanguage: string | null | undefined = null,
  targetLanguage: string | null | undefined = null,
): LanguageResolution {
  // The target is resolved FIRST: a refusal on the source side still has to be
  // able to name the target it resolved. An unusable explicit target reports
  // `invalid-target` — the same refusal the preference path gives.
  const target = resolveTarget(targetLanguage, appLocale);
  const resolvedTarget = typeof target === 'string' ? target : null;

  const source = resolveSourceWithDetection(sourceLanguage, bookLanguage, text, bookSourceLanguage);
  if (!source.resolved) {
    return {
      kind: 'no-translation',
      reason: source.reason,
      source: null,
      target: resolvedTarget,
      sourceOrigin: null,
    };
  }

  if (typeof target !== 'string') {
    return {
      kind: 'no-translation',
      reason: target.reason,
      source: source.source,
      target: resolvedTarget,
      sourceOrigin: source.origin,
    };
  }

  if (source.source === target) {
    // The SAME rule as the preference path, and deliberately spelled the same
    // way so the two cannot drift: a source the reader CHOSE (book or account)
    // is a decision and stays a refusal, an explicit target is likewise never
    // rewritten, and only an inference the APP made is corrected — to English,
    // except when English is already the source (`en -> en`), which is the
    // genuinely degenerate pair.
    const targetWasExplicit = targetLanguage !== null && targetLanguage !== undefined;
    const sourceWasChosen = source.origin === 'preference' || source.origin === 'bookPreference';
    if (!sourceWasChosen && !targetWasExplicit && source.source !== FALLBACK_TARGET) {
      return {
        kind: 'translate',
        source: source.source,
        target: FALLBACK_TARGET,
        sourceOrigin: source.origin,
      };
    }
    return {
      kind: 'no-translation',
      reason: 'same-language',
      source: source.source,
      target,
      sourceOrigin: source.origin,
    };
  }
  return {
    kind: 'translate',
    source: source.source,
    target,
    sourceOrigin: source.origin,
  };
}

function resolveSourceWithDetection(
  preferred: string | null | undefined,
  bookLanguage: string | null | undefined,
  text: string,
  bookSourceLanguage: string | null | undefined,
): SourceResolution {
  if (bookSourceLanguage !== null && bookSourceLanguage !== undefined) {
    return sourceOrReason(
      normalizeOrRefuse(bookSourceLanguage, 'invalid-source'),
      'bookPreference',
    );
  }
  if (preferred !== null && preferred !== undefined) {
    return sourceOrReason(normalizeOrRefuse(preferred, 'invalid-source'), 'preference');
  }
  const fromBook = normalizeLanguageTag(bookLanguage);
  if (fromBook !== null) return { resolved: true, source: fromBook, origin: 'book' };
  const detected = detectLanguage(text);
  if (detected !== null) return { resolved: true, source: detected, origin: 'detected' };
  return { resolved: false, reason: 'unknown-source' };
}

function resolveTarget(
  preferred: string | null | undefined,
  appLocale: string | null | undefined,
): SideResolution {
  if (preferred !== null && preferred !== undefined) {
    return normalizeOrRefuse(preferred, 'invalid-target');
  }
  return normalizeLanguageTag(appLocale) ?? FALLBACK_TARGET;
}

/**
 * Normalises a tag, or reports the side-specific reason it could not be used.
 * Shared so the cascade variants never re-implement the reason mapping.
 */
function normalizeOrRefuse(
  raw: string | null | undefined,
  reason: NoTranslationReason,
): SideResolution {
  return normalizeLanguageTag(raw) ?? { reason };
}
