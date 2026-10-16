<script lang="ts">
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import ChevronRight from 'lucide-svelte/icons/chevron-right';
  import Bookmark from 'lucide-svelte/icons/bookmark';
  import X from 'lucide-svelte/icons/x';
  import FitControls from './FitControls.svelte';
  import PageInput from './PageInput.svelte';
  import ZoomDropdown from './ZoomDropdown.svelte';
  import type { FitMode } from './fitZoom';
  import { chromeIconButton } from './chromeControl';
  import type { MessageKey } from '$lib/shared/i18n';
  import { debugState } from '$lib/shared/debug/debugState.svelte';

  type Props = {
    title: string;
    bookProgress: number;
    currentPdfPage: number;
    totalPdfPages: number;
    viewerKind: 'pdf' | 'epub' | null;
    isFullscreen: boolean;
    visible: boolean;
    currentPage: number;
    totalPages: number;
    zoomPercent: number;
    bookmarkCount: number;
    bookmarksLoading: boolean;
    showBookmarks: boolean;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onPrev: () => void;
    onNext: () => void;
    onGoToPage: (page: number) => Promise<boolean>;
    onFit: (mode: FitMode) => void;
    onZoomSelect: (percent: number) => void;
    onToggleBookmarks: () => void;
  };

  let {
    title,
    bookProgress,
    currentPdfPage,
    totalPdfPages,
    viewerKind,
    isFullscreen,
    visible,
    currentPage,
    totalPages,
    zoomPercent,
    bookmarkCount,
    bookmarksLoading,
    showBookmarks,
    t,
    onPrev,
    onNext,
    onGoToPage,
    onFit,
    onZoomSelect,
    onToggleBookmarks,
  }: Props = $props();

  let prevButton: HTMLButtonElement | null = $state(null);
  let nextButton: HTMLButtonElement | null = $state(null);
  let focusedControl = $state<'prev' | 'next'>('prev');

  const isPdf = $derived(viewerKind === 'pdf');
  const prevDisabled = $derived(currentPage <= 1);
  const nextDisabled = $derived(currentPage >= totalPages);
  const BookmarksIcon = $derived(showBookmarks ? X : Bookmark);
  const barTransform = $derived(visible ? 'translate-y-0' : 'translate-y-full');

  // Roving tabindex across the paging buttons: exactly one enabled button
  // stays in the tab order; arrow keys move focus inside the toolbar.
  // The fullscreen edge chevrons stay OUTSIDE this ring: they are pointer-only
  // shortcuts sharing the handlers and disabled state, never focus state.
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
    // Inputs (page number) and other controls keep their own keys.
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

<!-- The single bottom bar: paging, the page field, fit and zoom for both modes,
  the progress readout, and the bookmarks control. It is never hidden by
  fullscreen state — only the immersive chrome visibility drives `barTransform`. -->
<footer
  class="flex h-12 shrink-0 items-center justify-between gap-2 border-t border-(--color-border) bg-(--color-reader-chrome) px-3 transition-transform duration-300 sm:px-4 {isFullscreen
    ? 'fixed inset-x-0 bottom-0 z-50'
    : ''} {barTransform}"
  class:shadow-md={isFullscreen}
>
  <span class="font-inter text-xs font-normal text-(--color-text-muted) min-w-0 truncate">
    {title}
  </span>

  <div
    class="flex items-center gap-2 px-2 py-2 flex-wrap sm:gap-3 sm:px-4"
    role="toolbar"
    aria-label={t('reader.toolbar')}
    tabindex={-1}
    data-testid="bottom-bar-controls"
    onkeydown={handleToolbarKeydown}
  >
    <button
      type="button"
      bind:this={prevButton}
      onclick={onPrev}
      disabled={prevDisabled}
      tabindex={rovingTabindex.prev}
      onfocus={() => (focusedControl = 'prev')}
      class={chromeIconButton('action')}
      aria-label={t('reader.prev_page')}
    >
      <ChevronLeft size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
    </button>
    <PageInput {currentPage} {totalPages} {t} {onGoToPage} />
    <button
      type="button"
      bind:this={nextButton}
      onclick={onNext}
      disabled={nextDisabled}
      tabindex={rovingTabindex.next}
      onfocus={() => (focusedControl = 'next')}
      class={chromeIconButton('action')}
      aria-label={t('reader.next_page')}
    >
      <ChevronRight size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
    </button>
    {#if isPdf}
      {#if debugState.enabled}
        <span
          class="text-micro text-(--pdf-reader-text,var(--color-text-auxiliary)) opacity-60 font-mono"
          >p{currentPage}/{totalPages} | {zoomPercent}%</span
        >
      {/if}
      <FitControls {t} {onFit} />
      <ZoomDropdown value={zoomPercent} onSelect={onZoomSelect} labelKind="zoom" {t} />
    {:else}
      <ZoomDropdown value={zoomPercent} onSelect={onZoomSelect} labelKind="fontSize" {t} />
    {/if}
  </div>

  <div class="flex items-center gap-3">
    {#if isPdf && totalPdfPages > 0}
      <span class="font-inter text-xs font-normal text-(--color-text-muted)"
        >{totalPdfPages - currentPdfPage} {t('pdf.pagesLeft')}</span
      >
      <div class="h-2 w-50 rounded-full bg-(--color-chrome-track)">
        <div
          class="h-full rounded-full bg-(--color-accent-sky) transition-all duration-300"
          style="width: {Math.round((currentPdfPage / totalPdfPages) * 100)}%"
        ></div>
      </div>
      <span class="font-inter text-xs font-normal text-(--color-text-muted)"
        >{Math.round((currentPdfPage / totalPdfPages) * 100)}%</span
      >
    {:else}
      <div class="h-2 w-50 rounded-full bg-(--color-chrome-track)">
        <div
          data-testid="footer-progress-bar"
          class="h-full rounded-full bg-(--color-accent-sky) transition-all duration-300"
          style="width: {bookProgress}%"
        ></div>
      </div>
      <span class="font-inter text-xs font-normal text-(--color-text-muted)">{bookProgress}%</span>
    {/if}
    <button
      type="button"
      onclick={onToggleBookmarks}
      class={chromeIconButton('toggle', showBookmarks)}
      aria-label={showBookmarks ? t('settings.close') : t('reader.bookmark')}
      title={showBookmarks ? t('settings.close') : t('reader.bookmark')}
    >
      <BookmarksIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
    </button>
    <span
      data-testid="bookmark-count"
      class="text-xs text-(--color-text-muted)"
      aria-busy={bookmarksLoading}
      aria-hidden="true"
    >
      {bookmarksLoading ? '—' : bookmarkCount}
    </span>
  </div>
</footer>
