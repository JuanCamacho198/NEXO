<script lang="ts">
  import ReaderControls from '../chrome/ReaderControls.svelte';
  import ZoomDropdown from '../chrome/ZoomDropdown.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    currentPage: number;
    totalPages: number;
    currentPercentage: number;
    fontSize: number;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onPrev: () => void;
    onNext: () => void;
    onGoToPage: (page: number) => Promise<boolean>;
    onFontSizeChange: (size: number) => void;
  };

  let {
    currentPage,
    totalPages,
    currentPercentage,
    fontSize,
    t,
    onPrev,
    onNext,
    onGoToPage,
    onFontSizeChange,
  }: Props = $props();
</script>

<ReaderControls
  {currentPage}
  {totalPages}
  {t}
  {onPrev}
  {onNext}
  {onGoToPage}
  prevTestId="epub-prev"
  nextTestId="epub-next"
  pageInputTestId="epub-page-input"
  totalPagesTestId="epub-total-pages"
>
  {#snippet right()}
    <span class="text-xs text-(--color-text-muted) min-w-10 text-center"
      >{Math.round(currentPercentage)}%</span
    >
    <ZoomDropdown value={fontSize} onSelect={onFontSizeChange} />
  {/snippet}
</ReaderControls>
