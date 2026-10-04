import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, unmount } from 'svelte';
import SettingsPanel from '$lib/features/settings/components/SettingsPanel.svelte';
import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
import type { LibraryBookDto } from '$lib/shared/types';

/**
 * Regression: opening Settings -> Cuenta must not crash the locale wiring.
 *
 * The parent (`AppRouter`) passes `locale={settingsState.locale}` with no
 * `bind:`, so the `locale` entry on the props object is a getter-only
 * accessor. `SettingsPanel` forwards locale changes through the
 * `onLocaleChange` prop, which production wires to the unbound
 * `settingsState.handleLocaleChange`. If that handler is invoked with the
 * props object as `this`, its `this.locale = next` write lands on the
 * getter-only accessor and throws the native
 *
 *   Cannot set property locale of #<Object> which has only a getter
 *
 * which the appearance loader swallows into `settingsError` and paints on
 * screen. This test reproduces the production prop shape (getter-only
 * `locale`) and the production handler wiring.
 */

const dictionary: Record<string, string> = {
  'settings.title': 'Settings',
  'settings.tab.account': 'Account',
  'settings.tab.data': 'Data',
  'settings.tab.about': 'About',
  'settings.authentication': 'Authentication',
  'settings.authDescription': 'Sign in to sync',
  'settings.language': 'Language',
  'settings.languageSpanish': 'Spanish',
  'settings.languageEnglish': 'English',
  'settings.theme': 'Theme',
  'settings.theme.light': 'Light',
  'settings.theme.dark': 'Dark',
  'settings.saving': 'Saving...',
  'app.backToHome': 'Back to home',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  let result = dictionary[key] ?? key;
  for (const [k, v] of Object.entries(params ?? {})) {
    result = result.replace(`{{${k}}}`, String(v));
  }
  return result;
};

vi.mock('$lib/shared/api/tauriClient', () => {
  const readerDefaults = {
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    selectionColor: '#3388ff',
    epub: { fontSize: 100, fontFamily: 'serif' },
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    verticalScrolling: false,
    margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
    showHeader: true,
    showFooter: true,
    showPageNumbers: true,
    progressIndicator: 'percentage',
  };
  return {
    listLibraryBooks: vi.fn(async () => []),
    listBooks: vi.fn(async () => []),
    listCollections: vi.fn(async () => []),
    getDefaultReaderSettings: vi.fn(() => readerDefaults),
    getReaderSettings: vi.fn(async () => readerDefaults),
    upsertReaderSettings: vi.fn(async () => readerDefaults),
    resetReaderSettingsToDefaults: vi.fn(async () => readerDefaults),
    getSettings: vi.fn(async () => []),
    upsertSettings: vi.fn(async () => undefined),
    // A persisted locale guarantees `loadAppearance` fires `onLocaleChange`
    // during mount, which is exactly the production open path.
    getLocaleSetting: vi.fn(async () => 'en'),
    upsertLocale: vi.fn(async () => undefined),
    getProgress: vi.fn(async () => null),
    getReadingStats: vi.fn(async () => null),
    listHighlights: vi.fn(async () => []),
    listBookmarks: vi.fn(async () => []),
  };
});

describe('SettingsPanel locale binding regression', () => {
  let app: ReturnType<typeof mount> | null = null;

  beforeEach(() => {
    settingsState.locale = 'es';
  });

  afterEach(() => {
    if (app) unmount(app);
    app = null;
    document.body.innerHTML = '';
  });

  it('forwards the persisted locale to the store without throwing on the getter-only prop', async () => {
    let localeProp: 'es' | 'en' = 'es';
    const target = document.createElement('div');
    document.body.appendChild(target);

    const props = {
      isOpen: true,
      mode: 'page' as const,
      t,
      books: [] as LibraryBookDto[],
      // Production wiring: an unbound class method forwarded straight through.
      onLocaleChange: settingsState.handleLocaleChange,
      onRequestClose: () => {},
      locale: 'es' as const,
    };
    // Mirrors `locale={settingsState.locale}`: a dynamic, getter-only prop.
    Object.defineProperty(props, 'locale', {
      get: () => localeProp,
      enumerable: true,
    });

    app = mount(SettingsPanel, { target, props });

    await vi.waitFor(() => {
      expect(settingsState.locale).toBe('en');
    });

    localeProp = settingsState.locale as 'es' | 'en';
    expect(document.body.textContent).not.toMatch(/getter/i);
  });
});
