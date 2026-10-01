import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { get } from 'svelte/store';
import SettingsCuentaTab from '$lib/features/settings/components/SettingsCuentaTab.svelte';
import { theme } from '$lib/shared/stores/theme';

/**
 * The `Cuenta` tab is the single surface for account, language and app theme
 * after the Appearance / Reader settings tabs were removed. The language
 * switcher and the app theme control live here; the app theme control drives
 * the shared `theme` store (`data-theme` / `nexo-theme`), never a second one.
 */

const dictionary: Record<string, string> = {
  'settings.authentication': 'Authentication',
  'settings.authDescription': 'Sign in to sync your data',
  'settings.language': 'Language',
  'settings.languageSpanish': 'Spanish',
  'settings.languageEnglish': 'English',
  'settings.theme': 'Theme',
  'settings.theme.light': 'Light',
  'settings.theme.dark': 'Dark',
  'settings.daily_goal_title': 'Daily goal',
  'settings.daily_goal_description': 'Set how much you want to read',
  'settings.daily_goal_set': 'Set goal',
  'settings.saving': 'Saving...',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  let result = dictionary[key] ?? key;
  for (const [k, v] of Object.entries(params ?? {})) {
    result = result.replace(`{{${k}}}`, String(v));
  }
  return result;
};

function renderCuenta() {
  return render(SettingsCuentaTab, {
    t,
    profile: { name: 'Reader', email: 'reader@example.com', avatarUrl: null, isSignedIn: false },
    dailyGoalCards: [],
    selectedDailyGoal: 20,
    locale: 'es',
  });
}

describe('SettingsCuentaTab language and app theme', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('renders the language switcher with the current locale selected', async () => {
    const user = userEvent.setup();
    renderCuenta();

    expect(screen.getByText('Spanish')).toBeInTheDocument();
    await user.click(screen.getByText('Spanish'));
    expect(screen.getByText('English')).toBeInTheDocument();
  });

  it('exposes the app theme control on the shared theme store', async () => {
    const user = userEvent.setup();
    renderCuenta();

    const group = screen.getByRole('group', { name: 'Theme' });
    expect(group).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Light' }));

    expect(get(theme)).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem('nexo-theme')).toBe('light');

    await user.click(screen.getByRole('button', { name: 'Dark' }));

    expect(get(theme)).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('nexo-theme')).toBe('dark');
  });
});
