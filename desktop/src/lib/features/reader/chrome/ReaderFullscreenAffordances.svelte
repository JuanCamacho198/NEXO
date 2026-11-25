<script lang="ts">
  import { fade, fly } from 'svelte/transition';
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import ChevronRight from 'lucide-svelte/icons/chevron-right';
  import Share2 from 'lucide-svelte/icons/share-2';
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

  const EXIT_HINT_MS = 3000;

  // How to get out, said once on the way in (as browsers and video players
  // do), then out of the way of the page.
  let exitHintVisible = $state(false);
  $effect(() => {
    if (!isFullscreen) {
      exitHintVisible = false;
      return;
    }
    exitHintVisible = true;
    const timer = setTimeout(() => (exitHintVisible = false), EXIT_HINT_MS);
    return () => clearTimeout(timer);
  });

  // The chevrons belong to the chrome: they come with the bars or with a
  // pointer near an edge, and otherwise leave the page alone. A hidden one
  // does not take clicks, so the margin click pages the book underneath.
  const edgesShown = $derived(edgeNavVisible || chromeVisible);

  function edgeClass(disabled: boolean): string {
    if (!edgesShown)
      return 'pointer-events-none opacity-0 focus-visible:pointer-events-auto focus-visible:opacity-100';
    return disabled ? 'cursor-not-allowed opacity-30' : 'cursor-pointer opacity-100';
  }

  const EDGE_BUTTON =
    'fixed top-1/2 z-40 flex size-12 -translate-y-1/2 items-center justify-center rounded-full border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg)/85 text-(--color-text-inverse) shadow-[0_12px_28px_-14px_rgba(0,0,0,0.6)] backdrop-blur transition-opacity duration-300 hover:bg-(--color-highlight-menu-bg) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)';
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
    class="{EDGE_BUTTON} left-4 {edgeClass(prevDisabled)}"
    aria-label={t('reader.prev_page')}
    onclick={onPrev}
    disabled={prevDisabled}
    aria-disabled={prevDisabled}
  >
    <ChevronLeft size={22} strokeWidth={2} aria-hidden="true" />
  </button>
  <button
    type="button"
    class="{EDGE_BUTTON} right-4 {edgeClass(nextDisabled)}"
    aria-label={t('reader.next_page')}
    onclick={onNext}
    disabled={nextDisabled}
    aria-disabled={nextDisabled}
  >
    <ChevronRight size={22} strokeWidth={2} aria-hidden="true" />
  </button>
  {#if selectedText}
    <button
      type="button"
      class="fixed bottom-20 left-1/2 z-40 flex -translate-x-1/2 cursor-pointer items-center gap-2 rounded-full bg-(--color-brand) px-4 py-2 text-sm font-semibold text-(--color-brand-on) shadow-lg transition-opacity hover:opacity-90"
      onclick={onShare}
    >
      <Share2 size={16} strokeWidth={2} aria-hidden="true" />
      {t('reader.share')}
    </button>
  {/if}
  {#if exitHintVisible}
    <div
      class="pointer-events-none fixed top-20 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2.5 rounded-full border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) py-2 pr-4 pl-2 text-sm text-(--color-text-inverse) shadow-[0_18px_40px_-16px_rgba(0,0,0,0.6)]"
      aria-live="polite"
      data-testid="fullscreen-exit-hint"
      in:fly={{ y: -8, duration: 200 }}
      out:fade={{ duration: 300 }}
    >
      <kbd
        class="rounded-md border border-white/20 bg-white/10 px-2 py-0.5 font-sans text-xs font-semibold"
        >Esc</kbd
      >
      <span>{t('pdf.fullscreenExit')}</span>
    </div>
  {/if}
{/if}
