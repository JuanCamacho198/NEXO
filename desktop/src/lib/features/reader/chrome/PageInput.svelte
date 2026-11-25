<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import { CHROME_PAGE_FIELD } from './chromeControl';

  type Props = {
    currentPage: number;
    totalPages: number;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onGoToPage?: (page: number) => Promise<boolean>;
    /**
     * What the two numbers count, when that is not the whole document (EPUB
     * paginated mode counts the pages of the chapter on screen). It becomes the
     * field's name and tooltip so "1 / 31" is never read as the book's pages.
     */
    unitLabel?: string;
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
    unitLabel,
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

  // Typing a page replaces the number, it does not append to it: "12" typed
  // over "1" used to read "112" and fall outside the book.
  function handleFocus(event: FocusEvent): void {
    (event.currentTarget as HTMLInputElement).select();
  }

  // Enter commits (and leaves the field, so the arrows page again); Escape
  // gives the number back untouched.
  function handleKeydown(event: KeyboardEvent): void {
    const target = event.currentTarget as HTMLInputElement;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      target.value = String(currentPage);
      target.blur();
    } else if (event.key === 'Enter') {
      target.blur();
    }
  }

  // Both halves of "page M of N" are unbreakable units: the field keeps its
  // recipe width and the label never splits between the slash and the number.
  // Without `shrink-0` the label was a flex shrink victim and rendered as "/"
  // above "43" whenever the field grew greedy (see CHROME_PAGE_FIELD).
  const wrapClass = 'flex shrink-0 items-center gap-0.5 text-sm text-(--color-text-muted)';
  const inputClass = CHROME_PAGE_FIELD;
  const totalClass =
    'shrink-0 whitespace-nowrap pr-1.5 text-sm tabular-nums text-(--color-text-muted)';
</script>

<span class={wrapClass} title={unitLabel}>
  <input
    type="text"
    inputmode="numeric"
    pattern="[0-9]*"
    value={pageValue}
    onchange={handlePageInput}
    onfocus={handleFocus}
    onkeydown={handleKeydown}
    class={inputClass}
    aria-label={unitLabel ?? t('reader.page_input')}
    data-testid={pageInputTestId}
  />
  <span class={totalClass} data-testid={totalPagesTestId} aria-hidden="true">/ {totalPages}</span>
</span>
