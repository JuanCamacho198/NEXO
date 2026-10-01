import { i18n } from '$lib/shared/i18n';
import type { CommandErrorDto, UiLocale } from '$lib/shared/types';
import type { SettingsPort } from '$lib/shared/ports/SettingsPort';
import { TauriSettingsAdapter } from '$lib/shared/ports/adapters/tauri/TauriSettingsAdapter';

export type LocaleDeps = {
  settingsPort?: SettingsPort;
  getLocaleSetting?: () => Promise<string | null>;
  setLocale?: (locale: UiLocale) => Promise<void>;
  toSupportedLocale?: (value: string | null) => UiLocale | null;
  onLocaleChange?: (locale: UiLocale) => void;
};

type MaybeCommandError = Error & { commandError?: CommandErrorDto };

const mapCommandErrorMessage = (error: unknown): { message: string; recoverable: boolean } => {
  const err = error as MaybeCommandError;
  const fallback = error instanceof Error ? error.message : 'Settings command failed.';
  if (err.commandError)
    return { message: err.commandError.message, recoverable: err.commandError.recoverable };
  return { message: fallback, recoverable: false };
};

export function createSettingsLocale(deps: LocaleDeps = {}): {
  locale: UiLocale;
  settingsError: string | null;
  settingsUnavailable: string | null;
  handleLocaleSelect: (value: string) => Promise<void>;
  loadLocale: () => Promise<void>;
} {
  const settingsPort: SettingsPort = deps.settingsPort ?? new TauriSettingsAdapter();
  const getLocaleSettingFn = deps.getLocaleSetting ?? (() => settingsPort.getLocale());
  const setLocaleFn =
    deps.setLocale ?? ((locale: UiLocale): Promise<void> => i18n.setLocale(locale));
  const toSupportedLocaleFn =
    deps.toSupportedLocale ?? ((v: string | null): UiLocale | null => i18n.toSupportedLocale(v));

  const fallbackLocale = (i18n.FALLBACK_LOCALE ?? 'en') as UiLocale;

  let locale = $state<UiLocale>(fallbackLocale);
  let settingsError = $state<string | null>(null);
  let settingsUnavailable = $state<string | null>(null);

  async function handleLocaleSelect(value: string): Promise<void> {
    const safeLocale = toSupportedLocaleFn(value) ?? fallbackLocale;
    locale = safeLocale;
    deps.onLocaleChange?.(safeLocale);
    settingsError = null;
    settingsUnavailable = null;
    try {
      await setLocaleFn(safeLocale);
    } catch (error) {
      const details = mapCommandErrorMessage(error);
      if (details.recoverable) settingsUnavailable = details.message;
      else settingsError = details.message;
    }
  }

  async function loadLocale(): Promise<void> {
    settingsError = null;
    settingsUnavailable = null;
    try {
      const persistedLocale = toSupportedLocaleFn(await getLocaleSettingFn());
      if (persistedLocale) {
        locale = persistedLocale;
        deps.onLocaleChange?.(persistedLocale);
      }
    } catch (error) {
      const details = mapCommandErrorMessage(error);
      if (details.recoverable) settingsUnavailable = details.message;
      else settingsError = details.message;
    }
  }

  return {
    get locale() {
      return locale;
    },
    set locale(v: UiLocale) {
      locale = v;
    },
    get settingsError() {
      return settingsError;
    },
    get settingsUnavailable() {
      return settingsUnavailable;
    },
    handleLocaleSelect,
    loadLocale,
  };
}
