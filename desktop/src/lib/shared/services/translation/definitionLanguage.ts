/**
 * The language a "Definir" result is written in (D6).
 *
 * One function, two callers — the settings screen that writes the preference
 * and the reader that spends it — so the two can never disagree about what a
 * stored value means.
 *
 * `null` is "the app locale", which is exactly what "Definir" did before the
 * preference existed: it is a real, working value, not a missing one. That is
 * why an account that has never touched the setting keeps its old behaviour
 * with no migration and no default row.
 *
 * The preference is stored as a bare ISO 639-1 code because the backend turns it
 * into a wiki hostname (`{lang}.wiktionary.org`) and into a MyMemory target —
 * neither accepts a region tag. The locale is a UI locale (`es`, `en`) and is
 * normalised the same way, so both sides of this function produce a comparable
 * code.
 */
import { normalizeLanguageTag } from '$lib/shared/services/translation/translationLanguages';

/** What every definition falls back to when nothing more specific is set. */
export const FALLBACK_DEFINITION_LANGUAGE = 'en';

export function resolveDefinitionLanguage(
  definitionLanguage: string | null | undefined,
  uiLocale: string | null | undefined,
): string {
  return (
    normalizeLanguageTag(definitionLanguage ?? undefined) ??
    normalizeLanguageTag(uiLocale ?? undefined) ??
    FALLBACK_DEFINITION_LANGUAGE
  );
}
