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
  'settings.daily_goal_local_hint': 'Saved on this device. Sign in to sync across devices.',
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

describe('SettingsCuentaTab atom migration (UI-04)', () => {
  it('renders the theme pair through the Button atom with pressed state', async () => {
    const user = userEvent.setup();
    renderCuenta();

    const light = screen.getByRole('button', { name: 'Light' });
    const dark = screen.getByRole('button', { name: 'Dark' });
    for (const control of [light, dark]) {
      expect(control).toHaveClass('text-sm', 'font-medium', 'rounded-lg', 'inline-flex');
      expect(control).not.toHaveClass('text-xs');
    }

    await user.click(light);
    expect(light).toHaveAttribute('aria-pressed', 'true');
    expect(dark).toHaveAttribute('aria-pressed', 'false');
  });

  it('renders the daily-goal trigger and chips through the Button atom', async () => {
    const user = userEvent.setup();
    const { profileState } = makeProfileState('user-1');
    render(SettingsCuentaTab, { t, profileState });

    const trigger = screen.getByRole('button', { name: 'Change' });
    expect(trigger).toHaveClass('text-sm', 'font-medium', 'rounded-lg', 'inline-flex');
    expect(trigger).toHaveAttribute('aria-controls', 'daily-goal-options');
    expect(trigger).toHaveAttribute('id', 'daily-goal-trigger');

    await user.click(trigger);
    const chip = screen.getByRole('button', { name: /Relaxed/ });
    expect(chip).toHaveClass('text-sm', 'font-medium', 'rounded-lg', 'inline-flex');
    expect(chip).toHaveAttribute('aria-pressed');
  });

  it('folds the account blocks into Panel sections, not hand-rolled divs', () => {
    const { container } = renderCuenta();
    expect(container.querySelector('div.rounded-xl')).toBeNull();
  });
});
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

  it('lets a local user set the goal, saves it and shows the local-only hint', async () => {
    const user = userEvent.setup();
    const { profileState, appState } = makeProfileState(null);
    render(SettingsCuentaTab, { t, profileState });

    const trigger = screen.getByRole('button', { name: 'Change' });
    expect(trigger).not.toBeDisabled();
    expect(
      screen.getByText('Saved on this device. Sign in to sync across devices.'),
    ).toBeInTheDocument();

    await user.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');

    await user.click(screen.getByRole('button', { name: /Relaxed/ }));

    expect(appState.saveDailyGoalMinutes).toHaveBeenCalledWith(10);
    expect(await screen.findByRole('status')).toHaveTextContent('Daily goal saved');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('renders the row label as a level-3 heading under the page header', () => {
    const { profileState } = makeProfileState('user-1');
    render(SettingsCuentaTab, { t, profileState });

    const heading = screen.getByRole('heading', { level: 3, name: /Daily goal/ });
    expect(heading).toBeInTheDocument();

    // The tab now follows the Storage/Sync page pattern: one h1 page header and
    // flat blocks whose titles are h3. The old "every heading is an H3" pin is
    // obsolete once the page header exists; assert the header is the sole h1.
    const levels = screen.getAllByRole('heading').map((h) => Number(h.tagName.slice(1)));
    expect(levels[0]).toBe(1);
    expect(levels.filter((level) => level === 1)).toHaveLength(1);
  });
});
