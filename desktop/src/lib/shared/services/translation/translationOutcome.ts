/**
 * Maps a translation rejection to the outcome the toolbar shows.
 *
 * The distinction is deliberate and user-facing: `no-result` means the service
 * answered without a translation (an echo, an HTML entity, untranslatable
 * input, or the same-language 403), which the user can act on by selecting
 * something else; `unavailable` means the request itself failed (offline,
 * quota, HTTP error), which is a service condition, not the selection's fault.
 */
import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';

/** Rust error codes from `commands/translate.rs` that mean "no translation". */
const NO_RESULT_CODES = new Set(['TRANSLATE_NO_TRANSLATION', 'TRANSLATE_UNTRANSLATABLE_INPUT']);

export function translationFailureOutcome(error: unknown): TranslationOutcome {
  const code = (error as { commandError?: { code?: string } })?.commandError?.code ?? '';
  if (NO_RESULT_CODES.has(code)) return { status: 'no-result' };
  return { status: 'unavailable' };
}
