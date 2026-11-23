<script lang="ts">
  import FilterChip from '$lib/shared/ui/forms/FilterChip.svelte';
  import { headingMarker } from '$lib/shared/ui/emberStyles';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import SearchBar from '$lib/shared/ui/navigation/SearchBar.svelte';
  import { CHIP_TAXONOMY } from './discoverChips';

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
      <div class="flex items-center gap-3">
        <div class="h-8 {headingMarker}" aria-hidden="true"></div>
        <h1
          id="discover-heading"
          class="m-0 text-[1.75rem] font-bold tracking-[-0.02em] text-(--color-primary)"
        >
          {t('discover.heroTitle')}
        </h1>
      </div>
      <p class="m-0 text-sm text-(--color-text-muted)">{t('discover.heroSubtitle')}</p>
    </div>
    {#if isOnline}
      <span
        role="status"
        class="inline-flex shrink-0 items-center gap-2 rounded-full border border-(--color-edge) bg-(--color-home-card) px-3 py-1.5 text-xs font-medium text-(--color-secondary)"
      >
        <span class="size-1.5 rounded-full bg-(--color-success)" aria-hidden="true"></span>
        {t('discover.onlineSources', { count: sourceCount })}
      </span>
    {:else}
      <span
        role="status"
        class="inline-flex shrink-0 items-center gap-2 rounded-full border border-(--color-edge) bg-(--color-surface-subtle) px-3 py-1.5 text-xs font-medium text-(--color-text-muted)"
      >
        {t('discover.offlineLabel')}
      </span>
    {/if}
  </div>

  <!-- Enter (or the implicit form submit) is the only trigger. A second submit
       button read as a duplicate control and broke the field's pill silhouette. -->
  <form class="max-w-3xl" onsubmit={submit}>
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
    <FilterChip selected={selectedChip === null} onclick={() => onSelectChip(null)}>
      {t('home.shelfTab.all')}
    </FilterChip>
    {#each CHIP_TAXONOMY as chip (chip.id)}
      <FilterChip selected={selectedChip === chip.id} onclick={() => toggleChip(chip.id)}>
        {t(chip.labelKey) || chip.fallback}
      </FilterChip>
    {/each}
  </div>
</div>
