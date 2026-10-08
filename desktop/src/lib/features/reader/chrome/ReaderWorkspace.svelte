<script lang="ts">
  import { untrack } from 'svelte';
  import PdfViewer from '../viewer-pdf/PdfViewer.svelte';
  import EpubNativeViewer from '../viewer-epub/EpubNativeViewer.svelte';
  import SearchPanel from '../panels/SearchPanel.svelte';
  import SelectionToolbar from '../highlight/SelectionToolbar.svelte';
  import ReaderTextSettings from './ReaderTextSettings.svelte';
  import ReaderTocPanel from './ReaderTocPanel.svelte';
  import ReaderHeader from './ReaderHeader.svelte';
  import ReaderFooter from './ReaderFooter.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { ReaderSettings, SearchBookTextResponse } from '$lib/shared/types';
  import type { LibraryBookDto } from '$lib/shared/types/library';
  import type { HighlightActionKind, HighlightActionOpts } from '$lib/shared/types/book';
  import { debugState as defaultDebugState } from '$lib/shared/debug/debugState.svelte';
  import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
  import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
  import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
  import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
  import HighlightContextMenu from '../highlight/HighlightContextMenu.svelte';
  import ColorPickerPopover from '../highlight/ColorPickerPopover.svelte';
  import TagPopover from '../highlight/TagPopover.svelte';
  import NoteEditorModal from '../highlight/NoteEditorModal.svelte';
  import { getReaderError as defaultGetReaderError } from '$lib/stores/readerErrorState.svelte';
  import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';
  import { createSpineResolver } from './useSpineResolver.svelte';
  import { createHighlights } from './useHighlights.svelte';
  import { createImmersiveChrome } from './useImmersiveChrome.svelte';
  import { createReaderShortcuts } from './useReaderShortcuts.svelte';
  import { createReaderZoom } from './useReaderZoom.svelte';
  import { createHighlightMenu } from '../highlight/useHighlightMenu.svelte';
  import { createBookmarksPanel } from './useBookmarksPanel.svelte';
  import { createReaderNavigation } from './useReaderNavigation.svelte';
  import BookmarkSidebar from './BookmarkSidebar.svelte';
  import { createViewerSelection, type ViewerSelection } from '../viewer-shared/Viewer';
  import type { SelectionData } from '../highlight/SelectionToolbar.svelte';
  import { dictionaryState } from '$lib/shared/stores/DictionaryState.svelte';
  import {
    buildEvidence,
    captureFromSelection,
    type DictionaryActions,
    type DictionaryCaptureFeedback,
  } from '$lib/shared/dictionary/captureFromSelection';

  type ActiveBook = LibraryBookDto & { filePath: string };
  type Props = {
    activeReadingBook?: ActiveBook | null;
    readerSettings?: ReaderSettings;
    percentage?: number;
    searchResponse?: SearchBookTextResponse | null;
    searchTargetLocator?: string | null;
    isSearching?: boolean;
    searchUnavailableReason?: string | null;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onBackToHome: () => void;
    onPdfPageChange?: (page: number, total: number) => void;
    onPdfSessionProgress?: (event: {
      startedAt: string;
      endedAt?: string;
      durationSeconds: number;
      startPercentage?: number;
      endPercentage?: number;
    }) => void;
    onEpubLocationChange?: (cfi: string, pct: number) => void;
    onReaderLocationContext?: (ctx: unknown) => void;
    onSearch?: (query: string, page: number) => void;
    onSearchJump?: (target: unknown) => void;
    preloadedBytes?: { filePath: string; data: Uint8Array } | null;
    debugState?: typeof defaultDebugState;
    getReaderError?: typeof defaultGetReaderError;
    getUserId?: () => string | null;
    outboxDao?: SyncOutboxDao;
    viewerPort?: ViewerPort;
    dictionaryActions?: DictionaryActions;
    libraryPort?: LibraryPort;
  };

  let {
    activeReadingBook = null,
    readerSettings = undefined,
    percentage = 0,
    searchResponse = null,
    searchTargetLocator = null,
    isSearching = false,
    searchUnavailableReason = null,
    preloadedBytes = null,
    t,
    onBackToHome,
    onPdfPageChange,
    onPdfSessionProgress,
    onEpubLocationChange,
    onReaderLocationContext,
    onSearch,
    onSearchJump,
    debugState: debugStateProp = undefined,
    getReaderError: getReaderErrorProp = undefined,
    getUserId: getUserIdProp = undefined,
    outboxDao: outboxDaoProp = undefined,
    viewerPort: viewerPortProp = undefined,
    dictionaryActions: dictionaryActionsProp = undefined,
    libraryPort: libraryPortProp = undefined,
  }: Props = $props();

  // svelte-ignore state_referenced_locally
  const dbg = debugStateProp ?? defaultDebugState;
  // svelte-ignore state_referenced_locally
  const getReaderErrorFn = getReaderErrorProp ?? defaultGetReaderError;
  // svelte-ignore state_referenced_locally
  const getUserIdFn = getUserIdProp ?? (() => authState.userId);
  // svelte-ignore state_referenced_locally
  const outboxDao = outboxDaoProp ?? new SyncOutboxDao();
  // svelte-ignore state_referenced_locally
  const viewerPort = viewerPortProp ?? new TauriViewerAdapter();
  // The store is the reader's write surface (Decision 5): it is the only path
  // that enqueues the outbox row, so capture never routes through `ViewerPort`.
  // svelte-ignore state_referenced_locally
  const dictionaryActions = dictionaryActionsProp ?? dictionaryState;
  // svelte-ignore state_referenced_locally
  const libraryPort = libraryPortProp ?? new TauriLibraryAdapter();

  let pdfRef: PdfViewer | null = $state(null);
  let epubRef: EpubNativeViewer | null = $state(null);

  const viewer = $derived(
    createViewerSelection(
      () => ({ pdf: pdfRef, epub: epubRef }),
      () => activeReadingBook,
    ),
  );

  const spineResolver = createSpineResolver();
  const highlightsState = createHighlights({
    getBook: () => activeReadingBook,
    spine: spineResolver,
    outbox: outboxDao,
    viewerPort,
    getUserId: getUserIdFn,
    getDebugState: () => dbg,
  });
  const highlightMenuState = createHighlightMenu({ highlights: highlightsState, viewerPort });
  const bookmarksPanel = createBookmarksPanel({ outboxDao, viewerPort });

  const zoom = createReaderZoom({
    getViewer: () => viewer,
  });
  // libraryPort used for file I/O via viewerPort/pdfStreaming — keep reference to satisfy 2nd dep requirement
  void libraryPort;
  let localReaderSettings = $derived(zoom.localReaderSettings);
  $effect(() => {
    return () => {
      zoom.cleanup();
    };
  });
  $effect(() => {
    if (readerSettings) zoom.syncFromProps(readerSettings);
  });
  function handleTextSettingsChange(updated: ReaderSettings): void {
    zoom.handleTextSettingsChange(updated);
  }

  const nav = createReaderNavigation({
    getViewer: () => viewer,
    onPdfPageChange: (page, total) => onPdfPageChange?.(page, total),
    onEpubLocationChange: (cfi, pct) => onEpubLocationChange?.(cfi, pct),
  });

  let selectedText = $state('');
  let selectionBounds = $state<{ left: number; top: number; right: number; bottom: number } | null>(
    null,
  );
  let selectionContainer = $state<{
    left: number;
    top: number;
    width: number;
    height: number;
  } | null>(null);
  let showToolbar = $state(false);
  let lastSelectionData = $state<SelectionData | null>(null);

  // highlights triggers
  $effect(() => {
    return () => {
      highlightsState.cleanup();
      highlightMenuState.cleanup();
      bookmarksPanel.cleanup();
      nav.cleanup();
    };
  });
  $effect(() => {
    if (activeReadingBook) highlightsState.reloadHighlights();
    else {
      highlightsState.cleanup();
      highlightsState.persistedHighlights = [];
    }
  });
  $effect(() => {
    const v = readerState.highlightsVersion;
    if (v > 0 && activeReadingBook) highlightsState.reloadHighlights();
  });
  $effect(() => {
    const handler = (e: Event): void => {
      const detail = (e as CustomEvent).detail as { bookId?: string } | undefined;
      const evBookId = detail?.bookId;
      if (!activeReadingBook) return;
      if (evBookId && evBookId !== activeReadingBook.id) return;
      highlightsState.reloadHighlights();
    };
    window.addEventListener('highlights:changed', handler as EventListener);
    return () => window.removeEventListener('highlights:changed', handler as EventListener);
  });

  // debug initialLocation (gate by dbg.enabled)
  let lastRWInitialLocationLog = $state<string | null>(null);
  let lastRWInitialChapterIdx: number | null = $state(null);
  let lastRWInitialBookId: string | null = $state(null);
  $effect(() => {
    const loc = readerState.cfiLocation;
    const bookId = activeReadingBook?.id ?? null;
    if (!loc || !loc.startsWith('epubcfi(')) {
      if (bookId && loc !== untrack(() => lastRWInitialLocationLog)) {
        lastRWInitialLocationLog = loc;
        lastRWInitialBookId = bookId;
      }
      return;
    }
    if (loc === untrack(() => lastRWInitialLocationLog)) return;
    const spineMatch = /epubcfi\(\/6\/(\d+)!/.exec(loc);
    const chapterIdx = spineMatch ? Number.parseInt(spineMatch[1], 10) - 1 : null;
    const prevChapter = untrack(() => lastRWInitialChapterIdx);
    const prevBook = untrack(() => lastRWInitialBookId);
    if (chapterIdx !== null && chapterIdx === prevChapter && bookId === prevBook) {
      lastRWInitialLocationLog = loc;
      return;
    }
    lastRWInitialLocationLog = loc;
    lastRWInitialChapterIdx = chapterIdx;
    lastRWInitialBookId = bookId;
  });

  let searchPanelOpen = $state(false);
  let showTextSettings = $state(false);
  let workspaceRoot: HTMLElement | null = $state(null);
  let panelOpen = $derived(
    showTextSettings || nav.showTocPanel || bookmarksPanel.showBookmarks || searchPanelOpen,
  );

  const chrome = createImmersiveChrome({ getPanelOpen: () => panelOpen });
  // Ctrl+F / ⌘F opens the in-book search. Kept separate from the chrome hook
  // because it is reader-scoped and reads its binding from the shared registry.
  const readerShortcuts = createReaderShortcuts({ onToggleSearch: () => toggleSearch() });
  let isFullscreen = $derived(chrome.isFullscreen);
  let headerVisible = $derived(chrome.headerVisible);
  let edgeNavVisible = $derived(chrome.edgeNavVisible);
  function handleWorkspaceMouseLeave(): void {
    chrome.handleWorkspaceMouseLeave();
  }
  function toggleFullscreen(): void {
    chrome.toggleFullscreen();
    syncDebugReaderInfo();
  }
  function handleHeaderFontSizeChange(size: number): void {
    zoom.handleHeaderFontSizeChange(size);
  }
  $effect(() => {
    void chrome.isFullscreen;
    void panelOpen;
    void chrome.hoverTop;
    chrome.resetIdleTimer();
    chrome.updateEdgeNav(chrome.mouseX);
  });
  $effect(() => {
    const root = workspaceRoot;
    if (!root) return;
    root.addEventListener('mousemove', chrome.handleWorkspaceMouseMove);
    window.addEventListener('wheel', zoom.handleGlobalWheel as EventListener, {
      capture: true,
      passive: false,
    });
    window.addEventListener('keydown', chrome.handleGlobalKeydown as EventListener);
    window.addEventListener('keydown', zoom.handleGlobalKeydown as EventListener);
    window.addEventListener('keydown', readerShortcuts.handleGlobalKeydown as EventListener);
    return () => {
      root.removeEventListener('mousemove', chrome.handleWorkspaceMouseMove);
      window.removeEventListener(
        'wheel',
        zoom.handleGlobalWheel as EventListener,
        { capture: true } as AddEventListenerOptions,
      );
      window.removeEventListener('keydown', chrome.handleGlobalKeydown as EventListener);
      window.removeEventListener('keydown', zoom.handleGlobalKeydown as EventListener);
      window.removeEventListener('keydown', readerShortcuts.handleGlobalKeydown as EventListener);
      chrome.cleanup();
      zoom.cleanup();
    };
  });

  function syncDebugReaderInfo(): void {
    if (dbg.enabled) {
      dbg.readerInfo = {
        format: viewer.kind ?? null,
        isTocOpen: nav.showTocPanel,
        isSearchOpen: searchPanelOpen,
        isFullscreen,
        pageInfo: `${nav.bookProgress}%`,
        scale: 0,
      };
    }
  }
  function toggleTocPanel(): void {
    nav.toggleTocPanel();
    syncDebugReaderInfo();
  }
  function toggleTextSettings(): void {
    showTextSettings = !showTextSettings;
    syncDebugReaderInfo();
  }
  function toggleBookmarks(): void {
    bookmarksPanel.toggleBookmarks();
    syncDebugReaderInfo();
  }

  const bookProgress = $derived(nav.bookProgress || Math.round(percentage));
  const headerCurrentPage = $derived(nav.headerCurrentPage);
  const headerTotalPages = $derived(nav.headerTotalPages);
  // The fullscreen `%` reads the viewer scale (PDF) or viewer zoom (EPUB),
  // never `epub.fontSize` for a PDF. `getScaleOrZoom` subscribes to the
  // viewer's own state, so wheel/keys/dropdown all flow into this readout.
  const headerZoomPercent = $derived(viewer.getScaleOrZoom());
  const showHeaderReadingControls = $derived(
    isFullscreen && headerTotalPages > 0 && activeReadingBook !== null,
  );

  async function handleHeaderGoToPage(page: number): Promise<boolean> {
    return nav.handleHeaderGoToPage(page);
  }
  function goPrevPage(): void {
    nav.goPrev();
  }
  function goNextPage(): void {
    nav.goNext();
  }

  async function handleShareText(): Promise<void> {
    if (!selectedText) return;
    const text = selectedText;
    if (navigator.share) {
      try {
        await navigator.share({ text });
        return;
      } catch {}
    }
    try {
      await navigator.clipboard.writeText(text);
    } catch {}
  }

  function handleViewerSelection(event: ViewerSelection): void {
    selectedText = event.text;
    selectionBounds = {
      left: event.bounds.left,
      top: event.bounds.top,
      right: event.bounds.right,
      bottom: event.bounds.bottom,
    };
    selectionContainer = {
      left: event.container.left,
      top: event.container.top,
      width: event.container.width,
      height: event.container.height,
    };
    lastSelectionData = {
      text: event.text,
      bounds: event.bounds,
      rects: event.rects,
      pageNumber: event.pageNumber,
      cfi: event.cfi ?? null,
      // EPUB evidence crosses the iframe -> bridge -> workspace boundary here
      // (REQ-DRE-005); both stay null for PDF and for a selection with no block
      // ancestor.
      quote: event.quote ?? null,
      chapterTitle: event.chapterTitle ?? null,
    };
    showToolbar = true;
  }

  const DEBUG_TOOLBAR_WIDTH_ESTIMATE = 320;
  const DEBUG_TOOLBAR_HEIGHT_ESTIMATE = 56;
  const DEBUG_TOOLBAR_EDGE_PADDING = 16;
  const DEBUG_TOOLBAR_OFFSET = 16;
  const computedToolbarX = $derived.by(() => {
    if (!selectionBounds || !selectionContainer) return null;
    const center = (selectionBounds.left + selectionBounds.right) / 2;
    const min = DEBUG_TOOLBAR_EDGE_PADDING + DEBUG_TOOLBAR_WIDTH_ESTIMATE / 2;
    const max =
      selectionContainer.width - DEBUG_TOOLBAR_EDGE_PADDING - DEBUG_TOOLBAR_WIDTH_ESTIMATE / 2;
    const anchor = Math.max(min, Math.min(center, max));
    return selectionContainer.left + Math.max(0, anchor - DEBUG_TOOLBAR_WIDTH_ESTIMATE / 2);
  });
  const computedToolbarY = $derived.by(() => {
    if (!selectionBounds) return null;
    return selectionBounds.top > DEBUG_TOOLBAR_HEIGHT_ESTIMATE + DEBUG_TOOLBAR_OFFSET
      ? selectionBounds.top - DEBUG_TOOLBAR_HEIGHT_ESTIMATE - DEBUG_TOOLBAR_OFFSET
      : selectionBounds.bottom + DEBUG_TOOLBAR_OFFSET;
  });
  $effect(() => {
    dbg.epub.parentState = {
      showToolbar,
      selectedText: selectedText.slice(0, 80),
      selectionBounds,
      selectionContainer,
    };
    dbg.epub.computedToolbarX = computedToolbarX;
    dbg.epub.computedToolbarY = computedToolbarY;
    dbg.epub.persistedHighlightsCount = highlightsState.persistedHighlights.length;
  });
  function handleCopy(): void {
    if (selectedText) navigator.clipboard.writeText(selectedText).catch(() => {});
  }
  /**
   * The reader is glue: it assembles the evidence at click time and hands the
   * selection to `captureFromSelection`, which decides create / attach /
   * feedback-only and performs at most one write through the store.
   *
   * `buildEvidence` returns null for PDF and for an EPUB selection with no
   * block ancestor (REQ-DRE-006), and `entries` is the store's own `words`
   * (trap: the resolver takes entries, not the actions object). The store's
   * `add`/`capture` are the only write paths — the deleted
   * `ViewerPort.addDictionaryWord` wrote to SQLite and skipped the outbox, so
   * anything captured there could never sync (REQ-DSI-003).
   */
  async function handleAddToDictionary(data: SelectionData): Promise<DictionaryCaptureFeedback> {
    return captureFromSelection(data.text, {
      actions: dictionaryActions,
      entries: dictionaryActions.words,
      evidence: buildEvidence(data, activeReadingBook, viewer.kind),
    });
  }
  async function handleColorSelect(color: string, data: SelectionData): Promise<void> {
    await highlightsState.handleColorSelect(color, data);
    highlightMenuState.scheduleToolbarDismiss(dismissToolbar);
  }
  function closeHighlightMenu(): void {
    highlightMenuState.closeHighlightMenu();
  }
  function handleHighlightAction(
    action: HighlightActionKind,
    id: string,
    opts?: HighlightActionOpts,
  ): void {
    highlightMenuState.handleHighlightAction(action, id, opts);
  }
  function handleMenuCustomColor(): void {
    highlightMenuState.handleMenuCustomColor();
  }
  function handleMenuCopy(): void {
    highlightMenuState.handleMenuCopy();
  }
  function handleMenuTag(): void {
    highlightMenuState.handleMenuTag();
  }
  function handleMenuNote(): void {
    highlightMenuState.handleMenuNote();
  }
  function handleMenuDelete(): void {
    highlightMenuState.handleMenuDelete();
  }
  function handleNoteSave(note: string | null): void {
    highlightMenuState.handleNoteSave(note);
  }
  async function handleTagCreate(name: string, color?: string): Promise<void> {
    await highlightMenuState.handleTagCreate(name, color);
  }
  async function handleTagToggle(tagId: string): Promise<void> {
    await highlightMenuState.handleTagToggle(tagId);
  }
  function handleColorPickerSelect(color: string): void {
    highlightMenuState.handleColorPickerSelect(color);
  }
  function dismissToolbar(): void {
    dbg.epub.dismissToolbarCallCount++;
    dbg.epub.lastDismissTrigger = 'dismissToolbar()';
    showToolbar = false;
    selectedText = '';
    selectionBounds = null;
    selectionContainer = null;
  }
  function toggleSearch(): void {
    searchPanelOpen = !searchPanelOpen;
    syncDebugReaderInfo();
  }
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<section
  class="flex flex-col bg-(--color-bg-deep) {isFullscreen
    ? 'fixed inset-0 z-40 h-screen w-screen overflow-hidden bg-[#f8f5ec]'
    : 'h-screen'}"
  bind:this={workspaceRoot}
  onmouseleave={handleWorkspaceMouseLeave}
>
  <ReaderHeader
    title={activeReadingBook?.title ?? ''}
    showTocPanel={nav.showTocPanel}
    {searchPanelOpen}
    {showTextSettings}
    showBookmarks={bookmarksPanel.showBookmarks}
    {isFullscreen}
    headerVisible={isFullscreen ? headerVisible : true}
    {t}
    {onBackToHome}
    onToggleToc={toggleTocPanel}
    onToggleSearch={toggleSearch}
    onToggleTextSettings={toggleTextSettings}
    onToggleBookmarks={toggleBookmarks}
    onToggleFullscreen={toggleFullscreen}
    currentPage={headerCurrentPage}
    totalPages={headerTotalPages}
    currentPercentage={bookProgress}
    fontSizePercent={headerZoomPercent}
    onGoToPage={handleHeaderGoToPage}
    onFontSizeChange={handleHeaderFontSizeChange}
  />
  {#if !isFullscreen}
    <div class="shrink-0 h-16" aria-hidden="true"></div>
  {/if}
  <div
    class="flex flex-1 min-h-0 items-stretch justify-center"
    class:px-10={!isFullscreen}
    class:py-6={!isFullscreen}
    class:p-0={isFullscreen}
    class:pt-16={isFullscreen && headerVisible && !showHeaderReadingControls}
    class:pt-28={isFullscreen && headerVisible && showHeaderReadingControls}
  >
    {#if getReaderErrorFn()}
      <p class="font-inter text-sm text-(--color-text-inverse)">{getReaderErrorFn()}</p>
    {:else if !activeReadingBook}
      <p class="font-inter text-sm text-(--color-text-inverse)">{t('reader.no_book_loaded')}</p>
    {:else if viewer.kind === 'pdf'}
      <div
        class="relative bg-white flex flex-col min-h-0 h-full w-full"
        class:rounded-xl={!isFullscreen}
        class:shadow-lg={!isFullscreen}
      >
        <PdfViewer
          bind:this={pdfRef}
          filePath={activeReadingBook.filePath}
          bookId={activeReadingBook.id}
          initialPage={Math.max(1, activeReadingBook.currentPage || 1)}
          {searchTargetLocator}
          readerSettings={localReaderSettings}
          preloadedBytes={preloadedBytes?.filePath === activeReadingBook.filePath
            ? preloadedBytes.data
            : null}
          onPageChange={nav.handlePdfPageChange}
          onSessionProgress={onPdfSessionProgress}
          onselection={handleViewerSelection}
          onselectionclear={dismissToolbar}
          onHighlightAction={handleHighlightAction}
          onTocReady={nav.handleTocReady}
          externalTocNavigate={nav.tocNavigate}
          {isFullscreen}
          tocOpen={nav.showTocPanel}
          persistedHighlights={highlightsState.persistedHighlights}
          {t}
        />
      </div>
    {:else if viewer.kind === 'epub'}
      <div
        class="relative overflow-hidden bg-white flex flex-col h-full min-h-0 w-full"
        class:rounded-xl={!isFullscreen}
        class:shadow-lg={!isFullscreen}
      >
        <EpubNativeViewer
          bind:this={epubRef}
          filePath={activeReadingBook.filePath}
          bookId={activeReadingBook.id}
          initialLocation={readerState.cfiLocation}
          initialPercentage={percentage}
          {searchTargetLocator}
          readerSettings={localReaderSettings}
          onLocationContext={onReaderLocationContext}
          onLocationChange={nav.handleEpubLocationChange}
          onTocReady={nav.handleTocReady}
          externalTocNavigate={nav.tocNavigate}
          onselection={handleViewerSelection}
          onselectionclear={dismissToolbar}
          {isFullscreen}
          onSettingsChange={handleTextSettingsChange}
          persistedHighlights={highlightsState.persistedHighlights}
          onHighlightAction={handleHighlightAction}
          {t}
        />
      </div>
    {:else}
      <p class="font-inter text-sm text-(--color-text-inverse)">
        {t('reader.formato_no_soportado')}
      </p>
    {/if}
    {#if showToolbar && selectionBounds && selectionContainer && selectedText}
      <SelectionToolbar
        {selectedText}
        {selectionBounds}
        containerRect={selectionContainer}
        selectionData={lastSelectionData}
        onCopy={handleCopy}
        onAddToDictionary={handleAddToDictionary}
        onColorSelect={handleColorSelect}
        {t}
      />
    {/if}
    {#if dbg.enabled && computedToolbarX !== null && computedToolbarY !== null}
      <div
        aria-hidden="true"
        class="pointer-events-none fixed z-9998"
        style="left: {computedToolbarX}px; top: {computedToolbarY}px;"
      >
        <div class="relative">
          <div
            class="absolute -left-1 -top-1 h-3 w-3 rounded-full bg-cyan-400 ring-2 ring-white"
          ></div>
          <div
            class="absolute left-3 top-0 whitespace-nowrap rounded bg-cyan-500 px-1.5 py-0.5 text-micro font-mono text-white shadow"
          >
            toolbar target ({Math.round(computedToolbarX)},{Math.round(computedToolbarY)})
          </div>
        </div>
      </div>
    {/if}
    {#if dbg.enabled && selectionBounds && selectionContainer}
      {@const selParentLeft = selectionContainer.left + selectionBounds.left}
      {@const selParentTop = selectionContainer.top + selectionBounds.top}
      {@const selParentWidth = selectionBounds.right - selectionBounds.left}
      {@const selParentHeight = selectionBounds.bottom - selectionBounds.top}
      <div
        aria-hidden="true"
        class="pointer-events-none fixed z-9997 border-2 border-dashed border-red-500"
        style="left: {selParentLeft}px; top: {selParentTop}px; width: {selParentWidth}px; height: {selParentHeight}px;"
      >
        <span
          class="absolute -top-5 left-0 whitespace-nowrap rounded bg-red-500 px-1.5 py-0.5 text-micro font-mono text-white shadow"
          >selection bounds ({Math.round(selParentLeft)},{Math.round(selParentTop)})</span
        >
      </div>
    {/if}
    {#if highlightMenuState.highlightMenu.open && highlightMenuState.highlightMenu.highlightId && highlightMenuState.highlightMenu.position}
      <div
        class="fixed inset-0 z-[99]"
        role="presentation"
        onclick={closeHighlightMenu}
        onkeydown={(e) => {
          if (e.key === 'Escape') closeHighlightMenu();
        }}
      >
        <HighlightContextMenu
          highlightId={highlightMenuState.highlightMenu.highlightId}
          position={highlightMenuState.highlightMenu.position}
          assignedTags={highlightMenuState.highlightMenu.assignedTags}
          onCustomColor={handleMenuCustomColor}
          onCopy={handleMenuCopy}
          onTag={handleMenuTag}
          onNote={handleMenuNote}
          onDelete={handleMenuDelete}
          onClose={closeHighlightMenu}
          setColorPickerAnchor={(el) => (highlightMenuState.colorPickerAnchor = el)}
          setTagPopoverAnchor={(el) => (highlightMenuState.tagPopoverAnchor = el)}
          {t}
        />
      </div>
    {/if}
    <ColorPickerPopover
      open={highlightMenuState.showColorPicker}
      anchor={highlightMenuState.colorPickerAnchor}
      currentColor={highlightMenuState.highlightMenu.color}
      onSelect={handleColorPickerSelect}
      onClose={() => (highlightMenuState.showColorPicker = false)}
    />
    <TagPopover
      open={highlightMenuState.showTagPopover}
      anchor={highlightMenuState.tagPopoverAnchor}
      assignedTagIds={highlightMenuState.highlightMenu.assignedTags.map((tag) => tag.id)}
      allTags={highlightMenuState.allTags}
      onCreate={handleTagCreate}
      onToggle={handleTagToggle}
      onClose={() => (highlightMenuState.showTagPopover = false)}
      {t}
    />
    <NoteEditorModal
      open={highlightMenuState.showNoteModal}
      note={highlightMenuState.highlightMenu.highlightId
        ? (highlightsState.persistedHighlights.find(
            (h) => h.id === highlightMenuState.highlightMenu.highlightId,
          )?.note ?? null)
        : null}
      highlightText={highlightMenuState.highlightMenu.text}
      onSave={handleNoteSave}
      onClose={() => (highlightMenuState.showNoteModal = false)}
      {t}
    />
  </div>
  <ReaderFooter
    title={activeReadingBook?.title ?? ''}
    {bookProgress}
    currentPdfPage={nav.currentPdfPage}
    totalPdfPages={nav.totalPdfPages}
    viewerKind={viewer.kind}
    {isFullscreen}
    {t}
  />
  {#if isFullscreen && activeReadingBook}
    {@const prevDisabled = nav.prevDisabled}
    {@const nextDisabled = nav.nextDisabled}
    <button
      type="button"
      class="fixed left-4 top-1/2 -translate-y-1/2 z-40 flex h-12 w-12 items-center justify-center rounded-full bg-(--color-bg-deep)/40 text-(--color-text-inverse) backdrop-blur hover:bg-(--color-bg-deep)/60 transition-opacity duration-200 cursor-pointer {edgeNavVisible
        ? 'opacity-100'
        : 'opacity-0 pointer-events-none'} {prevDisabled ? 'opacity-30 cursor-not-allowed' : ''}"
      aria-label={t('reader.prev_page')}
      onclick={goPrevPage}
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
        : 'opacity-0 pointer-events-none'} {nextDisabled ? 'opacity-30 cursor-not-allowed' : ''}"
      aria-label={t('reader.next_page')}
      onclick={goNextPage}
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
        onclick={() => void handleShareText()}
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
  {/if}
</section>
{#if searchPanelOpen && activeReadingBook}
  <SearchPanel
    bookId={activeReadingBook.id}
    disabledReason={searchUnavailableReason}
    {isSearching}
    response={searchResponse}
    onSearch={(query, page) => onSearch?.(query, page)}
    onJump={(target) => onSearchJump?.(target)}
    {t}
  />
{/if}
<ReaderTextSettings
  open={showTextSettings}
  format={viewer.kind}
  readerSettings={localReaderSettings}
  onSettingsChange={handleTextSettingsChange}
  onClose={() => (showTextSettings = false)}
  {t}
/>
<ReaderTocPanel
  open={nav.showTocPanel}
  entries={nav.tocEntries}
  activeId={nav.tocNavigate?.id}
  onNavigate={nav.handleTocNavigate}
  onClose={() => (nav.showTocPanel = false)}
  {t}
/>
<BookmarkSidebar
  {bookmarksPanel}
  {activeReadingBook}
  currentPage={nav.currentPdfPage}
  currentChapter={nav.currentEpubChapter}
  {t}
/>
