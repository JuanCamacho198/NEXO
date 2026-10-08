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
      margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
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

  const spine = createEpubSpine({ getMetadata: () => metadata });
  const navigation = createEpubNavigation({
    getToc: () => spine.getToc(),
    getSpineHrefs: () => spine.getSpineHrefs(),
    getTotalChapters: () => totalChapters,
    spineIndexForToc: (i: number) => spine.spineIndexForToc(i),
    tocIndexForSpine: (i: number, href?: string) => spine.tocIndexForSpine(i, href),
  });

  let fontSize = $derived(readerSettings.epub.fontSize ?? 100);
  let fontFamily = $derived(readerSettings.epub.fontFamily?.trim() || 'serif');
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
  function getThemeBgColor(): string {
    return zoomTheme.getThemeBgColor();
  }

  $effect(() => {
    debugState.epub.currentChapterIndex = navigation.currentChapterIndex;
    (debugState.epub as unknown as Record<string, unknown>).currentSpineIndex = currentSpineIndex;
  });
  onDestroy(() => {
    logger.warn(
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
      renderChapter(navigation.currentChapterIndex);
      return;
    }
    refreshReaderStyles();
  });

  function handleKeydown(e: KeyboardEvent): void {
    if (e.key === 'ArrowLeft') goToPrev();
    if (e.key === 'ArrowRight') goToNext();
  }
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
      class="flex-1 w-full min-h-0 overflow-y-auto pb-16"
      style="background: {getThemeBgColor()};"
      bind:this={zoomContainerEl}
      onscroll={emitPreciseLocation}
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
  {/if}
</div>
