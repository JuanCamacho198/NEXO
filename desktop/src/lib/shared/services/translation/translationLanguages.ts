/**
 * Resolves the translation language pair for a reader selection.
 *
 * Source comes from the book (`LibraryBookDto.language`, ISO 639-1, optional in
 * practice), target from the UI locale. Picking the languages is a separate
 * concern with its own feature; this only normalises the two into a pair
 * MyMemory accepts, falling back to the UI locale when the book is silent.
 *
 * A single language pair also means a book in the UI's own language translates
 * a selection to itself — the Rust echo guard turns that into an honest "no
 * translation" instead of a bogus answer.
 */

/** Normalises a tag like `pt-BR`, `en_US` or ` es ` to `es` / `en` / `pt`. */
export function normalizeLanguageTag(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (trimmed.length === 0) return null;
  const primary = trimmed.split(/[-_]/)[0]?.trim().toLowerCase() ?? '';
  if (primary.length < 2 || !/^[a-z]+$/.test(primary)) return null;
  return primary.slice(0, 2);
}

export type TranslationLanguages = { sourceLang: string; targetLang: string };

/**
 * Source = book language (or the UI locale when the book has none), target =
 * UI locale. Both are normalised; the UI locale is assumed already supported
 * and is only a last-resort fallback so a pair always exists.
 */
export function resolveTranslationLanguages(
  bookLanguage: string | null | undefined,
  uiLocale: string,
): TranslationLanguages {
  const targetLang = normalizeLanguageTag(uiLocale) ?? 'en';
  const sourceLang = normalizeLanguageTag(bookLanguage) ?? targetLang;
  return { sourceLang, targetLang };
}
