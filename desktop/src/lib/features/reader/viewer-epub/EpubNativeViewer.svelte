<script lang="ts">
  import { onDestroy, untrack } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type {
    ReaderSettings,
    ReaderThemeMode,
    ReaderTextAlign,
    ReaderDirection,
  } from '$lib/shared/types';
  import EpubControls from './EpubControls.svelte';
  import { logger } from '$lib/shared/logger/Logger';
  import { debugState } from '$lib/shared/debug/debugState.svelte';
  import type { HighlightActionKind, HighlightActionOpts } from '$lib/shared/types/book';
  import { createEpubSpine } from '$lib/features/reader/viewer-epub/useEpubSpine.svelte';
  import { createEpubNavigation } from '$lib/features/reader/viewer-epub/useEpubNavigation.svelte';
  import { createEpubRender } from '$lib/features/reader/viewer-epub/useEpubRender.svelte';
  import { createEpubHighlights } from '$lib/features/reader/viewer-epub/useEpubHighlights.svelte';
  import { createEpubZoomTheme } from '$lib/features/reader/viewer-epub/useEpubZoomTheme.svelte';
  import { createEpubBridge } from '$lib/features/reader/viewer-epub/useEpubBridge.svelte';
  import { resolveFootnoteNote } from '$lib/features/reader/viewer-epub/epubViewerHelpers';

  interface EpubChapterMeta {
    index: number;
    id: string;
    label: string;
    href: string;
    depth?: number;
  }
  interface EpubMetadataExtract {
    title: string;
    author: string;
    language: string | null;
    publisher: string | null;
    toc: EpubChapterMeta[];
    spineHrefs: string[];
    chapters?: EpubChapterMeta[];
    spine_hrefs?: string[];
    totalChapters: number;
    total_chapters?: number;
    resourcesPath: string;
    resources_path?: string;
  }
  type Props = {
    filePath: string;
    bookId: string;
    initialLocation?: string;
    initialPercentage?: number;
    searchTargetLocator?: string | null;
    onLocationChange?: (cfiLocation: string, percentage: number) => void;
    onLocationContext?: (ctx: { locator: string; percentage: number }) => void;
    readerSettings?: ReaderSettings;
    onselection?: (event: {
      text: string;
      bounds: { left: number; top: number; right: number; bottom: number };
      container: { left: number; top: number; width: number; height: number };
      placement: string;
      rects: Array<{ left: number; top: number; width: number; height: number }>;
      pageNumber: number;
      cfi: string | null;
      // EPUB evidence forwarded by the bridge guard (REQ-DRE-005). Declared
      // structurally instead of importing `ViewerSelection`, because
      // `viewer-shared/Viewer.ts` already type-imports this component and the
      // duplicate declaration would create a type-only import cycle.
      quote?: string | null;
      chapterTitle?: string | null;
    }) => void;
    persistedHighlights?: Array<{
      id: string;
      color: string;
      pageNumber: number;
      cfi?: string | null;
      text?: string | null;
    }>;
    onHighlightAction?: (
      action: HighlightActionKind,
      id: string,
      opts?: HighlightActionOpts,
    ) => void;
    onselectionclear?: () => void;
    isFullscreen?: boolean;
    onIframeMouseMove?: (x: number, y: number) => void;
    onIframeMouseLeave?: () => void;
    onIframeClick?: (x: number, isLink: boolean) => void;
    cursorHidden?: boolean;
    hasSelectionMenu?: boolean;
    onTocReady?: (entries: Array<{ id: string; title: string; depth: number }>) => void;
    externalTocNavigate?: { id: string } | null;
    onSettingsChange?: (settings: ReaderSettings) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };
  let {
    filePath,
    bookId,
    initialLocation = '',
    initialPercentage = 0,
    searchTargetLocator = null,
    onLocationChange,
    onLocationContext,
    readerSettings = {
      themeMode: 'paper' as ReaderThemeMode,
      brightness: 100,
      contrast: 100,
      epub: { fontSize: 100, fontFamily: 'serif' },
      selectionColor: '#33bbff',
      lineHeight: 1.8,
      letterSpacing: 0,
      paragraphSpacing: 1,
      textAlign: 'left' as ReaderTextAlign,
      direction: 'ltr' as ReaderDirection,
      hyphenation: false,
      verticalScrolling: false,
      margins: { top: 2.5, bottom: 2.5, left: 2, right: 2 },
      showHeader: true,
      showFooter: true,
      showPageNumbers: true,
      progressIndicator: 'percentage',
    },
    onselection,
    onselectionclear,
    persistedHighlights = [],
    onHighlightAction,
    isFullscreen = false,
    onIframeMouseMove,
    onIframeMouseLeave,
    onIframeClick,
    cursorHidden = false,
    hasSelectionMenu = false,
    onTocReady,
    externalTocNavigate = null,
    onSettingsChange,
    t,
  }: Props = $props();

  let metadata = $state<EpubMetadataExtract | null>(null);
  let isLoading = $state(true);
  let error = $state<string | null>(null);
  let iframeEl = $state<HTMLIFrameElement | null>(null);
  let totalChapters = $state(0);
  let zoomContainerEl = $state<HTMLDivElement | null>(null);
  let iframeContentHeight = $state(0);
  let lastRenderedChapter = $state(-1);
  let lastContinueLocation = $state<string | null>(null);
  // U5: footnote popover. Viewport-space anchor + parent scroll to restore.
  let footnote = $state<{ x: number; y: number; text: string; returnScroll: number } | null>(null);

  const spine = createEpubSpine({ getMetadata: () => metadata });
  const navigation = createEpubNavigation({
    getToc: () => spine.getToc(),
    getSpineHrefs: () => spine.getSpineHrefs(),
    getTotalChapters: () => totalChapters,
    spineIndexForToc: (i: number) => spine.spineIndexForToc(i),
    tocIndexForSpine: (i: number, href?: string) => spine.tocIndexForSpine(i, href),
  });

  let fontSize = $derived(readerSettings.epub.fontSize ?? 100);
  // `''` means the book's own face — never coerce it to 'serif', or the
  // "Predeterminada" option stops being able to restore the publisher font.
  let fontFamily = $derived(readerSettings.epub.fontFamily?.trim() ?? '');
  let themeMode: ReaderThemeMode = $derived(readerSettings.themeMode);
  let lineHeight = $derived(readerSettings.lineHeight);
  let letterSpacing = $derived(readerSettings.letterSpacing);
  let paragraphSpacing = $derived(readerSettings.paragraphSpacing);
  let textAlign: ReaderTextAlign = $derived(readerSettings.textAlign);
  let direction: ReaderDirection = $derived(readerSettings.direction);
  let hyphenation = $derived(readerSettings.hyphenation);
  let margins = $derived(readerSettings.margins);

  let currentSpineIndex = $derived(navigation.currentSpineIndex);
  let displayTotal = $derived.by(() => {
    const t = spine.getToc().length;
    return t > 0 ? t : totalChapters;
  });
  let displayCurrentPage = $derived.by(() => {
    const t = spine.getToc().length;
    return t > 0 ? navigation.currentChapterIndex + 1 : navigation.currentSpineIndex + 1;
  });
  let displayPercentage = $derived.by(() =>
    displayTotal <= 0 ? 0 : ((displayCurrentPage - 0.5) / displayTotal) * 100,
  );

  function getToc(): EpubChapterMeta[] {
    return spine.getToc();
  }
  function getSpineHrefs(): string[] {
    return spine.getSpineHrefs();
  }
  void getToc;
  void getSpineHrefs;

  const highlights = createEpubHighlights({
    getMetadata: () => metadata,
    getIframeEl: () => iframeEl,
    getSpineHrefs: () => spine.getSpineHrefs(),
    getToc: () => spine.getToc(),
    getCurrentChapterIndex: () => navigation.currentChapterIndex,
    getCurrentSpineIndex: () => currentSpineIndex,
    getPersistedHighlights: () => persistedHighlights,
    setPersistedHighlights: (v) => (persistedHighlights = v),
    getIsLoading: () => isLoading,
    getLastRenderedChapter: () => lastRenderedChapter,
    onHighlightAction: (action, id, opts) => onHighlightAction?.(action, id, opts),
  });

  const zoomTheme = createEpubZoomTheme({
    getReaderSettings: () => readerSettings,
    onSettingsChange: (s) => onSettingsChange?.(s),
    getFontSize: () => fontSize,
    setFontSize: (v) => {
      const updated: ReaderSettings = {
        ...readerSettings,
        epub: { ...readerSettings.epub, fontSize: v },
      };
      onSettingsChange?.(updated);
    },
    getThemeMode: () => themeMode,
  });

  const render = createEpubRender({
    getIframeEl: () => iframeEl,
    getZoomContainerEl: () => zoomContainerEl,
    getMetadata: () => metadata,
    getSpineHrefs: () => spine.getSpineHrefs(),
    getToc: () => spine.getToc(),
    spineIndexForToc: (i: number) => spine.spineIndexForToc(i),
    getBookId: () => bookId,
    getResourcesPath: () => metadata?.resourcesPath ?? '',
    getZoomLevel: () => zoomTheme.zoomLevel,
    getFontSize: () => fontSize,
    getFontFamily: () => fontFamily,
    getThemeMode: () => themeMode,
    getLineHeight: () => lineHeight,
    getLetterSpacing: () => letterSpacing,
    getParagraphSpacing: () => paragraphSpacing,
    getTextAlign: () => textAlign,
    getDirection: () => direction,
    getHyphenation: () => hyphenation,
    getMargins: () => margins,
    getCurrentChapterIndex: () => navigation.currentChapterIndex,
    setCurrentChapterIndex: (v: number) => (navigation.currentChapterIndex = v),
    getCurrentSpineIndex: () => currentSpineIndex,
    getPendingFragment: () => navigation.pendingFragment,
    setPendingFragment: (v) => (navigation.pendingFragment = v),
    getPendingCfiScroll: () => navigation.pendingCfiScroll,
    setPendingCfiScroll: (v) => (navigation.pendingCfiScroll = v),
    getLastRenderedChapter: () => lastRenderedChapter,
    setLastRenderedChapter: (v) => (lastRenderedChapter = v),
    getTotalChapters: () => totalChapters,
    getIframeContentHeight: () => iframeContentHeight,
    setIframeContentHeight: (v) => (iframeContentHeight = v),
    scrollToCfi: (cfi: string) => bridge.scrollToCfi(cfi),
    scrollToFragment: (f: string | null) => bridge.scrollToFragment(f),
    emitPreciseLocation: () => bridge.emitPreciseLocation(),
    setError: (msg: string) => (error = msg),
  });

  const bridge = createEpubBridge({
    getMetadata: () => metadata,
    setMetadata: (v) => (metadata = v),
    getIsLoading: () => isLoading,
    setIsLoading: (v) => (isLoading = v),
    getError: () => error,
    setError: (v) => (error = v),
    getIframeEl: () => iframeEl,
    getZoomContainerEl: () => zoomContainerEl,
    getSpineHrefs: () => spine.getSpineHrefs(),
    getToc: () => spine.getToc(),
    getBookId: () => bookId,
    getFilePath: () => filePath,
    getInitialLocation: () => initialLocation,
    getInitialPercentage: () => initialPercentage,
    getSearchTargetLocator: () => searchTargetLocator,
    getExternalTocNavigate: () => externalTocNavigate,
    getTotalChapters: () => totalChapters,
    setTotalChapters: (v) => (totalChapters = v),
    getCurrentChapterIndex: () => navigation.currentChapterIndex,
    setCurrentChapterIndex: (v) => (navigation.currentChapterIndex = v),
    getCurrentSpineIndex: () => currentSpineIndex,
    getPendingFragment: () => navigation.pendingFragment,
    setPendingFragment: (v) => (navigation.pendingFragment = v),
    getPendingCfiScroll: () => navigation.pendingCfiScroll,
    setPendingCfiScroll: (v) => (navigation.pendingCfiScroll = v),
    getLastRenderedChapter: () => lastRenderedChapter,
    setLastRenderedChapter: (v) => (lastRenderedChapter = v),
    getLastContinueLocation: () => lastContinueLocation,
    setLastContinueLocation: (v) => (lastContinueLocation = v),
    getOnTocReady: () => onTocReady,
    onLocationChange: (cfi, pct) => onLocationChange?.(cfi, pct),
    onLocationContext: (ctx) => onLocationContext?.(ctx),
    onselection: (event) => onselection?.(event),
    onselectionclear: () => onselectionclear?.(),
    handleEpubHighlightClick: (m) => highlights.handleEpubHighlightClick(m),
    handleEpubHighlightFailed: (m) => highlights.handleEpubHighlightFailed(m),
    handleEpubHighlightPlaced: (m) => highlights.handleEpubHighlightPlaced(m),
    syncIframeHeight: () => render.syncIframeHeight(),
    handleExternalTocNavigate: (id) => navigation.handleExternalTocNavigate(id),
    handleSearchTargetLocator: (t, o) => navigation.handleSearchTargetLocator(t, o),
  });

  function emitPreciseLocation(): void {
    bridge.emitPreciseLocation();
  }

  function handleContainerScroll(): void {
    emitPreciseLocation();
    // U2: scrolling invalidates the selection anchor bounds the menu is
    // pinned to — the menu must not float over the new viewport.
    onselectionclear?.();
  }
  function refreshReaderStyles(): void {
    render.refreshReaderStyles();
  }
  async function renderChapter(index: number): Promise<void> {
    return render.renderChapter(index);
  }

  export function goToPrev(): void {
    navigation.goToPrev();
  }
  export function goToNext(): void {
    navigation.goToNext();
  }
  export function goToChapter(index: number): void {
    navigation.goToChapter(index);
  }
  export async function handleGoToPage(page: number): Promise<boolean> {
    return navigation.handleGoToPage(page);
  }
  export function setZoom(percent: number): void {
    zoomTheme.setZoom(percent);
  }
  export function getZoomPercent(): number {
    return fontSize;
  }
  export function getCurrentPage(): number {
    return displayCurrentPage;
  }
  export function getTotalForHeader(): number {
    return displayTotal;
  }
  export function getTotalPages(): number {
    return displayTotal;
  }
  /**
   * U3: collapse the chapter selection from the parent. Clicks inside the
   * iframe document never reach the parent, so parent-chrome clicks would
   * otherwise leave the menu stranded with no event to clear it.
   */
  export function clearIframeSelection(): void {
    try {
      iframeEl?.contentDocument?.getSelection()?.removeAllRanges();
    } catch {
      // Torn-down or unreadable frame: the parent dismiss covers it.
    }
  }
  function getThemeBgColor(): string {
    return zoomTheme.getThemeBgColor();
  }

  $effect(() => {
    debugState.epub.currentChapterIndex = navigation.currentChapterIndex;
    (debugState.epub as unknown as Record<string, unknown>).currentSpineIndex = currentSpineIndex;
  });
  onDestroy(() => {
    logger.debug(
      'epub-hl: onDestroy',
      {
        bookId: bookId.slice(0, 8),
        chapter: untrack(() => navigation.currentChapterIndex),
        lastRendered: untrack(() => lastRenderedChapter),
      },
      'reader',
    );
    zoomTheme.cleanup();
  });

  $effect(() => {
    if (!metadata || isLoading || !iframeEl) return;
    void fontSize;
    void fontFamily;
    void themeMode;
    void lineHeight;
    void letterSpacing;
    void paragraphSpacing;
    void textAlign;
    void direction;
    void hyphenation;
    void margins.top;
    void margins.right;
    void margins.bottom;
    void margins.left;
    void zoomTheme.zoomLevel;
    if (lastRenderedChapter !== navigation.currentChapterIndex) {
      if (
        render.getCurrentRenderIndex() === navigation.currentChapterIndex &&
        render.getEpoch() > 0
      )
        return;
      footnote = null;
      renderChapter(navigation.currentChapterIndex);
      return;
    }
    refreshReaderStyles();
  });

  function handleKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape' && footnote) {
      // First Esc closes the note; the immersive chrome exits on the next one.
      e.stopImmediatePropagation();
      closeFootnote();
      return;
    }
    // U4 (selection-menu): one Esc, one layer. Selection menu before the
    // immersive chrome, which exits on the next Esc via its own handler.
    if (e.key === 'Escape' && hasSelectionMenu) {
      e.stopImmediatePropagation();
      onselectionclear?.();
      return;
    }
    if (e.key === 'ArrowLeft') goToPrev();
    if (e.key === 'ArrowRight') goToNext();
  }

  // U1 (fullscreen): the chapter iframe swallows mouse events, so the
  // workspace-level mousemove never fires over book text. Forward
  // viewport-space coords. U5 (reading-quality): footnote `#` links open
  // inline instead of jumping chapters. One effect, one click pipeline:
  // highlights win, then footnotes, then tercile paging.
  function closeFootnote(restoreScroll = true): void {
    const returnScroll = footnote?.returnScroll;
    footnote = null;
    if (restoreScroll && returnScroll !== undefined && zoomContainerEl) {
      zoomContainerEl.scrollTop = returnScroll;
    }
  }

  $effect(() => {
    const frame = iframeEl;
    // Track chapter + loading so listeners re-attach after each srcdoc swap.
    void lastRenderedChapter;
    void isLoading;
    if (!frame) return;
    const onMove = (e: MouseEvent): void => {
      if (!onIframeMouseMove) return;
      const rect = frame.getBoundingClientRect();
      onIframeMouseMove(rect.left + e.clientX, rect.top + e.clientY);
    };
    const onLeave = (): void => {
      onIframeMouseLeave?.();
    };
    const onClick = (e: MouseEvent): void => {
      const target = e.target as Element | null;
      const win = frame.contentWindow as
        | (Window & { __epubHighlightOverlay?: { hitTest?: (x: number, y: number) => unknown } })
        | null;
      try {
        if (win?.__epubHighlightOverlay?.hitTest?.(e.clientX, e.clientY)) return;
      } catch {
        return;
      }
      const anchor = target?.closest?.('a[href^="#"]') as HTMLAnchorElement | null;
      if (anchor && frame.contentDocument) {
        const note = resolveFootnoteNote(anchor.getAttribute('href'), frame.contentDocument);
        if (note) {
          e.preventDefault();
          const rect = frame.getBoundingClientRect();
          footnote = {
            x: rect.left + e.clientX,
            y: rect.top + e.clientY,
            text: note.text,
            returnScroll: zoomContainerEl?.scrollTop ?? 0,
          };
          return;
        }
      }
      if (!onIframeClick) return;
      const isLink = !!target?.closest?.('a');
      const rect = frame.getBoundingClientRect();
      onIframeClick(rect.left + e.clientX, isLink);
    };
    const attach = (): void => {
      const doc = frame.contentDocument;
      if (!doc) return;
      doc.addEventListener('mousemove', onMove as EventListener);
      doc.addEventListener('mouseleave', onLeave);
      doc.addEventListener('click', onClick as EventListener);
    };
    const detach = (): void => {
      const doc = frame.contentDocument;
      if (!doc) return;
      doc.removeEventListener('mousemove', onMove as EventListener);
      doc.removeEventListener('mouseleave', onLeave);
      doc.removeEventListener('click', onClick as EventListener);
    };
    frame.addEventListener('load', attach);
    attach();
    return () => {
      detach();
      frame.removeEventListener('load', attach);
    };
  });

  // U3 trick (iframe half): parent CSS never crosses the document boundary,
  // so mirror cursorHidden onto the frame + its body. Interactive chrome lives
  // outside the iframe and keeps cursor:pointer/auto.
  $effect(() => {
    const frame = iframeEl;
    void lastRenderedChapter;
    if (!frame) return;
    frame.style.cursor = cursorHidden ? 'none' : '';
    const body = frame.contentDocument?.body;
    if (body) body.style.cursor = cursorHidden ? 'none' : '';
  });
</script>

<svelte:window onkeydown={handleKeydown} />

<div
  class="flex flex-col h-full w-full min-h-0 outline-none relative"
  tabindex="-1"
  role="presentation"
  class:w-full={isFullscreen}
  class:h-full={isFullscreen}
>
  {#if isLoading}
    <div class="flex flex-col items-center justify-center h-full gap-4">
      <div class="h-1 w-full max-w-48 bg-(--color-border)/20 overflow-hidden rounded-full">
        <div class="h-full w-1/3 bg-(--color-accent) animate-pulse rounded-full"></div>
      </div>
      <span class="text-sm opacity-60">{t('epub.loading')}</span>
    </div>
  {:else if error}
    <div class="flex items-center justify-center h-full text-sm text-red-600 px-4 text-center">
      {t('epub.error')}: {error}
    </div>
  {:else}
    {#if !isFullscreen}
      <EpubControls
        currentPage={displayCurrentPage}
        totalPages={displayTotal}
        currentPercentage={displayPercentage}
        {fontSize}
        {t}
        onPrev={goToPrev}
        onNext={goToNext}
        onGoToPage={handleGoToPage}
        onFontSizeChange={(size: number) => {
          const updated: ReaderSettings = {
            ...readerSettings,
            epub: { ...readerSettings.epub, fontSize: size },
          };
          onSettingsChange?.(updated);
        }}
      />
    {/if}
    <div
      data-testid="epub-scroll-container"
      class="flex-1 w-full min-h-0 overflow-y-auto pb-16"
      style="background: {getThemeBgColor()};"
      bind:this={zoomContainerEl}
      onscroll={handleContainerScroll}
    >
      <iframe
        bind:this={iframeEl}
        class="w-full border-none block"
        class:outline-2={debugState.enabled}
        class:outline-dashed={debugState.enabled}
        class:outline-red-500={debugState.enabled}
        style:height={iframeContentHeight > 0 ? `${iframeContentHeight}px` : 'auto'}
        title="chapter"
      ></iframe>
    </div>
    {#if footnote}
      {@const vw = typeof window !== 'undefined' ? window.innerWidth : 1024}
      {@const vh = typeof window !== 'undefined' ? window.innerHeight : 768}
      <div
        role="dialog"
        aria-label={t('reader.footnoteBack')}
        class="fixed z-50 w-80 max-w-[calc(100vw-2rem)] rounded-xl border border-(--color-border) bg-(--color-surface) p-4 shadow-xl"
        style="left: {Math.max(16, Math.min(footnote.x, vw - 336))}px; top: {Math.max(
          16,
          Math.min(footnote.y + 12, vh - 220),
        )}px;"
      >
        <p class="text-sm leading-relaxed text-(--color-text)">{footnote.text}</p>
        <button
          type="button"
          class="mt-3 cursor-pointer rounded-full bg-(--color-accent-blue) px-3 py-1.5 text-xs font-medium text-(--color-accent-on) hover:opacity-90"
          onclick={() => closeFootnote()}
        >
          {t('reader.footnoteBack')}
        </button>
      </div>
    {/if}
  {/if}
</div>
