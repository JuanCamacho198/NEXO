<script lang="ts">
  import Clock from 'lucide-svelte/icons/clock';
  import TrendingUp from 'lucide-svelte/icons/trending-up';
  import TrendingDown from 'lucide-svelte/icons/trending-down';
  import type { DeltaResult } from './readingStatsState.svelte';

  type Metric = { label: string; value: string; delta: DeltaResult };
  type Props = { primary: Metric; secondary: Metric[] };

  let { primary, secondary }: Props = $props();
</script>

{#snippet delta(d: DeltaResult)}
  {#if d.text}
    <p
      class="mt-2 inline-flex items-center gap-1 text-xs {d.tone === 'positive'
        ? 'text-(--color-success)'
        : d.tone === 'negative'
          ? 'text-(--color-error)'
          : 'text-(--color-text-muted)'}"
    >
      {#if d.tone === 'positive'}
        <TrendingUp size={13} strokeWidth={2} aria-hidden="true" />
      {:else if d.tone === 'negative'}
        <TrendingDown size={13} strokeWidth={2} aria-hidden="true" />
      {/if}
      {d.text}
    </p>
  {/if}
{/snippet}

<!-- One plane, hairline cells: the figures read as a single summary rather
  than a wall of equal tiles. The gap-px grid draws the dividers. -->
<section
  class="grid grid-cols-2 gap-px overflow-hidden rounded-(--radius-lg) border border-(--color-edge) bg-(--color-edge) lg:grid-cols-[1.4fr_repeat(4,1fr)]"
>
  <div
    class="relative col-span-2 overflow-hidden bg-(--color-home-card) p-5 lg:col-span-1 lg:row-span-1"
  >
    <p class="relative flex items-center gap-2 text-sm text-(--color-secondary)">
      <Clock size={16} strokeWidth={1.8} class="text-(--color-brand-text)" aria-hidden="true" />
      {primary.label}
    </p>
    <p
      class="relative mt-3 text-5xl leading-none font-bold tracking-[-0.03em] tabular-nums text-(--color-primary)"
    >
      {primary.value}<span class="ml-1.5 text-lg font-semibold text-(--color-text-muted)">min</span>
    </p>
    <div class="relative">{@render delta(primary.delta)}</div>
  </div>

  {#each secondary as metric (metric.label)}
    <div class="bg-(--color-home-card) p-5">
      <p class="text-xs text-(--color-text-muted)">{metric.label}</p>
      <p
        class="mt-3 text-3xl leading-none font-bold tracking-tight tabular-nums text-(--color-primary)"
      >
        {metric.value}
      </p>
      {@render delta(metric.delta)}
    </div>
  {/each}
</section>
