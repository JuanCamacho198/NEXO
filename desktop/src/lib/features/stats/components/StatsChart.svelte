<script lang="ts">
  import {
    formatChartLabel,
    type ChartMeta,
    type ChartPoint,
    type Granularity,
  } from './readingStatsState.svelte';
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
  // The 800-unit viewBox renders narrower than 800px inline, so text is set
  // in viewBox units that land near 10-11px on screen.
  const labelFontSize = $derived(size === 'modal' ? 12 : 13);
  const hasData = $derived(
    chartMeta.points.length > 0 && chartMeta.points.some((point) => point.value > 0),
  );

  // Same vertical mapping as `buildChartMeta`, so ticks sit on the data.
  const yFor = (value: number): number => {
    const span = chartMeta.max - chartMeta.min;
    const normalized = span === 0 ? 0.5 : (value - chartMeta.min) / span;
    return chartMeta.height - normalized * (chartMeta.height - 18) - 10;
  };
  const baseline = $derived(chartMeta.height - 10);

  const yTicks = $derived.by(() => {
    const top = chartMeta.max;
    const values = top <= 1 ? [0, top] : [0, Math.round(top / 2), Math.round(top)];
    return [...new Set(values)].map((value) => ({ value, y: yFor(value) }));
  });

  /**
   * Catmull-Rom through the points, as cubic Béziers. Control points are
   * clamped to the baseline so a run of zero days never dips below the axis.
   */
  function smoothLine(points: ChartPoint[]): string {
    if (points.length === 0) return '';
    if (points.length === 1) return `M ${points[0].x},${points[0].y}`;
    const clamp = (y: number): number => Math.min(y, baseline);
    let d = `M ${points[0].x},${points[0].y}`;
    for (let i = 0; i < points.length - 1; i++) {
      const p0 = points[i - 1] ?? points[i];
      const p1 = points[i];
      const p2 = points[i + 1];
      const p3 = points[i + 2] ?? p2;
      const c1x = p1.x + (p2.x - p0.x) / 6;
      const c1y = clamp(p1.y + (p2.y - p0.y) / 6);
      const c2x = p2.x - (p3.x - p1.x) / 6;
      const c2y = clamp(p2.y - (p3.y - p1.y) / 6);
      d += ` C ${c1x},${c1y} ${c2x},${c2y} ${p2.x},${p2.y}`;
    }
    return d;
  }

  const line = $derived(smoothLine(chartMeta.points));
  const area = $derived.by(() => {
    const pts = chartMeta.points;
    if (pts.length === 0) return '';
    const last = pts[pts.length - 1];
    return `${line} L ${last.x},${chartMeta.height} L ${pts[0].x},${chartMeta.height} Z`;
  });

  const activeIndex = $derived(
    chart.hoveredPoint
      ? chartMeta.points.findIndex(
          (p) => p.label === chart.hoveredPoint?.label && p.value === chart.hoveredPoint?.value,
        )
      : -1,
  );
  const lastIndex = $derived(chartMeta.points.length - 1);
</script>

<div class="overflow-hidden rounded-(--radius-md) bg-(--color-surface-subtle) px-4 pt-5 pb-3">
  {#if hasData}
    <svg
      role="img"
      aria-label="chart"
      viewBox={`0 0 ${chartMeta.width} ${chartMeta.height + 28}`}
      class="w-full overflow-visible"
      style={size === 'modal' ? `height: ${chartMeta.height + extraHeight}px` : undefined}
      onmouseleave={() => chart.handlePointLeave()}
    >
      <defs>
        <linearGradient id={strokeId} x1="0%" x2="100%" y1="0%" y2="0%">
          <stop offset="0%" stop-color="var(--color-brand-start)"></stop>
          <stop offset="100%" stop-color="var(--color-brand)"></stop>
        </linearGradient>
        <linearGradient id={fillId} x1="0%" x2="0%" y1="0%" y2="100%">
          <stop offset="0%" stop-color="var(--color-chart-fill-top)"></stop>
          <stop offset="100%" stop-color="var(--color-chart-fill-bottom)"></stop>
        </linearGradient>
      </defs>

      {#each yTicks as tick (tick.value)}
        <line
          x1="0"
          y1={tick.y}
          x2={chartMeta.width}
          y2={tick.y}
          stroke="var(--color-chart-grid)"
          stroke-width="1"
          stroke-dasharray={tick.value === 0 ? undefined : '3 5'}
        ></line>
        {#if tick.value > 0}
          <text
            x="0"
            y={tick.y - 6}
            font-size={labelFontSize}
            fill="var(--color-text-muted)"
            class="tabular-nums">{tick.value} min</text
          >
        {/if}
      {/each}

      <path d={area} fill={`url(#${fillId})`}></path>
      <path
        d={line}
        fill="none"
        stroke={`url(#${strokeId})`}
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      ></path>

      {#if activeIndex >= 0}
        {@const p = chartMeta.points[activeIndex]}
        <line
          x1={p.x}
          y1="0"
          x2={p.x}
          y2={chartMeta.height}
          stroke="var(--color-brand)"
          stroke-opacity="0.45"
          stroke-width="1"
          stroke-dasharray="2 4"
        ></line>
      {/if}

      {#each chartMeta.points as point, i}
        {@const shown = i === activeIndex || (activeIndex < 0 && i === lastIndex)}
        <!-- The hit target is larger than the mark; only the active (or the
          latest) point is drawn so the curve reads as one line, not beads. -->
        <circle
          cx={point.x}
          cy={point.y}
          r="12"
          fill="transparent"
          class="cursor-pointer outline-none"
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
        {#if shown}
          <circle
            cx={point.x}
            cy={point.y}
            r="5"
            fill="var(--color-brand)"
            stroke="var(--color-home-card)"
            stroke-width="2.5"
            pointer-events="none"
          ></circle>
        {/if}
      {/each}

      {#each chartMeta.points as point, i}
        {#if (i % chartMeta.labelInterval === 0 && lastIndex - i >= chartMeta.labelInterval) || i === lastIndex}
          <text
            x={point.x}
            y={chartMeta.height + 18}
            text-anchor={i === 0 ? 'start' : i === lastIndex ? 'end' : 'middle'}
            font-size={labelFontSize}
            fill={i === activeIndex ? 'var(--color-primary)' : 'var(--color-text-muted)'}
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
        class="flex size-12 items-center justify-center rounded-full border border-(--color-edge) bg-(--color-brand-soft) text-(--color-brand-text)"
        aria-hidden="true"
      >
        <ChartLine size={22} strokeWidth={1.6} />
      </span>
      <p class="text-sm font-medium text-(--color-secondary)">{emptyLabel}</p>
      <p class="max-w-md text-xs text-(--color-text-muted)">{emptyHint}</p>
    </div>
  {/if}
</div>
