<script lang="ts">
  import { SafeCover } from '$lib/features/library';
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
  import { useReadingChart } from './useReadingChart.svelte';
  import Book from 'lucide-svelte/icons/book';
  import Clock from 'lucide-svelte/icons/clock';
  import Flame from 'lucide-svelte/icons/flame';
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
    void statsState.loadRangeStats(from, to, undefined, 'current');
    void statsState.loadRangeStats(prevFrom, prevTo, undefined, 'previous');
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
    class="sticky top-0 z-20 border-b border-(--color-border) bg-(--color-surface-overlay) px-4 py-3 shadow-(--shadow-panel) md:px-6"
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
  <div
    bind:this={heroEl}
    class="rounded-(--radius-2xl) border border-(--color-border) bg-(image:--gradient-hero) p-5 shadow-(--shadow-hero)"
  >
    <div class="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
      <div class="flex-1">
        <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">
          {_t('stats.title')}
        </h1>
        <p class="mt-1 text-sm text-(--color-text-muted)">{_t('stats.subtitle')}</p>
      </div>
      <div
        class="flex items-center gap-4 rounded-xl border border-(--color-streak-border) bg-(image:--gradient-streak) px-5 py-3"
      >
        <div
          class="flex h-12 w-12 items-center justify-center rounded-full bg-(image:--gradient-streak-icon) text-white"
          aria-hidden="true"
        >
          <Flame size={22} strokeWidth={2} />
        </div>
        <div>
          <p
            class="text-3xl font-bold tracking-tight {streakDays === 0
              ? 'text-(--color-text-muted)'
              : 'text-(--color-streak-text)'}"
          >
            {_t('stats.days', { count: streakDays })}
          </p>
          <p class="text-xs text-(--color-text-muted)">{_t('stats.currentStreak')}</p>
        </div>
      </div>
      <Dropdown options={periodDropdownOptions} bind:value={activePeriod} class="min-w-[120px]" />
    </div>
    {#if activitySeries.length > 0}{@const last7 = activitySeries.slice(-7)}{@const max7 = Math.max(
        ...last7.map((p) => p.value),
        1,
      )}
      <div class="mt-4 flex items-end gap-1.5">
        <span class="mr-2 text-xs font-medium text-(--color-text-muted)">7d:</span
        >{#each last7 as point}{@const h = Math.max(8, Math.round((point.value / max7) * 32))}
          <div class="flex flex-col items-center gap-1">
            <div
              class="w-6 rounded-t bg-(--color-accent-blue) transition-all"
              style="height: {h}px; opacity: {point.value > 0 ? 1 : 0.25}"
            ></div>
            <span class="text-[9px] text-(--color-text-muted)">{point.value}m</span>
          </div>{/each}
      </div>{/if}
    {#if classifiedGenres.length > 0}<div class="mt-3 flex flex-wrap gap-2">
        {#each classifiedGenres.slice(0, 5) as g}<span
            class="inline-flex items-center gap-1.5 rounded-full border border-(--color-border) bg-(--color-surface) px-2.5 py-1 text-xs"
            ><span class="h-2 w-2 rounded-full" style={`background:${g.color};`} aria-hidden="true"
            ></span>{g.genre}
            {g.percent}%</span
          >{/each}
      </div>{:else}<p class="mt-3 text-xs text-(--color-text-muted)">
        {_t('stats.noGenres')} — {_t('stats.noGenresHint')}
      </p>{/if}
  </div>
  {#if disabledReason}<div
      class="rounded-(--radius-xl) border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900"
    >
      {disabledReason}
    </div>{:else if isLoading}<div
      class="rounded-(--radius-xl) border border-(--color-border) bg-(--color-bg-panel) px-4 py-8 text-sm text-(--color-text-muted)"
    >
      {_t('stats.loading')}
    </div>{:else}
    <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <article
        class="flex flex-col justify-between rounded-(--radius-xl) border border-(--color-border) bg-(--color-panel-accent) p-5 shadow-(--shadow-panel)"
      >
        <div class="flex items-center gap-2 text-(--color-text-muted)">
          <Clock size={16} strokeWidth={1.8} aria-hidden="true" />
          <p class="text-xs">{primaryMetric.label}</p>
        </div>
        <p class="mt-4 text-5xl font-semibold tracking-tight text-(--color-primary)">
          {primaryMetric.value}
        </p>
        {#if primaryMetric.delta.text}<p
            class="mt-2 text-xs"
            class:text-(--color-success)={primaryMetric.delta.tone === 'positive'}
            class:text-(--color-error)={primaryMetric.delta.tone === 'negative'}
            class:text-(--color-text-muted)={primaryMetric.delta.tone === 'neutral' ||
              primaryMetric.delta.tone === 'none'}
          >
            {primaryMetric.delta.text}
          </p>{/if}
      </article>
      <div class="grid grid-cols-2 gap-4 lg:col-span-2">
        {#each secondaryMetrics as metric}<article
            class="rounded-(--radius-xl) border border-(--color-border) bg-(--color-bg-panel) p-4 shadow-(--shadow-panel)"
          >
            <p class="text-xs text-(--color-text-muted)">{metric.label}</p>
            <p class="mt-3 text-2xl font-semibold tracking-tight text-(--color-secondary)">
              {metric.value}
            </p>
            {#if metric.delta.text}<p
                class="mt-2 text-xs"
                class:text-(--color-success)={metric.delta.tone === 'positive'}
                class:text-(--color-error)={metric.delta.tone === 'negative'}
                class:text-(--color-text-muted)={metric.delta.tone === 'neutral' ||
                  metric.delta.tone === 'none'}
              >
                {metric.delta.text}
              </p>{/if}
          </article>{/each}
      </div>
    </div>
    <div class="grid grid-cols-1 gap-4 2xl:grid-cols-[2.2fr_1fr]">
      <article
        bind:this={chartContainer}
        class="relative rounded-(--radius-2xl) border border-(--color-border) bg-(--color-bg-panel) p-4 shadow-(--shadow-panel)"
      >
        <div class="mb-4 flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div>
            <h2 class="text-base font-semibold text-(--color-primary)">
              {_t('stats.minutesReadChart')}
            </h2>
            <p class="text-sm text-(--color-text-muted)">{_t('stats.activityTimeline')}</p>
          </div>
          <Dropdown
            options={granularityOptions.map((o) => ({ ...o, label: _t(o.label as MessageKey) }))}
            bind:value={activeGranularity}
            class="min-w-[100px]"
          /><button
            type="button"
            class="flex h-8 w-8 items-center justify-center rounded-lg border border-(--color-border) bg-(--color-surface-subtle) text-sm text-(--color-text-muted) cursor-pointer hover:border-(--color-primary) hover:text-(--color-primary) transition-colors duration-150"
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
        {#if chart.hoveredPoint && chart.tooltipPos}<div
            class="pointer-events-none absolute z-10 rounded-lg border border-(--color-border) bg-(--color-bg-panel) px-3 py-2 text-xs shadow-(--shadow-panel)"
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
      <Modal bind:open={chart.chartModalOpen} title={_t('stats.minutesReadChart')} size="xl"
        >{#snippet children()}<div class="relative -mx-2 -mt-2">
            {#if chart.hoveredPoint && chart.tooltipPos}<div
                class="pointer-events-none absolute z-10 rounded-lg border border-(--color-border) bg-(--color-bg-panel) px-3 py-2 text-xs shadow-(--shadow-panel)"
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
      <article
        bind:this={genreContainer}
        class="relative rounded-(--radius-2xl) border border-(--color-border) bg-(--color-bg-panel) p-4 shadow-(--shadow-panel)"
      >
        {#if chart.genreTooltip && chart.genreTooltipPos}<div
            class="pointer-events-none absolute z-10 rounded-lg border border-(--color-border) bg-(--color-bg-panel) px-3 py-2 text-xs shadow-(--shadow-panel)"
            style="left: {chart.genreTooltipPos.x + 14}px; top: {chart.genreTooltipPos.y - 38}px;"
          >
            <p class="font-medium text-(--color-primary)">{chart.genreTooltip.genre}</p>
            <p class="mt-0.5 text-(--color-text-muted)">
              {chart.genreTooltip.minutes} min · {chart.genreTooltip.percent}%
            </p>
            {#if chart.genreTooltip.books.length > 0}<div
                class="mt-1.5 border-t border-(--color-border) pt-1.5"
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
        <div class="mb-4">
          <h2 class="text-base font-semibold text-(--color-primary)">{_t('stats.timeByGenre')}</h2>
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
    </div>
    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[1.35fr_1fr]">
      <article
        class="rounded-(--radius-2xl) border border-(--color-border) bg-(--color-bg-panel) p-4 shadow-(--shadow-panel)"
      >
        <div class="mb-4">
          <h2 class="text-base font-semibold text-(--color-primary)">
            {_t('stats.mostReadBooks')}
          </h2>
          <p class="text-sm text-(--color-text-muted)">{_t('stats.mostReadBooksDesc')}</p>
        </div>
        <div class="space-y-3">
          {#each mostReadBooks as book}<button
              type="button"
              class="flex w-full items-center gap-3 rounded-[22px] border border-(--color-border) bg-(--color-surface-subtle) p-3 cursor-pointer hover:border-(--color-primary) text-left"
              onclick={() => navigationState.openShelfDetails(book.id)}
              ><div
                class="h-14 w-10 shrink-0 overflow-hidden rounded-xl bg-(--color-surface-subtle)"
              >
                <SafeCover
                  path={book.coverPath ?? ''}
                  alt={_t('stats.bookCover', { title: book.title })}
                  className="h-full w-full object-cover"
                  >{#snippet fallback()}<div
                      class="flex h-full w-full items-center justify-center bg-(image:--gradient-cover-fallback) text-[9px] uppercase tracking-[0.16em] text-(--color-primary)"
                    >
                      {_t('stats.bookPlaceholder')}
                    </div>{/snippet}</SafeCover
                >
              </div>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium text-(--color-primary)">{book.title}</p>
                <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-(--color-border)">
                  <div
                    class="h-full rounded-full bg-(image:--gradient-accent-h)"
                    style={`width: ${Math.max(12, Math.round((book.minutesRead / Math.max(mostReadBooks[0]?.minutesRead || 1, 1)) * 100))}%;`}
                  ></div>
                </div>
              </div>
              <span class="shrink-0 text-sm text-(--color-secondary)">{book.minutesRead} min</span
              ></button
            >{/each}
        </div>
      </article>
      <article class="grid gap-4">
        <div
          class="rounded-(--radius-2xl) border border-(--color-border) bg-(--color-bg-panel) p-4 shadow-(--shadow-panel)"
        >
          <div class="mb-3">
            <h2 class="text-base font-semibold text-(--color-primary)">
              {_t('stats.currentStreak')}
            </h2>
            <p class="text-sm text-(--color-text-muted)">{_t('stats.streakDesc')}</p>
          </div>
          <p class="text-4xl font-semibold tracking-tight text-(--color-primary)">
            {_t('stats.days', { count: streakDays })}
          </p>
          <p class="mt-1 text-sm text-(--color-text-muted)">{_t('stats.keepGoing')}</p>
          <div class="mt-5 flex flex-wrap gap-2">
            {#each streakCalendar as day}<div class="flex flex-col items-center gap-2">
                <div
                  class={`flex h-8 w-8 items-center justify-center rounded-full text-xs ${day.active ? 'bg-(image:--gradient-accent) text-(--color-accent-on)' : 'border border-(--color-border) bg-(--color-surface-subtle) text-(--color-text-muted)'}`}
                >
                  {day.label}
                </div>
              </div>{/each}
          </div>
        </div>
        <div
          class="rounded-(--radius-2xl) border border-(--color-border) bg-(--color-bg-panel) p-4 shadow-(--shadow-panel)"
        >
          <div class="mb-4">
            <h2 class="text-base font-semibold text-(--color-primary)">
              {_t('stats.additionalInfo')}
            </h2>
            <p class="text-sm text-(--color-text-muted)">{_t('stats.additionalDesc')}</p>
          </div>
          <div class="grid grid-cols-1 gap-3 sm:grid-cols-3 xl:grid-cols-1">
            <div
              class="rounded-[20px] border border-(--color-border) bg-(--color-surface-subtle) p-3"
            >
              <p class="text-xs text-(--color-text-muted)">{_t('stats.averagePerSession')}</p>
              <p class="mt-2 text-2xl font-semibold text-(--color-primary)">
                {averageMinutesPerSession} min
              </p>
            </div>
            <div
              class="rounded-[20px] border border-(--color-border) bg-(--color-surface-subtle) p-3"
            >
              <p class="text-xs text-(--color-text-muted)">{_t('stats.averagePerDay')}</p>
              <p class="mt-2 text-2xl font-semibold text-(--color-primary)">
                {averageMinutesPerDay} min
              </p>
            </div>
            <div
              class="rounded-[20px] border border-(--color-border) bg-(--color-surface-subtle) p-3"
            >
              <p class="text-xs text-(--color-text-muted)">{_t('stats.pagesRead')}</p>
              <p class="mt-2 text-2xl font-semibold text-(--color-primary)">
                {totalPagesRead.toLocaleString('es-CO')}
              </p>
            </div>
          </div>
          {#if unclassifiedEntry && unclassifiedEntry.percent > 0}
            <p class="mt-3 border-t border-(--color-border) pt-3 text-xs text-(--color-text-muted)">
              {_t('stats.unclassifiedNote', { percent: unclassifiedEntry.percent })}
            </p>
          {/if}
        </div>
      </article>
    </div>
  {/if}
</section>
