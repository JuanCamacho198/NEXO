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

/**
 * The `${code}: ${detail}` prefix `TranslateError`'s `Display` writes.
 *
 * The command is `Result<String, String>` and stringifies its typed error with
 * `.map_err(|e| e.to_string())`, so the rejection that reaches here is that bare
 * string — never JSON. Recovering the code from the SHAPE the backend already
 * emits is what makes this mapping work at all: reading only a pre-attached
 * `commandError.code` left `code` empty for every translate failure, so every
 * one of them — including an honest "there is no translation" — rendered as
 * "service unavailable" and `no-result` was unreachable.
 *
 * A string that does not match keeps the old answer (`unavailable`): an
 * unrecognised failure must never be reclaimed as a normal "no translation".
 */
const BARE_ERROR_CODE = /^(TRANSLATE_[A-Z0-9_]+): ?/;

/** The rust code this rejection names, from either carrier. `''` when unknown. */
function translationErrorCode(error: unknown): string {
  const attached = (error as { commandError?: { code?: string } })?.commandError?.code;
  if (typeof attached === 'string' && attached.length > 0) return attached;

  const message =
    typeof error === 'string'
      ? error
      : error instanceof Error
        ? error.message
        : typeof (error as { message?: unknown })?.message === 'string'
          ? ((error as { message: string }).message as string)
          : '';
  return BARE_ERROR_CODE.exec(message)?.[1] ?? '';
}

export function translationFailureOutcome(error: unknown): TranslationOutcome {
  if (NO_RESULT_CODES.has(translationErrorCode(error))) return { status: 'no-result' };
  return { status: 'unavailable' };
}
