<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import { CHROME_PAGE_FIELD } from './chromeControl';

  type Props = {
    currentPage: number;
    totalPages: number;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onGoToPage?: (page: number) => Promise<boolean>;
    /** Optional data-testid for the page input */
    pageInputTestId?: string;
    /** Optional data-testid for the total pages span */
    totalPagesTestId?: string;
  };

  let { currentPage, totalPages, t, onGoToPage, pageInputTestId, totalPagesTestId }: Props =
    $props();

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

  const wrapClass = 'flex items-center gap-1 text-xs text-(--color-text-muted)';
  const inputClass = CHROME_PAGE_FIELD;
  const totalClass = 'text-xs text-(--color-text-muted)';
</script>

<span class={wrapClass}>
  <input
    type="text"
    inputmode="numeric"
    pattern="[0-9]*"
    value={pageValue}
    onchange={handlePageInput}
    class={inputClass}
    aria-label={t('reader.page_input')}
    data-testid={pageInputTestId}
  />
  <span class={totalClass} data-testid={totalPagesTestId} aria-hidden="true">/ {totalPages}</span>
</span>
