<script lang="ts">
  import { untrack } from 'svelte';
  import PdfViewer from '../viewer-pdf/PdfViewer.svelte';
  import EpubNativeViewer from '../viewer-epub/EpubNativeViewer.svelte';
  import SearchPanel from '../panels/SearchPanel.svelte';
  import SelectionToolbar from '../highlight/SelectionToolbar.svelte';
  import ReaderTextSettings from './ReaderTextSettings.svelte';
  import ReaderTocPanel from './ReaderTocPanel.svelte';
  import type { TocEntry } from './ReaderTocPanel.svelte';
  import ReaderTopBar from './ReaderTopBar.svelte';
  import ReaderBottomBar from './ReaderBottomBar.svelte';
  import ReaderFullscreenAffordances from './ReaderFullscreenAffordances.svelte';
  import ReaderSelectionDebugOverlay from './ReaderSelectionDebugOverlay.svelte';
  import ReaderDiagnosticPanel from './ReaderDiagnosticPanel.svelte';
  import { emptyReaderDiagnostics, type ReaderDiagnostics } from './readerDiagnostics';
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
  import { registerZoomStepResolver } from '../viewer-epub/epubZoomWheel';
  import { clampZoomPercent } from '../viewer-pdf/pdfNavigation';
  import { createHighlightMenu } from '../highlight/useHighlightMenu.svelte';
  import { createBookmarksPanel } from './useBookmarksPanel.svelte';
  import { createReaderNavigation } from './useReaderNavigation.svelte';
  import BookmarkSidebar from './BookmarkSidebar.svelte';
  import { createViewerSelection, type ViewerSelection } from '../viewer-shared/Viewer';
  import type { SelectionData } from '../highlight/SelectionToolbar.svelte';
  import { dictionaryState } from '$lib/shared/stores/DictionaryState.svelte';
  import { logger } from '$lib/shared/logger/Logger';
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
    /**
     * Opt-in live diagnostic readout. Off by default and read-only; see
     * `ReaderDiagnosticPanel`. Passed as a prop so the surface has exactly one
     * entry point and cannot be switched on by anything the reader renders.
     */
    showDiagnostics?: boolean;
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
    showDiagnostics = false,
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
  // Bookmarks follow the BOOK, not the panel (ADR-005): the count is in the
  // bottom bar and must be right before the panel is ever opened. The
  // supersede guard for concurrent loads lives in `bookmarksState`.
  $effect(() => {
    const bookId = activeReadingBook?.id ?? null;
    if (!bookId) return;
    void bookmarksPanel.bookmarksState.loadBookmarks(bookId);
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
  let edgeNavVisible = $derived(chrome.edgeNavVisible);
  function handleWorkspaceMouseLeave(): void {
    chrome.handleWorkspaceMouseLeave();
  }
  // U3: parent-chrome clicks never reach the iframe document, so its
  // selection (and menu) would survive them. Collapse from the parent; the
  // toolbar owns its own clicks and the iframe its own flow.
  //
  // U2: the ORDER matters. Collapsing the frame's selection fires its
  // `selectionchange`, which posts a delayed clear — but delivered
  // asynchronously, so it can land after a *later* selection has raised the
  // toolbar again and close a menu that should be open. `clearIframeSelection`
  // is what produces that post; dismissing first and clearing after means the
  // clear belongs to a selection that no longer has a menu. Verified against
  // the live app: clicking away with the selection collapsed and the selection
  // still present both end with the toolbar gone and the frame's selection
  // empty.
  function handleWorkspacePointerDown(e: PointerEvent): void {
    if (viewer.kind !== 'epub') return;
    const target = e.target as Element | null;
    if (target?.closest?.('.selection-toolbar')) return;
    dismissToolbar('workspace-pointerdown');
    // typeof-guard: bind:this may hold a viewer without this export (mocks,
    // torn-down chapters).
    if (typeof epubRef?.clearIframeSelection === 'function') {
      epubRef.clearIframeSelection();
    }
  }
  function toggleFullscreen(): void {
    chrome.toggleFullscreen();
    syncDebugReaderInfo();
  }
  $effect(() => {
    void chrome.isFullscreen;
    void panelOpen;
    void chrome.hoverTop;
    void chrome.hoverBottom;
    chrome.resetIdleTimer();
    chrome.updateEdgeNav(chrome.mouseX);
  });
  $effect(() => {
    const root = workspaceRoot;
    if (!root) return;
    root.addEventListener('mousemove', chrome.handleWorkspaceMouseMove);
    window.addEventListener('pointerdown', handleWorkspacePointerDown, { capture: true });
    window.addEventListener('wheel', zoom.handleGlobalWheel as EventListener, {
      capture: true,
      passive: false,
    });
    // The chapter is an iframe, and a wheel gesture dispatched inside it never
    // reaches the window listener above — which is why Ctrl+wheel over the book
    // did nothing while Ctrl+`+`/`-` worked. Publish the step for the frame to
    // call. It is `adjustZoom` itself, the same function the keys and the menu
    // use, so the step size, the clamp and the persistence stay in ONE place.
    const unregisterZoomStep = registerZoomStepResolver(() => zoom.adjustZoom);
    window.addEventListener('keydown', chrome.handleGlobalKeydown as EventListener);
    window.addEventListener('keydown', zoom.handleGlobalKeydown as EventListener);
    window.addEventListener('keydown', readerShortcuts.handleGlobalKeydown as EventListener);
    return () => {
      root.removeEventListener('mousemove', chrome.handleWorkspaceMouseMove);
      unregisterZoomStep();
      window.removeEventListener('pointerdown', handleWorkspacePointerDown, {
        capture: true,
      } as AddEventListenerOptions);
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
        pageInfo: `${bookProgress}%`,
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

  const bookProgress = $derived(nav.hasEpubProgress ? nav.bookProgress : Math.round(percentage));
  // ── Opt-in diagnostics ──────────────────────────────────────────────────
  // The panel is fed by polling the viewer's own read-only snapshot rather
  // than by mirroring the numbers into `$state` here. Two reasons:
  //   1. `pageBox`/`pageCount` and the pending-restore flags are plain layout
  //      state inside the viewer's composables — they are not reactive and
  //      cannot be subscribed to from here. Polling is the only honest read.
  //   2. A mirror would be a second copy that can disagree with the reader.
  // The poll only runs while the panel is on, so the cost is zero by default
  // — which is what "invisible, not a product surface" has to mean in practice.
  let liveDiagnostics = $state<ReaderDiagnostics>(emptyReaderDiagnostics());
  const diagnosticsEnabled = $derived(showDiagnostics && viewer.kind === 'epub');
  $effect(() => {
    if (!diagnosticsEnabled) return;
    const read = (): void => {
      const snapshot = epubRef?.getDiagnostics?.();
      if (snapshot) liveDiagnostics = snapshot;
    };
    read();
    const timer = setInterval(read, 250);
    return () => clearInterval(timer);
  });
  const headerCurrentPage = $derived(nav.headerCurrentPage);
  const headerTotalPages = $derived(nav.headerTotalPages);
  // The fullscreen `%` reads the viewer scale (PDF) or viewer zoom (EPUB),
  // never `epub.fontSize` for a PDF. `getScaleOrZoom` subscribes to the
  // viewer's own state, so wheel/keys/dropdown all flow into this readout.
  const headerZoomPercent = $derived(viewer.getScaleOrZoom());

  async function handleHeaderGoToPage(page: number): Promise<boolean> {
    // U2: navigation invalidates the selection anchor — never carry the menu
    // into the next chapter.
    dismissToolbar('header-goto-page');
    return nav.handleHeaderGoToPage(page);
  }
  function goPrevPage(): void {
    dismissToolbar('prev-page');
    nav.goPrev();
  }
  function goNextPage(): void {
    dismissToolbar('next-page');
    nav.goNext();
  }
  // U4: lateral tercile paging as fallback when chevrons rest faded.
  // Selection and links win; plain margin clicks turn pages.
  function handleIframeTercileClick(x: number, isLink: boolean): void {
    if (!isFullscreen) return;
    if (isLink) return;
    if (showToolbar || selectedText) return;
    const w = typeof window !== 'undefined' ? window.innerWidth : 9999;
    if (x < w / 3) goPrevPage();
    else if (x > (2 * w) / 3) goNextPage();
  }
  function handleTocNavigate(entry: TocEntry): void {
    dismissToolbar('toc-navigate');
    nav.handleTocNavigate(entry);
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
    // Case 5 (menu must not reopen): the write appends to `persistedHighlights`
    // and re-renders the overlay. The collapse of the frame's selection has to
    // land FIRST, so nothing can re-read it as a live selection while the
    // overlay is being rebuilt.
    if (typeof epubRef?.clearIframeSelection === 'function') {
      epubRef.clearIframeSelection();
    }
    highlightMenuState.scheduleToolbarDismiss(() => dismissToolbar('color-applied'));
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
  /**
   * Close the selection toolbar and record why.
   *
   * The contract's governing rule is that a failure is findable; the same
   * applies to a dismissal. When the toolbar fails to disappear, the question
   * is always "which clear ran, and did one run at all" — neither was
   * observable before, because this function's only trace was a debug counter
   * the reader cannot see. Every path that closes the menu funnels through here
   * with a `reason`, and the reason reaches the log.
   */
  function dismissToolbar(reason = 'unknown'): void {
    const wasOpen = showToolbar;
    dbg.epub.dismissToolbarCallCount++;
    dbg.epub.lastDismissTrigger = reason;
    showToolbar = false;
    selectedText = '';
    selectionBounds = null;
    selectionContainer = null;
    logger.debug(
      'RW: dismissToolbar',
      { reason, wasOpen, count: dbg.epub.dismissToolbarCallCount },
      'reader',
    );
  }
  /**
   * The viewer's `onselectionclear`: the selection ended inside the chapter and
   * the toolbar must follow it. `reason` is forwarded from the frame's own
   * handler (which knows why the selection ended); it is absent for the parent
   * paths that call the handler indirectly, which is itself the useful signal.
   *
   * A clear arriving for a book, or a chapter, whose toolbar was never raised
   * is normal — `scroll` fires continuously — so this logs at debug, not warn.
   * What it must not do is stay silent.
   */
  function handleSelectionClear(reason?: string): void {
    logger.debug('RW: selection cleared in frame', { reason: reason ?? '(none)' }, 'reader');
    dismissToolbar(`onselectionclear:${reason ?? 'none'}`);
  }
  function toggleSearch(): void {
    searchPanelOpen = !searchPanelOpen;
    syncDebugReaderInfo();
  }
  // The drawer closes through a named path (not an inline assignment) so the
  // debug snapshot stays in sync: `readerInfo.isSearchOpen` reads
  // `searchPanelOpen`, and a bare assignment would leave it stale.
  function closeSearch(): void {
    searchPanelOpen = false;
    syncDebugReaderInfo();
  }
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<section
  class="flex flex-col bg-(--color-bg-deep) {isFullscreen
    ? 'fixed inset-0 z-40 h-screen w-screen overflow-hidden bg-[#f8f5ec]'
    : 'h-full min-h-0'} {chrome.cursorHidden ? 'cursor-none' : ''}"
  bind:this={workspaceRoot}
  onmouseleave={handleWorkspaceMouseLeave}
>
  <ReaderTopBar
    title={activeReadingBook?.title ?? ''}
    showTocPanel={nav.showTocPanel}
    {searchPanelOpen}
    {showTextSettings}
    {isFullscreen}
    visible={isFullscreen ? chrome.chromeVisible : true}
    {t}
    {onBackToHome}
    onToggleToc={toggleTocPanel}
    onToggleSearch={toggleSearch}
    onToggleTextSettings={toggleTextSettings}
    onToggleFullscreen={toggleFullscreen}
  />
  <div class="flex flex-1 min-h-0 items-stretch justify-center" class:p-0={isFullscreen}>
    {#if getReaderErrorFn()}
      <p class="font-inter text-sm text-(--color-text-inverse)">{getReaderErrorFn()}</p>
    {:else if !activeReadingBook}
      <p class="font-inter text-sm text-(--color-text-inverse)">{t('reader.no_book_loaded')}</p>
    {:else if viewer.kind === 'pdf'}
      <div
        class="flex min-h-0 min-w-0 flex-1"
        class:px-10={!isFullscreen}
        class:py-6={!isFullscreen}
      >
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
            onselectionclear={() => dismissToolbar('pdf-onselectionclear')}
            onHighlightAction={handleHighlightAction}
            onTocReady={nav.handleTocReady}
            externalTocNavigate={nav.tocNavigate}
            {isFullscreen}
            tocOpen={nav.showTocPanel}
            persistedHighlights={highlightsState.persistedHighlights}
            {t}
          />
        </div>
      </div>
    {:else if viewer.kind === 'epub'}
      <div
        class="flex min-h-0 min-w-0 flex-1"
        class:px-10={!isFullscreen}
        class:py-6={!isFullscreen}
      >
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
            onselectionclear={handleSelectionClear}
            {isFullscreen}
            hasSelectionMenu={showToolbar}
            onSettingsChange={handleTextSettingsChange}
            persistedHighlights={highlightsState.persistedHighlights}
            onHighlightAction={handleHighlightAction}
            onIframeMouseMove={(x, y) => chrome.handleForwardedMouse(x, y)}
            onIframeMouseLeave={() => chrome.handleWorkspaceMouseLeave()}
            onIframeClick={(x, isLink) => handleIframeTercileClick(x, isLink)}
            cursorHidden={chrome.cursorHidden}
            {t}
          />
        </div>
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
    <ReaderSelectionDebugOverlay
      enabled={dbg.enabled}
      {computedToolbarX}
      {computedToolbarY}
      {selectionBounds}
      {selectionContainer}
    />
    <ReaderDiagnosticPanel diagnostics={{ ...liveDiagnostics, enabled: diagnosticsEnabled }} />
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
  <ReaderBottomBar
    title={activeReadingBook?.title ?? ''}
    chapterTitle={nav.currentChapterTitle}
    {bookProgress}
    currentPdfPage={nav.currentPdfPage}
    totalPdfPages={nav.totalPdfPages}
    viewerKind={viewer.kind}
    {isFullscreen}
    visible={isFullscreen ? chrome.chromeVisible : true}
    currentPage={headerCurrentPage}
    totalPages={headerTotalPages}
    zoomPercent={headerZoomPercent}
    bookmarkCount={bookmarksPanel.bookmarksState.bookmarksList.length}
    bookmarksLoading={bookmarksPanel.bookmarksState.bookmarksLoading}
    showBookmarks={bookmarksPanel.showBookmarks}
    {t}
    onPrev={goPrevPage}
    onNext={goNextPage}
    onGoToPage={handleHeaderGoToPage}
    onFit={(mode) => {
      void pdfRef?.applyFit(mode);
    }}
    onZoomSelect={(pct) => {
      if (viewer.kind === 'pdf') void pdfRef?.setScale(clampZoomPercent(pct) / 100);
      else zoom.handleHeaderFontSizeChange(pct);
    }}
    onToggleBookmarks={toggleBookmarks}
  />
  <ReaderFullscreenAffordances
    {isFullscreen}
    hasBook={activeReadingBook !== null}
    {edgeNavVisible}
    prevDisabled={nav.prevDisabled}
    nextDisabled={nav.nextDisabled}
    chromeVisible={chrome.chromeVisible}
    {selectedText}
    {t}
    onPrev={goPrevPage}
    onNext={goNextPage}
    onShare={() => void handleShareText()}
    onMouseMove={(e) => chrome.handleWorkspaceMouseMove(e)}
  />
  {#if searchPanelOpen && activeReadingBook}
    <SearchPanel
      open={searchPanelOpen}
      onClose={closeSearch}
      bookId={activeReadingBook.id}
      disabledReason={searchUnavailableReason}
      {isSearching}
      response={searchResponse}
      onSearch={(query, page) => onSearch?.(query, page)}
      onJump={(target) => onSearchJump?.(target)}
      {t}
    />
  {/if}
</section>
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
  onNavigate={handleTocNavigate}
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
