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
  import type { TranslationPreferencesDto } from '$lib/shared/types/settings';
  import type { HighlightActionKind, HighlightActionOpts } from '$lib/shared/types/book';
  import { debugState as defaultDebugState } from '$lib/shared/debug/debugState.svelte';
  import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
  import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
  import type { TranslationPort, TranslationOutcome } from '$lib/shared/ports/TranslationPort';
  import type {
    DefinitionResult,
    DictionaryLookupPort,
  } from '$lib/shared/ports/DictionaryLookupPort';
  import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
  import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
  import { TauriTranslationAdapter } from '$lib/shared/ports/adapters/tauri/TauriTranslationAdapter';
  import { TauriDictionaryLookupAdapter } from '$lib/shared/ports/adapters/tauri/TauriDictionaryLookupAdapter';
  import { resolveTranslationSourceWithDetection } from '$lib/shared/services/translation/translationLanguageResolution';
  import { resolveDefinitionLanguage } from '$lib/shared/services/translation/definitionLanguage';
  import { translationFailureOutcome } from '$lib/shared/services/translation/translationOutcome';
  import { normalizeLanguageTag } from '$lib/shared/services/translation/translationLanguages';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
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
  import {
    closeMenu,
    openMenu as nextOpenMenu,
    type OpenMenu,
    type OpenMenuReason,
  } from './openMenu';
  import { FLOATING_MENU_SELECTOR } from './floatingMenu';
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
    translationPort?: TranslationPort;
    dictionaryLookupPort?: DictionaryLookupPort;
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
    translationPort: translationPortProp = undefined,
    dictionaryLookupPort: dictionaryLookupPortProp = undefined,
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
  // Translation is transport-only: no cache, no history (see TranslationPort).
  // svelte-ignore state_referenced_locally
  const translationPort = translationPortProp ?? new TauriTranslationAdapter();
  // The definition cascade and its per-account cache live in Rust; this port is
  // transport-only and holds no state (see DictionaryLookupPort).
  // svelte-ignore state_referenced_locally
  const dictionaryLookupPort = dictionaryLookupPortProp ?? new TauriDictionaryLookupAdapter();

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
  const highlightMenuState = createHighlightMenu({
    highlights: highlightsState,
    viewerPort,
    // The workspace owns the single "which menu is open" state. The highlight
    // menu only REPORTS intent; this is where that intent becomes the state
    // change that closes the selection toolbar in the same step. Both callbacks
    // are set below, because `showToolbar`/`openMenuState` are declared after
    // this composable.
    onIntentOpen: () => raiseMenu('highlight'),
    onIntentClose: () => dismissToolbar('highlight-menu-closed'),
  });
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
  /**
   * THE single source of truth for "which floating menu is open".
   *
   * The reader has exactly two menu surfaces — `SelectionToolbar` and
   * `HighlightContextMenu` — and they must never be on screen together. They used
   * to be owned separately (this component's `showToolbar` and
   * `highlightMenuState.highlightMenu.open`), and the reported defect was that
   * separation failing: a selection that CONTAINS a highlight raised both, and
   * then each closed the other. Now `openMenuState` names the one menu that is
   * shown; `showToolbar` is derived from it, so a second open menu is not a
   * representable state. The transitions live in `./openMenu` (pure, unit
   * tested); this component is the only writer.
   */
  let openMenuState = $state<OpenMenu>('none');
  let showToolbar = $derived(openMenuState === 'selection');
  let lastSelectionData = $state<SelectionData | null>(null);

  /**
   * Raise a menu, closing the other in the SAME step (ADR: one menu at a time).
   *
   * `nextOpenMenu` is total: whichever menu was open is not open in the result,
   * so the invariant does not depend on the other component reacting in time.
   * The highlight menu's own visibility is pushed from `openMenuState` (see the
   * `$effect` below), so setting the state here is all that is needed.
   */
  function raiseMenu(kind: 'selection' | 'highlight'): void {
    openMenuState = nextOpenMenu(openMenuState, kind);
  }

  // The highlight menu is a pure function of the single state: when the state
  // stops being 'highlight' the menu hides, including its sub-popovers. This is
  // what makes the two menus mutually exclusive rather than merely cooperative.
  $effect(() => {
    highlightMenuState.setOpen(openMenuState === 'highlight');
  });

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
    // A press INSIDE a floating menu is not a press OUTSIDE it: the click that is
    // already on its way has to reach the button it was aimed at. Measured with
    // the highlight menu left out of this guard — the `pointerdown` dismissed it,
    // the menu was gone by the `pointerup`, and the `click` landed on the
    // backdrop behind it, so every one of its actions did nothing. See
    // `./floatingMenu` for the trace and for which surfaces carry the marker.
    if (target?.closest?.(FLOATING_MENU_SELECTOR)) return;
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
    // A selection is a request for the selection toolbar, and the single state
    // closes the highlight menu in the same step. A highlight whose own click
    // raised the highlight menu is on the other branch (the frame suppresses the
    // selection post for a run that overlaps a highlight), so the two cannot
    // race.
    raiseMenu('selection');
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
  /**
   * Translate a selection.
   *
   * The pair comes from the reader's saved preferences through
   * `resolveTranslationSourceWithDetection`, which REFUSES to form a degenerate
   * pair instead of guessing one. The old resolver fell back to the target when
   * the book carried no language, producing `en -> en`, which the service
   * answers with a 403 — the reader saw a bland "no translation" and no way to
   * fix it. Now a refusal is a typed reason rendered as its own message, and the
   * translate command is never called for it (`no-result` would be a lie: the
   * request was never made).
   *
   * The source cascade is: explicit preference -> `Book.language` -> LOCAL
   * detection over the selected text -> `unknown-source`. The detection step is
   * what makes prediction work for an unlabelled book (the common case:
   * `books.language` is nullable and in practice missing). MyMemory rejects
   * `auto` as a source with a 403, so the guess has to be ours and offline.
   * `data.text` is passed verbatim: the detector refuses to guess on thin
   * evidence (under four words, a tie, no marker hits) and then the panel asks.
   *
   * The port is transport-only: nothing is cached here, because translated
   * selections are reading history scoped to the account in Rust.
   */
  async function handleTranslate(data: SelectionData): Promise<TranslationOutcome> {
    const preferences = await translationPort.getPreferences().catch(() => null);
    const resolution = resolveTranslationSourceWithDetection(
      preferences?.sourceLanguage ?? null,
      activeReadingBook?.language,
      data.text,
      settingsState.locale,
    );

    if (resolution.kind === 'no-translation') {
      return {
        status: 'refused',
        reason: resolution.reason,
        source: resolution.source,
        target: resolution.target,
        sourceOrigin: resolution.sourceOrigin,
      };
    }

    try {
      const text = await translationPort.translate({
        text: data.text,
        sourceLang: resolution.source,
        targetLang: resolution.target,
      });
      return {
        status: 'ok',
        text,
        source: resolution.source,
        target: resolution.target,
        sourceOrigin: resolution.sourceOrigin,
      };
    } catch (error) {
      return translationFailureOutcome(error);
    }
  }

  /**
   * Persist a language pair chosen inside the selection menu (T6).
   *
   * The write is the SAME call the settings tab makes — `savePreferences` — so
   * a pair chosen while reading is the pair Settings shows afterwards; a second
   * write path would be a second truth. It is deliberately NOT read back into a
   * local copy: `handleTranslate` re-reads preferences per request, which is
   * what makes the retranslation that follows pick the new pair up.
   *
   * `null` means "automatic" and is a real value, not an error: it clears the
   * preference and lets the cascade fall back to the book's language and to
   * local detection. A degenerate pair chosen here is not intercepted — the
   * next resolution refuses it with its own reason, which is the behaviour a
   * refusal already had.
   */
  async function handleChangeLanguages(next: {
    source: string | null;
    target: string | null;
  }): Promise<void> {
    try {
      await translationPort.savePreferences({
        sourceLanguage: next.source,
        targetLanguage: next.target,
      });
    } catch {
      // Best effort: a failed write must not stop the reader from reading the
      // retranslation with the pair they just picked.
    }
  }

  /**
   * Define a selection.
   *
   * Two languages travel, and they are DIFFERENT things:
   *
   * - `readerLang` is the language the definition must be WRITTEN in. It comes
   *   from the account's `definitionLanguage` preference (D6), falling back to
   *   the UI locale when that preference is `null` — which is the behaviour this
   *   had before the preference existed, so an account that never chose one is
   *   unaffected. It is never inferred from the book.
   * - `wordLang` is the language the SELECTED WORD is in. It comes from
   *   `Book.language`, because the backend uses it to pick the
   *   `{{lengua|XX}}` section of the Wiktionary page — the wrong section yields
   *   the wrong gloss, or none.
   *
   * When the book carries no language, `normalizeLanguageTag` returns `null` and
   * `null` is sent. That is not a silent failure: `SourceLanguage::from_optional`
   * in `commands/define.rs` documents the behaviour — the native step is SKIPPED
   * and the cascade assumes `en`, which is the measured worst case and the
   * fallback that keeps the answer non-empty. A reader with an unlabelled book
   * still gets a definition; they just lose the best step. The port's doc
   * comment carries the same note so a future caller cannot "fix" it by
   * guessing a language.
   *
   * The preference is read per request, not cached in the component: the reader
   * may change it in Settings and come straight back, and a definition that
   * ignored a just-chosen language would read as the setting being broken.
   *
   * The backend trims and can answer with an empty result; a rejection here is a
   * genuine transport or storage failure, which the toolbar renders as
   * "unreachable", distinct from "no definition".
   */
  async function handleDefine(data: SelectionData): Promise<DefinitionResult> {
    // Wrapped, not chained: an adapter whose `getPreferences` is missing or
    // throws synchronously would otherwise take the whole definition down with
    // it, and a preference lookup is never a reason to refuse to define a word.
    const preferences = await readDefinitionPreferences();
    return dictionaryLookupPort.define({
      text: data.text,
      readerLang: resolveDefinitionLanguage(preferences?.definitionLanguage, settingsState.locale),
      wordLang: normalizeLanguageTag(activeReadingBook?.language),
    });
  }
  async function readDefinitionPreferences(): Promise<TranslationPreferencesDto | null> {
    try {
      return await translationPort.getPreferences();
    } catch {
      return null;
    }
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
   *
   * The reason is ALSO pushed onto `window.__toolbarDismissals`, a bounded
   * ring the CDP specs read. It is not decoration: "the toolbar went away" is
   * satisfied both by a colour being applied and by a stray blur killing the
   * menu first, and the two are identical in the DOM. A spec that only asserts
   * the toolbar is gone passes against the bug — `readerSelectionFirstPress`
   * reads this list to tell the two apart.
   */
  function dismissToolbar(reason: string = 'unknown'): void {
    const wasOpen = showToolbar;
    const wasHighlight = openMenuState === 'highlight';
    const ring = ((window as unknown as { __toolbarDismissals?: string[] }).__toolbarDismissals ??=
      []);
    ring.push(reason);
    // Bounded so a long reading session cannot grow it without limit.
    if (ring.length > 200) ring.splice(0, ring.length - 200);
    dbg.epub.dismissToolbarCallCount++;
    dbg.epub.lastDismissTrigger = reason;
    // ONE state, so a dismissal of either menu closes BOTH surfaces. The
    // highlight menu follows through its `$effect`, which is what makes "the
    // selection toolbar closed" and "the highlight menu closed" the same fact.
    openMenuState = closeMenu(openMenuState, reason as OpenMenuReason);
    selectedText = '';
    selectionBounds = null;
    selectionContainer = null;
    logger.debug(
      'RW: dismissToolbar',
      { reason, wasOpen, wasHighlight, count: dbg.epub.dismissToolbarCallCount },
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
        onTranslate={handleTranslate}
        onChangeLanguages={handleChangeLanguages}
        onDefine={handleDefine}
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
    canGoNext={!nav.nextDisabled}
    canGoPrev={!nav.prevDisabled}
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
