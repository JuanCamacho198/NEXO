<script lang="ts">
  import { headingMarker, panelClass } from '$lib/shared/ui/emberStyles';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import { Modal } from '$lib/shared/ui/';
  import { UNCLASSIFIED_GENRE } from '$lib/shared/services/genreHeuristic';
  import { getSafeProgressPercentage } from '$lib/shared/stores/HomeState';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
  import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import {
    periodLabels,
    calculateGenreDistribution,
    periodWindow,
    previousWindow,
    formatDelta,
    buildChartMeta,
    type DeltaResult,
    type PeriodKey,
    type Granularity,
    type Props,
  } from './readingStatsState.svelte';
  import StatsChart from './StatsChart.svelte';
  import GenreDonut from './GenreDonut.svelte';
  import StatsOverview from './StatsOverview.svelte';
  import StatsStreakCard from './StatsStreakCard.svelte';
  import StatsMostRead from './StatsMostRead.svelte';
  import StatsHabits from './StatsHabits.svelte';
  import { useReadingChart } from './useReadingChart.svelte';
  import Book from 'lucide-svelte/icons/book';
  import type { MessageKey } from '$lib/shared/i18n';
  let {
    appState,
    t: tProp,
  }: Props & { t?: (key: MessageKey, params?: Record<string, string | number>) => string } =
    $props();
  let activePeriod = $state<PeriodKey>('month');
  let activeGranularity = $state<Granularity>('day');
  const chart = useReadingChart();
  let heroEl: HTMLDivElement | undefined = $state();
  let heroVisible = $state(true);
  let chartContainer: HTMLElement | null = $state(null);
  let genreContainer: HTMLElement | null = $state(null);
  $effect(() => {
    if (!heroEl) return;
    const o = new IntersectionObserver(
      ([e]) => {
        heroVisible = e.isIntersecting;
      },
      { threshold: 0, rootMargin: '-1px 0px 0px 0px' },
    );
    o.observe(heroEl);
    return () => o.disconnect();
  });
  const booksByGenre = $derived.by(() => {
    const m = new Map<string, string[]>();
    for (const b of libraryState.books) {
      const g = b.genre?.trim() || UNCLASSIFIED_GENRE;
      if (!m.has(g)) m.set(g, []);
      m.get(g)!.push(b.title);
    }
    return m;
  });
  const periodDropdownOptions = $derived(
    Object.entries(periodLabels).map(([v]) => ({
      value: v,
      label: _t(`stats.period${v.charAt(0).toUpperCase() + v.slice(1)}` as MessageKey),
    })),
  );
  const granularityOptions = $derived<Array<{ value: string; label: string }>>([
    { value: 'day', label: 'stats.granularityDay' },
    { value: 'week', label: 'stats.granularityWeek' },
    { value: 'month', label: 'stats.granularityMonth' },
  ]);
  $effect(() => {
    void statsState.loadActivity(activePeriod, activeGranularity);
  });
  $effect(() => {
    const { from, to } = periodWindow(activePeriod);
    const { from: prevFrom, to: prevTo } = previousWindow(activePeriod);
    const userId = authState.userId ?? '';
    void statsState.loadRangeStats(from, to, undefined, 'current', userId);
    void statsState.loadRangeStats(prevFrom, prevTo, undefined, 'previous', userId);
  });
  $effect(() => {
    void statsState.loadStreak(undefined, authState.userId ?? '');
  });
  const sd = $derived(statsState);
  const genreDistribution = $derived(calculateGenreDistribution(libraryState.books));
  const classifiedGenres = $derived(
    genreDistribution.filter((entry) => entry.genre !== UNCLASSIFIED_GENRE),
  );
  const unclassifiedEntry = $derived(
    genreDistribution.find((entry) => entry.genre === UNCLASSIFIED_GENRE),
  );
  const totalMinutes = $derived(
    sd.currentStats?.totalMinutesRead ?? libraryState.books.reduce((s, b) => s + b.minutesRead, 0),
  );
  const totalSessions = $derived(
    sd.currentStats?.totalSessions ?? Math.max(libraryState.books.length * 2, 0),
  );
  const booksStarted = $derived(
    sd.currentStats?.booksStarted ??
      libraryState.books.filter((b) => getSafeProgressPercentage(b) > 0).length,
  );
  const booksCompleted = $derived(
    sd.currentStats?.booksCompleted ??
      libraryState.books.filter(
        (b) => b.readingStatus === 'completed' || getSafeProgressPercentage(b) >= 100,
      ).length,
  );
  const averageProgress = $derived(
    sd.currentStats?.avgProgressPercentage ??
      (libraryState.books.length
        ? libraryState.books.reduce((s, b) => s + getSafeProgressPercentage(b), 0) /
          libraryState.books.length
        : 0),
  );
  const _t = (k: MessageKey, p?: Record<string, string | number>): string =>
    tProp ? tProp(k, p) : k;
  const delta = (current: number | undefined, previous: number | undefined): DeltaResult =>
    formatDelta(current, previous, activePeriod, _t);
  const primaryMetric = $derived({
    label: _t('stats.minutesRead'),
    value: totalMinutes.toLocaleString('es-CO'),
    delta: delta(sd.currentStats?.totalMinutesRead, sd.previousStats?.totalMinutesRead),
  });
  const secondaryMetrics = $derived([
    {
      label: _t('stats.sessions'),
      value: totalSessions.toLocaleString('es-CO'),
      delta: delta(sd.currentStats?.totalSessions, sd.previousStats?.totalSessions),
    },
    {
      label: _t('stats.booksStarted'),
      value: booksStarted.toLocaleString('es-CO'),
      delta: delta(sd.currentStats?.booksStarted, sd.previousStats?.booksStarted),
    },
    {
      label: _t('stats.booksCompleted'),
      value: booksCompleted.toLocaleString('es-CO'),
      delta: delta(sd.currentStats?.booksCompleted, sd.previousStats?.booksCompleted),
    },
    {
      label: _t('stats.averageProgress'),
      value: `${Math.round(averageProgress)}%`,
      delta: delta(sd.currentStats?.avgProgressPercentage, sd.previousStats?.avgProgressPercentage),
    },
  ]);
  const activitySeries = $derived(
    sd.activitySeries.length > 0
      ? sd.activitySeries.map((p) => ({ label: p.bucket, value: p.minutes }))
      : [],
  );
  const chartMeta = $derived(buildChartMeta(activitySeries));
  const mostReadBooks = $derived.by(() =>
    [...libraryState.books].sort((l, r) => r.minutesRead - l.minutesRead).slice(0, 3),
  );
  const streakDays = $derived(sd.streakDays);
  const streakCalendar = $derived.by(() => {
    const d = 14,
      a = Math.min(sd.streakDays, d);
    return Array.from({ length: d }, (_, i) => ({
      label: ['L', 'M', 'M', 'J', 'V', 'S', 'D'][i % 7],
      active: i >= d - a,
    }));
  });
  const averageMinutesPerSession = $derived(
    totalSessions > 0 ? Math.round(totalMinutes / totalSessions) : 0,
  );
  const averageMinutesPerDay = $derived(
    activitySeries.length > 0 ? Math.round(totalMinutes / activitySeries.length) : 0,
  );
  const totalPagesRead = $derived(
    libraryState.books.reduce((s, b) => s + Math.max(b.currentPage, 0), 0),
  );
  const isLoading = $derived(sd.isLoadingActivity || sd.isLoadingRange || sd.isLoadingStreak);
  const disabledReason = $derived(
    sd.rangeUnavailableReason || sd.activityUnavailableReason || sd.streakUnavailableReason,
  );
</script>

{#if !heroVisible}<div
    class="sticky top-0 z-20 border-b border-(--color-edge) bg-(--color-surface-overlay) px-4 py-3 backdrop-blur-md md:px-6"
  >
    <div class="mx-auto flex max-w-7xl items-center justify-between">
      <span class="text-sm font-semibold text-(--color-primary)">{_t('stats.title')}</span><Dropdown
        options={periodDropdownOptions}
        bind:value={activePeriod}
        class="min-w-[120px]"
      />
    </div>
  </div>{/if}
<section class="space-y-5">
  <div bind:this={heroEl} class="flex flex-wrap items-end justify-between gap-4">
    <div class="min-w-0">
      <div class="flex items-center gap-3">
        <div class="h-8 {headingMarker}" aria-hidden="true"></div>
        <h1 class="text-[1.75rem] font-bold tracking-[-0.02em] text-(--color-primary)">
          {_t('stats.title')}
        </h1>
      </div>
      <p class="mt-1 text-sm text-(--color-text-muted)">{_t('stats.subtitle')}</p>
    </div>
    <Dropdown options={periodDropdownOptions} bind:value={activePeriod} class="min-w-[140px]" />
  </div>

  {#if disabledReason}
    <div
      class="rounded-(--radius-lg) border border-(--color-warning)/40 bg-(--color-warning)/10 px-4 py-3 text-sm text-(--color-primary)"
    >
      {disabledReason}
    </div>
  {:else if isLoading}
    <div class="{panelClass} px-5 py-8 text-sm text-(--color-text-muted)">
      {_t('stats.loading')}
    </div>
  {:else}
    <StatsOverview primary={primaryMetric} secondary={secondaryMetrics} />

    <div class="grid grid-cols-1 gap-5 xl:grid-cols-[1.7fr_1fr]">
      <article bind:this={chartContainer} class="relative {panelClass} p-5">
        <div class="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 class="text-lg font-bold tracking-[-0.01em] text-(--color-primary)">
              {_t('stats.minutesReadChart')}
            </h2>
            <p class="text-sm text-(--color-text-muted)">{_t('stats.activityTimeline')}</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <Dropdown
              options={granularityOptions.map((o) => ({ ...o, label: _t(o.label as MessageKey) }))}
              bind:value={activeGranularity}
              class="min-w-[100px]"
            />
            <button
              type="button"
              class="flex size-9 cursor-pointer items-center justify-center rounded-full border border-(--color-edge) bg-(--color-surface-subtle) text-(--color-text-muted) transition-colors duration-150 hover:border-(--color-brand)/60 hover:text-(--color-brand-text) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
              onclick={() => (chart.chartModalOpen = true)}
              aria-label="Pantalla completa"
              title="Pantalla completa"
              ><svg
                xmlns="http://www.w3.org/2000/svg"
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                ><polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"
                ></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line
                  x1="3"
                  y1="21"
                  x2="10"
                  y2="14"
                ></line></svg
              ></button
            >
          </div>
        </div>
        {#if chart.hoveredPoint && chart.tooltipPos}<div
            class="pointer-events-none absolute z-10 rounded-(--radius-sm) border border-(--color-edge) bg-(--color-elevated) px-3 py-2 text-xs shadow-[0_8px_24px_-8px_rgba(0,0,0,0.5)]"
            style="left: {chart.tooltipPos.x + 14}px; top: {chart.tooltipPos.y - 42}px;"
          >
            <p class="font-medium text-(--color-primary)">{chart.hoveredPoint.value} min</p>
            <p class="mt-0.5 text-(--color-text-muted)">{chart.hoveredPoint.label}</p>
          </div>{/if}
        <StatsChart
          {chartMeta}
          granularity={activeGranularity}
          locale={settingsState.locale}
          size="inline"
          containerRef={chartContainer}
          {chart}
          emptyLabel={_t('stats.noActivity')}
          emptyHint={_t('stats.noActivityHint')}
        />
      </article>
      <StatsStreakCard {streakDays} calendar={streakCalendar} t={_t} />
    </div>
    <Modal bind:open={chart.chartModalOpen} title={_t('stats.minutesReadChart')} size="xl"
      >{#snippet children()}<div class="relative -mx-2 -mt-2">
          {#if chart.hoveredPoint && chart.tooltipPos}<div
              class="pointer-events-none absolute z-10 rounded-(--radius-sm) border border-(--color-edge) bg-(--color-elevated) px-3 py-2 text-xs shadow-[0_8px_24px_-8px_rgba(0,0,0,0.5)]"
              style="left: {chart.tooltipPos.x + 14}px; top: {chart.tooltipPos.y - 42}px;"
            >
              <p class="font-medium text-(--color-primary)">{chart.hoveredPoint.value} min</p>
              <p class="mt-0.5 text-(--color-text-muted)">{chart.hoveredPoint.label}</p>
            </div>{/if}<StatsChart
            {chartMeta}
            granularity={activeGranularity}
            locale={settingsState.locale}
            size="modal"
            containerRef={chartContainer}
            {chart}
            emptyLabel={_t('stats.noActivity')}
            emptyHint={_t('stats.noActivityHint')}
          />
        </div>{/snippet}</Modal
    >

    <div class="grid grid-cols-1 gap-5 xl:grid-cols-2">
      <article bind:this={genreContainer} class="relative {panelClass} p-5">
        {#if chart.genreTooltip && chart.genreTooltipPos}<div
            class="pointer-events-none absolute z-10 rounded-(--radius-sm) border border-(--color-edge) bg-(--color-elevated) px-3 py-2 text-xs shadow-[0_8px_24px_-8px_rgba(0,0,0,0.5)]"
            style="left: {chart.genreTooltipPos.x + 14}px; top: {chart.genreTooltipPos.y - 38}px;"
          >
            <p class="font-medium text-(--color-primary)">{chart.genreTooltip.genre}</p>
            <p class="mt-0.5 text-(--color-text-muted)">
              {chart.genreTooltip.minutes} min · {chart.genreTooltip.percent}%
            </p>
            {#if chart.genreTooltip.books.length > 0}<div
                class="mt-1.5 border-t border-(--color-edge) pt-1.5"
              >
                {#each chart.genreTooltip.books.slice(0, 3) as bookTitle}<p
                    class="truncate text-(--color-text-muted)"
                  >
                    <Book size={14} strokeWidth={1.8} class="inline -mt-0.5 mr-1" />{bookTitle}
                  </p>{/each}{#if chart.genreTooltip.books.length > 3}<p
                    class="mt-0.5 text-(--color-text-muted)"
                  >
                    +{chart.genreTooltip.books.length - 3} más
                  </p>{/if}
              </div>{/if}
          </div>{/if}
        <div class="mb-5">
          <h2 class="text-lg font-bold tracking-[-0.01em] text-(--color-primary)">
            {_t('stats.timeByGenre')}
          </h2>
          <p class="text-sm text-(--color-text-muted)">{_t('stats.genreDistribution')}</p>
        </div>
        <GenreDonut
          distribution={genreDistribution}
          {booksByGenre}
          {totalMinutes}
          containerRef={genreContainer}
          {chart}
          minutesLabel={_t('stats.minutes')}
          emptyLabel={_t('stats.noGenres')}
          emptyHint={_t('stats.noGenresHint')}
        />
      </article>
      <StatsMostRead
        books={mostReadBooks}
        t={_t}
        onOpen={(book) => navigationState.openShelfDetails(book.id)}
      />
    </div>

    <StatsHabits
      minutesPerSession={averageMinutesPerSession}
      minutesPerDay={averageMinutesPerDay}
      pagesRead={totalPagesRead}
      unclassifiedPercent={unclassifiedEntry?.percent ?? 0}
      t={_t}
    />
  {/if}
</section>
