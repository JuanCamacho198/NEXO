import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import SettingsTabs from '$lib/features/settings/components/SettingsTabs.svelte';
import SettingsCuentaTab from '$lib/features/settings/components/SettingsCuentaTab.svelte';
import { createSettingsProfile } from '$lib/features/settings/useSettingsProfile.svelte';
import type { MessageKey } from '$lib/shared/i18n';

const t = (key: MessageKey, _params?: Record<string, string | number>): string => key;

/** The glyph class lucide emits: `lucide-icon lucide-<name> ...`. */
const glyphOf = (icon: Element): string | undefined =>
  (icon.getAttribute('class') ?? '')
    .split(/\s+/)
    .find((cls) => cls.startsWith('lucide-') && cls !== 'lucide-icon');

const SVG_CONTRACT = {
  viewBox: '0 0 24 24',
  stroke: 'currentColor',
  'stroke-width': '1.8',
  'aria-hidden': 'true',
} as const;

function expectShimContract(icon: Element): void {
  expect(icon.getAttribute('class')).toContain('lucide-icon');
  for (const [attr, value] of Object.entries(SVG_CONTRACT)) {
    expect(icon.getAttribute(attr), attr).toBe(value);
  }
}

describe('settings icon migration', () => {
  it('renders the six settings tabs through direct lucide components', () => {
    render(SettingsTabs, {
      activeTab: 'cuenta',
      onTabChange: () => {},
      onKeydown: () => {},
      t,
    });

    const icons = Array.from(screen.getByRole('tablist').querySelectorAll('svg'));
    expect(icons.map(glyphOf)).toEqual([
      'lucide-user',
      'lucide-database',
      'lucide-database',
      'lucide-cloud-check',
      'lucide-bookmark',
      'lucide-info',
    ]);
    for (const icon of icons) {
      expectShimContract(icon);
      expect(icon.getAttribute('width')).toBe('14');
    }
  });

  it('renders the component-valued daily-goal cards as the producer glyphs', () => {
    const { container } = render(SettingsCuentaTab, {
      t,
      profile: { name: 'Reader', email: 'reader@example.com', avatarUrl: null, isSignedIn: false },
      dailyGoalCards: createSettingsProfile({ t }).dailyGoalCards,
      selectedDailyGoal: 20,
    });

    const cardIcons = Array.from(container.querySelectorAll('svg.h-5.w-5'));
    expect(cardIcons.map(glyphOf)).toEqual([
      'lucide-hand',
      'lucide-book',
      'lucide-chart-column',
      'lucide-flame',
    ]);
    for (const icon of cardIcons) {
      expectShimContract(icon);
      expect(icon.getAttribute('width')).toBe('20');
    }
    expect(container.querySelectorAll('svg.lucide-check')).toHaveLength(1);
  });
});
