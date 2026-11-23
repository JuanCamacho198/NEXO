<script lang="ts">
  import FilterChip from '$lib/shared/ui/forms/FilterChip.svelte';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import type { CatalogSourceInfo } from '$lib/shared/services/catalog';
  import { ALL_SOURCES, type DiscoverSourceFilter } from './discoverSourceFilter';
  import { providerLabel } from './discoverProviderLabel';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  /**
   * Source filter row for the Discover idle view: a lead "All" chip plus one
   * chip per available source, narrowing the rails to that source only. Labels
   * reuse `providerLabel()` so the chips and the card attribution badges can
   * never drift (brand names stay out of i18n). Renders nothing when the build
   * exposes no source (addons unregistered) — an empty row would be a lie.
   */
  let {
    t,
    sources,
    selected,
    onSelect,
  }: {
    t: Translate;
    sources: readonly CatalogSourceInfo[];
    selected: DiscoverSourceFilter;
    onSelect: (source: DiscoverSourceFilter) => void;
  } = $props();

  function toggle(sourceId: string): void {
    onSelect(selected === sourceId ? ALL_SOURCES : sourceId);
  }
</script>

{#if sources.length > 0}
  <div role="group" aria-label={t('discover.sourceFilter.all')} class="flex flex-wrap gap-2">
    <FilterChip selected={selected === ALL_SOURCES} onclick={() => onSelect(ALL_SOURCES)}>
      {t('discover.sourceFilter.all')}
    </FilterChip>
    {#each sources as source (source.sourceId)}
      <FilterChip selected={selected === source.sourceId} onclick={() => toggle(source.sourceId)}>
        {providerLabel(source.sourceId)}
      </FilterChip>
    {/each}
  </div>
{/if}
