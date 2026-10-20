/**
 * useEpubBridge — iframe bridge for EpubNativeViewer (PR5).
 * Extracts handleIframeMessage (stale pageNumber guard), scrollToCfi/Fragment,
 * emitPreciseLocation, epoch guard, initReader, and navigation side-effects
 * (externalTocNavigate, searchTargetLocator, continue) while preserving
 * byte-identical behavior.
 */
import { invoke } from '@tauri-apps/api/core';
import { onMount, untrack } from 'svelte';
import { debugState } from '$lib/shared/debug/debugState.svelte';
import { clearReaderError } from '$lib/stores/readerErrorState.svelte';
import { locatorFromCfi, locatorToJson, normalizeHref } from '$lib/shared/sync/LocatorCodec';
import { stripFragment } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import type { EpubChapterMeta } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import { isInlineColumnLayout } from '$lib/features/reader/viewer-epub/readerPagination';
import { handleError, ReaderError } from '$lib/shared/utils/errors';
import { logger } from '$lib/shared/logger/Logger';

export interface EpubMetadataExtract {
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

export type EpubBridgeDeps = {
  getMetadata: () => EpubMetadataExtract | null;
  setMetadata: (v: EpubMetadataExtract | null) => void;
  getIsLoading: () => boolean;
  setIsLoading: (v: boolean) => void;
  getError: () => string | null;
  setError: (v: string | null) => void;
  getIframeEl: () => HTMLIFrameElement | null;
  getZoomContainerEl: () => HTMLDivElement | null;
  getSpineHrefs: () => string[];
  getToc: () => EpubChapterMeta[];
  getBookId: () => string;
  getFilePath: () => string;
  getInitialLocation: () => string;
  getInitialPercentage: () => number;
  getSearchTargetLocator: () => string | null;
  getExternalTocNavigate: () => { id: string } | null;
  getTotalChapters: () => number;
  setTotalChapters: (v: number) => void;
  getCurrentChapterIndex: () => number;
  setCurrentChapterIndex: (v: number) => void;
  getCurrentSpineIndex: () => number;
  getPendingFragment: () => string | null;
  setPendingFragment: (v: string | null) => void;
  getPendingCfiScroll: () => string | null;
  setPendingCfiScroll: (v: string | null) => void;
  getLastRenderedChapter: () => number;
  setLastRenderedChapter: (v: number) => void;
  getLastContinueLocation: () => string | null;
  setLastContinueLocation: (v: string | null) => void;
  getOnTocReady?: () =>
    ((entries: Array<{ id: string; title: string; depth: number }>) => void) | undefined;
  onLocationChange?: (cfiLocation: string, percentage: number) => void;
  onLocationContext?: (ctx: { locator: string; percentage: number }) => void;
  onselection?: (event: {
    text: string;
    bounds: { left: number; top: number; right: number; bottom: number };
    container: { left: number; top: number; width: number; height: number };
    placement: string;
    rects: Array<{ left: number; top: number; width: number; height: number }>;
    pageNumber: number;
    cfi: string | null;
    quote?: string | null;
    chapterTitle?: string | null;
  }) => void;
  onselectionclear?: (reason?: string) => void;
  handleEpubHighlightClick: (msg: {
    id: string;
    x: number;
    y: number;
    color: string;
    text?: string;
    pageNumber: number;
  }) => void;
  handleEpubHighlightFailed: (msg: {
    id: string;
    reason: string;
    pageNumber: number;
    cfi?: string;
    color?: string;
  }) => void;
  handleEpubHighlightPlaced: (msg: { id: string; pageNumber: number }) => void;
  syncIframeHeight: () => void;
  /** A page-turn intent forwarded from the chapter frame (paginated mode). */
  handlePaginationKey?: (direction: 'next' | 'prev') => void;
  handleExternalTocNavigate: (id: string | null) => boolean;
  handleSearchTargetLocator: (
    target: string | null,
    options: { currentChapterIndex: number; totalChapters: number; tocLength: number },
  ) => { navigated: boolean; needsScroll: boolean; chapterIdx: number | null } | null;
};

export interface EpubIframeErrorData {
  msg?: unknown;
  url?: unknown;
  line?: unknown;
  col?: unknown;
  kind?: unknown;
}

/** Truncate an iframe error message to `max` chars (PII: never forward raw text). */
export function truncateIframeMsg(s: string, max = 200): string {
  return s.length > max ? s.slice(0, max) : s;
}

/**
 * U1: pure verdict for an `epub-selection` payload. A clear beats a stale
 * guard — empty text always clears, even with a late pageNumber; selections
 * *with* text from other chapters are dropped.
 */
export function classifySelectionMessage(
  data: { text?: unknown; pageNumber?: unknown },
  currentSpine: number,
): 'clear' | 'select' | 'drop' {
  if (!data.text) return 'clear';
  if (typeof data.pageNumber === 'number' && data.pageNumber !== currentSpine) return 'drop';
  return 'select';
}

/** Reduce a path/URL to its basename (mirrors sentryPiiScrubber — no home dirs). */
export function basename(p: string): string {
  const idx = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
  return idx >= 0 ? p.slice(idx + 1) : p;
}

/**
 * Pure mapper: iframe `epub-srcdoc-error` payload → `ReaderError` with
 * queryable context. IDs/counters only — never chapter text/HTML.
 */
export function mapIframeMessageToError(
  d: EpubIframeErrorData,
  bookId: string,
  chapterIndex: number,
): ReaderError {
  const msg = truncateIframeMsg(String(d.msg ?? ''), 200);
  const src = basename(String(d.url ?? ''));
  return new ReaderError(msg, 'READER_IFRAME_ERROR').withContext({
    format: 'epub',
    action: 'iframe_error',
    kind: d.kind ?? 'js',
    iframeSource: src,
    line: d.line ?? 0,
    col: d.col ?? 0,
    bookId,
    chapterIndex,
  });
}

export function createEpubBridge(deps: EpubBridgeDeps): {
  scrollToFragment(fragment: string | null): void;
  scrollToCfi(cfi: string): void;
  emitPreciseLocation(): void;
  handleIframeMessage(event: MessageEvent): void;
  initReader(): Promise<void>;
} {
  /**
   * In paginated mode a `scrollIntoView` moves the inline offset by whatever
   * geometry the target has, which lands the reader between two pages — measured
   * as a last page at offset 17728 against the exact 17760 the page grid needs
   * (the 32px body padding). Snapping the offset back onto the page grid after
   * the scroll is what keeps "page N" honest, and it is the phase-1 substitute
   * for phase 3's real CFI-to-page mapping.
   */
  function snapToPageGrid(): void {
    const iframeEl = deps.getIframeEl();
    const root = iframeEl?.contentDocument?.documentElement;
    if (!root || !isInlineColumnLayout(iframeEl?.contentDocument ?? null)) return;
    const w = root.clientWidth;
    if (w <= 0) return;
    const snapped = Math.round(root.scrollLeft / w) * w;
    if (snapped !== root.scrollLeft) root.scrollLeft = snapped;
  }

  /** Scroll iframe to fragment anchor (preserved #frag in toc.href). 3×rAF ensures layout. */
  function scrollToFragment(fragment: string | null): void {
    const iframeEl = deps.getIframeEl();
    const zoomContainerEl = deps.getZoomContainerEl();
    // Consumed by the attempt, like `scrollToCfi`: an early return must not leave
    // `pendingFragment` set, or the turn guard reading it refuses every page turn.
    if (!fragment || !iframeEl?.contentDocument) {
      deps.setPendingFragment(null);
      return;
    }
    const doc = iframeEl.contentDocument;
    const target = doc.getElementById(fragment);
    if (!target) {
      logger.warn('epub-frag: fragment not found', { fragment }, 'reader');
      deps.setPendingFragment(null);
      return;
    }
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          // `smooth` animates toward the target and keeps adjusting afterwards,
          // which fights the page grid; a page layout wants an immediate,
          // exact landing.
          const paginated = isInlineColumnLayout(doc);
          target.scrollIntoView({ block: 'start', behavior: paginated ? 'auto' : 'smooth' });
          if (paginated) {
            snapToPageGrid();
            return;
          }
          if (zoomContainerEl) {
            const rect = target.getBoundingClientRect();
            const containerRect = zoomContainerEl.getBoundingClientRect();
            if (rect.top < containerRect.top || rect.bottom > containerRect.bottom) {
              target.scrollIntoView({ block: 'center', behavior: 'auto' });
            }
          }
        });
      });
    });
    deps.setPendingFragment(null);
  }

  function scrollToCfi(cfi: string): void {
    const metadata = deps.getMetadata();
    const iframeEl = deps.getIframeEl();
    // The restore is CONSUMED by the attempt, whatever it resolves to. Leaving
    // `pendingCfiScroll` set on an early return (no bridge, unresolvable CFI)
    // would report a restore in flight forever, and the turn guard that keys on
    // it would then refuse every page turn for the life of the chapter.
    if (!metadata || !iframeEl?.contentDocument || !iframeEl.contentWindow) {
      deps.setPendingCfiScroll(null);
      return;
    }
    const doc = iframeEl.contentDocument;
    const bridge = (
      iframeEl.contentWindow as Window & {
        __cfiBridge?: { cfiToRange: (cfi: string, href: string, doc: Document) => Range | null };
      }
    ).__cfiBridge;
    const rawChapterHref =
      deps.getSpineHrefs()[deps.getCurrentSpineIndex()] ??
      stripFragment(deps.getToc()[deps.getCurrentChapterIndex()]?.href ?? '');
    const chapterHref = normalizeHref(rawChapterHref);
    if (!bridge || !chapterHref) {
      deps.setPendingCfiScroll(null);
      return;
    }

    try {
      const range = bridge.cfiToRange(cfi, chapterHref, doc);
      if (!range || !range.startContainer) {
        deps.setPendingCfiScroll(null);
        return;
      }
      const targetNode =
        range.startContainer.nodeType === Node.TEXT_NODE
          ? range.startContainer.parentElement
          : (range.startContainer as Element);
      if (targetNode) {
        const paginated = isInlineColumnLayout(doc);
        (targetNode as Element).scrollIntoView({
          block: 'center',
          behavior: paginated ? 'auto' : 'smooth',
        });
        if (paginated) snapToPageGrid();
      }
      deps.setPendingCfiScroll(null);
    } catch (err) {
      deps.setPendingCfiScroll(null);
      logger.warn(
        'epub-cfi: scrollToCfi failed',
        { error: err instanceof Error ? err.message : String(err) },
        'reader',
      );
    }
  }

  /** Emit the precise CFI at the top visible text node in the chapter iframe. */
  function emitPreciseLocation(): void {
    const metadata = deps.getMetadata();
    const iframeEl = deps.getIframeEl();
    if (!metadata || !iframeEl?.contentDocument || !iframeEl.contentWindow) return;

    const doc = iframeEl.contentDocument;
    const bridge = (
      iframeEl.contentWindow as Window & {
        __cfiBridge?: {
          rangeToCFI: (range: Range, href: string, document: Document) => string | null;
        };
      }
    ).__cfiBridge;
    const rawChapterHref =
      deps.getSpineHrefs()[deps.getCurrentSpineIndex()] ??
      stripFragment(deps.getToc()[deps.getCurrentChapterIndex()]?.href ?? '');
    const chapterHref = normalizeHref(rawChapterHref);
    if (!bridge || !chapterHref) return;

    const nodes: Text[] = [];
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
    let node = walker.nextNode();
    while (node) {
      if ((node.nodeValue ?? '').trim().length > 0) nodes.push(node as Text);
      node = walker.nextNode();
    }
    const chapterChars = nodes.reduce((total, textNode) => total + (textNode.data?.length ?? 0), 0);
    if (chapterChars <= 0) return;

    const visibleNode = nodes.find((textNode) => {
      const range = doc.createRange();
      range.selectNodeContents(textNode);
      const rect = range.getBoundingClientRect();
      // The visibility window is axis-dependent. Scrolling mode moves the
      // reader vertically, so "visible" is the vertical band. Paginated mode
      // lays the columns out across the inline axis and keeps every node inside
      // the vertical band, so the vertical test alone would always match the
      // first text node and the emitted location would freeze on page one. The
      // axis is read from the chapter root's own overflow — which is what the
      // pagination stylesheet sets — rather than from a mode flag duplicated
      // here, so the two can never disagree.
      if (isInlineColumnLayout(doc)) {
        // The inline scroller is the chapter root, not the parent container:
        // the parent is pinned to one page box while the column overflow lives
        // in this document.
        const root = doc.documentElement;
        const offset = root.scrollLeft;
        const width = root.clientWidth || iframeEl.clientWidth;
        return rect.right > offset && rect.left < offset + width;
      }
      return rect.bottom >= 0 && rect.top <= (iframeEl?.clientHeight ?? window.innerHeight);
    });
    if (!visibleNode) return;

    const range = doc.createRange();
    range.setStart(visibleNode, 0);
    range.setEnd(visibleNode, Math.min(1, visibleNode.data.length));
    const preciseCfi = bridge.rangeToCFI(range, chapterHref, doc);
    if (!preciseCfi) return;

    const charOffset = nodes
      .slice(0, nodes.indexOf(visibleNode))
      .reduce((total, textNode) => total + textNode.data.length, 0);
    const locator = locatorFromCfi(deps.getSpineHrefs(), preciseCfi, {
      chapterChars,
      charOffset,
    });
    if (!locator) return;

    const progression = locator.locations.progression ?? 0;
    const percentage =
      ((deps.getCurrentSpineIndex() + progression) / deps.getTotalChapters()) * 100;
    deps.onLocationChange?.(preciseCfi, percentage);
    deps.onLocationContext?.({ locator: locatorToJson(locator), percentage });
  }

  /**
   * The selection the reader was last told about, as `spine|cfi`.
   *
   * A release emits its selection twice (immediate, then debounced). The pair
   * must collapse into one menu, and the two forms are not byte-identical (the
   * immediate one carries no rects), so they cannot be compared as payloads.
   * This records the last *opened* selection so its own debounced twin is
   * dropped, and is cleared by any explicit clear so a reader selecting the
   * same words again gets a menu.
   */
  let lastSelectionKey: string | null = null;

  function handleIframeMessage(event: MessageEvent): void {
    if (!event.data || typeof event.data !== 'object') return;

    if (debugState.enabled) {
      debugState.epub.postMessageCount++;
      debugState.epub.lastRawMessage = {
        type: String(event.data.type ?? ''),
        pageNumber: typeof event.data.pageNumber === 'number' ? event.data.pageNumber : null,
        hasText: typeof event.data.text === 'string' && event.data.text.length > 0,
        textPreview: typeof event.data.text === 'string' ? event.data.text.slice(0, 60) : '',
        hasBounds: !!event.data.bounds,
        hasContainer: !!event.data.container,
        hasCfi: typeof event.data.cfi === 'string' && event.data.cfi.length > 0,
        cfiPreview: typeof event.data.cfi === 'string' ? event.data.cfi.slice(0, 60) : '',
      };
      debugState.epub.guardResult = 'none';
    }

    if (event.data.type === 'epub-srcdoc-error') {
      handleError(
        mapIframeMessageToError(event.data, deps.getBookId(), deps.getCurrentChapterIndex()),
        'reader',
      );
      return;
    }

    if (event.data.type === 'epub-resize') {
      deps.syncIframeHeight();
      return;
    }

    if (event.data.type === 'epub-pagination-key') {
      // The chapter frame owns the keydown once the frame is focused (see the
      // pagination key-routing script in `buildChapterSrcdoc`); it forwards the
      // intent here so the viewer performs a real page turn instead of the
      // browser's few-dozen-pixel native scroll.
      deps.handlePaginationKey?.(event.data.direction === 'next' ? 'next' : 'prev');
      return;
    }

    if (event.data.type === 'epub-highlight-click') {
      deps.handleEpubHighlightClick(event.data);
      return;
    }

    if (event.data.type === 'epub-hl-failed') {
      deps.handleEpubHighlightFailed(event.data);
      return;
    }

    if (event.data.type === 'epub-hl-placed') {
      deps.handleEpubHighlightPlaced(event.data);
      return;
    }

    if (event.data.type !== 'epub-selection') {
      if (debugState.enabled) debugState.epub.guardResult = 'drop-unknown-type';
      return;
    }

    {
      const cfiPreview =
        typeof event.data.cfi === 'string' ? event.data.cfi.slice(0, 40) : '(null)';
      logger.debug(
        'epub-sel: received page',
        {
          pageNumber: event.data.pageNumber,
          currentSpine: deps.getCurrentSpineIndex(),
          toc: deps.getCurrentChapterIndex(),
          cfi: cfiPreview,
        },
        'reader',
      );
    }

    // U1: a clear beats a stale guard. Empty-text messages close the menu
    // even when they arrive late from the previous chapter; selections *with*
    // text from old chapters are still dropped below.
    const verdict = classifySelectionMessage(event.data, deps.getCurrentSpineIndex());
    if (verdict === 'clear') {
      // The frame's own handler names why the selection ended (`scroll`,
      // `inside-click`, `escape`, …). Forwarding it is what turns "the menu did
      // not close" from an unattributable symptom into a logged event with a
      // source.
      const clearReason = typeof event.data.reason === 'string' ? event.data.reason : undefined;
      if (debugState.enabled) {
        debugState.epub.guardResult = 'pass-clear';
        debugState.epub.emptyTextMessageCount++;
      }
      logger.debug(
        'epub-sel: clear received',
        { reason: clearReason ?? '(none)', pageNumber: event.data.pageNumber ?? null },
        'reader',
      );
      debugState.epub.onselectionclearCalled++;
      lastSelectionKey = null;
      deps.onselectionclear?.(clearReason);
      return;
    }

    if (verdict === 'drop') {
      if (debugState.enabled) debugState.epub.guardResult = 'drop-chapter-mismatch';
      return;
    }

    if (!deps.onselection) {
      if (debugState.enabled) debugState.epub.guardResult = 'drop-no-handler';
      return;
    }

    if (debugState.enabled) debugState.epub.guardResult = 'pass';
    debugState.epub.onselectionCalled++;
    debugState.epub.rectCount = Array.isArray(event.data.rects) ? event.data.rects.length : 0;

    const resolvedPageNumber =
      typeof event.data.pageNumber === 'number'
        ? event.data.pageNumber
        : deps.getCurrentSpineIndex();
    const resolvedCfi = typeof event.data.cfi === 'string' ? event.data.cfi : null;
    const resolvedQuote = typeof event.data.quote === 'string' ? event.data.quote : null;
    const resolvedChapterTitle =
      typeof event.data.chapterTitle === 'string' ? event.data.chapterTitle : null;

    // A release emits the selection twice — an immediate form and the debounced
    // authoritative one. The reader must see ONE menu, and the two forms differ
    // (the immediate one carries no rects), so the pair cannot be collapsed by
    // comparing payloads. The pair is recognised by the page it belongs to plus
    // the fact that neither a clear nor a different chapter intervened: the
    // first form opens the menu, and the second is allowed through only when it
    // carries rects the first could not. A gesture on a *different* chapter
    // resets the flag, so a genuine second selection is never swallowed.
    const selectionKey = `${deps.getCurrentSpineIndex()}|${resolvedCfi ?? ''}`;
    const isFast = event.data.fast === true;
    if (!isFast && selectionKey === lastSelectionKey) {
      logger.debug(
        'epub-sel: duplicate selection ignored',
        {
          key: selectionKey.slice(0, 80),
          rects: Array.isArray(event.data.rects) ? event.data.rects.length : 0,
        },
        'reader',
      );
      return;
    }
    lastSelectionKey = selectionKey;

    deps.onselection({
      text: event.data.text,
      bounds: event.data.bounds,
      container: event.data.container,
      placement: 'epub-chapter',
      rects: event.data.rects ?? [],
      pageNumber: resolvedPageNumber,
      cfi: resolvedCfi,
      quote: resolvedQuote,
      chapterTitle: resolvedChapterTitle,
    });
  }

  async function initReader(): Promise<void> {
    deps.setIsLoading(true);
    deps.setError(null);

    try {
      const meta = await invoke<EpubMetadataExtract>('parse_epub', {
        filePath: deps.getFilePath(),
        bookId: deps.getBookId(),
      });

      deps.setMetadata(meta);
      deps.setTotalChapters(meta.totalChapters);

      const locAtInit = deps.getInitialLocation();
      const initialCfi = locAtInit;
      if (!locAtInit || locAtInit === '') {
        logger.debug(
          'epub-hl: initReader with empty initialLocation, deferring to continue effect if any',
          { chapter: deps.getCurrentChapterIndex() },
          'reader',
        );
      }
      const tocForInit =
        (meta as EpubMetadataExtract).toc ?? (meta as EpubMetadataExtract).chapters ?? [];
      if (initialCfi && initialCfi.startsWith('epubcfi(') && tocForInit.length > 0) {
        const spineMatch = /epubcfi\(\/6\/(\d+)!/.exec(initialCfi);
        if (spineMatch) {
          const spineOneBased = Number.parseInt(spineMatch[1]!, 10);
          const spineIdx = spineOneBased - 1;
          if (spineIdx >= 0) {
            const mapped = (() => {
              const byIndex = tocForInit.findIndex((c) => c.index === spineIdx);
              if (byIndex !== -1) return byIndex;
              logger.debug(
                'epub-toc: initReader spine not in TOC, fallback to spineIdx',
                { spineIdx, tocLen: tocForInit.length },
                'reader',
              );
              return null;
            })();
            const tocIdx = mapped !== null ? mapped : spineIdx;
            if (tocIdx >= 0 && tocIdx < tocForInit.length) {
              deps.setCurrentChapterIndex(tocIdx);
              deps.setPendingCfiScroll(initialCfi);
            } else if (mapped === null && tocIdx >= 0 && tocIdx < deps.getTotalChapters()) {
              logger.debug(
                'epub-toc: initReader fallback spineIdx as tocIdx',
                { spineIdx, tocIdx },
                'reader',
              );
              deps.setCurrentChapterIndex(tocIdx);
              deps.setPendingCfiScroll(initialCfi);
            }
          }
        }
      } else if (deps.getInitialPercentage() > 0 && deps.getInitialPercentage() < 100) {
        const chapterGuess = Math.floor(
          (deps.getInitialPercentage() / 100) * deps.getTotalChapters(),
        );
        deps.setCurrentChapterIndex(Math.min(chapterGuess, deps.getTotalChapters() - 1));
      }

      const onTocReady = deps.getOnTocReady?.();
      if (onTocReady) {
        const entries = tocForInit.map((ch) => ({
          id: ch.id,
          title: ch.label,
          depth: ch.depth ?? 0,
          index: ch.index,
        }));
        onTocReady(entries);
      }

      if (debugState.enabled) {
        invoke('index_epub_text', { bookId: deps.getBookId() }).catch((err: unknown) => {
          handleError(err, 'reader', {
            format: 'epub',
            bookId: deps.getBookId(),
            action: 'index_text',
          });
        });
      }
      clearReaderError();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      deps.setError(msg);
      // Phase 1 of `reader-error-enrichment`: route through `handleError` so
      // the failure reaches Sentry with structured context. `handleError` calls
      // `errorState.setError(event)` (verified errors.ts:87) which drives the
      // global toast via ErrorToast. The previous `setReaderError(msg)` is
      // removed to avoid a double-toast (errorState is the single notification
      // surface for recoverable reader failures).
      handleError(err, 'reader', {
        format: 'epub',
        bookId: deps.getBookId(),
        action: 'init_reader',
      });
    } finally {
      deps.setIsLoading(false);
    }
  }

  // Lifecycle: message listener
  onMount(() => {
    logger.debug('epub-hl: VIEWER BUILD v4 epoch-guard active', {}, 'reader');
    logger.debug(
      'epub-hl: onMount',
      {
        bookId: deps.getBookId().slice(0, 8),
        initialLocation: (deps.getInitialLocation() ?? '').slice(0, 80),
        chapter: deps.getCurrentChapterIndex(),
      },
      'reader',
    );
    initReader();
    window.addEventListener('message', handleIframeMessage);
    return () => {
      window.removeEventListener('message', handleIframeMessage);
    };
  });

  // External TOC navigation
  $effect(() => {
    const nav = deps.getExternalTocNavigate();
    if (nav?.id && deps.getMetadata()) {
      deps.handleExternalTocNavigate(nav.id);
    }
  });

  // Search / "View in book" target navigation
  $effect(() => {
    const target = deps.getSearchTargetLocator();
    if (!target || !target.startsWith('epubcfi(') || !deps.getMetadata()) return;
    const result = deps.handleSearchTargetLocator(target, {
      currentChapterIndex: deps.getCurrentChapterIndex(),
      totalChapters: deps.getTotalChapters(),
      tocLength: deps.getToc().length,
    });
    if (!result) return;
    if (result.needsScroll && !result.navigated) {
      scrollToCfi(target);
    }
  });

  // ContinueReading: handle late initialLocation
  $effect(() => {
    const loc = deps.getInitialLocation();
    if (!loc || !loc.startsWith('epubcfi(') || !deps.getMetadata() || deps.getIsLoading()) return;
    if (loc === untrack(() => deps.getLastContinueLocation())) return;
    const result = deps.handleSearchTargetLocator(loc, {
      currentChapterIndex: untrack(() => deps.getCurrentChapterIndex()),
      totalChapters: deps.getTotalChapters(),
      tocLength: deps.getToc().length,
    });
    const currentIdx = untrack(() => deps.getCurrentChapterIndex());
    const pending = untrack(() => deps.getPendingCfiScroll());
    if (result) {
      logger.debug(
        'continue: initialLocation changed',
        { initialLocation: loc.slice(0, 80), chapterIdx: result.chapterIdx, current: currentIdx },
        'reader',
      );
    }
    deps.setLastContinueLocation(loc);
    if (result && result.needsScroll && !result.navigated) {
      if (pending !== loc) {
        logger.debug(
          'continue: already at chapter, scrolling to',
          { location: loc.slice(0, 60) },
          'reader',
        );
        deps.setPendingCfiScroll(loc);
        if (untrack(() => deps.getLastRenderedChapter()) === result.chapterIdx) {
          scrollToCfi(loc);
        }
      } else {
        logger.debug('continue: already at chapter with same pendingCfi, ignoring', {}, 'reader');
      }
    } else if (result?.navigated) {
      logger.debug(
        'continue: navigating to chapter',
        { chapterIdx: result.chapterIdx, from: currentIdx },
        'reader',
      );
    }
  });

  return {
    scrollToFragment,
    scrollToCfi,
    emitPreciseLocation,
    handleIframeMessage,
    initReader,
  };
}

export type EpubBridgeState = ReturnType<typeof createEpubBridge>;
