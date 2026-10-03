import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { get } from 'svelte/store';
import { beforeEach, describe, expect, it } from 'vitest';
import { theme, setTheme } from '$lib/shared/stores/theme';
import ThemeToggle from '$lib/shared/ui/navigation/ThemeToggle.svelte';

const dictionary: Record<string, string> = {
  'theme.currentDark': 'Tema oscuro',
  'theme.currentLight': 'Tema claro',
  'theme.switchToLight': 'Cambiar a tema claro',
  'theme.switchToDark': 'Cambiar a tema oscuro',
};

const t = (key: string): string => dictionary[key] ?? key;

describe('ThemeToggle (HOME-04)', () => {
  beforeEach(() => {
    setTheme('dark');
  });

  it('labels the current theme and the switch action', () => {
    render(ThemeToggle, { props: { t } });

    expect(screen.getByText('Tema oscuro')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cambiar a tema claro' })).toBeInTheDocument();
  });

  it('toggles the theme when clicked', async () => {
    const user = userEvent.setup();
    render(ThemeToggle, { props: { t } });

    await user.click(screen.getByRole('button', { name: 'Cambiar a tema claro' }));

    expect(get(theme)).toBe('light');
    expect(screen.getByText('Tema claro')).toBeInTheDocument();
  });

  it('falls back to Spanish copy when no translator is injected', () => {
    render(ThemeToggle, { props: {} });

    expect(screen.getByText('Tema oscuro')).toBeInTheDocument();
  });
});
