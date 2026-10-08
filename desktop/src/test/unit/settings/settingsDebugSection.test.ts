import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import SettingsDebugSection from '$lib/features/settings/components/SettingsDebugSection.svelte';
import { debugState } from '$lib/shared/debug/debugState.svelte';
import { MockSettingsAdapter } from '$lib/shared/ports/adapters/mock/MockSettingsAdapter';
import {
  getCachedDebugEnabled,
  resetDebugPreferencesForTests,
  setDebugPreferencesPort,
} from '$lib/shared/services/debugPreferences';

const messages: Record<string, string> = {
  'settings.debug.title': 'Debug tools',
  'settings.debug.description': 'Show the floating debug toggle and the reader diagnostics.',
  'settings.debug.showDebug': 'Show debug tools',
  'settings.debug.debugOn': 'Debug tools are visible.',
  'settings.debug.debugOff': 'Debug tools are hidden.',
};

const t = (key: string): string => messages[key] ?? key;

describe('SettingsDebugSection (U4.1)', () => {
  let settings: MockSettingsAdapter;

  beforeEach(() => {
    settings = new MockSettingsAdapter();
    resetDebugPreferencesForTests();
    setDebugPreferencesPort(settings);
    debugState.enabled = false;
  });

  afterEach(() => {
    debugState.enabled = false;
    resetDebugPreferencesForTests();
  });

  it('renders the switch off by default', async () => {
    render(SettingsDebugSection, { t });
    const toggle = screen.getByTestId('debug-enabled-switch');
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'));
    expect(getCachedDebugEnabled()).toBe(false);
  });

  it('toggling on flips debugState and persists the flag', async () => {
    render(SettingsDebugSection, { t });
    const toggle = screen.getByTestId('debug-enabled-switch');
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'));

    await fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
    expect(debugState.enabled).toBe(true);

    const stored = await settings.getAppSettings();
    expect(stored.find((item) => item.key === 'debug.enabled')?.valueJson).toBe('true');
  });

  it('restores a persisted on across a fresh mount', async () => {
    await settings.upsertAppSettings([{ key: 'debug.enabled', valueJson: 'true', updatedAt: '' }]);
    render(SettingsDebugSection, { t });
    const toggle = screen.getByTestId('debug-enabled-switch');
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
    expect(debugState.enabled).toBe(true);
  });
});
