<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import Flame from 'lucide-svelte/icons/flame';
  import CalendarDays from 'lucide-svelte/icons/calendar-days';
  import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';

  type Props = {
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

  const streakValue = $derived(
    isLoadingStreak
      ? '—'
      : _t
        ? _t('stats.days', { count: streakDays })
        : `${streakDays} ${streakDays === 1 ? 'day' : 'days'}`,
  );
</script>

<aside
  class="rounded-(--radius-lg) border border-(--color-edge) bg-(--color-home-card) px-6 py-4"
  data-testid="stats-summary"
  aria-label={progressTitle}
  aria-busy={isLoading || undefined}
>
  {#if disabledReason}
    <p class="text-sm text-(--color-text-muted)">{disabledReason}</p>
  {:else}
    <div class="flex flex-wrap items-center gap-x-10 gap-y-3">
      <div class="min-w-50 flex-1">
        <div class="flex items-center justify-between gap-3">
          <span class="flex items-center gap-2.5 text-sm font-medium text-(--color-secondary)">
            <CalendarDays
              size={18}
              strokeWidth={1.8}
              class="text-(--color-text-muted)"
              aria-hidden="true"
            />
            {goalLabel}
          </span>
          {#if isLoading}
            <span class="h-5 w-20 animate-pulse rounded bg-(--color-border)" aria-hidden="true"
            ></span>
          {:else}
            <span class="text-base font-bold tabular-nums text-(--color-primary)">{goalValue}</span>
          {/if}
        </div>
        <div
          class="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-(--color-edge)"
          role="progressbar"
          aria-label={progressGoalAria}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={isLoading ? undefined : goalPct}
        >
          {#if !isLoading}
            <div
              class="h-full w-full origin-left rounded-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand) transition-transform duration-(--duration-slow) ease-(--ease-smooth)"
              style="transform: scaleX({goalPct / 100})"
            ></div>
          {/if}
        </div>
      </div>

      <div class="flex items-center gap-2">
        <Flame
          size={20}
          strokeWidth={1.8}
          fill="currentColor"
          class="text-(--color-brand)"
          aria-hidden="true"
        />
        {#if isLoading}
          <span
            class="inline-block h-4 w-12 animate-pulse rounded bg-(--color-border)"
            aria-hidden="true"
          ></span>
        {:else}
          <span
            class="text-base font-bold tabular-nums text-(--color-brand-text)"
            data-testid="home-streak-value">{streakValue}</span
          >
        {/if}
      </div>
    </div>
  {/if}
</aside>
