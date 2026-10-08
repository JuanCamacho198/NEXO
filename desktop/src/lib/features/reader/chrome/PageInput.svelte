<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    currentPage: number;
    totalPages: number;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onGoToPage?: (page: number) => Promise<boolean>;
    variant: 'toolbar' | 'header';
    /** Optional data-testid for the page input */
    pageInputTestId?: string;
    /** Optional data-testid for the total pages span */
    totalPagesTestId?: string;
  };

  let {
    currentPage,
    totalPages,
    t,
    onGoToPage,
    variant,
    pageInputTestId,
    totalPagesTestId,
  }: Props = $props();

  let pageValue = $state(1);

  $effect(() => {
    pageValue = currentPage;
  });

  async function handlePageInput(event: Event): Promise<void> {
    const target = event.target as HTMLInputElement;
    const page = Number.parseInt(target.value, 10);
    if (Number.isFinite(page) && page >= 1 && page <= totalPages) {
      const ok = (await onGoToPage?.(page)) ?? false;
      if (!ok) {
        target.value = String(currentPage);
      }
    } else {
      target.value = String(currentPage);
    }
  }

  const wrapClass = $derived(
    variant === 'header'
      ? 'flex items-center gap-1 text-xs text-(--color-text-auxiliary)'
      : 'flex items-center gap-1 text-xs text-(--color-primary)',
  );
  const inputClass = $derived(
    variant === 'header'
      ? 'w-[50px] min-h-11 p-1 border border-(--color-surface-strong) rounded text-center bg-(--color-bg-deep) text-(--color-text-auxiliary)'
      : 'w-[50px] min-h-11 p-1 border border-(--color-border) rounded text-center bg-(--color-surface) text-(--color-primary)',
  );
  const totalClass = $derived(
    variant === 'header'
      ? 'text-xs text-(--color-text-auxiliary) opacity-70'
      : 'text-xs text-(--color-text-muted) opacity-70',
  );
</script>

<span class={wrapClass}>
  <input
    type="number"
    min="1"
    max={totalPages}
    value={pageValue}
    onchange={handlePageInput}
    class={inputClass}
    aria-label={t('reader.page_input')}
    data-testid={pageInputTestId}
  />
  <span class={totalClass} data-testid={totalPagesTestId} aria-hidden="true">/ {totalPages}</span>
</span>
