import type { UiKey } from '@/i18n/ui.en';
import { messagesEn } from '@/i18n/ui.en';
import { messagesEs } from '@/i18n/ui.es';

export type UiLocale = 'es' | 'en';

export const defaultLocale: UiLocale = 'es';

export const locales: readonly UiLocale[] = ['es', 'en'];

const dictionaries: Record<UiLocale, Record<UiKey, string>> = {
  es: messagesEs,
  en: messagesEn,
};

export type TranslationParams = Record<string, string | number>;

export function interpolate(template: string, params?: TranslationParams): string {
  if (!params) {
    return template;
  }
  return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, key: string) => {
    const value = params[key];
    return value === undefined ? '' : String(value);
  });
}

export function t(locale: UiLocale, key: UiKey, params?: TranslationParams): string {
  return interpolate(dictionaries[locale][key], params);
}

export function getLocaleFromUrl(url: URL): UiLocale {
  const firstSegment = url.pathname.split('/').filter(Boolean)[0];
  if (firstSegment === 'en') {
    return 'en';
  }
  return defaultLocale;
}
