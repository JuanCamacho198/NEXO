<script lang="ts">
  import Flame from 'lucide-svelte/icons/flame';
  import { panelClass } from '$lib/shared/ui/emberStyles';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    streakDays: number;
    /** Oldest first; the last entry is today. */
    calendar: Array<{ label: string; active: boolean }>;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { streakDays, calendar, t }: Props = $props();

  const alive = $derived(streakDays > 0);
</script>

<article class="flex flex-col {panelClass} p-5">
  <h2 class="text-lg font-bold tracking-[-0.01em] text-(--color-primary)">
    {t('stats.currentStreak')}
  </h2>
  <p class="text-sm text-(--color-text-muted)">{t('stats.streakDesc')}</p>

  <div class="mt-5 flex items-center gap-4">
    <span
      class="flex size-14 shrink-0 items-center justify-center rounded-full {alive
        ? 'bg-linear-to-br from-(--color-brand) to-(--color-brand-end) text-(--color-brand-mark-ink) shadow-[0_10px_24px_-10px_var(--color-brand-glow)]'
        : 'border border-(--color-edge) bg-(--color-surface-subtle) text-(--color-text-muted)'}"
      aria-hidden="true"
    >
      <Flame size={26} strokeWidth={1.8} fill={alive ? 'currentColor' : 'none'} />
    </span>
    <div>
      <p
        class="text-4xl leading-none font-bold tracking-[-0.02em] tabular-nums {alive
          ? 'text-(--color-primary)'
          : 'text-(--color-secondary)'}"
      >
        {t('stats.days', { count: streakDays })}
      </p>
      <p class="mt-1.5 text-sm text-(--color-text-muted)">{t('stats.keepGoing')}</p>
    </div>
  </div>

  <!-- Two weeks as a 7-column grid; the last cell is today. -->
  <ol class="m-0 mt-6 grid list-none grid-cols-7 gap-1.5 p-0" aria-label={t('stats.streakDesc')}>
    {#each calendar as day, i (i)}
      {@const today = i === calendar.length - 1}
      <li
        class="flex aspect-square items-center justify-center rounded-(--radius-sm) text-xs font-semibold {day.active
          ? 'bg-linear-to-br from-(--color-brand-start) to-(--color-brand) text-(--color-brand-on)'
          : 'border border-(--color-edge) bg-(--color-surface-subtle) text-(--color-text-muted)'} {today
          ? 'ring-2 ring-(--color-brand)/60 ring-offset-2 ring-offset-(--color-home-card)'
          : ''}"
      >
        {day.label}
      </li>
    {/each}
  </ol>
</article>
