<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    formatBytes: (bytes: number) => string;
    tempBytes: number;
    booksBytes: number;
  };

  let { t, formatBytes, tempBytes, booksBytes }: Props = $props();

  // Both parts are local bytes of the same whole (what NEXO keeps on this
  // device), so a part-to-whole bar is honest. Temporary takes the ember:
  // it is the part "Free up space" removes.
  const total = $derived(Math.max(0, tempBytes) + Math.max(0, booksBytes));
  const parts = $derived([
    {
      key: 'temporary',
      label: t('storage.breakdown.temporary'),
      bytes: Math.max(0, tempBytes),
      swatch: 'bg-(--color-chart-1)',
    },
    {
      key: 'books',
      label: t('storage.breakdown.bookFiles'),
      bytes: Math.max(0, booksBytes),
      swatch: 'bg-(--color-chart-2)',
    },
  ]);

  function percentOf(bytes: number): number {
    return total > 0 ? (bytes / total) * 100 : 0;
  }
</script>

<div class="flex flex-col gap-4">
  <!-- Decorative twin of the list below, which carries the same figures as text. -->
  <div
    class="flex h-3 w-full gap-0.5 overflow-hidden rounded-full bg-(--color-surface-hover)"
    aria-hidden="true"
    data-testid="storage-breakdown-bar"
  >
    {#each parts as part (part.key)}
      {#if part.bytes > 0}
        <span
          class="h-full min-w-1.5 first:rounded-l-full last:rounded-r-full {part.swatch}"
          style="width: {percentOf(part.bytes)}%"
        ></span>
      {/if}
    {/each}
  </div>

  <dl
    class="m-0 grid grid-cols-[auto_auto_1fr] items-center gap-x-3 gap-y-2 text-sm"
    data-testid="storage-breakdown"
  >
    {#each parts as part (part.key)}
      <dt class="flex items-center gap-2 text-(--color-secondary)">
        <span class="size-2.5 shrink-0 rounded-full {part.swatch}" aria-hidden="true"></span>
        {part.label}
      </dt>
      <dd class="m-0 font-medium tabular-nums text-(--color-primary)">{formatBytes(part.bytes)}</dd>
      <dd class="m-0 tabular-nums text-(--color-text-muted)">
        {Math.round(percentOf(part.bytes))}%
      </dd>
    {/each}
  </dl>
</div>
