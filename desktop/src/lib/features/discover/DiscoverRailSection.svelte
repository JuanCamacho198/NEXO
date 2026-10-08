<script lang="ts">
  import type { CatalogBook } from '$lib/shared/services/catalog';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import type { DiscoverRailState } from './DiscoverRailsDomainState.svelte';
  import { deriveVisibleRailCount } from './railPlan';
  import DiscoverCard from './DiscoverCard.svelte';
  import DiscoverRailError from './DiscoverRailError.svelte';
  import DiscoverSkeletonCard from './DiscoverSkeletonCard.svelte';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  /**
   * Per-rail renderer: owns its own Loading skeleton, Loaded grid (with the
   * "Ver todo" affordance) and Error + retry. `Hidden` and a chip filter that
   * matched nothing render no section at all — no placeholder header.
   */
  let {
    title,
    state: railState,
    books = [],
    t,
    onOpen,
    onPrefetch,
    onRetry,
    onViewAll,
  }: {
    title: string;
    state: DiscoverRailState;
    /** Already chip-filtered books; used only while the rail is `Loaded`. */
    books?: CatalogBook[];
    t: Translate;
    onOpen: (book: CatalogBook) => void;
    /** Optional cache warm on pointer/keyboard intent; threaded to the cards. */
    onPrefetch?: (book: CatalogBook) => void;
    onRetry: () => void;
    onViewAll: () => void;
  } = $props();

  /**
   * Measured width of the rail container. The section spans the available
   * content box and holds no padding of its own, so its width is exactly the
   * width the card grid lays out in.
   */
  let sectionEl = $state<HTMLElement | null>(null);
  let railWidth = $state(0);

  $effect(() => {
    const element = sectionEl;
    if (!element || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver((entries) => {
      railWidth = entries[0]?.contentRect.width ?? element.clientWidth;
    });
    observer.observe(element);
    railWidth = element.clientWidth;
    return () => observer.disconnect();
  });

  /**
   * One filled row at any width. Loading placeholders and loaded cards derive
   * from the same number, so a resize never disagrees with the skeleton.
   */
  const visibleCount = $derived(deriveVisibleRailCount(railWidth));
  const visible = $derived(books.slice(0, visibleCount));
  const skeletonSlots = $derived(Array.from({ length: visibleCount }, (_, index) => index));
</script>

{#if railState.kind === 'Loading'}
  <section bind:this={sectionEl} aria-label={title} class="flex flex-col gap-3">
    <h2 class="m-0 text-lg font-semibold text-(--color-primary)">{title}</h2>
    <div class="grid gap-3" style="grid-template-columns: repeat(auto-fill, minmax(160px, 1fr))">
      {#each skeletonSlots as slot (slot)}
        <DiscoverSkeletonCard />
      {/each}
    </div>
  </section>
{:else if railState.kind === 'Error'}
  <section bind:this={sectionEl} aria-label={title} class="flex flex-col gap-3">
    <h2 class="m-0 text-lg font-semibold text-(--color-primary)">{title}</h2>
    <DiscoverRailError code={railState.code} {t} {onRetry} />
  </section>
{:else if visible.length > 0}
  <section bind:this={sectionEl} aria-label={title} class="flex flex-col gap-3">
    <div class="flex items-baseline justify-between gap-3">
      <h2 class="m-0 text-lg font-semibold text-(--color-primary)">{title}</h2>
      <button
        type="button"
        class="rounded-md px-2 py-1 text-sm font-medium text-(--color-primary) transition-colors hover:bg-(--color-primary)/10"
        onclick={onViewAll}
      >
        {t('discover.rail.viewAll')}
      </button>
    </div>
    <div class="grid gap-3" style="grid-template-columns: repeat(auto-fill, minmax(160px, 1fr))">
      {#each visible as book (book.id)}
        <DiscoverCard {book} {onOpen} {onPrefetch} />
      {/each}
    </div>
  </section>
{/if}
