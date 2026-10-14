<script lang="ts">
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

  const activeClass = 'border-(--color-primary)/40 bg-(--color-primary)/12 text-(--color-primary)';
  const idleClass =
    'border-(--color-border) bg-(--color-surface-subtle) text-(--color-text-muted) hover:border-(--color-primary)/40 hover:text-(--color-primary)';
</script>

{#if sources.length > 0}
  <div role="group" aria-label={t('discover.sourceFilter.all')} class="flex flex-wrap gap-2">
    <button
      type="button"
      aria-pressed={selected === ALL_SOURCES}
      onclick={() => onSelect(ALL_SOURCES)}
      class="rounded-full border px-3 py-1 text-xs font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-(--color-primary)/50 {selected ===
      ALL_SOURCES
        ? activeClass
        : idleClass}"
    >
      {t('discover.sourceFilter.all')}
    </button>
    {#each sources as source (source.sourceId)}
      <button
        type="button"
        aria-pressed={selected === source.sourceId}
        onclick={() => toggle(source.sourceId)}
        class="rounded-full border px-3 py-1 text-xs font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-(--color-primary)/50 {selected ===
        source.sourceId
          ? activeClass
          : idleClass}"
      >
        {providerLabel(source.sourceId)}
      </button>
    {/each}
  </div>
{/if}
