/**
 * Resolves the language pair for a translation from the user's preferences.
 *
 * Preferences are optional on both sides, mirroring the Android model:
 * `targetLanguage = null` means "use the app locale", `sourceLanguage = null`
 * means "infer it from the book". This module only decides; it performs no I/O
 * and holds no state, so it is testable without mocks.
 *
 * The refusal path is the point of the feature. An English book read with the
 * app in English used to resolve to `en -> en`, and the service answers that
 * with `403 PLEASE SELECT TWO DISTINCT LANGUAGES`; the reader saw a bland
 * "no translation" and no way to understand or fix it. Every way the pair can
 * end up degenerate therefore returns a typed reason instead of a pair.
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
 */
export type LanguageResolution =
  | { kind: 'translate'; source: string; target: string }
  | { kind: 'no-translation'; reason: NoTranslationReason };

/** Last-resort target when the app locale itself is unusable. */
const FALLBACK_TARGET = 'en';

/**
 * Resolves the pair to translate with.
 *
 * Resolution order, per side:
 * - Explicit source preference wins; otherwise the book's language; otherwise
 *   the pair cannot be formed (`unknown-source`). The app locale is
 *   deliberately NOT used as the source: that fallback is what produced the
 *   `en -> en` pair the service rejects, and guessing the reader's language
 *   from the UI would produce a confident answer in the wrong language.
 * - Explicit target preference wins; otherwise the app locale; otherwise
 *   English.
 *
 * Codes are normalised to their ISO 639-1 primary subtag (`pt-BR` -> `pt`,
 * `en_US` -> `en`, `zh-Hans-CN` -> `zh`) because MyMemory keys on the primary
 * subtag. Two variants of one base language (`en-US` vs `en-GB`) are therefore
 * the SAME language and are refused: technically they translate, but the
 * reader asked for a translation and would get back what they already read.
 */
export function resolveLanguagePreferencePair(
  preferences: TranslationLanguagePreferences,
  bookLanguage: string | null | undefined,
  appLocale: string | null | undefined,
): LanguageResolution {
  const source = resolveSource(preferences.sourceLanguage, bookLanguage);
  if (typeof source !== 'string') return { kind: 'no-translation', reason: source.reason };

  const target = resolveTarget(preferences.targetLanguage, appLocale);
  if (typeof target !== 'string') return { kind: 'no-translation', reason: target.reason };

  if (source === target) return { kind: 'no-translation', reason: 'same-language' };
  return { kind: 'translate', source, target };
}

/** A side is either a resolved code or the reason it could not be resolved. */
type SideResolution = string | { reason: NoTranslationReason };

function resolveSource(
  preferred: string | null | undefined,
  bookLanguage: string | null | undefined,
): SideResolution {
  if (preferred !== null && preferred !== undefined) {
    return normalizeOrRefuse(preferred, 'invalid-source');
  }
  return normalizeOrRefuse(bookLanguage, 'unknown-source');
}

/**
 * The SOURCE cascade with a local detection step, for a caller that has the
 * selected text in hand.
 *
 * Same resolution as {@link resolveLanguagePreferencePair}, except that when
 * neither an explicit source preference nor the book's language can name the
 * source, the language is DETECTED locally from `text` before giving up. The
 * detection exists because MyMemory does not accept `auto` as a source
 * (`langpair=auto|es` answers `403 "'AUTO' IS AN INVALID SOURCE LANGUAGE..."`,
 * measured), so this is the only fallback available.
 *
 * Order, and why:
 * 1. Explicit source preference — the reader's word beats every inference.
 * 2. `Book.language`, when usable. When it exists it WINS, even when detection
 *    would disagree: the metadata is declared by whoever produced the book, and
 *    detection is a heuristic guess over a handful of function words. A wrong
 *    guess is silently fluent, so the declared value is the honest one to trust.
 * 3. `detectLanguage(text)` — the real fallback for a book with no language.
 * 4. `unknown-source`, which the panel turns into a question.
 *
 * The target side is deliberately untouched here: its cascade (secondary
 * language, etc.) is a separate concern that layers on top by passing the
 * resolved pair through. Keeping the two sides apart means adding to the target
 * cascade does not require rewriting the source one.
 *
 * `bookLanguage` that is present but unusable (`'  '`, `'1'`) normalises to
 * `null`, which is "the book said nothing" — NOT a hard refusal — so step 3
 * still runs.
 */
export function resolveTranslationSourceWithDetection(
  sourceLanguage: string | null | undefined,
  bookLanguage: string | null | undefined,
  text: string,
  appLocale: string | null | undefined,
): LanguageResolution {
  const source = resolveSourceWithDetection(sourceLanguage, bookLanguage, text);
  if (typeof source !== 'string') return { kind: 'no-translation', reason: source.reason };

  const target = resolveTarget(null, appLocale);
  if (typeof target !== 'string') return { kind: 'no-translation', reason: target.reason };

  if (source === target) return { kind: 'no-translation', reason: 'same-language' };
  return { kind: 'translate', source, target };
}

function resolveSourceWithDetection(
  preferred: string | null | undefined,
  bookLanguage: string | null | undefined,
  text: string,
): SideResolution {
  if (preferred !== null && preferred !== undefined) {
    return normalizeOrRefuse(preferred, 'invalid-source');
  }
  const fromBook = normalizeLanguageTag(bookLanguage);
  if (fromBook !== null) return fromBook;
  return detectLanguage(text) ?? { reason: 'unknown-source' };
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
