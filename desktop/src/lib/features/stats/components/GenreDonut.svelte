<script lang="ts">
  import type { ReadingChartState } from './useReadingChart.svelte';

  type Props = {
    distribution: Array<{ genre: string; minutes: number; percent: number; color: string }>;
    booksByGenre: Map<string, string[]>;
    totalMinutes: number;
    containerRef: HTMLElement | null;
    chart: ReadingChartState;
    minutesLabel: string;
    emptyLabel: string;
    emptyHint: string;
  };
  let {
    distribution,
    booksByGenre,
    totalMinutes,
    containerRef,
    chart,
    minutesLabel,
    emptyLabel,
    emptyHint,
  }: Props = $props();

  // r = 15.9155 → circumference ≈ 100, so dasharray/dashoffset are percentages.
  const RADIUS = 15.9155;
  const segments = $derived.by(() => {
    let offset = 0;
    return distribution.map((entry) => {
      const segment = { ...entry, offset };
      offset += entry.percent;
      return segment;
    });
  });
</script>

{#if distribution.length > 0}
  <div class="flex flex-col items-center gap-6 lg:flex-row lg:items-center lg:justify-between">
    <div class="relative h-52 w-52">
      <svg
        viewBox="0 0 42 42"
        class="h-52 w-52 -rotate-90"
        role="img"
        aria-label={`${minutesLabel}: ${totalMinutes}`}
      >
        {#each segments as segment}
          <circle
            cx="21"
            cy="21"
            r={RADIUS}
            fill="none"
            stroke={segment.color}
            stroke-width="6"
            stroke-dasharray={`${Math.max(segment.percent - 1, 0)} ${100 - Math.max(segment.percent - 1, 0)}`}
            stroke-dashoffset={-segment.offset}
          ></circle>
        {/each}
      </svg>
      <div
        class="absolute inset-[26px] flex flex-col items-center justify-center rounded-full bg-(--color-chart-center) text-center"
      >
        <span class="text-3xl font-semibold text-(--color-primary)"
          >{totalMinutes.toLocaleString('es-CO')}</span
        >
        <span class="text-xs text-(--color-text-muted)">{minutesLabel}</span>
      </div>
    </div>
    <div class="w-full space-y-3">
      {#each distribution as entry}
        <div
          class="flex cursor-pointer items-center justify-between gap-3 rounded-lg px-2 py-1.5 text-sm transition-colors duration-150 hover:bg-(--color-surface-subtle)"
          role="button"
          tabindex="0"
          aria-label={`${entry.genre}: ${entry.minutes} min, ${entry.percent}%`}
          onmouseenter={(e) =>
            chart.handleGenreEnter(
              entry,
              booksByGenre.get(entry.genre) ?? [],
              e as MouseEvent,
              containerRef,
            )}
          onmousemove={(e) => chart.handleGenreMove(e as MouseEvent, containerRef)}
          onmouseleave={() => chart.handleGenreLeave()}
          onfocus={(e) =>
            chart.handleGenreFocus(
              entry,
              booksByGenre.get(entry.genre) ?? [],
              e.currentTarget as HTMLElement,
              containerRef,
            )}
          onblur={() => chart.handleGenreLeave()}
          onkeydown={(e) => {
            if ((e as KeyboardEvent).key === 'Escape') chart.handleGenreLeave();
          }}
        >
          <div class="flex items-center gap-3">
            <span class="h-3 w-3 rounded-full" style={`background:${entry.color};`}></span>
            <span class="text-(--color-secondary)">{entry.genre}</span>
          </div>
          <span class="text-(--color-primary)">{entry.percent}%</span>
        </div>
      {/each}
    </div>
  </div>
{:else}
  <div
    class="flex min-h-52 flex-col items-center justify-center gap-2 px-6 py-10 text-center"
    role="status"
  >
    <p class="text-sm font-medium text-(--color-secondary)">{emptyLabel}</p>
    <p class="max-w-md text-xs text-(--color-text-muted)">{emptyHint}</p>
  </div>
{/if}
