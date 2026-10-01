import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createSettingsLocale } from '$lib/features/settings/useSettingsLocale.svelte';
import { i18n } from '$lib/shared/i18n';

describe('useSettingsLocale', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('starts on the fallback locale with no error', () => {
    const l = createSettingsLocale();
    expect(l.locale).toBe(i18n.FALLBACK_LOCALE);
    expect(l.settingsError).toBeNull();
    expect(l.settingsUnavailable).toBeNull();
  });

  it('handleLocaleSelect persists, notifies and updates the locale', async () => {
    const setLocale = vi.fn().mockResolvedValue(undefined);
    const onLocaleChange = vi.fn();
    const l = createSettingsLocale({ setLocale, onLocaleChange });

    await l.handleLocaleSelect('en');

    expect(setLocale).toHaveBeenCalledWith('en');
    expect(onLocaleChange).toHaveBeenCalledWith('en');
    expect(l.locale).toBe('en');
    expect(l.settingsError).toBeNull();
  });

  it('handleLocaleSelect falls back to the fallback locale for unsupported values', async () => {
    const setLocale = vi.fn().mockResolvedValue(undefined);
    const onLocaleChange = vi.fn();
    const l = createSettingsLocale({ setLocale, onLocaleChange });

    await l.handleLocaleSelect('klingon');

    expect(l.locale).toBe(i18n.FALLBACK_LOCALE);
    expect(onLocaleChange).toHaveBeenCalledWith(i18n.FALLBACK_LOCALE);
  });

  it('loadLocale adopts the persisted locale and notifies the parent', async () => {
    const getLocaleSetting = vi.fn().mockResolvedValue('en');
    const onLocaleChange = vi.fn();
    const l = createSettingsLocale({ getLocaleSetting, onLocaleChange });

    await l.loadLocale();

    expect(l.locale).toBe('en');
    expect(onLocaleChange).toHaveBeenCalledWith('en');
  });

  it('loadLocale ignores an unsupported persisted value', async () => {
    const getLocaleSetting = vi.fn().mockResolvedValue('fr');
    const onLocaleChange = vi.fn();
    const l = createSettingsLocale({ getLocaleSetting, onLocaleChange });

    await l.loadLocale();

    expect(l.locale).toBe(i18n.FALLBACK_LOCALE);
    expect(onLocaleChange).not.toHaveBeenCalled();
  });

  it('surfaces a non-recoverable write failure as settingsError', async () => {
    const setLocale = vi.fn().mockRejectedValue(new Error('disk on fire'));
    const l = createSettingsLocale({ setLocale });

    await l.handleLocaleSelect('en');

    expect(l.settingsError).toBe('disk on fire');
    expect(l.settingsUnavailable).toBeNull();
  });

  it('surfaces a recoverable write failure as settingsUnavailable', async () => {
    const error = Object.assign(new Error('offline'), {
      commandError: { code: 'NETWORK', message: 'offline', recoverable: true },
    });
    const setLocale = vi.fn().mockRejectedValue(error);
    const l = createSettingsLocale({ setLocale });

    await l.handleLocaleSelect('en');

    expect(l.settingsUnavailable).toBe('offline');
    expect(l.settingsError).toBeNull();
  });
});
