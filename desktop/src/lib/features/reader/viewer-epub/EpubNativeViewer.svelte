<script lang="ts">
  import { onDestroy, untrack } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type {
    ReaderSettings,
    ReaderThemeMode,
    ReaderTextAlign,
    ReaderDirection,
  } from '$lib/shared/types';
  import { logger } from '$lib/shared/logger/Logger';
  import { debugState } from '$lib/shared/debug/debugState.svelte';
  import type { HighlightActionKind, HighlightActionOpts } from '$lib/shared/types/book';
  import { createEpubSpine } from '$lib/features/reader/viewer-epub/useEpubSpine.svelte';
  import { createEpubNavigation } from '$lib/features/reader/viewer-epub/useEpubNavigation.svelte';
  import { createEpubRender } from '$lib/features/reader/viewer-epub/useEpubRender.svelte';
  import { createEpubHighlights } from '$lib/features/reader/viewer-epub/useEpubHighlights.svelte';
  import { createEpubZoomTheme } from '$lib/features/reader/viewer-epub/useEpubZoomTheme.svelte';
  import { createEpubBridge } from '$lib/features/reader/viewer-epub/useEpubBridge.svelte';
  import { createEpubPagination } from '$lib/features/reader/viewer-epub/useEpubPagination.svelte';
  import type { PageBox } from '$lib/features/reader/viewer-epub/readerPagination';
  import { resolveFootnoteNote } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
  import { resolveReaderArrowIntent } from '$lib/features/reader/viewer-epub/keyboardNav';
  import {
    countPageTotal,
    pageNumberForSpine,
  } from '$lib/features/reader/viewer-epub/epubReachablePages';

  /**
   * How long a turn is queued for while a chapter's layout settles and any
   * position restore lands. A press made the instant a book opens arrives before
   * both; measured, the restore plus column layout completes within a few
   * hundred milliseconds of the frame existing. Bounded so a genuinely one-page
   * chapter still falls through to chapter navigation instead of waiting forever.
   */
  const DEFER_TURN_MAX_MS = 4000;

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
    onselectionclear?: (reason?: string) => void;
    isFullscreen?: boolean;
    onIframeMouseMove?: (x: number, y: number) => void;
    onIframeMouseLeave?: () => void;
    onIframeClick?: (x: number, isLink: boolean) => void;
    cursorHidden?: boolean;
    hasSelectionMenu?: boolean;
    onTocReady?: (
      entries: Array<{ id: string; title: string; depth: number; index?: number }>,
    ) => void;
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
      paginated: false,
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
  /** The element that really scrolls the chapter (see `epubVisibleText.ts`). */
  let scrollContainerEl = $state<HTMLDivElement | null>(null);
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
  // Paginated mode (phase 1). The flag is a persisted reader setting; the
  // geometry lives in `pagination` below.
  let paginated = $derived(readerSettings.paginated ?? false);

  let currentSpineIndex = $derived(navigation.currentSpineIndex);
  // The page box the pagination CSS and the pinned iframe height must agree on.
  // Declared as `$state` because the viewer's template reads it to pin the
  // iframe; `pagination` writes it on every measured change.
  let pageBox = $state<PageBox | null>(null);
  // A spine document is a page, so the spine — not the outline — is "of M".
  // The footer already percentages the spine; counting the TOC here printed 21
  // pages against a footer that said 43, and hid every chapter body from the
  // page field, since a partial NCX names only some documents.
  let spineLength = $derived(spine.getSpineHrefs().length || totalChapters);
  let displayTotal = $derived(countPageTotal(spineLength));
  // The header reports the chapter that is ON SCREEN, not the one navigation has
  // requested. A Next at a chapter's last page bumps `currentChapterIndex`
  // synchronously and falls through to chapter navigation, but the new chapter
  // only reaches the screen when its `srcdoc` has loaded (`markReady` ->
  // `setLastRenderedChapter`). Reading the requested index made the number
  // advance through 9, 10, 11… while `scrollLeft` stayed pinned to the old
  // chapter's maximum and the visible text never changed — the measured
  // "header advances, screen does not" desync. `lastRenderedChapter` is
  // committed-render state (the pagination lifecycle and the highlights overlay
  // already key on it), so it is the number that matches what the reader sees.
  // Before the first render commits (`< 0`) the requested index is the only
  // answer available, so it is the fallback.
  let displayCurrentPage = $derived.by(() => {
    const committed =
      lastRenderedChapter >= 0 ? lastRenderedChapter : navigation.currentChapterIndex;
    const page = pageNumberForSpine(committed, spineLength);
    return page || navigation.currentSpineIndex + 1;
  });

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
    getPageBox: () => (paginated ? pageBox : null),
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
    getScrollContainerEl: () => scrollContainerEl,
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
    onLocationChange: (cfi, pct) => {
      onLocationChange?.(cfi, pct);
    },
    onLocationContext: (ctx) => onLocationContext?.(ctx),
    onselection: (event) => onselection?.(event),
    onselectionclear: (reason) => onselectionclear?.(reason),
    handleEpubHighlightClick: (m) => highlights.handleEpubHighlightClick(m),
    handleEpubHighlightFailed: (m) => highlights.handleEpubHighlightFailed(m),
    handleEpubHighlightPlaced: (m) => highlights.handleEpubHighlightPlaced(m),
    syncIframeHeight: () => render.syncIframeHeight(),
    handlePaginationKey: (direction) => {
      if (direction === 'next') goToNext();
      else goToPrev();
    },
    handleExternalTocNavigate: (id) => navigation.handleExternalTocNavigate(id),
    handleSearchTargetLocator: (t, o) => navigation.handleSearchTargetLocator(t, o),
  });

  // Paginated mode. Created after `render`/`bridge` because its deps reach into
  // both; it writes `pageBox`, which `render.getPageBox` reads, so the
  // stylesheet and the pinned iframe height can never disagree.
  const pagination = createEpubPagination({
    getContainerEl: () => zoomContainerEl,
    getChapterRoot: () => iframeEl?.contentDocument?.documentElement ?? null,
    isPaginated: () => paginated,
    refreshReaderStyles: () => render.refreshReaderStyles(),
    getPageBox: () => (paginated ? pageBox : null),
    // The composable is the only writer of the box; the viewer holds the only
    // reactive copy. A pull (`pageBox = pagination.pageBox` in an $effect) would
    // read and write the same state graph and Svelte aborts with
    // `effect_update_depth_exceeded` — verified live. Pushing through a callback
    // keeps the data flow one-directional.
    setPageBox: (box) => {
      pageBox = box;
    },
    onHeightModeChange: (pinned) => {
      // Leaving paginated mode restores the content-measured height; entering
      // clears it so the box (not a stale chapter height) drives the iframe.
      // The box itself is measured by `pagination.enter()`/`refresh()`.
      if (!pinned) {
        pageBox = null;
        iframeContentHeight = 0;
      }
    },
    emitPreciseLocation: () => bridge.emitPreciseLocation(),
    onPageTurn: () => onselectionclear?.('page-turn'),
    // The CFI/fragment restore is what places the reader at their stored
    // position on open; while it is in flight the offset is still the fresh
    // document's 0, so a turn taken there would step from a position about to be
    // overwritten. See `turn()` in the composable.
    isRestoring: () => navigation.pendingCfiScroll !== null || navigation.pendingFragment !== null,
  });

  function emitPreciseLocation(): void {
    bridge.emitPreciseLocation();
  }

  function handleContainerScroll(): void {
    // Both axes matter. In scrolling mode the container moves vertically; in
    // paginated mode it moves in the inline direction, and the location
    // emission has to follow either one so the footer percentage and the
    // highlight anchors do not freeze on a page turn.
    pagination.syncPageFromScroll();
    emitPreciseLocation();
    // U2: scrolling invalidates the selection anchor bounds the menu is
    // pinned to — the menu must not float over the new viewport.
    //
    // Contract §2.2 case 3 requires BOTH the menu to close AND the selection to
    // clear. `onselectionclear` only hides the menu: the parent's
    // `dismissToolbar` collapses nothing, and the frame's own `scroll` clear is
    // suppressed while `dragState === 'dragging'` (a scroll can be the tail of a
    // drag), so the chapter's selection survived a scroll with the toolbar gone.
    // This is the REAL scroll surface for scrolling mode: the iframe is sized to
    // the whole chapter (measured `scrollHeight === clientHeight`), so a scroll
    // never fires inside the frame and `onFrameScroll` never runs for the reader.
    // Collapse the frame's selection explicitly, exactly as the pointerdown and
    // colour paths already do.
    clearIframeSelection();
    onselectionclear?.('container-scroll');
  }
  function refreshReaderStyles(): void {
    render.refreshReaderStyles();
  }
  async function renderChapter(index: number): Promise<void> {
    return render.renderChapter(index);
  }

  export function goToPrev(): void {
    // A restore in flight owns the offset. The press is QUEUED rather than
    // applied (from a position the restore is about to overwrite) or dropped
    // (which loses the reader's input): it runs once the restore has landed, so
    // an immediate press advances exactly one page from the restored position.
    if (paginated && (pagination.isRestoring() || pagination.isLayoutSettling())) {
      deferTurn(-1);
      return;
    }
    // Paginated mode turns a screen at a time. At the chapter's first page the
    // turn falls through to the chapter itself, so the reader can keep going
    // backwards across a boundary instead of hitting a dead stop.
    if (paginated && pagination.goToPrevPage()) return;
    navigation.goToPrev();
  }
  export function goToNext(): void {
    if (paginated && (pagination.isRestoring() || pagination.isLayoutSettling())) {
      deferTurn(1);
      return;
    }
    if (paginated && pagination.goToNextPage()) return;
    navigation.goToNext();
  }

  /**
   * True while a press is already queued behind the layout/restore.
   *
   * A reader who presses Next twice during load should end up one page on, not
   * two chapters on. Without this every press spawns its own retry chain and the
   * whole burst fires the moment the layout lands. One press while queued is
   * already a request to advance; the rest are the same gesture repeated.
   */
  let turnQueued = false;

  /**
   * Run a turn once the chapter's layout has settled and any position restore
   * has landed.
   *
   * A press made the instant a book opens arrives while the columns are still a
   * single box and the CFI restore is still in flight. Applying it there steps
   * from an offset the restore is about to overwrite — measured live as
   * `scrollLeft` 0 -> 23310 (21 pages), the restore's own target. Queueing the
   * press until both settle turns it into exactly one page from the position the
   * reader is actually restored to. Bounded so a genuinely one-page chapter
   * still falls through to chapter navigation instead of retrying forever.
   */
  function deferTurn(delta: 1 | -1): void {
    if (turnQueued) return;
    turnQueued = true;
    const deadline = Date.now() + DEFER_TURN_MAX_MS;
    const retry = (): void => {
      if (!paginated) {
        turnQueued = false;
        return;
      }
      // A restore that starts (a new chapter's CFI) keeps the wait alive until
      // the deadline, so a slow restore is still honoured rather than skipped.
      if (pagination.isRestoring() || pagination.isLayoutSettling()) {
        if (Date.now() < deadline) {
          requestAnimationFrame(retry);
        } else {
          turnQueued = false;
          if (delta === 1) navigation.goToNext();
          else navigation.goToPrev();
        }
        return;
      }
      turnQueued = false;
      if (delta === 1 ? pagination.goToNextPage() : pagination.goToPrevPage()) return;
      if (delta === 1) navigation.goToNext();
      else navigation.goToPrev();
    };
    requestAnimationFrame(retry);
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
   * Read-only snapshot for the opt-in diagnostic panel (`ReaderDiagnosticPanel`).
   *
   * The two hard numbers are `pageBox`/`pageCount` (owned by `pagination`) and
   * `pendingCfiScroll`/`pendingFragment` (owned by `navigation`): the reader
   * cannot see either today, and the conflict between the restore and the turn
   * is exactly the pair. Nothing here writes.
   *
   * `page`/`totalPages` are the header's own numbers in scroll mode (TOC
   * entries, which is what the reader sees in the page field) and the chapter's
   * page grid in paginated mode, where "page 4 of 31" is the number that
   * actually describes the layout. Reporting the header's numbers in scroll mode
   * keeps the panel's "Page" row non-zero instead of printing `1/0` for the
   * default mode.
   *
   * `layoutSettled` reuses `pagination.isLayoutSettling()` — the turn gate's own
   * definition of "the columns are laid out" — so the panel cannot disagree with
   * the code that decides whether a turn is safe to honour.
   */
  export function getDiagnostics(): {
    mode: 'PAGINATED' | 'SCROLL';
    page: number;
    totalPages: number;
    scrollLeft: number;
    layoutSettled: boolean;
    restoring: boolean;
  } {
    const root = iframeEl?.contentDocument?.documentElement ?? null;
    const paginatedNow = paginated && pagination.pageCount > 0;
    return {
      mode: paginatedNow ? 'PAGINATED' : 'SCROLL',
      page: paginatedNow ? pagination.currentPage : displayCurrentPage,
      totalPages: paginatedNow ? pagination.pageCount : displayTotal,
      scrollLeft: root?.scrollLeft ?? zoomContainerEl?.scrollLeft ?? 0,
      layoutSettled: paginated ? !pagination.isLayoutSettling() : true,
      restoring: navigation.pendingCfiScroll !== null || navigation.pendingFragment !== null,
    };
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
    pagination.cleanup();
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

  // Paginated-mode lifecycle. One effect, two edges:
  //  - a mode change enters or leaves pagination (measure, pin, restyle);
  //  - a chapter render re-measures, because the new chapter is a new layout
  //    with its own page count, and the reader restarts at its first page.
  //
  // Deliberately NOT in scope here: zoom, font-size and orientation changes as
  // their own triggers. Those belong to phase 3, and the composable's guarded
  // box observer already covers a window resize on an already-laid-out chapter.
  let paginationMode = $state(false);
  // The chapter index the pagination state currently describes. A render of a
  // *different* chapter resets paging; a re-render of the same one does not.
  let paginationChapter = -1;
  $effect(() => {
    const active = paginated;
    if (active === paginationMode) return;
    paginationMode = active;
    // The container has to exist before a box can be measured; the render
    // effect runs after mount, and this one re-runs on the mode flip.
    // `untrack`: entering/leaving publishes a measured box, and letting that
    // write feed back into this effect's dependencies would loop.
    untrack(() => {
      if (active) {
        paginationChapter = lastRenderedChapter;
        pagination.enter();
      } else {
        pagination.exit();
      }
    });
  });

  $effect(() => {
    if (!paginated) return;
    // Re-measure whenever a chapter has just been laid out. `lastRenderedChapter`
    // is the render pipeline's own "this chapter is committed" signal, so
    // measuring on it avoids racing the srcdoc swap. The measure itself is
    // untracked for the same reason as the mode effect above.
    void lastRenderedChapter;
    if (lastRenderedChapter < 0) return;
    // Only a chapter CHANGE resets the reader to page 1. The pipeline also
    // re-renders the SAME chapter as its assets settle — measured on the real
    // app: three `srcdoc` swaps for one opening, with the column set growing
    // 9 -> 15 -> 25 pages between them. Resetting on each of those threw a
    // reader paging through the chapter back to page 1. A re-render of the
    // current chapter keeps the page index and only re-derives the count.
    const changedChapter = untrack(() => paginationChapter) !== lastRenderedChapter;
    untrack(() => {
      if (changedChapter) {
        paginationChapter = lastRenderedChapter;
        pagination.resetForChapter();
      } else {
        pagination.restoreAfterRerender();
      }
    });
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
      // Escape closes the menu AND the frame's selection. Leaving the range
      // selected kept the text highlighted with nothing to act on it (measured;
      // documented in `readerSelection.spec.ts` as a known defect).
      clearIframeSelection();
      onselectionclear?.('escape');
      return;
    }
    // The arrow contract lives in `keyboardNav.resolveReaderArrowIntent`: it
    // owns which keys page, when an editable context wins, and the auto-repeat
    // filter. Reading `e.key` directly here would bypass all three — most
    // importantly the repeat filter, which is what made a held arrow issue one
    // backend chapter extraction per key repeat.
    const intent = resolveReaderArrowIntent(e);
    if (intent === 'prevPage') goToPrev();
    else if (intent === 'nextPage') goToNext();
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
    const onFrameScroll = (): void => {
      // Only meaningful in paginated mode; scrolling mode already reports
      // through the parent container's own listener.
      if (!paginated) return;
      pagination.syncPageFromScroll();
      emitPreciseLocation();
      onselectionclear?.('frame-scroll');
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
      // Paginated mode scrolls the chapter document itself, so a page turn
      // fires `scroll` here and never on the (pinned) parent container. Without
      // this listener the footer percentage and the highlight anchors would not
      // follow a page turn. A capture listener catches the scroll on whichever
      // element the browser bubbles it from.
      doc.addEventListener('scroll', onFrameScroll, true);
    };
    const detach = (): void => {
      const doc = frame.contentDocument;
      if (!doc) return;
      doc.removeEventListener('mousemove', onMove as EventListener);
      doc.removeEventListener('mouseleave', onLeave);
      doc.removeEventListener('click', onClick as EventListener);
      doc.removeEventListener('scroll', onFrameScroll, true);
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
    <div
      data-testid="epub-scroll-container"
      bind:this={scrollContainerEl}
      class="flex-1 w-full min-h-0"
      class:overflow-y-auto={!paginated}
      class:pb-16={!paginated}
      class:overflow-x-auto={paginated}
      class:overflow-y-hidden={paginated}
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
        style:height={paginated && pageBox
          ? `${pageBox.height}px`
          : iframeContentHeight > 0
            ? `${iframeContentHeight}px`
            : 'auto'}
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
