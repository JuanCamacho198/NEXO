import { beforeEach, describe, expect, it } from 'vitest';
import { MockSettingsAdapter } from '$lib/shared/ports/adapters/mock/MockSettingsAdapter';
import {
  DEBUG_ENABLED_SETTING_KEY,
  getCachedDebugEnabled,
  loadDebugEnabled,
  parseDebugEnabled,
  resetDebugPreferencesForTests,
  setDebugEnabled,
  setDebugPreferencesPort,
} from '$lib/shared/services/debugPreferences';

describe('debugPreferences — model', () => {
  it('defaults to off', () => {
    resetDebugPreferencesForTests();
    expect(getCachedDebugEnabled()).toBe(false);
  });

  it('parses only a true boolean as on', () => {
    expect(parseDebugEnabled(true)).toBe(true);
    expect(parseDebugEnabled(false)).toBe(false);
    expect(parseDebugEnabled(null)).toBe(false);
    expect(parseDebugEnabled('true')).toBe(false);
    expect(parseDebugEnabled(1)).toBe(false);
  });
});

describe('debugPreferences — persistence', () => {
  let settings: MockSettingsAdapter;

  beforeEach(() => {
    settings = new MockSettingsAdapter();
    resetDebugPreferencesForTests();
    setDebugPreferencesPort(settings);
  });

  it('is off before any load', async () => {
    expect(await loadDebugEnabled()).toBe(false);
  });

  it('persists the flag across restart at the port level', async () => {
    await setDebugEnabled(true);

    // A restart drops the in-memory cache; loading must restore the stored flag.
    resetDebugPreferencesForTests();
    setDebugPreferencesPort(settings);
    expect(await loadDebugEnabled()).toBe(true);
    expect(getCachedDebugEnabled()).toBe(true);

    await setDebugEnabled(false);
    resetDebugPreferencesForTests();
    setDebugPreferencesPort(settings);
    expect(await loadDebugEnabled()).toBe(false);
  });

  it('stores the flag under the debug key as JSON', async () => {
    await setDebugEnabled(true);
    const stored = await settings.getAppSettings();
    const entry = stored.find((item) => item.key === DEBUG_ENABLED_SETTING_KEY);
    expect(entry?.valueJson).toBe('true');
  });

  it('unknown storage content falls back to off', async () => {
    await settings.upsertAppSettings([
      { key: DEBUG_ENABLED_SETTING_KEY, valueJson: 'not-json{{{', updatedAt: '' },
    ]);
    expect(await loadDebugEnabled()).toBe(false);
  });
});
