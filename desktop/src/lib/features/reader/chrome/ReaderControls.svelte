<script lang="ts">
  import type { Snippet } from 'svelte';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    currentPage: number;
    totalPages: number;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onPrev: () => void;
    onNext: () => void;
    onGoToPage: (page: number) => Promise<boolean>;
    right?: Snippet;
    /** Optional data-testid for the previous page button */
    prevTestId?: string;
    /** Optional data-testid for the next page button */
    nextTestId?: string;
    /** Optional data-testid for the page input */
    pageInputTestId?: string;
    /** Optional data-testid for the total pages span */
    totalPagesTestId?: string;
  };

  let {
    currentPage,
    totalPages,
    t,
    onPrev,
    onNext,
    onGoToPage,
    right,
    prevTestId,
    nextTestId,
    pageInputTestId,
    totalPagesTestId,
    ...restProps
  }: Props = $props();

  let pageValue = $state(1);

  $effect(() => {
    pageValue = currentPage;
  });

  async function handlePageInput(event: Event): Promise<void> {
    const target = event.target as HTMLInputElement;
    const page = Number.parseInt(target.value, 10);
    if (Number.isFinite(page) && page >= 1 && page <= totalPages) {
      const success = await onGoToPage(page);
      if (!success) {
        target.value = String(currentPage);
      }
    } else {
      target.value = String(currentPage);
    }
  }
</script>

<div
  class="flex items-center gap-3 px-3 py-2 bg-(--color-surface) border-b border-(--color-border) flex-wrap"
>
  <button
    type="button"
    onclick={onPrev}
    disabled={currentPage <= 1}
    class="inline-flex items-center justify-center px-2.5 py-1.5 border border-(--color-border) rounded bg-(--color-surface) text-(--color-primary) cursor-pointer text-xs min-w-8 min-h-8 hover:not-disabled:bg-[color-mix(in_srgb,var(--color-primary)_8%,var(--color-surface))] disabled:opacity-50 disabled:cursor-not-allowed"
    aria-label={t('reader.prev_page')}
    data-testid={prevTestId}
    {...restProps}
  >
    <ChevronLeft size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
  </button>
  <button
    type="button"
    onclick={onNext}
    disabled={currentPage >= totalPages}
    class="inline-flex items-center justify-center px-2.5 py-1.5 border border-(--color-border) rounded bg-(--color-surface) text-(--color-primary) cursor-pointer text-xs min-w-8 min-h-8 hover:not-disabled:bg-[color-mix(in_srgb,var(--color-primary)_8%,var(--color-surface))] disabled:opacity-50 disabled:cursor-not-allowed"
    aria-label={t('reader.next_page')}
    data-testid={nextTestId}
  >
    <ArrowRight size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
  </button>
  <span class="flex items-center gap-1 text-xs text-(--color-primary)">
    <input
      type="number"
      min="1"
      max={totalPages}
      value={pageValue}
      onchange={handlePageInput}
      class="w-[50px] p-1 border border-(--color-border) rounded text-center bg-(--color-surface) text-(--color-primary)"
      aria-label={t('reader.page_input')}
      data-testid={pageInputTestId}
    />
    <span class="text-xs text-(--color-text-muted) opacity-70" data-testid={totalPagesTestId}
      >/ {totalPages}</span
    >
  </span>
  {#if right}
    {@render right()}
  {/if}
</div>
