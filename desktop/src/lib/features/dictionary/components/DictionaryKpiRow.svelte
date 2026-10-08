<script lang="ts">
  import BookOpen from 'lucide-svelte/icons/book-open';
  import CalendarPlus from 'lucide-svelte/icons/calendar-plus';
  import Library from 'lucide-svelte/icons/library';
  import type { Icon as LucideIcon } from 'lucide-svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { DictionaryKpis } from '../dictionaryKpis';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    kpis: DictionaryKpis;
  };

  let { t, kpis }: Props = $props();

  const cards = $derived<{ key: MessageKey; icon: typeof LucideIcon; value: number }[]>([
    { key: 'dictionary.kpiTotal', icon: BookOpen, value: kpis.total },
    { key: 'dictionary.kpiThisWeek', icon: CalendarPlus, value: kpis.thisWeek },
    { key: 'dictionary.kpiReferencedBooks', icon: Library, value: kpis.referencedBooks },
  ]);
</script>

<div class="grid grid-cols-1 gap-3 sm:grid-cols-3" data-testid="dictionary-kpi-row">
  {#each cards as card (card.key)}
    {@const CardIcon = card.icon}
    <article
      class="flex items-center justify-between gap-3 rounded-lg border border-(--color-panel-border) bg-(--color-panel) p-4"
    >
      <div class="flex min-w-0 flex-col gap-1">
        <span
          class="truncate text-xs font-semibold tracking-wide text-(--color-text-tertiary)"
          data-testid="dictionary-kpi-label"
        >
          {t(card.key)}
        </span>
        <span class="text-2xl font-extrabold text-(--color-primary)">{card.value}</span>
      </div>
      <div
        class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-(--color-accent-fill) text-(--color-accent-blue)"
      >
        <CardIcon size={16} strokeWidth={1.8} class="h-4 w-4" aria-hidden="true" />
      </div>
    </article>
  {/each}
</div>
