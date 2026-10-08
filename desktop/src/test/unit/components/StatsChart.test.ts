import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import StatsChart from '$lib/features/stats/components/StatsChart.svelte';
import { buildChartMeta } from '$lib/features/stats/components/readingStatsState.svelte';
import type { ReadingChartState } from '$lib/features/stats/components/useReadingChart.svelte';

// The empty branch never touches the interaction handlers, so an empty object
// cast to the state type is enough to mount the component in isolation.
const chartStub = {} as ReadingChartState;

const baseProps = {
  granularity: 'day' as const,
  locale: 'es',
  size: 'inline' as const,
  containerRef: null,
  chart: chartStub,
  emptyLabel: 'Sin actividad en este período',
  emptyHint: 'Lee un libro y tu progreso aparecerá aquí.',
};

describe('StatsChart', () => {
  it('renders an explicit empty state instead of a blank panel when there are no points', () => {
    render(StatsChart, { ...baseProps, chartMeta: buildChartMeta([]) });

    expect(screen.getByText('Sin actividad en este período')).toBeInTheDocument();
    expect(screen.getByText('Lee un libro y tu progreso aparecerá aquí.')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'chart' })).not.toBeInTheDocument();
  });

  it('treats a dense all-zero series as no activity instead of drawing a flat line', () => {
    const chartMeta = buildChartMeta([
      { label: '1/6', value: 0 },
      { label: '2/6', value: 0 },
    ]);
    render(StatsChart, { ...baseProps, chartMeta });

    expect(chartMeta.points).toHaveLength(2);
    expect(screen.getByText('Sin actividad en este período')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'chart' })).not.toBeInTheDocument();
  });

  it('renders the plotted svg when the series has points', () => {
    const chartMeta = buildChartMeta([
      { label: '1/6', value: 20 },
      { label: '2/6', value: 40 },
    ]);
    render(StatsChart, { ...baseProps, chartMeta });

    expect(screen.getByRole('img', { name: 'chart' })).toBeInTheDocument();
    expect(screen.queryByText('Sin actividad en este período')).not.toBeInTheDocument();
    expect(chartMeta.points).toHaveLength(2);
  });
});
