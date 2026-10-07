<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import SearchBar from '$lib/shared/ui/navigation/SearchBar.svelte';
  import { TRENDING_CHIPS } from './DiscoverDomainState.svelte';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  let {
    t,
    sourceCount,
    isOnline,
    selectedChip,
    onSelectChip,
    onSearchSubmit,
  }: {
    t: Translate;
    sourceCount: number;
    isOnline: boolean;
    selectedChip: string | null;
    onSelectChip: (chip: string | null) => void;
    onSearchSubmit: (query: string) => void;
  } = $props();

  let searchInput = $state('');

  function submit(event: Event): void {
    event.preventDefault();
    onSearchSubmit(searchInput);
  }

  function toggleChip(chip: string): void {
    onSelectChip(selectedChip === chip ? null : chip);
  }
</script>

<div class="flex flex-col gap-4">
  <div class="flex flex-wrap items-start justify-between gap-3">
    <div class="flex min-w-0 flex-col gap-1">
      <h1 id="discover-heading" class="m-0 text-xl font-semibold text-(--color-primary)">
        {t('discover.heroTitle')}
      </h1>
      <p class="m-0 text-sm text-(--color-text-muted)">{t('discover.heroSubtitle')}</p>
    </div>
    {#if isOnline}
      <span
        role="status"
        class="shrink-0 rounded-full border border-(--color-border) bg-(--color-accent-soft) px-3 py-1 text-xs font-medium text-(--color-secondary)"
      >
        {t('discover.onlineSources', { count: sourceCount })}
      </span>
    {:else}
      <span
        role="status"
        class="shrink-0 rounded-full border border-(--color-border) bg-(--color-surface-subtle) px-3 py-1 text-xs font-medium text-(--color-text-muted)"
      >
        {t('discover.offlineLabel')}
      </span>
    {/if}
  </div>

  <!-- Enter (or the implicit form submit) is the only trigger. A second submit
       button read as a duplicate control and broke the field's pill silhouette. -->
  <form class="max-w-2xl" onsubmit={submit}>
    <SearchBar
      bind:value={searchInput}
      class="w-full"
      placeholder={t('discover.searchPlaceholder')}
      ariaLabel={t('discover.searchAriaLabel')}
      shortcutKey="/"
      shortcutLabel={t('discover.searchShortcut')}
      shortcutAriaLabel={t('discover.searchShortcutAria')}
      onsubmit={onSearchSubmit}
    />
  </form>

  <div role="group" aria-label={t('discover.trending')} class="flex flex-wrap gap-2">
    <button
      type="button"
      aria-pressed={selectedChip === null}
      onclick={() => onSelectChip(null)}
      class="rounded-full border px-3 py-1 text-xs font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-(--color-primary)/50 {selectedChip ===
      null
        ? 'border-(--color-primary)/40 bg-(--color-primary)/12 text-(--color-primary)'
        : 'border-(--color-border) bg-(--color-surface-subtle) text-(--color-text-muted) hover:border-(--color-primary)/40 hover:text-(--color-primary)'}"
    >
      {t('home.shelfTab.all')}
    </button>
    {#each TRENDING_CHIPS as chip (chip)}
      <button
        type="button"
        aria-pressed={selectedChip === chip}
        onclick={() => toggleChip(chip)}
        class="rounded-full border px-3 py-1 text-xs font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-(--color-primary)/50 {selectedChip ===
        chip
          ? 'border-(--color-primary)/40 bg-(--color-primary)/12 text-(--color-primary)'
          : 'border-(--color-border) bg-(--color-surface-subtle) text-(--color-text-muted) hover:border-(--color-primary)/40 hover:text-(--color-primary)'}"
      >
        {chip}
      </button>
    {/each}
  </div>
</div>
