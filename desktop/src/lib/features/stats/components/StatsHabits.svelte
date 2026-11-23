<script lang="ts">
  import Timer from 'lucide-svelte/icons/timer';
  import CalendarDays from 'lucide-svelte/icons/calendar-days';
  import FileText from 'lucide-svelte/icons/file-text';
  import { panelClass } from '$lib/shared/ui/emberStyles';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    minutesPerSession: number;
    minutesPerDay: number;
    pagesRead: number;
    unclassifiedPercent?: number;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { minutesPerSession, minutesPerDay, pagesRead, unclassifiedPercent = 0, t }: Props = $props();

  const habits = $derived([
    { icon: Timer, label: t('stats.averagePerSession'), value: `${minutesPerSession} min` },
    { icon: CalendarDays, label: t('stats.averagePerDay'), value: `${minutesPerDay} min` },
    { icon: FileText, label: t('stats.pagesRead'), value: pagesRead.toLocaleString('es-CO') },
  ]);
</script>

<article class="{panelClass} p-5">
  <h2 class="text-lg font-bold tracking-[-0.01em] text-(--color-primary)">
    {t('stats.additionalInfo')}
  </h2>
  <p class="text-sm text-(--color-text-muted)">{t('stats.additionalDesc')}</p>

  <dl
    class="m-0 mt-5 grid grid-cols-1 divide-y divide-(--color-edge) sm:grid-cols-3 sm:divide-x sm:divide-y-0"
  >
    {#each habits as habit (habit.label)}
      {@const Icon = habit.icon}
      <div class="flex items-center gap-3.5 py-3 sm:px-5 sm:py-1 sm:first:pl-0">
        <span
          class="flex size-10 shrink-0 items-center justify-center rounded-full bg-(--color-brand-soft) text-(--color-brand-text)"
          aria-hidden="true"
        >
          <Icon size={18} strokeWidth={1.8} />
        </span>
        <div class="min-w-0">
          <dt class="text-xs text-(--color-text-muted)">{habit.label}</dt>
          <dd class="m-0 text-2xl font-bold tracking-tight tabular-nums text-(--color-primary)">
            {habit.value}
          </dd>
        </div>
      </div>
    {/each}
  </dl>

  {#if unclassifiedPercent > 0}
    <p class="mt-4 border-t border-(--color-edge) pt-3 text-xs text-(--color-text-muted)">
      {t('stats.unclassifiedNote', { percent: unclassifiedPercent })}
    </p>
  {/if}
</article>
