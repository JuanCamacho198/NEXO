/**
 * Selection translation port.
 *
 * The Rust `translateSelection` command performs the MyMemory request. There is
 * deliberately NO cache behind this port: the desktop has no active-account
 * mechanism, and translated selections are reading history. On Android the
 * translation cache was global and leaked between accounts — a real bug. This
 * feature ships without a cache, without history and without language
 * preferences so that risk cannot exist here.
 *
 * Source language comes from the book, target language from the UI locale.
 * Picking them is a separate concern; the port only carries them.
 */
import type { NoTranslationReason } from '$lib/shared/services/translation/translationLanguageResolution';
import type { TranslationCacheEntryDto, TranslationPreferencesDto } from '$lib/shared/types';

export interface TranslationPort {
  /**
   * Translates `text` from `sourceLang` to `targetLang` (ISO 639-1 codes).
   *
   * Resolves to the translated string. Rejects when the text is not
   * translatable (too short, no letters, a URL/email), when the service
   * echoes the original, returns an HTML entity, exhausts its quota, or is
   * unreachable. Callers must show "no translation" rather than the original.
   */
  translate(input: { text: string; sourceLang: string; targetLang: string }): Promise<string>;

  /**
   * The active account's saved language pair, or `null` when none was ever
   * saved. The account is resolved in Rust; nothing here is caller-scoped.
   */
  getPreferences(): Promise<TranslationPreferencesDto | null>;

  /** Persists the pair for the active account and returns what was stored. */
  savePreferences(input: {
    sourceLanguage: string | null;
    targetLanguage: string | null;
  }): Promise<TranslationPreferencesDto>;

  /**
   * Persists the language a "Definir" result is written in (D6) and returns
   * what was stored.
   *
   * Its own call rather than a third field on `savePreferences` because it is
   * saved by a different gesture: the pair has one button for both sides, this
   * is a selector that saves on change. Sending the pair along would let a
   * stale source/target in the form overwrite a fresh one.
   */
  saveDefinitionLanguage(input: {
    definitionLanguage: string | null;
  }): Promise<TranslationPreferencesDto>;

  /** The account's translation history, newest first. */
  listHistory(): Promise<TranslationCacheEntryDto[]>;

  /** Forgets every cached translation of the active account. */
  clearHistory(): Promise<number>;
}

/**
 * Outcome of a translate request, shaped for the toolbar.
 *
 * `no-result` and `unavailable` are kept apart because they are different
 * truths: `no-result` means the service answered but the answer was not a
 * translation (an echo, an HTML entity, untranslatable input); `unavailable`
 * means the request itself failed (offline, quota, HTTP error). Showing the
 * same copy for both would hide a real outage behind a bland "no result".
 *
 * `refused` is a third truth and is decided BEFORE any request: the language
 * pair could not be formed (the book has no language and no source preference
 * was set, both sides are the same language, a preference is unusable). There
 * is no service involved, so each reason gets its own copy naming the fix —
 * previously these all collapsed into `no-result`, which told the reader
 * nothing and offered no way out.
 *
 * `ok` and `refused` carry the PAIR the request ran (or would have run) with,
 * as the resolution resolved it — `null` on a side the cascade could not name.
 * Two reasons it travels on the outcome rather than being re-derived by the
 * panel: a refusal that names the pair cannot offer to fix it without showing
 * which side is missing, and re-resolving downstream would re-run detection and
 * could resolve a DIFFERENT pair than the one the result was produced with.
 * `no-result` and `unavailable` carry no pair: one is a service answer, the
 * other a transport failure, and neither is a statement about languages — a
 * selector there would offer to change something that was not the problem.
 */
export type TranslationOutcome =
  | { status: 'ok'; text: string; source: string; target: string }
  | { status: 'no-result' }
  | { status: 'unavailable' }
  | {
      status: 'refused';
      reason: NoTranslationReason;
      source: string | null;
      target: string | null;
    };
