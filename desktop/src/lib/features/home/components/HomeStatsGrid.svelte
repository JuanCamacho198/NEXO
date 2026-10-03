<script lang="ts">
  import type { ReadingStatsSummaryDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  import Clock from 'lucide-svelte/icons/clock';
  import Flame from 'lucide-svelte/icons/flame';
  import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';

  type Props = {
    stats: ReadingStatsSummaryDto | null;
    isLoading?: boolean;
    disabledReason?: string | null;
    streakDays?: number;
    isLoadingStreak?: boolean;
    t?: (key: MessageKey, params?: Record<string, string | number>) => string;
    todayMinutes?: number | null;
    dailyGoalMinutes?: number | null;
    goalProgress?: number | null;
  };

  let {
    stats,
    isLoading = false,
    disabledReason = null,
    streakDays = 0,
    isLoadingStreak = false,
    t: _t,
    todayMinutes: todayMinutesProp = null,
    dailyGoalMinutes: dailyGoalMinutesProp = null,
    goalProgress: goalProgressProp = null,
  }: Props = $props();

  const todayMinutes = $derived(
    todayMinutesProp !== null ? todayMinutesProp : statsState.todayMinutes,
  );
  const dailyGoalMinutes = $derived(
    dailyGoalMinutesProp !== null
      ? dailyGoalMinutesProp
      : (statsState.dailyGoalMinutes ?? settingsState.dailyGoalMinutes ?? 20),
  );
  const goalProgress = $derived(
    goalProgressProp !== null ? goalProgressProp : statsState.goalProgress,
  );

  const goalPct = $derived(Math.round(Math.min(1, Math.max(0, goalProgress ?? 0)) * 100));
  const goalValue = $derived(
    _t
      ? _t('home.metrics.minutesFormat', { current: todayMinutes, total: dailyGoalMinutes })
      : `${todayMinutes}/${dailyGoalMinutes} min`,
  );

  const progressTitle = $derived(_t ? _t('home.progressTitle') : 'Reading progress');
  const goalLabel = $derived(_t ? _t('home.metrics.dailyGoalLabel') : 'Daily goal');
  const progressGoalAria = $derived(_t ? _t('home.progressGoalAria') : 'Daily goal progress');
  const streakLabel = $derived(_t ? _t('stats.streakLabel') : 'Streak');
  const sessionsLabel = $derived(_t ? _t('stats.sessionsLabel') : 'Sessions');
  const startedLabel = $derived(_t ? _t('stats.booksStartedLabel') : 'Started');
  const completedLabel = $derived(_t ? _t('stats.booksCompletedLabel') : 'Completed');

  const streakValue = $derived(
    isLoadingStreak
      ? '—'
      : _t
        ? _t('stats.days', { count: streakDays })
        : `${streakDays} ${streakDays === 1 ? 'day' : 'days'}`,
  );

  type ActivityItem = { label: string; value: number };

  const activityItems = $derived<ActivityItem[]>([
    { label: sessionsLabel, value: stats?.totalSessions ?? 0 },
    { label: startedLabel, value: stats?.booksStarted ?? 0 },
    { label: completedLabel, value: stats?.booksCompleted ?? 0 },
  ]);
</script>

<aside
  class="rounded-(--radius-xl) border border-(--color-border) bg-(--color-surface) p-4 shadow-(--shadow-soft)"
  data-testid="stats-summary"
  aria-busy={isLoading || undefined}
>
  {#if disabledReason}
    <p class="text-sm text-(--color-text-muted)">{disabledReason}</p>
  {:else}
    <div class="flex items-center gap-2">
      <Clock size={14} strokeWidth={1.8} class="text-(--color-text-muted)" aria-hidden="true" />
      <h2 class="text-sm font-semibold tracking-tight text-(--color-primary)">{progressTitle}</h2>
    </div>

    <div class="mt-3">
      <div class="flex items-baseline justify-between gap-3">
        <span class="text-xs font-medium text-(--color-text-muted)">{goalLabel}</span>
        {#if isLoading}
          <span class="h-5 w-20 animate-pulse rounded bg-(--color-border)" aria-hidden="true"
          ></span>
        {:else}
          <span class="text-base font-semibold text-(--color-primary)">{goalValue}</span>
        {/if}
      </div>
      <div
        class="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-(--color-border)"
        role="progressbar"
        aria-label={progressGoalAria}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={isLoading ? undefined : goalPct}
      >
        {#if !isLoading}
          <div class="h-full rounded-full bg-(--color-accent)" style="width: {goalPct}%"></div>
        {/if}
      </div>
    </div>

    <dl class="mt-4 grid grid-cols-2 gap-x-3 gap-y-3 border-t border-(--color-border) pt-3">
      <div class="min-w-0">
        <dt
          class="flex items-center gap-1 text-2xs uppercase tracking-wider text-(--color-text-muted)"
        >
          <Flame size={12} strokeWidth={1.8} aria-hidden="true" />
          {streakLabel}
        </dt>
        <dd class="mt-1 truncate text-sm font-semibold text-(--color-primary)">{streakValue}</dd>
      </div>
      {#each activityItems as item (item.label)}
        <div class="min-w-0">
          <dt class="truncate text-2xs uppercase tracking-wider text-(--color-text-muted)">
            {item.label}
          </dt>
          <dd class="mt-1 text-sm font-semibold text-(--color-primary)">
            {#if isLoading}
              <span class="inline-block h-4 w-8 animate-pulse rounded bg-(--color-border)"></span>
            {:else}
              {item.value}
            {/if}
          </dd>
        </div>
      {/each}
    </dl>
  {/if}
</aside>
