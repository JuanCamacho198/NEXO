<script lang="ts">
  import * as pdfjsLib from 'pdfjs-dist';
  import 'pdfjs-dist/web/pdf_viewer.css';
  import { onMount } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { PdfOutlineItem, ReaderSettings } from '$lib/shared/types';
  import {
    DEFAULT_PDF_SCALE,
    isPageWithinBounds,
  } from '$lib/features/reader/viewer-pdf/pdfNavigation';
  import { resolveReaderArrowIntent } from '$lib/features/reader/viewer-epub/keyboardNav';
  import {
    VERTICAL_SCROLL_STEP_PX,
    ZOOM_EPSILON,
    clamp,
  } from '$lib/features/reader/viewer-pdf/pdfState.svelte';
  import {
    readProgressPercent,
    parseLocatorPage,
    captureScrollAnchor,
    restoreScrollAnchor,
    canScrollElementInDirection,
  } from '$lib/features/reader/viewer-pdf/pdfSelection';
  import { createPdfDocumentState } from '$lib/features/reader/viewer-pdf/usePdfDocument.svelte';
  import { createPdfRenderState } from '$lib/features/reader/viewer-pdf/usePdfRender.svelte';
  import { createPdfSelectionState } from '$lib/features/reader/viewer-pdf/usePdfSelection.svelte';
  import { createPdfOutlineState } from '$lib/features/reader/viewer-pdf/usePdfOutline.svelte';
  import { createPdfZoomThemeState } from '$lib/features/reader/viewer-pdf/usePdfZoomTheme.svelte';
  import { resolveFitScalePercent, type FitMode } from '$lib/features/reader/chrome/fitZoom';
  import PdfControls from './PdfControls.svelte';
  import { logger } from '$lib/shared/logger/Logger';
  import PdfSelectionOverlay from './PdfSelectionOverlay.svelte';
  import PdfLoadingOverlay from './PdfLoadingOverlay.svelte';
  import type { TocEntry } from '../chrome/ReaderTocPanel.svelte';
  import { debugState } from '$lib/shared/debug/debugState.svelte';
  import { setReaderError, clearReaderError } from '$lib/stores/readerErrorState.svelte';

  type PersistedHighlight = {
    id: string;
    color: string;
    pageNumber: number;
    rects: Array<{ left: number; top: number; width: number; height: number }>;
    text?: string;
  };
  type Props = {
    filePath: string;
    bookId?: string;
    initialPage?: number;
    onPageChange?: (page: number, total: number) => void;
    searchTargetLocator?: string | null;
    onSessionProgress?: (event: {
      startedAt: string;
      endedAt?: string;
      durationSeconds: number;
      startPercentage?: number;
      endPercentage?: number;
    }) => void;
    readerSettings?: ReaderSettings;
    isFullscreen?: boolean;
    tocOpen?: boolean;
    onselection?: (event: {
      text: string;
      bounds: { left: number; top: number; right: number; bottom: number };
      container: { left: number; top: number; width: number; height: number };
      placement: string;
      rects: Array<{ left: number; top: number; width: number; height: number }>;
      pageNumber: number;
      cfi?: string | null;
    }) => void;
    onselectionclear?: () => void;
    onHighlightAction?: (
      action: import('$lib/shared/types/book').HighlightActionKind,
      id: string,
      opts?: import('$lib/shared/types/book').HighlightActionOpts,
    ) => void;
    onTocReady?: (entries: TocEntry[]) => void;
    externalTocNavigate?: TocEntry | null;
    persistedHighlights?: PersistedHighlight[];
    preloadedBytes?: Uint8Array | null;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };
  const DEFAULT_READER_SETTINGS: ReaderSettings = {
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    epub: { fontSize: 100, fontFamily: 'serif' },
    selectionColor: 'var(--color-accent-blue)',
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    verticalScrolling: false,
    margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
    showHeader: true,
    showFooter: true,
    showPageNumbers: true,
    progressIndicator: 'percentage',
  };
  let {
    filePath,
    initialPage = 1,
    onPageChange,
    searchTargetLocator = null,
    onSessionProgress,
    readerSettings = DEFAULT_READER_SETTINGS,
    isFullscreen = false,
    tocOpen = false,
    onselection,
    onselectionclear,
    onHighlightAction,
    onTocReady,
    externalTocNavigate = null,
    persistedHighlights = [],
    preloadedBytes = null,
    t,
  }: Props = $props();

  let canvas: HTMLCanvasElement | undefined = $state();
  let textLayer: HTMLDivElement | undefined = $state();
  let viewerRoot: HTMLDivElement | undefined = $state();
  let canvasContainer: HTMLDivElement | undefined = $state();
  let currentPage = $state(1);
  let flashSearchResult = $state(false);
  let sessionStartAt = new Date().toISOString();
  let lastPercent = 0;
  let scale = $state(DEFAULT_PDF_SCALE);
  let navigationError = $state<string | null>(null);
  let isViewerFocused = $state(false);

  const docState = createPdfDocumentState({
    getFilePath: () => filePath,
    getPreloadedBytes: () => preloadedBytes,
  });
  // svelte-ignore state_referenced_locally
  const outlineState = createPdfOutlineState({
    getPdfDoc: () => docState.pdfDoc,
    getFilePath: () => filePath,
    getTotalPages: () => docState.totalPages,
    t: t as unknown as (k: string) => string,
  });
  const renderState = createPdfRenderState({
    getPdfDoc: () => docState.pdfDoc,
    getScale: () => scale,
    getCanvas: () => canvas,
    getTextLayer: () => textLayer,
    getCanvasContainer: () => canvasContainer,
    getCurrentPage: () => currentPage,
    getTotalPages: () => docState.totalPages,
    getShowToc: () => tocOpen,
    getIsFullscreen: () => isFullscreen,
  });
  // svelte-ignore state_referenced_locally
  const selectionState = createPdfSelectionState({
    getTextLayer: () => textLayer,
    getScale: () => scale,
    getCurrentPage: () => currentPage,
    onselection,
    onselectionclear,
    onHighlightAction,
  });
  const zoomState = createPdfZoomThemeState({
    getReaderSettings: () => readerSettings,
  });

  const isStaleNavigation = (id: number): boolean => renderState.isStaleNavigation(id);
  const isStaleLoad = (id: number): boolean => docState.isStaleLoad(id);
  void isStaleLoad;
  void isStaleNavigation;

  const readerThemePalette = $derived(zoomState.readerThemePalette);
  const visualFilterStyle = $derived(zoomState.visualFilterStyle);
  const flatOutline = $derived(outlineState.flatOutline);

  $effect(() => {
    if (textLayer) textLayer.style.letterSpacing = `${readerSettings.letterSpacing}px`;
  });
  $effect(() => {
    if (flatOutline.length > 0)
      onTocReady?.(
        flatOutline.map((e) => ({ id: e.item.id, title: e.item.title, depth: e.depth })),
      );
  });
  $effect(() => {
    if (externalTocNavigate?.id) {
      const entry = flatOutline.find((e) => e.item.id === externalTocNavigate.id);
      if (entry) void navigateToOutlineItem(entry.item);
    }
  });

  const emitSessionProgress = (nextPage: number, nextTotal: number): void => {
    const now = new Date();
    const nextPercent = readProgressPercent(nextPage, nextTotal);
    const startedAt = sessionStartAt;
    const endedAt = now.toISOString();
    onSessionProgress?.({
      startedAt,
      endedAt,
      durationSeconds: Math.max(
        0,
        Math.round((now.getTime() - new Date(startedAt).getTime()) / 1000),
      ),
      startPercentage: lastPercent,
      endPercentage: nextPercent,
    });
    sessionStartAt = endedAt;
    lastPercent = nextPercent;
  };
  async function navigateToOutlineItem(item: PdfOutlineItem): Promise<void> {
    if (!item.dest) return;
    navigationError = null;
    const page = await outlineState.resolveDestinationPage(item.dest);
    if (!page) {
      navigationError = t('pdf.tocNavigationFailed');
      return;
    }
    const ok = await navigateToPage(page);
    if (!ok) navigationError = t('pdf.tocNavigationFailed');
  }

  async function loadPdf(): Promise<void> {
    const navRequestId = renderState.nextNavigationRequestId();
    renderState.cancelTextLayer();
    await renderState.cancelActiveRenderTask();
    outlineState.clearOutlineCache();
    navigationError = null;
    scale = DEFAULT_PDF_SCALE;
    const result = await docState.loadPdf();
    if (result.error) {
      setReaderError(result.error);
      return;
    }
    if (!result.pdfDoc || docState.isStaleLoad(result.loadRequestId)) return;
    const targetPage = Math.min(Math.max(1, initialPage || 1), result.totalPages);
    const rendered = await renderState.renderPage(targetPage, {
      requestId: navRequestId,
      renderScale: scale,
    });
    if (
      !rendered ||
      renderState.isStaleNavigation(navRequestId) ||
      docState.isStaleLoad(result.loadRequestId)
    )
      return;
    currentPage = targetPage;
    onPageChange?.(targetPage, result.totalPages);
    lastPercent = readProgressPercent(targetPage, result.totalPages);
    sessionStartAt = new Date().toISOString();
    clearReaderError();
  }

  export const navigateToPage = async (
    targetPage: number,
    options?: { flash?: boolean },
  ): Promise<boolean> => {
    if (!docState.pdfDoc || !isPageWithinBounds(targetPage, docState.totalPages)) return false;
    selectionState.hideToolbar();
    navigationError = null;
    const navRequestId = renderState.bumpNavigationId();
    renderState.cancelTextLayer();
    if (textLayer) textLayer.innerHTML = '';
    try {
      const rendered = await renderState.renderPage(targetPage, {
        requestId: navRequestId,
        renderScale: scale,
      });
      if (!rendered || renderState.isStaleNavigation(navRequestId)) {
        navigationError = t('pdf.navigationFailed');
        setReaderError(navigationError);
        return false;
      }
      currentPage = targetPage;
      onPageChange?.(currentPage, docState.totalPages);
      emitSessionProgress(currentPage, docState.totalPages);
      if (options?.flash) {
        flashSearchResult = true;
        window.setTimeout(() => (flashSearchResult = false), 900);
      }
      return true;
    } catch {
      navigationError = t('pdf.navigationFailed');
      setReaderError(navigationError);
      return false;
    }
  };
  function goToPrevPage(): void {
    void navigateToPage(currentPage - 1);
  }
  function goToNextPage(): void {
    void navigateToPage(currentPage + 1);
  }
  function handleViewerKeydown(event: KeyboardEvent): void {
    if (!isViewerFocused) return;
    const intent = resolveReaderArrowIntent(event);
    if (!intent) return;
    if (intent === 'prevPage') {
      event.preventDefault();
      goToPrevPage();
      return;
    }
    if (intent === 'nextPage') {
      event.preventDefault();
      goToNextPage();
      return;
    }
    if (intent === 'scrollUp') {
      event.preventDefault();
      scrollByVerticalStep(-VERTICAL_SCROLL_STEP_PX);
      return;
    }
    if (intent === 'scrollDown') {
      event.preventDefault();
      scrollByVerticalStep(VERTICAL_SCROLL_STEP_PX);
    }
  }
  function scrollByVerticalStep(delta: number): void {
    const p = canvasContainer;
    if (p && canScrollElementInDirection(p, delta)) {
      p.scrollBy({ top: delta, behavior: 'auto' });
      return;
    }
    const f = viewerRoot;
    if (f && canScrollElementInDirection(f, delta)) {
      f.scrollBy({ top: delta, behavior: 'auto' });
      return;
    }
    if (typeof window !== 'undefined') window.scrollBy({ top: delta, behavior: 'auto' });
  }
  export async function setScale(newScale: number): Promise<void> {
    const nextScale = zoomState.clampPdfScale(newScale);
    if (Math.abs(nextScale - scale) <= ZOOM_EPSILON) return;
    scale = nextScale;
    selectionState.hideToolbar();
    if (!docState.pdfDoc || !canvas || !textLayer || !renderState.currentPageObj) return;
    const anchor = captureScrollAnchor(canvasContainer ?? null);
    const navRequestId = renderState.bumpNavigationId();
    try {
      if (renderState.textLayerInstance) {
        const viewport = renderState.currentPageObj.getViewport({ scale: nextScale });
        const outputScale = window.devicePixelRatio || 1;
        canvas.width = Math.round(viewport.width * outputScale);
        canvas.height = Math.round(viewport.height * outputScale);
        canvas.style.width = `${viewport.width}px`;
        canvas.style.height = `${viewport.height}px`;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        const transform = outputScale !== 1 ? [outputScale, 0, 0, outputScale, 0, 0] : undefined;
        const rc = { canvasContext: ctx, viewport, canvas, transform };
        await renderState.cancelActiveRenderTask();
        const task = renderState.currentPageObj.render(rc);
        renderState.activeRenderTask = task;
        try {
          await task.promise;
        } catch (err) {
          const c =
            typeof err === 'object' &&
            err !== null &&
            'name' in err &&
            String((err as { name?: string }).name) === 'RenderingCancelledException';
          if (c) return;
          throw err;
        }
        renderState.activeRenderTask = null;
        if (renderState.isStaleNavigation(navRequestId)) return;
        renderState.textLayerInstance.update({ viewport });
        for (const span of textLayer.querySelectorAll('span')) span.style.pointerEvents = 'auto';
        if (!renderState.isStaleNavigation(navRequestId))
          restoreScrollAnchor(anchor, canvasContainer ?? null);
      } else {
        const rendered = await renderState.renderPage(currentPage, {
          requestId: navRequestId,
          renderScale: nextScale,
        });
        if (rendered && !renderState.isStaleNavigation(navRequestId))
          restoreScrollAnchor(anchor, canvasContainer ?? null);
      }
    } catch (err) {
      logger.error(
        'Error setting scale',
        { error: err instanceof Error ? err.message : String(err) },
        'reader',
      );
      navigationError = t('pdf.navigationFailed');
    }
  }
  export function getCurrentPage(): number {
    return currentPage;
  }
  export function getScale(): number {
    return scale;
  }
  export async function applyFit(mode: FitMode): Promise<void> {
    const page = renderState.currentPageObj;
    if (!page) return;
    const natural = page.getViewport({ scale: 1 });
    await setScale(
      resolveFitScalePercent(mode, {
        containerWidth: canvasContainer?.clientWidth ?? 0,
        containerHeight: canvasContainer?.clientHeight ?? 0,
        pageWidth: natural.width,
        pageHeight: natural.height,
      }) / 100,
    );
  }
  export function getTotalPages(): number {
    return docState.totalPages;
  }
  export function getCurrentFilePath(): string {
    return filePath;
  }

  onMount(() => {
    pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
      'pdfjs-dist/build/pdf.worker.min.mjs',
      import.meta.url,
    ).toString();
    const handleSelectionChange = (): void => {
      const sel = window.getSelection();
      if (!sel || !sel.toString().trim()) selectionState.clearSelectionUi();
    };
    document.addEventListener('selectionchange', handleSelectionChange);
    return () => {
      docState.cleanup();
      renderState.cleanup();
      document.removeEventListener('selectionchange', handleSelectionChange);
    };
  });
  $effect(() => {
    if (filePath && filePath !== docState.lastLoadedFilePath) {
      docState.lastLoadedFilePath = filePath;
      void loadPdf();
    }
  });
  $effect(() => {
    if (tocOpen) void outlineState.ensureOutlineLoaded(tocOpen);
  });
  $effect(() => {
    const tp = parseLocatorPage(searchTargetLocator);
    if (
      !tp ||
      !docState.pdfDoc ||
      docState.totalPages <= 0 ||
      tp > docState.totalPages ||
      tp === currentPage
    )
      return;
    void navigateToPage(tp, { flash: true });
  });
  const handleViewerKeydown_ = (event: KeyboardEvent): void => {
    if (event.key === 'ArrowLeft') goToPrevPage();
    else if (event.key === 'ArrowRight') goToNextPage();
  };
</script>

{#snippet pdfControlsSnippet()}
  <PdfControls
    {currentPage}
    totalPages={docState.totalPages}
    {scale}
    isLoading={docState.isLoading}
    error={docState.error}
    {t}
    onPrevPage={goToPrevPage}
    onNextPage={goToNextPage}
    onGoToPage={navigateToPage}
    onSetScale={(s) => setScale(s)}
    onFit={applyFit}
  />
{/snippet}

<svelte:window onkeydown={handleViewerKeydown} />

<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
<div
  class="pdf-viewer h-full flex flex-col min-h-0"
  bind:this={viewerRoot}
  tabindex="0"
  role="region"
  aria-label="PDF Viewer"
  onfocus={() => (isViewerFocused = true)}
  onblur={() => (isViewerFocused = false)}
  onkeydown={handleViewerKeydown_}
  onclick={(event) => {
    selectionState.dismissHighlightManager();
    const target = event.target;
    if (
      target instanceof HTMLSelectElement ||
      target instanceof HTMLInputElement ||
      target instanceof HTMLButtonElement ||
      target instanceof HTMLTextAreaElement
    )
      return;
    if (textLayer && target instanceof Node && textLayer.contains(target)) {
      selectionState.handleTextSelection();
      return;
    }
    viewerRoot?.focus();
  }}
  onmouseup={selectionState.handleTextSelection}
  onpointerup={selectionState.handleTextSelection}
  ontouchend={selectionState.handleTextSelection}
  style={`--pdf-reader-root-bg: ${readerThemePalette.rootBackground}; --pdf-reader-surface-bg: ${readerThemePalette.surfaceBackground}; --pdf-reader-text: ${readerThemePalette.textColor};`}
>
  <PdfLoadingOverlay
    isLoading={docState.isLoading}
    error={docState.error}
    loadProgress={docState.loadProgress}
    loadProgressMax={docState.loadProgressMax}
    {t}
  />
  {#if !isFullscreen}{@render pdfControlsSnippet()}{/if}
  {#if navigationError}<p
      class="m-0 px-3 pt-2 text-red-600 text-xs"
      role="status"
      aria-live="polite"
    >
      {navigationError}
    </p>{/if}
  <div
    class="flex flex-1 overflow-hidden"
    style:visibility={docState.isLoading || docState.error ? 'hidden' : 'visible'}
  >
    <div
      class="flex-1 min-h-0 overflow-auto bg-(--pdf-reader-root-bg,var(--color-background))"
      bind:this={canvasContainer}
      style="padding: 0;"
    >
      <div class="flex min-h-full items-center justify-center">
        <div
          class="relative inline-block"
          class:search-hit={flashSearchResult}
          style="isolation: isolate;"
        >
          <canvas bind:this={canvas} style="filter: {visualFilterStyle};"></canvas>
          <PdfSelectionOverlay
            {persistedHighlights}
            {currentPage}
            {scale}
            activeHighlightId={selectionState.activeHighlightId}
            onHighlightClick={(hl, e) => selectionState.handleHighlightClick(hl, e)}
          />
          <div
            bind:this={textLayer}
            class="textLayer"
            draggable="false"
            role="presentation"
            ondragstart={(e) => e.preventDefault()}
          ></div>
        </div>
      </div>
    </div>
  </div>
</div>

<style>
  canvas {
    display: block;
    position: relative;
    z-index: 0;
    box-shadow: var(--shadow-soft);
    background: var(--pdf-reader-surface-bg, var(--color-color-picker-bg));
  }
  .search-hit {
    outline: 3px solid var(--color-accent-blue);
    outline-offset: 6px;
    border-radius: var(--radius-sm);
  }
</style>
