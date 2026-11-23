<script lang="ts">
  import type { ReadingChartState } from './useReadingChart.svelte';
  import ChartPie from 'lucide-svelte/icons/chart-pie';

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
  // A surface-coloured gap between segments (in % of the ring) so adjacent
  // genres never fuse into one arc.
  const GAP = 1.6;
  const segments = $derived.by(() => {
    let offset = 0;
    const single = distribution.length === 1;
    return distribution.map((entry) => {
      const length = single ? 100 : Math.max(entry.percent - GAP, 0.4);
      const segment = { ...entry, offset, length };
      offset += entry.percent;
      return segment;
    });
  });

  const activeGenre = $derived(chart.genreTooltip?.genre ?? null);
</script>

{#if distribution.length > 0}
  <div class="flex flex-col items-center gap-8 sm:flex-row sm:items-center">
    <div class="relative size-48 shrink-0">
      <svg
        viewBox="0 0 42 42"
        class="size-48 -rotate-90"
        role="img"
        aria-label={`${minutesLabel}: ${totalMinutes}`}
      >
        <circle
          cx="21"
          cy="21"
          r={RADIUS}
          fill="none"
          stroke="var(--color-edge)"
          stroke-opacity="0.5"
          stroke-width="4.5"
        ></circle>
        {#each segments as segment (segment.genre)}
          <circle
            cx="21"
            cy="21"
            r={RADIUS}
            fill="none"
            stroke={segment.color}
            stroke-width={activeGenre === segment.genre ? 6 : 4.5}
            stroke-opacity={activeGenre && activeGenre !== segment.genre ? 0.3 : 1}
            stroke-dasharray={`${segment.length} ${100 - segment.length}`}
            stroke-dashoffset={-segment.offset}
            class="transition-[stroke-width,stroke-opacity] duration-(--duration-fast)"
          ></circle>
        {/each}
      </svg>
      <div class="absolute inset-0 flex flex-col items-center justify-center text-center">
        <span class="text-3xl font-bold tracking-tight tabular-nums text-(--color-primary)"
          >{totalMinutes.toLocaleString('es-CO')}</span
        >
        <span class="text-xs text-(--color-text-muted)">{minutesLabel}</span>
      </div>
    </div>

    <ul class="m-0 w-full min-w-0 list-none space-y-1 p-0">
      {#each distribution as entry (entry.genre)}
        <li>
          <div
            class="cursor-pointer rounded-(--radius-sm) px-2.5 py-2 transition-colors duration-150 hover:bg-(--color-surface-subtle) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) {activeGenre ===
            entry.genre
              ? 'bg-(--color-surface-subtle)'
              : ''}"
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
            <div class="flex items-center justify-between gap-3 text-sm">
              <div class="flex min-w-0 items-center gap-2.5">
                <span class="size-2.5 shrink-0 rounded-full" style={`background:${entry.color};`}
                ></span>
                <span class="truncate text-(--color-secondary)">{entry.genre}</span>
              </div>
              <span class="shrink-0 tabular-nums text-(--color-text-muted)">
                {entry.minutes} min ·
                <span class="font-semibold text-(--color-primary)">{entry.percent}%</span>
              </span>
            </div>
            <div class="mt-1.5 ml-5 h-1 overflow-hidden rounded-full bg-(--color-edge)/60">
              <div
                class="h-full rounded-full"
                style={`width:${Math.max(entry.percent, 2)}%; background:${entry.color};`}
              ></div>
            </div>
          </div>
        </li>
      {/each}
    </ul>
  </div>
{:else}
  <div
    class="flex min-h-52 flex-col items-center justify-center gap-3 px-6 py-10 text-center"
    role="status"
  >
    <span
      class="flex size-12 items-center justify-center rounded-full border border-(--color-edge) bg-(--color-brand-soft) text-(--color-brand-text)"
      aria-hidden="true"
    >
      <ChartPie size={22} strokeWidth={1.6} />
    </span>
    <p class="text-sm font-medium text-(--color-secondary)">{emptyLabel}</p>
    <p class="max-w-md text-xs text-(--color-text-muted)">{emptyHint}</p>
  </div>
{/if}
