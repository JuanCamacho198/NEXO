import { beforeEach, describe, expect, it, vi } from 'vitest';

// Mock the tauriClient module before importing
vi.mock('$lib/shared/api/tauriClient', () => ({
  getLocaleSetting: vi.fn().mockResolvedValue(null),
  upsertLocaleSetting: vi.fn(),
}));

import { getLocaleSetting, upsertLocaleSetting } from '$lib/shared/api/tauriClient';

describe('i18n', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.localStorage?.clear();
  });

  it('uses es default locale on first run and persists it', async () => {
    const mockGetLocale = getLocaleSetting as ReturnType<typeof vi.fn>;
    const mockUpsert = upsertLocaleSetting as ReturnType<typeof vi.fn>;
    mockGetLocale.mockResolvedValueOnce(null);

    const { i18n } = await import('$lib/shared/i18n');
    const locale = await i18n.initializeLocale();

    expect(locale).toBe('es');
    expect(mockUpsert).toHaveBeenCalledWith('es');
    expect(globalThis.localStorage?.getItem('nexo.ui.locale')).toBe('es');
  });

  it('falls back to en for unsupported persisted locale', async () => {
    const mockGetLocale = getLocaleSetting as ReturnType<typeof vi.fn>;
    mockGetLocale.mockResolvedValueOnce('pt');

    const { i18n } = await import('$lib/shared/i18n');
    const locale = await i18n.initializeLocale();

    expect(locale).toBe('en');
    expect(upsertLocaleSetting).toHaveBeenCalledWith('en');
    expect(globalThis.localStorage?.getItem('nexo.ui.locale')).toBe('en');
  });

  // All MessageKey entries are required in both locales (Record<MessageKey, string>),
  // so locale-to-locale fallback is never exercised at runtime.
  // This test verifies that i18n.t() returns the correct translations for each locale.
  it('returns correct translation per locale', async () => {
    const mockGetLocale = getLocaleSetting as ReturnType<typeof vi.fn>;
    mockGetLocale.mockResolvedValueOnce('es');

    const { i18n } = await import('$lib/shared/i18n');
    const locale = await i18n.initializeLocale();

    expect(i18n.t('es', 'errors.commandFailure')).toBe('Fallo desconocido del comando');
    expect(i18n.t('en', 'errors.commandFailure')).toBe('Unknown command failure');
    expect(i18n.t('es', 'errors.settingsCommandFailed')).toBe('Fallo el comando de ajustes.');
    expect(i18n.t('en', 'errors.settingsCommandFailed')).toBe('Settings command failed.');
    void locale;
  });
});
