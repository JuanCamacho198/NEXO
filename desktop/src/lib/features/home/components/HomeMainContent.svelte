<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import { Button } from '$lib/shared/ui';

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
      <h2
        id="home-recent-books-heading"
        class="text-base font-semibold tracking-tight text-(--color-primary)"
      >
        {t('home.recentBooksTitle')}
      </h2>
      <p class="mt-0.5 text-xs text-(--color-text-muted)">{t('home.recentBooksHint')}</p>
    </div>
    {#if onNavigateLibrary}
      <Button variant="ghost" size="sm" onclick={onNavigateLibrary} class="shrink-0">
        {t('home.viewAll')}
        <ArrowRight size={14} strokeWidth={1.8} class="ml-1" aria-hidden="true" />
      </Button>
    {/if}
  </header>

  {#if shelfSection}
    {@render shelfSection()}
  {:else}
    <p class="text-sm text-(--color-text-muted)">{t('home.myShelfPlaceholder')}</p>
  {/if}
</section>
