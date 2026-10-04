import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';
import SettingsCuentaTab from '$lib/features/settings/components/SettingsCuentaTab.svelte';
import { createSettingsProfile } from '$lib/features/settings/useSettingsProfile.svelte';
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
  'settings.daily_goal_label': 'Daily goal',
  'settings.daily_goal_change': 'Change',
  'settings.daily_goal_close': 'Close',
  'settings.daily_goal_saved': 'Daily goal saved',
  'settings.daily_goal_save_error': "Couldn't save your daily goal. Try again.",
  'settings.daily_goal_sign_in_required': 'Sign in to save your daily goal',
  'settings.daily_goal_relaxed': 'Relaxed',
  'settings.daily_goal_regular': 'Regular',
  'settings.daily_goal_serious': 'Serious',
  'settings.daily_goal_intense': 'Intense',
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

/**
 * Daily goal redesign: a single collapsed row that expands into inline chips
 * and auto-saves. The previous surface was a 9-element island with a fake
 * 100% progress meter, a Spanish-only aria-label and a "save" button that
 * silently no-op'd for local users. These tests pin the replacement behavior.
 */
function makeProfileState(userId: string | null) {
  const appState = {
    saveDailyGoalMinutes: vi.fn(async () => undefined),
    signOutAndReturnToWelcome: vi.fn(async () => undefined),
  };
  const settingsState = { dailyGoalMinutes: 20 };
  const authState = { userId };
  const profileState = createSettingsProfile({
    appState,
    settingsState,
    authState,
    t,
  } as never);
  return { profileState, appState, settingsState };
}

describe('SettingsCuentaTab daily goal', () => {
  it('collapses the options by default and toggles aria-expanded', async () => {
    const user = userEvent.setup();
    const { profileState } = makeProfileState('user-1');
    render(SettingsCuentaTab, { t, profileState });

    const trigger = screen.getByRole('button', { name: 'Change' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: /Relaxed/ })).toBeNull();

    await user.click(trigger);

    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: /Relaxed/ })).toBeInTheDocument();
  });

  it('auto-saves the picked goal, collapses and announces the result', async () => {
    const user = userEvent.setup();
    const { profileState, appState } = makeProfileState('user-1');
    render(SettingsCuentaTab, { t, profileState });

    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.click(screen.getByRole('button', { name: /Relaxed/ }));

    expect(appState.saveDailyGoalMinutes).toHaveBeenCalledWith(10);
    expect(screen.getByRole('button', { name: 'Change' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Daily goal saved');
  });

  it('never fakes a save for a local user: the control is disabled and explains why', async () => {
    const user = userEvent.setup();
    const { profileState, appState } = makeProfileState(null);
    render(SettingsCuentaTab, { t, profileState });

    const trigger = screen.getByRole('button', { name: 'Change' });
    expect(trigger).toBeDisabled();
    expect(screen.getByText('Sign in to save your daily goal')).toBeInTheDocument();

    await user.click(trigger);

    expect(appState.saveDailyGoalMinutes).not.toHaveBeenCalled();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('renders the row label as a level-3 heading', () => {
    const { profileState } = makeProfileState('user-1');
    render(SettingsCuentaTab, { t, profileState });

    const heading = screen.getByRole('heading', { level: 3, name: /Daily goal/ });
    expect(heading).toBeInTheDocument();
    for (const h of screen.getAllByRole('heading')) {
      expect(h.tagName).toBe('H3');
    }
  });
});
