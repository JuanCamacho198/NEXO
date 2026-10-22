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
  import { attachFrameZoomWheel } from '$lib/features/reader/viewer-epub/epubZoomWheel';
  import {
    createEpubPageWheelGesture,
    resolveEpubPageWheelIntent,
  } from '$lib/features/reader/viewer-epub/epubPageWheel';
  import {
    contentImageFromEvent,
    isSelectionClick,
  } from '$lib/features/reader/viewer-epub/epubImageFit';
  import EpubImageViewer from '$lib/features/reader/viewer-epub/EpubImageViewer.svelte';
  import { documentsAfterInChapter } from '$lib/features/reader/viewer-epub/epubPageTurn';
  import { createBookPageCache } from '$lib/features/reader/viewer-epub/useBookPageCache.svelte';

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
  // Image viewer state. The URL is resolved in the chapter frame (its `src` is
  // already an `asset:` URL by the time the click arrives), so the parent only
  // carries it and the alt text across.
  let imageViewer = $state<{ src: string; alt: string } | null>(null);

  const spine = createEpubSpine({ getMetadata: () => metadata });
  const navigation = createEpubNavigation({
    getToc: () => spine.getToc(),
    getSpineHrefs: () => spine.getSpineHrefs(),
    getTotalChapters: () => totalChapters,
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

  /**
   * Render a document addressed by its spine index, which is the space the
   * restore paths and "open this chapter" callers speak. Rendering itself takes a
   * reading-order position, so convert here rather than teaching every caller
   * about the order.
   */
  function renderSpineChapter(spineIndex: number): Promise<void> {
    const position = navigation.orderForSpine(spineIndex);
    return position >= 0 ? render.renderChapter(position) : Promise.resolve();
  }

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
    spineIndexForOrder: (i: number) => navigation.resolve(i).spine,
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
    orderForSpine: (spineIdx) => navigation.orderForSpine(spineIdx),
    handleSearchTargetLocator: (t, o) => navigation.handleSearchTargetLocator(t, o),
  });

  // The reading order holds every spine document exactly once, so its length is
  // the DOCUMENT total — the unit scrolling mode counts, where a turn moves one
  // document. Paginated mode counts something else entirely: the columns the
  // chapter was sliced into. Both are correct and they are not interchangeable,
  // which is why the two are held separately and chosen by one condition.
  //
  // Declared BEFORE `pagination`, which reads it: it answers how many documents
  // of the chapter still follow the one on screen.
  const documentTotal = $derived(navigation.totalPages);

  // Paginated mode. Created after `render`/`bridge` because its deps reach into
  // both; it writes `pageBox`, which `render.getPageBox` reads, so the
  // stylesheet and the pinned iframe height can never disagree.
  const pagination = createEpubPagination({
    getContainerEl: () => zoomContainerEl,
    getChapterRoot: () => iframeEl?.contentDocument?.documentElement ?? null,
    isPaginated: () => paginated,
    refreshReaderStyles: () => render.refreshReaderStyles(),
    getPageBox: () => (paginated ? pageBox : null),
    // Everything the injected stylesheet is built from. `refresh()` restyles only
    // when this moved (or the page box did), so an observer re-run with nothing
    // changed cannot write the stylesheet back and re-enter the layout it was
    // measured from. `untrack`: the composable asks for it from an observer
    // callback, and tracking these settings there would make the observer a
    // dependency of the settings graph it feeds.
    getStylesKey: () =>
      untrack(() =>
        JSON.stringify({
          fontSize,
          fontFamily,
          themeMode,
          lineHeight,
          letterSpacing,
          paragraphSpacing,
          textAlign,
          direction,
          hyphenation,
          margins,
          zoom: zoomTheme.zoomLevel,
          // The box is written into the stylesheet (`column-width`, the page
          // height), so it is part of "what the last write was built from".
          pageBox: pagination.pageBox,
        }),
      ),
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
    // How many documents of the chapter being read follow the one on screen.
    // The chapter's columns cannot address them, so the page field has to — a
    // title page is ONE column, and answering the field from columns alone made
    // "1. Rusia" read "1 / 1" and disabled Next before its 57 356-character
    // body could be reached. See `epubPageTurn.ts`.
    getLaterDocuments: () => {
      // The document on screen is the render pipeline's committed index; before
      // its first commit the requested index is the only answer available, the
      // same fallback the page field uses.
      const at = untrack(() =>
        lastRenderedChapter >= 0 ? lastRenderedChapter : navigation.currentChapterIndex,
      );
      return untrack(() =>
        documentsAfterInChapter(at, documentTotal, (position) =>
          navigation.isChapterStart(position),
        ),
      );
    },
    // The CFI/fragment restore is what places the reader at their stored
    // position on open; while it is in flight the offset is still the fresh
    // document's 0, so a turn taken there would step from a position about to be
    // overwritten. See `turn()` in the composable.
    isRestoring: () => navigation.pendingCfiScroll !== null || navigation.pendingFragment !== null,
  });

  // "The layout has been measured" — the single condition the page field's two
  // numbers hang off. `derivePageCount` returns 0 (never 1) for an unmeasured
  // chapter on purpose, so 0 must fall back to the document count rather than
  // print "of 0" — and until the layout exists a column number has nothing to
  // address, so the document path answers instead.
  const paginatedMeasured = $derived(paginated && pagination.pageCount > 0);

  // Paginated mode replaces the total outright. Leaving the document count here
  // is what let the field claim "of 43" while the chapter on screen had 37
  // columns.
  //
  // The number is the CHAPTER's, not the document's: its columns plus one page
  // for every later document of the same chapter. A chapter is normally several
  // documents (title page, image spread, body) and only the one on screen has
  // columns, so a columns-only total read "of 1" over the title page and disabled
  // Next before the body. See `epubPageTurn.ts`.
  let displayTotal = $derived(paginatedMeasured ? pagination.chapterTotal : documentTotal);

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
  // answer available, so it is the fallback. Both are reading-order positions.
  //
  // Paginated mode replaces this number too: the reader is not on a document
  // there, it is on a COLUMN of one, and `pagination.currentPage` is the only
  // thing that knows which. Measured before this was wired up — chapter at
  // reading order 16, 25 columns, viewport 1200x779 — `scrollLeft` walked
  // 0 -> 6660 -> 3330 across five forward and three backward turns while this
  // field sat on 16 for all eight. The count existed and nobody asked for it.
  let displayCurrentPage = $derived.by(() => {
    if (paginatedMeasured) return pagination.currentPage;
    const committed =
      lastRenderedChapter >= 0 ? lastRenderedChapter : navigation.currentChapterIndex;
    return navigation.resolve(committed).order + 1;
  });

  // ── Whole-book page numbers ───────────────────────────────────────────────
  // The page field above answers "page N of M IN THIS CHAPTER". The footer's
  // percentage answers "how much text have I read". Neither says where the
  // reader is in the BOOK, which is what `getBookPagePosition` reports when the
  // reader asks for it.
  //
  // The count is per chapter and arrives as chapters are visited, never up
  // front: paginating the whole book on open is the multi-second cost this
  // deliberately avoids. See `bookPagination.ts` for the unit and the growth
  // contract.

  /** Chapter counts for the book on screen; survives chapter changes. */
  const bookPageCache = createBookPageCache();
  /**
   * Bumped whenever a chapter's count is recorded. The cache is a plain map read
   * from a `$derived`, so nothing would invalidate the derived on its own — this
   * is the one reactive edge between "a chapter was measured" and "the footer's
   * total moved".
   */
  let bookPagesVersion = $state(0);

  /**
   * The chapter behind the page field's numbers: the chapter ON SCREEN, whose
   * columns the pagination composable measured.
   */
  const bookPagesChapter = $derived.by((): number => {
    // Read the render pipeline's committed index as a real dependency: it is what
    // moves when the chapter on screen changes, and a read nested inside
    // `untrack` here would leave this derived frozen on its first chapter.
    const committed =
      lastRenderedChapter >= 0 ? lastRenderedChapter : navigation.currentChapterIndex;
    return untrack(() => navigation.resolve(committed).order);
  });

  /**
   * How many pages the chapter on screen is worth, in the SAME unit the page
   * field counts in: its columns in paginated mode, its documents while
   * scrolling. 0 means "not measured yet" and is never written to the cache.
   */
  const bookPagesChapterCount = $derived.by((): number => {
    if (!paginatedMeasured) {
      const at = untrack(() => navigation.resolve(bookPagesChapter).order);
      return Math.max(0, documentsInChapter(at));
    }
    return pagination.chapterTotal > 0 ? pagination.chapterTotal : 0;
  });

  /**
   * Documents the chapter at reading-order position `at` spans: the one on
   * screen plus every later one before the next chapter starts. The same rule
   * the page field's total uses, so the two agree in scrolling mode.
   */
  function documentsInChapter(at: number): number {
    return (
      1 +
      documentsAfterInChapter(at, documentTotal, (position) => navigation.isChapterStart(position))
    );
  }

  // Record the count as soon as it exists. This is the ONLY writer of the cache,
  // so the total cannot be built from a second, disagreeing source.
  //
  // Every dependency is read OUTSIDE `untrack`: a read nested inside it is not
  // tracked, and a version bump that skipped a changed chapter left the footer
  // frozen on the first chapter it had ever measured — measured live as
  // `measured: 1` while the chapter advanced 10 -> 15 and the label sat on
  // "3 / 3". Only the WRITE is untracked, which is what keeps the effect from
  // depending on the state it sets.
  $effect(() => {
    const chapter = bookPagesChapter;
    const count = bookPagesChapterCount;
    if (count <= 0) return;
    untrack(() => {
      const before = bookPageCache.position(chapter, displayCurrentPage);
      bookPageCache.record(chapter, count);
      const after = bookPageCache.position(chapter, displayCurrentPage);
      if (after.page !== before.page || after.total !== before.total) bookPagesVersion += 1;
    });
  });

  // Notify nothing and write nothing here: the parent reads this number through
  // `getBookPagePosition`, on its own reactive schedule. An earlier version
  // pushed a "it changed" callback into the workspace, which stored it back into
  // the state that drove this component — measured in the integration suite as
  // `effect_update_depth_exceeded`. The pull is the contract.

  // A chapter count belongs to ONE book's layout. Reopening a different book
  // must not inherit the previous one's totals; reopening the SAME book may,
  // which is why this keys on the book id rather than on mount.
  let bookPagesBookId = '';
  $effect(() => {
    const id = bookId;
    untrack(() => {
      if (id === bookPagesBookId) return;
      bookPagesBookId = id;
      bookPageCache.reset();
      bookPagesVersion += 1;
    });
  });

  // The unit depends on the reading mode, and a chapter counted in columns is not
  // a chapter counted in documents. Switching mode clears the cache instead of
  // adding columns to documents: a total mixing the two units is worse than a
  // total that has to fill in again.
  let bookPagesMode: boolean | null = null;
  $effect(() => {
    const mode = paginated;
    untrack(() => {
      // `null` is the first run: the real mode is not a change from anything.
      if (bookPagesMode === null || mode === bookPagesMode) {
        bookPagesMode = mode;
        return;
      }
      bookPagesMode = mode;
      bookPageCache.reset();
      bookPagesVersion += 1;
    });
  });

  /**
   * The footer's `N / M`, or null when nothing has been measured yet — the
   * footer must never print "N / 0". Reading `bookPagesVersion` is what makes
   * the panel re-derive after a chapter is recorded.
   */
  const bookPagePosition = $derived.by(() => {
    void bookPagesVersion;
    const position = bookPageCache.position(bookPagesChapter, displayCurrentPage);
    return position.total > 0 ? position : null;
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
  // The parent's restore path still calls `renderChapter(id)`, and a chapter id
  // names a spine document — so it goes through the order like every other jump.
  // `void` keeps it visibly reachable despite having no in-file caller today.
  async function renderChapter(spineIndex: number): Promise<void> {
    return renderSpineChapter(spineIndex);
  }
  void renderChapter;

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
  /**
   * The page field's "go to": in paginated mode the number typed is a page of
   * the CHAPTER on screen — a column of the document being read, or, past its
   * last column, one of the later documents of the same chapter (the field's own
   * total reaches that far). Handing it to `navigation.handleGoToPage` would read
   * the same number as a reading-order position and jump the reader to a
   * different document entirely — the same unit confusion the field itself had,
   * one layer down.
   *
   * The document path is kept for scroll mode and for a chapter whose layout is
   * not measured yet, where a column number has nothing to address.
   */
  export async function handleGoToPage(page: number): Promise<boolean> {
    if (paginatedMeasured) {
      const document = untrack(() =>
        navigation.resolve(
          lastRenderedChapter >= 0 ? lastRenderedChapter : navigation.currentChapterIndex,
        ),
      );
      const columns = pagination.pageCount;
      if (page > columns) {
        return navigation.handleGoToPage(document.order + Math.ceil(page - columns));
      }
      return pagination.goToPage(page);
    }
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
   * `N / M` across the whole book, or null when nothing is measured yet.
   *
   * The parent's chrome has no reaction to the viewer's internals, so the footer
   * is told when a chapter's count landed (`onBookPagesChange`) and pulls the
   * number on demand. Null is the honest "not ready" answer: the footer keeps the
   * percentage rather than printing "1 / 0".
   */
  export function getBookPagePosition(): { page: number; total: number } | null {
    return bookPagePosition;
  }
  /**
   * Read-only snapshot for the opt-in diagnostic panel (`ReaderDiagnosticPanel`).
   *
   * The two hard numbers are `pageBox`/`pageCount` (owned by `pagination`) and
   * `pendingCfiScroll`/`pendingFragment` (owned by `navigation`): the reader
   * cannot see either today, and the conflict between the restore and the turn
   * is exactly the pair. Nothing here writes.
   *
   * `page`/`totalPages` read the SAME values the page field shows
   * (`displayCurrentPage`/`displayTotal`), rather than deriving a second answer.
   * That is deliberate: the panel exists to explain what the reader sees, and a
   * panel that computed its own count would be free to disagree with the field —
   * which is exactly how the count came to be correct in this panel and wrong in
   * the header.
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
    return {
      mode: paginatedMeasured ? 'PAGINATED' : 'SCROLL',
      page: displayCurrentPage,
      totalPages: displayTotal,
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
      render.renderChapter(navigation.currentChapterIndex);
      return;
    }
    // Every setting that changes the text layout lands here, because this
    // effect already voids them to make them reactive (see the `void` list
    // above). `column-width` is pinned to the page box but the TEXT SIZE is not,
    // so a font-size or spacing change reflows the chapter into a different
    // number of columns while leaving the root's own border box — and therefore
    // its `ResizeObserver` — untouched. Nothing else would notice: measured
    // live, a step from 100% to 50% left the page field claiming "of 20" over a
    // chapter that had reflowed to 7 columns.
    //
    // The recount goes through `pagination.refresh()` rather than being written
    // here, so there is ONE path that restyles and derives the count: `refresh`
    // owns the restyle-then-measure order, the coalescing for a zoom burst, and
    // the re-read that catches a layout the browser had not committed yet.
    // Calling `recount()` directly would restyle here and then measure through
    // the composable's own cached box, which skips that re-read — which is how
    // a step to 75% came to print "of 37" over 38 columns and stay there.
    if (paginated) {
      // `refresh` restyles only because a key in its `getStylesKey` moved: this
      // effect runs for a settings change, but it can also re-run for a chapter
      // or loading transition that changed none of them, and a restyle is a
      // layout write. Skipping it there is what keeps the write out of the
      // observer's path.
      pagination.refresh();
      return;
    }
    refreshReaderStyles();
  });

  // Paginated-mode lifecycle. One effect, two edges:
  //  - a mode change enters or leaves pagination (measure, pin, restyle);
  //  - a chapter render re-measures, because the new chapter is a new layout
  //    with its own page count, and the reader restarts at its first page.
  //
  // A layout change on an ALREADY-open chapter is the settings effect above:
  // zoom, font size, font family, line height, letter spacing, paragraph
  // spacing, margins, alignment, direction, hyphenation and theme all change
  // the column count without changing the page box, so none of them produces a
  // resize for the observer below to see.
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
    // The image viewer owns the keyboard while it is open; its own handler runs
    // first and stops propagation, so this is a belt against a missed stop.
    if (imageViewer) return;
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
    /**
     * Tears down the in-frame ctrl+wheel forwarder. One slot per effect run, so
     * a `srcdoc` swap cannot leave a second listener counting the same notch.
     */
    let detachFrameWheel: (() => void) | null = null;
    /**
     * The coalescing state for "one wheel notch is one page".
     *
     * One per effect run, next to the listener that owns it: a `srcdoc` swap
     * re-attaches into a NEW document, and a window shared across documents
     * would let the last notch of the outgoing chapter swallow the first notch
     * of the incoming one.
     */
    const pageWheelGesture = createEpubPageWheelGesture();
    /**
     * A plain (unmodified) wheel over the chapter turns the page in paginated
     * mode.
     *
     * In this mode the chapter is columns on `documentElement` and the root has
     * `overflow-y: hidden` on purpose, so a vertical wheel has nothing to scroll
     * and used to do nothing at all — measured live, `scrollLeft` stayed at 1276
     * across a gesture while the Next control moved it by a column.
     *
     * The decision is pure (`epubPageWheel.ts`) and excludes ctrl/meta, so this
     * never competes with `attachFrameZoomWheel` below: a zoom gesture is left
     * completely untouched for it. `defaultPrevented` is honoured because the
     * zoom listener runs first on this same document.
     *
     * The turn itself is delegated to `goToNext`/`goToPrev` — the functions the
     * Next control and the arrow keys call — rather than writing `scrollLeft`
     * here. Those own the restore gate, the queued press and the claimed page,
     * and a second writer of the offset is how a page turn comes to disagree
     * with the header.
     */
    const onPageWheel = (event: Event): void => {
      const wheel = event as WheelEvent;
      if (wheel.defaultPrevented) return;
      const intent = resolveEpubPageWheelIntent(
        wheel,
        { paginated, pageCount: pagination.pageCount },
        pageWheelGesture,
        performance.now(),
      );
      if (intent === 'none') return;
      // Claimed so the browser's own scroll cannot also act on this notch. Safe
      // to do only after the decision said "turn": in scrolling mode this
      // returns 'none' and the gesture is never touched.
      wheel.preventDefault();
      if (intent === 'next') goToNext();
      else goToPrev();
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
      // Enlarge a content-sized image BEFORE the tercile/reader click dispatch:
      // that dispatch pages forward on a left-third click, so enlarging after it
      // would turn the page under the picture. The guards (link, selection,
      // ornament) live in `epubImageFit` so they are testable without a frame.
      const image = isLink || isSelectionClick(e) ? null : contentImageFromEvent(e);
      if (image) {
        e.preventDefault();
        imageViewer = { src: image.currentSrc || image.src, alt: image.alt || '' };
        return;
      }
      const rect = frame.getBoundingClientRect();
      onIframeClick(rect.left + e.clientX, isLink);
    };
    const attach = (): void => {
      const doc = frame.contentDocument;
      if (!doc) return;
      // A `load` fires on every `srcdoc` swap while this effect is still the
      // current run, so the listeners below can be added again to the SAME
      // document. They are guarded by removing first rather than by a flag: a
      // duplicate wheel listener would count one notch twice, and a duplicate
      // mouse/click listener was already a latent hazard here.
      doc.removeEventListener('mousemove', onMove as EventListener);
      doc.removeEventListener('mouseleave', onLeave);
      doc.removeEventListener('click', onClick as EventListener);
      doc.removeEventListener('scroll', onFrameScroll, true);
      detachFrameWheel?.();
      doc.addEventListener('mousemove', onMove as EventListener);
      doc.addEventListener('mouseleave', onLeave);
      doc.addEventListener('click', onClick as EventListener);
      // Removed first for the same reason as the listeners above: a duplicate
      // wheel listener would count one notch twice.
      doc.removeEventListener('wheel', onPageWheel, { capture: false });
      doc.addEventListener('wheel', onPageWheel, { passive: false });
      // Paginated mode scrolls the chapter document itself, so a page turn
      // fires `scroll` here and never on the (pinned) parent container. Without
      // this listener the footer percentage and the highlight anchors would not
      // follow a page turn. A capture listener catches the scroll on whichever
      // element the browser bubbles it from.
      doc.addEventListener('scroll', onFrameScroll, true);
      // Ctrl+wheel over the chapter. The window listener in `ReaderWorkspace`
      // never sees a gesture dispatched inside this document, so it has to be
      // forwarded from here; a plain wheel is left alone and keeps scrolling.
      // The current zoom is read through the viewer, not through a captured
      // prop, so a step taken by the keys or the menu is honoured.
      detachFrameWheel = attachFrameZoomWheel(doc, () => getZoomPercent());
      // Apply the current zoom to this document's plates.
      //
      // `attach` runs here precisely because it is bound to the frame's `load`,
      // and that is the only signal that a NEW document exists. The viewer's
      // settings effect cannot cover it: it early-returns when the requested
      // chapter already matches the rendered one, which is exactly the state a
      // re-render leaves behind — measured live as a spread that stayed at 729px
      // while the zoom label said 75%, with no plate marker in the document.
      refreshReaderStyles();
      // A plate's size is a multiple of its own `naturalWidth`, which is 0 until
      // the image decodes — and `load` on the FRAME fires before its images do.
      // Without this, an image-only chapter opened at 75% would render at
      // natural size until the reader touched a control.
      for (const img of Array.from(doc.querySelectorAll('img'))) {
        if (img.complete && img.naturalWidth > 0) continue;
        img.addEventListener('load', onPlateLoad);
      }
    };
    const onPlateLoad = (): void => {
      refreshReaderStyles();
    };
    const detach = (): void => {
      detachFrameWheel?.();
      detachFrameWheel = null;
      const doc = frame.contentDocument;
      if (!doc) return;
      doc.removeEventListener('mousemove', onMove as EventListener);
      doc.removeEventListener('mouseleave', onLeave);
      doc.removeEventListener('click', onClick as EventListener);
      doc.removeEventListener('scroll', onFrameScroll, true);
      doc.removeEventListener('wheel', onPageWheel, { capture: false });
      for (const img of Array.from(doc.querySelectorAll('img'))) {
        img.removeEventListener('load', onPlateLoad);
      }
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
    {#if imageViewer}
      <EpubImageViewer
        src={imageViewer.src}
        alt={imageViewer.alt}
        onClose={() => (imageViewer = null)}
        {t}
      />
    {/if}
  {/if}
</div>
