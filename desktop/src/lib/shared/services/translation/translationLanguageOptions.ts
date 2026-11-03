/**
 * The language list offered by the translation settings tab.
 *
 * A curated list, not "every ISO 639-1 code": MyMemory only answers for a
 * subset, and offering a code it silently ignores would let the reader save a
 * preference that produces an empty answer. Codes are primary subtags, matching
 * `normalizeLanguageTag`, so what is stored is exactly what the service keys on.
 */
export const TRANSLATION_LANGUAGE_OPTIONS = [
  'en',
  'es',
  'pt',
  'fr',
  'de',
  'it',
  'nl',
  'pl',
  'ru',
  'tr',
  'ar',
  'zh',
  'ja',
  'ko',
  'hi',
] as const;

export type TranslationLanguageOption = (typeof TRANSLATION_LANGUAGE_OPTIONS)[number];

/**
 * i18n key for a language name. The names live in the message catalogue rather
 * than in an Intl lookup so the label is translated in the reader's own
 * language, exactly like every other string in the panel.
 */
const LANGUAGE_LABEL_KEY = (code: string): string => `settings.translation.language.${code}`;

/** Resolves a code to its translated name, falling back to the bare code. */
export function languageLabel(code: string, translate: (key: string) => string): string {
  const label = translate(LANGUAGE_LABEL_KEY(code));
  return !label || label === LANGUAGE_LABEL_KEY(code) ? code.toUpperCase() : label;
}
