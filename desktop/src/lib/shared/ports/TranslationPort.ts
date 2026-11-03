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
}

/**
 * Outcome of a translate request, shaped for the toolbar.
 *
 * `no-result` and `unavailable` are kept apart because they are different
 * truths: `no-result` means the service answered but the answer was not a
 * translation (an echo, an HTML entity, untranslatable input); `unavailable`
 * means the request itself failed (offline, quota, HTTP error). Showing the
 * same copy for both would hide a real outage behind a bland "no result".
 */
export type TranslationOutcome =
  { status: 'ok'; text: string } | { status: 'no-result' } | { status: 'unavailable' };
