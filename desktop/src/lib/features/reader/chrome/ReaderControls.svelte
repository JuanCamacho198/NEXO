<script lang="ts">
  import type { Snippet } from 'svelte';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import PageInput from './PageInput.svelte';
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

  let prevButton: HTMLButtonElement | null = $state(null);
  let nextButton: HTMLButtonElement | null = $state(null);
  let focusedControl = $state<'prev' | 'next'>('prev');

  const prevDisabled = $derived(currentPage <= 1);
  const nextDisabled = $derived(currentPage >= totalPages);

  // Roving tabindex across the paging buttons: exactly one enabled button
  // stays in the tab order; arrow keys move focus inside the toolbar.
  const rovingTabindex = $derived.by((): { prev: number; next: number } => {
    if (!prevDisabled && (focusedControl === 'prev' || nextDisabled)) return { prev: 0, next: -1 };
    if (!nextDisabled) return { prev: -1, next: 0 };
    return { prev: -1, next: -1 };
  });

  function focusPaging(buttons: Array<HTMLButtonElement | null>, index: number): void {
    const enabled = buttons.filter((b): b is HTMLButtonElement => b !== null && !b.disabled);
    if (enabled.length === 0) return;
    const clamped = ((index % enabled.length) + enabled.length) % enabled.length;
    enabled[clamped]?.focus();
  }

  function handleToolbarKeydown(event: KeyboardEvent): void {
    if (
      event.key !== 'ArrowRight' &&
      event.key !== 'ArrowLeft' &&
      event.key !== 'Home' &&
      event.key !== 'End'
    )
      return;
    const buttons = [prevButton, nextButton];
    // Inputs (page number) and slotted controls keep their own keys.
    if (!buttons.includes(document.activeElement as HTMLButtonElement)) return;
    const enabled = buttons.filter((b): b is HTMLButtonElement => b !== null && !b.disabled);
    if (enabled.length === 0) return;
    event.preventDefault();
    const current = enabled.indexOf(document.activeElement as HTMLButtonElement);
    if (event.key === 'ArrowRight') focusPaging(buttons, current + 1);
    else if (event.key === 'ArrowLeft') focusPaging(buttons, current - 1);
    else if (event.key === 'Home') focusPaging(buttons, 0);
    else focusPaging(buttons, enabled.length - 1);
  }
</script>

<div
  class="flex items-center gap-2 px-2 py-2 bg-(--color-surface) border-b border-(--color-border) flex-wrap sm:gap-3 sm:px-3"
  role="toolbar"
  aria-label={t('reader.toolbar')}
  tabindex={-1}
  onkeydown={handleToolbarKeydown}
>
  <button
    type="button"
    bind:this={prevButton}
    onclick={onPrev}
    disabled={currentPage <= 1}
    tabindex={rovingTabindex.prev}
    onfocus={() => (focusedControl = 'prev')}
    class="inline-flex items-center justify-center px-2.5 py-1.5 border border-(--color-border) rounded bg-(--color-surface) text-(--color-primary) cursor-pointer text-xs min-w-8 min-h-8 hover:not-disabled:bg-[color-mix(in_srgb,var(--color-primary)_8%,var(--color-surface))] disabled:opacity-50 disabled:cursor-not-allowed"
    aria-label={t('reader.prev_page')}
    data-testid={prevTestId}
    {...restProps}
  >
    <ChevronLeft size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
  </button>
  <button
    type="button"
    bind:this={nextButton}
    onclick={onNext}
    disabled={currentPage >= totalPages}
    tabindex={rovingTabindex.next}
    onfocus={() => (focusedControl = 'next')}
    class="inline-flex items-center justify-center px-2.5 py-1.5 border border-(--color-border) rounded bg-(--color-surface) text-(--color-primary) cursor-pointer text-xs min-w-8 min-h-8 hover:not-disabled:bg-[color-mix(in_srgb,var(--color-primary)_8%,var(--color-surface))] disabled:opacity-50 disabled:cursor-not-allowed"
    aria-label={t('reader.next_page')}
    data-testid={nextTestId}
  >
    <ArrowRight size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
  </button>
  <PageInput
    variant="toolbar"
    {currentPage}
    {totalPages}
    {t}
    {onGoToPage}
    {pageInputTestId}
    {totalPagesTestId}
  />
  {#if right}
    {@render right()}
  {/if}
</div>
