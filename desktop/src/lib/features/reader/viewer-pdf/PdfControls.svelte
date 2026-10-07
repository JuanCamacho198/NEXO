<script lang="ts">
  import ReaderControls from '../chrome/ReaderControls.svelte';
  import ZoomDropdown from '../chrome/ZoomDropdown.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import { debugState } from '$lib/shared/debug/debugState.svelte';

  type Props = {
    currentPage: number;
    totalPages: number;
    scale: number;
    isLoading: boolean;
    error: string | null;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onPrevPage: () => void;
    onNextPage: () => void;
    onGoToPage: (page: number) => Promise<boolean>;
    onSetScale: (scale: number) => void;
  };

  let {
    currentPage,
    totalPages,
    scale,
    isLoading,
    error,
    t,
    onPrevPage,
    onNextPage,
    onGoToPage,
    onSetScale,
  }: Props = $props();

  const zoomPercent = $derived(Math.round(scale * 100));
</script>

<div style:visibility={isLoading || error ? 'hidden' : 'visible'}>
  <ReaderControls
    {currentPage}
    {totalPages}
    {t}
    onPrev={onPrevPage}
    onNext={onNextPage}
    {onGoToPage}
  >
    {#snippet right()}
      {#if debugState.enabled}
        <span
          class="text-micro text-(--pdf-reader-text,var(--color-text-auxiliary)) opacity-60 font-mono"
          >p{currentPage}/{totalPages} | {zoomPercent}%</span
        >
      {/if}
      <ZoomDropdown value={zoomPercent} onSelect={(v) => onSetScale(v / 100)} />
    {/snippet}
  </ReaderControls>
</div>
