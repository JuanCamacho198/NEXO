<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    isFullscreen: boolean;
    hasBook: boolean;
    edgeNavVisible: boolean;
    prevDisabled: boolean;
    nextDisabled: boolean;
    chromeVisible: boolean;
    selectedText: string;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onPrev: () => void;
    onNext: () => void;
    onShare: () => void;
    onMouseMove: (event: MouseEvent) => void;
  };

  let {
    isFullscreen,
    hasBook,
    edgeNavVisible,
    prevDisabled,
    nextDisabled,
    chromeVisible,
    selectedText,
    t,
    onPrev,
    onNext,
    onShare,
    onMouseMove,
  }: Props = $props();
</script>

{#if isFullscreen && hasBook}
  <!-- U2: persistent rescue strips. The chapter iframe swallows hover, so
    these 1-2px DOM strips above it guarantee a mouse path back. -->
  <div
    role="presentation"
    aria-hidden="true"
    class="fixed inset-x-0 top-0 z-40 h-2"
    onmousemove={onMouseMove}
  ></div>
  <div
    role="presentation"
    aria-hidden="true"
    class="fixed bottom-0 left-0 top-0 z-40 w-2"
    onmousemove={onMouseMove}
  ></div>
  <div
    role="presentation"
    aria-hidden="true"
    class="fixed bottom-0 right-0 top-0 z-40 w-2"
    onmousemove={onMouseMove}
  ></div>
  <!-- ADR-007: the edge chevrons share the paging handlers and disabled state
    with the bottom bar, but never its focus ring — no tabindex management and
    no entry in the toolbar's roving-tabindex array. They keep their own
    accessible names, which is why "exactly one prev button" is not a contract. -->
  <button
    type="button"
    class="fixed left-4 top-1/2 -translate-y-1/2 z-40 flex h-12 w-12 items-center justify-center rounded-full bg-(--color-bg-deep)/40 text-(--color-text-inverse) backdrop-blur hover:bg-(--color-bg-deep)/60 transition-opacity duration-200 cursor-pointer {edgeNavVisible
      ? 'opacity-100'
      : 'opacity-40'} {prevDisabled ? 'opacity-30 cursor-not-allowed' : ''}"
    aria-label={t('reader.prev_page')}
    onclick={onPrev}
    disabled={prevDisabled}
    aria-disabled={prevDisabled}
    ><svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"><path d="M15 18l-6-6 6-6" /></svg
    ></button
  >
  <button
    type="button"
    class="fixed right-4 top-1/2 -translate-y-1/2 z-40 flex h-12 w-12 items-center justify-center rounded-full bg-(--color-bg-deep)/40 text-(--color-text-inverse) backdrop-blur hover:bg-(--color-bg-deep)/60 transition-opacity duration-200 cursor-pointer {edgeNavVisible
      ? 'opacity-100'
      : 'opacity-40'} {nextDisabled ? 'opacity-30 cursor-not-allowed' : ''}"
    aria-label={t('reader.next_page')}
    onclick={onNext}
    disabled={nextDisabled}
    aria-disabled={nextDisabled}
    ><svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"><path d="M9 18l6-6-6-6" /></svg
    ></button
  >
  {#if selectedText}
    <button
      type="button"
      class="fixed bottom-20 left-1/2 -translate-x-1/2 z-40 flex items-center gap-2 rounded-full bg-(--color-accent-blue) px-4 py-2 text-sm font-medium text-(--color-accent-on) shadow-lg hover:opacity-90 cursor-pointer"
      onclick={onShare}
      ><svg
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        ><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle
          cx="18"
          cy="19"
          r="3"
        /><path d="M8.6 13.5l6.8 4M15.4 6.5l-6.8 4" /></svg
      >{t('reader.share')}</button
    >
  {/if}
  {#if chromeVisible}
    <p
      class="pointer-events-none fixed bottom-6 left-1/2 -translate-x-1/2 z-40 whitespace-nowrap text-xs text-(--color-text-inverse)/70"
      aria-hidden="true"
    >
      {t('reader.fullscreenKeysHint')}
    </p>
  {/if}
{/if}
