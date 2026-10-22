<script lang="ts">
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import ChevronRight from 'lucide-svelte/icons/chevron-right';
  import Bookmark from 'lucide-svelte/icons/bookmark';
  import X from 'lucide-svelte/icons/x';
  import FitControls from './FitControls.svelte';
  import PageInput from './PageInput.svelte';
  import ZoomDropdown from './ZoomDropdown.svelte';
  import StatusChip from '$lib/shared/ui/forms/StatusChip.svelte';
  import type { FitMode } from './fitZoom';
  import { chromeIconButton } from './chromeControl';
  import type { MessageKey } from '$lib/shared/i18n';
  import { debugState } from '$lib/shared/debug/debugState.svelte';

  type Props = {
    title: string;
    /** Current chapter title when known; the bar falls back to `title` otherwise. */
    chapterTitle?: string | null;
    bookProgress: number;
    currentPdfPage: number;
    totalPdfPages: number;
    viewerKind: 'pdf' | 'epub' | null;
    isFullscreen: boolean;
    visible: boolean;
    currentPage: number;
    totalPages: number;
    /**
     * `N / M` across the whole book, or null when the switch is off or nothing
     * measured yet. Null keeps the percentage: the footer never prints "/ 0".
     */
    bookPages?: { page: number; total: number } | null;
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
    chapterTitle = null,
    bookProgress,
    currentPdfPage,
    totalPdfPages,
    viewerKind,
    isFullscreen,
    visible,
    currentPage,
    totalPages,
    bookPages = null,
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
  // The bar names where the reader is, not what they already know: the chapter
  // when known, the book title otherwise. Never empty.
  const locationLabel = $derived(chapterTitle ?? title);
  // Zero bookmarks is the default state, so a bare count there is noise. The
  // count only appears once the book is known to have any.
  const hasBookmarkCount = $derived(!bookmarksLoading && bookmarkCount > 0);
  // The number is the chip's compact text; the labelled form goes to the
  // bookmarks control's accessible name so screen readers hear a described
  // count rather than a floating digit.
  const bookmarkCountText = $derived(String(bookmarkCount));
  const bookmarkCountLabel = $derived(t('reader.bookmarks_count', { count: bookmarkCount }));
  const prevDisabled = $derived(currentPage <= 1);
  const nextDisabled = $derived(currentPage >= totalPages);
  // The progress readout: `N / M` when the reader asked for book pages and a
  // count exists, the percentage otherwise.
  //
  // This label is the ONE place either number is rendered. The `%` text lives in
  // this sibling div of `bottom-bar-controls`, next to the zoom chip — a selector
  // that grabs "the label in the bar" reaches `150%` (the zoom) instead, which is
  // the trap this label is kept in a single, addressable span to avoid.
  //
  // A null `bookPages` is "nothing measured yet" or "switch off"; both mean the
  // percentage is the honest answer, including the case where a total of 0 would
  // otherwise print as "/ 0".
  const progressLabel = $derived.by((): string => {
    const position = bookPages;
    if (position && position.page > 0 && position.total > 0) {
      return `${position.page} / ${position.total}`;
    }
    return `${bookProgress}%`;
  });
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
  class="grid h-12 shrink-0 grid-cols-[1fr_auto_1fr] items-center gap-2 border-t border-(--color-border) bg-(--color-reader-chrome) px-3 transition-transform duration-300 sm:px-4 {isFullscreen
    ? 'fixed inset-x-0 bottom-0 z-50'
    : ''} {barTransform}"
  class:shadow-md={isFullscreen}
>
  <span
    class="justify-self-start font-inter text-xs font-normal text-(--color-text-muted) min-w-0 truncate"
  >
    {locationLabel}
  </span>

  <div
    class="justify-self-center flex items-center gap-2 px-2 py-2 flex-wrap sm:gap-3 sm:px-4"
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

  <div class="justify-self-end flex items-center gap-3">
    {#if isPdf && totalPdfPages > 0}
      <!-- The pages-left label and the bar+% read as one reading-progress
        cluster; the bar is the unit and its numbers sit immediately with it.
        The label keeps its own shrink-0 so a narrow column never squeezes it
        out of the row, which is what used to flatten it against the track. -->
      <span class="shrink-0 font-inter text-xs font-normal text-(--color-text-muted)">
        {totalPdfPages - currentPdfPage}
        {t('pdf.pagesLeft')}
      </span>
      <!-- Progress read as a single unit: the track and its percentage hug at
        gap-1.5 with tabular figures, so the eye reads one quantity and the
        number does not jitter as the percentage changes. -->
      <div class="flex items-center gap-1.5">
        <div class="h-2 w-50 rounded-full bg-(--color-chrome-track)">
          <div
            class="h-full rounded-full bg-(--color-accent-sky) transition-all duration-300"
            style="width: {Math.round((currentPdfPage / totalPdfPages) * 100)}%"
          ></div>
        </div>
        <span class="font-inter text-xs font-normal tabular-nums text-(--color-text-muted)">
          {Math.round((currentPdfPage / totalPdfPages) * 100)}%
        </span>
      </div>
    {:else}
      <div class="flex items-center gap-1.5">
        <div class="h-2 w-50 rounded-full bg-(--color-chrome-track)">
          <div
            data-testid="footer-progress-bar"
            class="h-full rounded-full bg-(--color-accent-sky) transition-all duration-300"
            style="width: {bookProgress}%"
          ></div>
        </div>
        <!-- One span, one source: the book page numbers when the reader asked
          for them and a count exists, the percentage otherwise. The testid is
          the stable hook for both — the label's TEXT is what differs. -->
        <span
          data-testid="footer-progress-label"
          class="font-inter text-xs font-normal tabular-nums text-(--color-text-muted)"
        >
          {progressLabel}
        </span>
      </div>
    {/if}
    <button
      type="button"
      onclick={onToggleBookmarks}
      class={chromeIconButton('toggle', showBookmarks)}
      aria-label={showBookmarks
        ? t('settings.close')
        : hasBookmarkCount
          ? `${t('reader.bookmark')} · ${bookmarkCountLabel}`
          : t('reader.bookmark')}
      title={showBookmarks
        ? t('settings.close')
        : hasBookmarkCount
          ? `${t('reader.bookmark')} · ${bookmarkCountLabel}`
          : t('reader.bookmark')}
    >
      <BookmarksIcon size={16} strokeWidth={2} class="h-4 w-4" aria-hidden="true" />
    </button>
    {#if hasBookmarkCount}
      <!-- The count is real information, not decoration: it renders as a chip
        attached to the bookmark control, and the button's accessible name
        above folds the same count in so assistive tech gets both the control
        and its tally from one stop. A plain wrapper keeps `bookmark-count`
        addressable as a stable hook for tests that predate the chip. -->
      <span data-testid="bookmark-count" aria-busy={bookmarksLoading}>
        <StatusChip text={bookmarkCountText} tone="accent" />
      </span>
    {:else}
      <!-- Zero is the default state, so nothing renders; the wrapper stays so
        loading is observable (aria-busy) and never ships a false zero. -->
      <span data-testid="bookmark-count" aria-busy={bookmarksLoading}></span>
    {/if}
  </div>
</footer>
