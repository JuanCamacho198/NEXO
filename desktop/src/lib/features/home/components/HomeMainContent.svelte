<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    shelfSection?: Snippet;
    onNavigateLibrary?: () => void;
  };

  let { t, shelfSection, onNavigateLibrary }: Props = $props();
</script>

<section class="space-y-4" aria-labelledby="home-recent-books-heading">
  <header class="flex flex-wrap items-end justify-between gap-3">
    <div class="min-w-0">
      <div class="flex items-center gap-3">
        <span
          class="h-7 w-1 shrink-0 rounded-full bg-linear-to-b from-(--color-brand-start) to-(--color-brand-end)"
          aria-hidden="true"
        ></span>
        <h2
          id="home-recent-books-heading"
          class="text-2xl font-bold tracking-[-0.02em] text-(--color-primary)"
        >
          {t('home.recentBooksTitle')}
        </h2>
      </div>
      <p class="mt-1 text-sm text-(--color-text-muted)">{t('home.recentBooksHint')}</p>
    </div>
    {#if onNavigateLibrary}
      <button
        type="button"
        onclick={onNavigateLibrary}
        class="group/all inline-flex shrink-0 items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-semibold text-(--color-brand-text) transition-colors duration-(--duration-fast) ease-(--ease-smooth) hover:bg-(--color-brand-soft) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
      >
        {t('home.viewAll')}
        <ArrowRight
          size={16}
          strokeWidth={2}
          class="transition-transform duration-(--duration-fast) ease-(--ease-smooth) group-hover/all:translate-x-0.5"
          aria-hidden="true"
        />
      </button>
    {/if}
  </header>

  {#if shelfSection}
    {@render shelfSection()}
  {:else}
    <p class="text-sm text-(--color-text-muted)">{t('home.myShelfPlaceholder')}</p>
  {/if}
</section>
