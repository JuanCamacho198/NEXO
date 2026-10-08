import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import HomeStatsGrid from '$lib/features/home/components/HomeStatsGrid.svelte';

const hexPattern = /#[0-9a-fA-F]{3,8}/;

const dictionary: Record<string, string> = {
  'stats.days': '{{count}} days',
  'home.metrics.dailyGoalLabel': 'Daily goal',
  'home.metrics.minutesFormat': '{{current}}/{{total}} min',
  'home.progressTitle': 'Reading progress',
  'home.progressGoalAria': 'Daily goal progress',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  const template = dictionary[key] ?? key;
  if (!params) {
    return template;
  }
  return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, token: string) =>
    String(params[token] ?? ''),
  );
};

describe('HomeStatsGrid', () => {
  it('renders goal progress and streak as a two-fact progress strip', () => {
    const { container } = render(HomeStatsGrid, {
      props: {
        t,
        todayMinutes: 10,
        dailyGoalMinutes: 20,
        goalProgress: 0.5,
        streakDays: 3,
        isLoadingStreak: false,
      },
    });

    const summary = container.querySelector('[data-testid="stats-summary"]');
    expect(summary).not.toBeNull();

    expect(summary).toHaveTextContent('Daily goal');
    expect(summary).toHaveTextContent('10/20 min');
    expect(summary).toHaveTextContent('3 days');

    expect(summary).not.toHaveTextContent('Sessions');
    expect(summary).not.toHaveTextContent('Started');
    expect(summary).not.toHaveTextContent('Completed');
    expect(summary?.querySelector('dl')).toBeNull();

    const streak = summary?.querySelector('span[class*="color-streak-text"]');
    expect(streak).toHaveTextContent('3 days');
  });

  it('exposes a named progress bar with the goal progress', () => {
    const { container } = render(HomeStatsGrid, {
      props: {
        t,
        todayMinutes: 10,
        dailyGoalMinutes: 20,
        goalProgress: 0.5,
        streakDays: 0,
      },
    });

    const bar = container.querySelector('[role="progressbar"]');
    expect(bar).not.toBeNull();
    expect(bar).toHaveAttribute('aria-label', 'Daily goal progress');
    expect(bar).toHaveAttribute('aria-valuenow', '50');
    // The fill is driven by transform (scaleX) so it animates off layout.
    expect(bar?.querySelector('div')?.getAttribute('style')).toContain('scaleX(0.5)');
  });

  it('clamps goal progress outside the 0..1 range', () => {
    const { container: high } = render(HomeStatsGrid, {
      props: { t, todayMinutes: 99, dailyGoalMinutes: 20, goalProgress: 1.7 },
    });
    const highBar = high.querySelector('[role="progressbar"]');
    expect(highBar).toHaveAttribute('aria-valuenow', '100');
    expect(highBar?.querySelector('div')?.getAttribute('style')).toContain('scaleX(1)');

    const { container: low } = render(HomeStatsGrid, {
      props: { t, todayMinutes: 0, dailyGoalMinutes: 20, goalProgress: -0.4 },
    });
    const lowBar = low.querySelector('[role="progressbar"]');
    expect(lowBar).toHaveAttribute('aria-valuenow', '0');
    expect(lowBar?.querySelector('div')?.getAttribute('style')).toContain('scaleX(0)');
  });

  it('shows an em dash for the streak while the streak is loading', () => {
    const { container } = render(HomeStatsGrid, {
      props: { t, streakDays: 3, isLoadingStreak: true },
    });

    expect(container.querySelector('[data-testid="stats-summary"]')).toHaveTextContent('—');
    expect(container.querySelector('[data-testid="stats-summary"]')).not.toHaveTextContent(
      '3 days',
    );
  });

  it('shows skeletons instead of values while loading', () => {
    const { container } = render(HomeStatsGrid, {
      props: {
        t,
        isLoading: true,
        todayMinutes: 10,
        dailyGoalMinutes: 20,
        goalProgress: 0.5,
      },
    });

    const summary = container.querySelector('[data-testid="stats-summary"]');
    expect(summary?.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0);
    expect(summary).not.toHaveTextContent('10/20 min');
    expect(summary).not.toHaveTextContent('0 days');
  });

  it('renders the disabled reason panel instead of the progress field', () => {
    const { container } = render(HomeStatsGrid, {
      props: { t, disabledReason: 'Stats unavailable right now.' },
    });

    expect(screen.getByText('Stats unavailable right now.')).toBeInTheDocument();
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
    expect(container.querySelector('dl')).toBeNull();
  });

  it('contains no hardcoded hex colors', () => {
    const { container } = render(HomeStatsGrid, {
      props: { t, todayMinutes: 10, dailyGoalMinutes: 20, goalProgress: 0.5 },
    });

    expect(container.innerHTML).not.toMatch(hexPattern);
  });
});
