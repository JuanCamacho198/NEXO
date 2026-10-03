import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import HomeStatsGrid from '$lib/features/home/components/HomeStatsGrid.svelte';
import type { ReadingStatsSummaryDto } from '$lib/shared/types';

const hexPattern = /#[0-9a-fA-F]{3,8}/;

const dictionary: Record<string, string> = {
  'stats.booksStartedLabel': 'Started',
  'stats.booksCompletedLabel': 'Completed',
  'stats.sessionsLabel': 'Sessions',
  'stats.streakLabel': 'Streak',
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

const stats: ReadingStatsSummaryDto = {
  totalMinutesRead: 0,
  totalSessions: 7,
  booksStarted: 4,
  booksCompleted: 2,
  avgProgressPercentage: 0,
};

describe('HomeStatsGrid', () => {
  it('renders goal, streak and activity in one compact progress field', () => {
    const { container } = render(HomeStatsGrid, {
      props: {
        stats,
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

    for (const label of [
      'Reading progress',
      'Daily goal',
      'Streak',
      'Sessions',
      'Started',
      'Completed',
    ]) {
      expect(summary).toHaveTextContent(label);
    }

    expect(summary).toHaveTextContent('10/20 min');

    const activity = summary?.querySelector('dl');
    expect(activity).toHaveTextContent('Streak');
    expect(activity).toHaveTextContent('3 days');
    expect(activity).toHaveTextContent('Sessions');
    expect(activity).toHaveTextContent('7');
    expect(activity).toHaveTextContent('Started');
    expect(activity).toHaveTextContent('4');
    expect(activity).toHaveTextContent('Completed');
    expect(activity).toHaveTextContent('2');
  });

  it('exposes a named progress bar with the goal progress', () => {
    const { container } = render(HomeStatsGrid, {
      props: {
        stats,
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
    expect(bar?.querySelector('div')?.getAttribute('style')).toContain('width: 50%');
  });

  it('clamps goal progress outside the 0..1 range', () => {
    const { container: high } = render(HomeStatsGrid, {
      props: { stats, t, todayMinutes: 99, dailyGoalMinutes: 20, goalProgress: 1.7 },
    });
    expect(high.querySelector('[role="progressbar"]')).toHaveAttribute('aria-valuenow', '100');

    const { container: low } = render(HomeStatsGrid, {
      props: { stats, t, todayMinutes: 0, dailyGoalMinutes: 20, goalProgress: -0.4 },
    });
    expect(low.querySelector('[role="progressbar"]')).toHaveAttribute('aria-valuenow', '0');
  });

  it('shows an em dash for the streak while the streak is loading', () => {
    const { container } = render(HomeStatsGrid, {
      props: { stats, t, streakDays: 3, isLoadingStreak: true },
    });

    expect(container.querySelector('[data-testid="stats-summary"]')).toHaveTextContent('—');
    expect(container.querySelector('[data-testid="stats-summary"]')).not.toHaveTextContent(
      '3 days',
    );
  });

  it('shows skeletons instead of values while loading', () => {
    const { container } = render(HomeStatsGrid, {
      props: {
        stats,
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
    expect(summary).not.toHaveTextContent('7');
  });

  it('renders the disabled reason panel instead of the progress field', () => {
    const { container } = render(HomeStatsGrid, {
      props: { stats, t, disabledReason: 'Stats unavailable right now.' },
    });

    expect(screen.getByText('Stats unavailable right now.')).toBeInTheDocument();
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
    expect(container.querySelector('dl')).toBeNull();
  });

  it('contains no hardcoded hex colors', () => {
    const { container } = render(HomeStatsGrid, {
      props: { stats, t, todayMinutes: 10, dailyGoalMinutes: 20, goalProgress: 0.5 },
    });

    expect(container.innerHTML).not.toMatch(hexPattern);
  });
});
