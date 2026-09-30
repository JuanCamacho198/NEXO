import { writable } from 'svelte/store';
import { SUPPORTED_UI_LOCALES, type UiLocale } from '$lib/shared/types';
import type { SettingsPort } from '$lib/shared/ports/SettingsPort';
import { TauriSettingsAdapter } from '$lib/shared/ports/adapters/tauri/TauriSettingsAdapter';
import { messagesEn, type MessageKey } from './messages.en';
import { messagesEs } from './messages.es';
import { logger } from '$lib/shared/logger/Logger';
import { createErrorEvent } from '$lib/shared/events/ErrorEvent';

type TranslationParams = Record<string, string | number>;

const DEFAULT_LOCALE: UiLocale = 'es';
const FALLBACK_LOCALE: UiLocale = 'en';
const LOCALE_STORAGE_KEY = 'nexo.ui.locale';

const supportedLocales = new Set<string>(SUPPORTED_UI_LOCALES);

const dictionaries: Record<UiLocale, Partial<Record<MessageKey, string>>> = {
  es: messagesEs,
  en: messagesEn,
};

const toSupportedLocale = (value: string | null | undefined): UiLocale | null => {
  if (!value) {
    return null;
  }

  const normalized = value.trim().toLowerCase();
  if (!supportedLocales.has(normalized)) {
    return null;
  }

  return normalized as UiLocale;
};

const interpolate = (template: string, params?: TranslationParams): string => {
  if (!params) {
    return template;
  }

  return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, key: string) => {
    const value = params[key];
    return value === undefined ? '' : String(value);
  });
};

let missingKeysLogged = new Set<string>();

const resolveMessage = (locale: UiLocale, key: MessageKey): string => {
  const primary = dictionaries[locale][key];
  if (primary) {
    return primary;
  }

  const fallback = dictionaries[FALLBACK_LOCALE][key];
  if (fallback) {
    // Log warning once per missing key per locale
    const logKey = `${locale}:${key}`;
    if (!missingKeysLogged.has(logKey)) {
      missingKeysLogged.add(logKey);
      logger.warn(
        createErrorEvent({
          severity: 'low',
          category: 'runtime',
          code: 'I18N_MISSING_KEY',
          message: `i18n key "${key}" not found in locale "${locale}", using fallback "${FALLBACK_LOCALE}"`,
          context: { locale, key, fallbackLocale: FALLBACK_LOCALE },
          source: 'app_shell' as const,
          recoverable: true,
        }),
      );
    }
    return fallback;
  }

  // Log warning once per missing key (no translation found at all)
  if (!missingKeysLogged.has(key)) {
    missingKeysLogged.add(key);
    logger.warn(
      createErrorEvent({
        severity: 'low',
        category: 'runtime',
        code: 'I18N_MISSING_KEY',
        message: `i18n key "${key}" not found in any locale`,
        context: { key, locales: Object.keys(dictionaries) },
        source: 'app_shell' as const,
        recoverable: true,
      }),
    );
  }

  return key;
};

const createI18nStore = (
  deps: { settingsPort?: SettingsPort } = {},
): {
  locale: import('svelte/store').Writable<UiLocale>;
  setLocale: (nextLocale: string | UiLocale) => Promise<void>;
  initializeLocale: () => Promise<UiLocale>;
  t: (locale: UiLocale, key: MessageKey, params?: TranslationParams) => string;
  readonly DEFAULT_LOCALE: UiLocale;
  readonly FALLBACK_LOCALE: UiLocale;
  toSupportedLocale: (value: string | null | undefined) => UiLocale | null;
} => {
  const settingsPort: SettingsPort = deps.settingsPort ?? new TauriSettingsAdapter();
  const localeStore = writable<UiLocale>(DEFAULT_LOCALE);

  const setLocale = async (nextLocale: string | UiLocale): Promise<void> => {
    const safeLocale = toSupportedLocale(nextLocale) ?? FALLBACK_LOCALE;
    localeStore.set(safeLocale);
    globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, safeLocale);
    await settingsPort.upsertLocale(safeLocale);
  };

  const initializeLocale = async (): Promise<UiLocale> => {
    const cachedRaw = globalThis.localStorage?.getItem(LOCALE_STORAGE_KEY);
    if (typeof cachedRaw === 'string') {
      const cached = toSupportedLocale(cachedRaw);
      if (cached) {
        localeStore.set(cached);
        return cached;
      }

      localeStore.set(FALLBACK_LOCALE);
      globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, FALLBACK_LOCALE);
      await settingsPort.upsertLocale(FALLBACK_LOCALE);
      return FALLBACK_LOCALE;
    }

    const persistedRaw = await settingsPort.getLocale();
    const persisted = toSupportedLocale(persistedRaw);
    if (persisted) {
      localeStore.set(persisted);
      globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, persisted);
      return persisted;
    }

    if (typeof persistedRaw === 'string' && persistedRaw.length > 0) {
      localeStore.set(FALLBACK_LOCALE);
      globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, FALLBACK_LOCALE);
      await settingsPort.upsertLocale(FALLBACK_LOCALE);
      return FALLBACK_LOCALE;
    }

    localeStore.set(DEFAULT_LOCALE);
    globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, DEFAULT_LOCALE);
    await settingsPort.upsertLocale(DEFAULT_LOCALE);
    return DEFAULT_LOCALE;
  };

  const t = (locale: UiLocale, key: MessageKey, params?: TranslationParams): string => {
    return interpolate(resolveMessage(locale, key), params);
  };

  return {
    locale: localeStore,
    setLocale,
    initializeLocale,
    t,
    DEFAULT_LOCALE,
    FALLBACK_LOCALE,
    toSupportedLocale,
  };
};

export const i18n = createI18nStore();
export { createI18nStore };
export type { MessageKey };
