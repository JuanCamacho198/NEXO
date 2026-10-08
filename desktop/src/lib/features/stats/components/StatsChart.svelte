<script lang="ts">
  import { formatChartLabel, type ChartMeta, type Granularity } from './readingStatsState.svelte';
  import type { ReadingChartState } from './useReadingChart.svelte';
  import ChartLine from 'lucide-svelte/icons/chart-line';

  type Props = {
    chartMeta: ChartMeta;
    granularity: Granularity;
    locale: string;
    size: 'inline' | 'modal';
    containerRef: HTMLElement | null;
    chart: ReadingChartState;
    emptyLabel: string;
    emptyHint: string;
  };
  let { chartMeta, granularity, locale, size, containerRef, chart, emptyLabel, emptyHint }: Props =
    $props();

  const strokeId = $derived(size === 'modal' ? 'lineStrokeModal' : 'lineStroke');
  const fillId = $derived(size === 'modal' ? 'lineFillModal' : 'lineFill');
  const extraHeight = $derived(size === 'modal' ? 320 : 40);
  const labelFontSize = $derived(size === 'modal' ? 11 : 10);
  const hasData = $derived(
    chartMeta.points.length > 0 && chartMeta.points.some((point) => point.value > 0),
  );
</script>

<div
  class="rounded-[22px] border border-(--color-border) bg-(image:--gradient-chart-bg) p-4 overflow-hidden"
>
  {#if hasData}
    <svg
      role="img"
      aria-label="chart"
      viewBox={`0 0 ${chartMeta.width} ${chartMeta.height + 28}`}
      class="w-full"
      style={`height: ${chartMeta.height + extraHeight}px`}
      onmouseleave={() => chart.handlePointLeave()}
    >
      <defs>
        <linearGradient id={strokeId} x1="0%" x2="100%" y1="0%" y2="0%">
          <stop offset="0%" stop-color="var(--color-accent-start)"></stop>
          <stop offset="100%" stop-color="var(--color-accent-end)"></stop>
        </linearGradient>
        <linearGradient id={fillId} x1="0%" x2="0%" y1="0%" y2="100%">
          <stop offset="0%" stop-color="var(--color-chart-fill-top)"></stop>
          <stop offset="100%" stop-color="var(--color-chart-fill-bottom)"></stop>
        </linearGradient>
      </defs>
      {#each [0, 0.25, 0.5, 0.75, 1] as tick}
        <line
          x1="0"
          y1={chartMeta.height - tick * (chartMeta.height - 18)}
          x2={chartMeta.width}
          y2={chartMeta.height - tick * (chartMeta.height - 18)}
          stroke="var(--color-chart-grid)"
          stroke-width="1"
        ></line>
      {/each}
      <path d={chartMeta.area} fill={`url(#${fillId})`}></path>
      <path
        d={chartMeta.line}
        fill="none"
        stroke={`url(#${strokeId})`}
        stroke-width="3"
        stroke-linecap="round"
      ></path>
      {#each chartMeta.points as point}
        <circle
          cx={point.x}
          cy={point.y}
          r="5"
          fill="var(--color-chart-2)"
          style="transition: r 0.15s ease;"
          class="cursor-pointer"
          role="button"
          tabindex="0"
          aria-label={`${point.value} min — ${point.label}`}
          onmouseenter={(e) => chart.handlePointEnter(point, e as MouseEvent, containerRef)}
          onmousemove={(e) => chart.handlePointMove(e as MouseEvent, containerRef)}
          onmouseleave={(e) => chart.handlePointLeave(e)}
          onfocus={(e) =>
            chart.handlePointFocus(point, e.currentTarget as SVGCircleElement, containerRef)}
          onblur={(e) => chart.handlePointBlur(e.currentTarget as SVGCircleElement)}
          onkeydown={(e) => {
            if ((e as KeyboardEvent).key === 'Escape') {
              chart.handlePointLeave();
            }
          }}
        ></circle>
      {/each}
      {#each chartMeta.points as point, i}
        {#if i % chartMeta.labelInterval === 0 || i === chartMeta.points.length - 1}
          <text
            x={point.x}
            y={chartMeta.height + 18}
            text-anchor="middle"
            font-size={labelFontSize}
            fill="var(--color-text-muted)"
          >
            {formatChartLabel(point.label, granularity, locale)}
          </text>
        {/if}
      {/each}
    </svg>
  {:else}
    <div
      class="flex min-h-56 flex-col items-center justify-center gap-3 px-6 py-10 text-center"
      role="status"
    >
      <span
        class="flex h-12 w-12 items-center justify-center rounded-full border border-(--color-border) bg-(--color-surface-subtle) text-(--color-text-muted)"
        aria-hidden="true"
      >
        <ChartLine size={22} strokeWidth={1.6} />
      </span>
      <p class="text-sm font-medium text-(--color-secondary)">{emptyLabel}</p>
      <p class="max-w-md text-xs text-(--color-text-muted)">{emptyHint}</p>
    </div>
  {/if}
</div>
