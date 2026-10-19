/**
 * useEpubPagination — owned state and behaviour for paginated mode.
 *
 * The mode is a reader setting (`ReaderSettings.paginated`), not viewer-local
 * state: it must persist across sessions and be shared by the settings panel,
 * the viewer and the header. The pure geometry and the turn policy live in
 * `readerPagination.ts`; the deferred work in `epubPaginationScheduler.ts`. This
 * composable owns only the mutable session state — the measured page box, the
 * derived count, the page the reader is on, and the page they have claimed by
 * turning — and it never touches the scroll container except through the
 * container passed in.
 *
 * Recalculation is phase 1: on mode entry, on chapter render, and when the
 * chapter's inline extent GROWS. Zoom, font-size and orientation changes are
 * deliberately NOT watched — that is phase 3.
 */
import {
  derivePageCount,
  isOnGrid,
  measurePageBox,
  pageAfterTurn,
  pageBoxKey,
  pageFromScrollLeft,
  resolveTurnOrigin,
  scrollLeftForPage,
  snapToGrid,
  type PageBox,
} from '$lib/features/reader/viewer-epub/readerPagination';
import { logger } from '$lib/shared/logger/Logger';
import {
  createFrameScheduler,
  createPaginationObservers,
  createTurnGate,
  createTurnReassert,
} from '$lib/features/reader/viewer-epub/epubPaginationScheduler';

export type EpubPaginationDeps = {
  /** The scroll container (the chapter's viewport). */
  getContainerEl: () => HTMLElement | null;
  /**
   * The chapter document's root element, which is the element the columns are
   * laid out on and therefore the one that actually scrolls in paginated mode.
   * The parent container does NOT scroll: it is pinned to the page box, and the
   * inline overflow lives inside the chapter's own document.
   */
  getChapterRoot?: () => HTMLElement | null;
  /** True when paginated mode is active. */
  isPaginated: () => boolean;
  /**
   * Re-render the chapter stylesheet, now that the page box changed. The
   * builder reads the box synchronously via `getPageBox`, so this is a
   * "restyle with the new values" call, not a parameterised one.
   */
  refreshReaderStyles: () => void;
  /** The page box the CSS builder must use; `null` in scrolling mode. */
  getPageBox: () => PageBox | null;
  /**
   * Publish a newly measured box. The viewer holds the reactive copy the
   * template reads to pin the iframe; this composable must NOT keep a second
   * `$state` copy of the same value, or an effect that mirrors one into the
   * other reads and writes the same graph and Svelte aborts with
   * `effect_update_depth_exceeded`.
   */
  setPageBox: (box: PageBox | null) => void;
  /** Tell the render pipeline the iframe height is pinned (paginated) or auto. */
  onHeightModeChange: (pinned: boolean) => void;
  /** Re-emit the location after a page turn, so the footer tracks the page. */
  emitPreciseLocation: () => void;
  /** Clear a stale text selection when the page moves. */
  onPageTurn?: () => void;
  /**
   * True while an asynchronous position restore (the CFI or fragment
   * `scrollIntoView`) is still in flight. The reader's stored position outranks
   * a turn pressed during load. Optional so a caller without a restore path
   * keeps the old behaviour.
   */
  isRestoring?: () => boolean;
};

export function createEpubPagination(deps: EpubPaginationDeps): {
  readonly pageBox: PageBox | null;
  readonly pageCount: number;
  readonly currentPage: number;
  refresh(): void;
  /**
   * Re-derive the page count from the chapter's live inline extent. True when
   * the count changed.
   */
  recount(): boolean;
  enter(): void;
  exit(): void;
  goToNextPage(): boolean;
  goToPrevPage(): boolean;
  goToPage(page: number): boolean;
  /**
   * True while a position restore is in flight, or the chapter's columns have
   * not laid out yet. The viewer queues a press for either; neither is "end of
   * chapter", so it must not fall through to chapter navigation.
   */
  isRestoring(): boolean;
  /** True while the chapter still lays out as a single column. */
  isLayoutSettling(): boolean;
  /** Sync the page number from the container's offset (a user drag/scroll). */
  syncPageFromScroll(): void;
  /** Re-derive after a chapter render, restoring the reader to page 1. */
  resetForChapter(): void;
  /**
   * Re-measure for a re-render of the chapter already on screen, keeping the
   * reader's page where the new count allows.
   */
  restoreAfterRerender(): void;
  cleanup(): void;
} {
  // Plain vars, not `$state`: the source is layout, which no rune can observe.
  // The reactive copy lives in the viewer, published through `setPageBox`.
  let pageBox: PageBox | null = null;
  let pageCount = 0;
  let currentPage = 1;
  let lastKey = '';
  let lastContentWidth = 0;
  /** The deferred-work scheduler: recount coalescing and turn re-assertion. */
  const scheduler = createFrameScheduler();
  /**
   * Defends the newest turn's offset against the restore writers that land a
   * frame later. Owned by the scheduler module, which already documents offset
   * re-assertion as its concern.
   */
  const turnReassert = createTurnReassert(scheduler);
  /** The two layout observers; the handlers defer to `scheduler`. */
  const observers = createPaginationObservers(scheduler, {
    onContainerResize: () => refresh(),
    onContentResize: () => {
      if (recount()) deps.emitPreciseLocation();
      reassertClaimedPage();
    },
  });
  /**
   * The page the reader chose in this chapter, or 0 when they have not turned a
   * page yet. A restore that lands after a turn must not move them, so this is
   * re-asserted until the chapter changes.
   */
  let claimedPage = 0;

  /**
   * The element that owns the inline scroll. In paginated mode the columns are
   * laid out on the chapter document's root, so that is what carries
   * `scrollWidth` and `scrollLeft`; the parent container is pinned to one page
   * and has nothing to scroll. Falling back to the container keeps the
   * composable usable before the frame has a document.
   */
  function scroller(): HTMLElement | null {
    const root = deps.getChapterRoot?.() ?? null;
    if (root) return root;
    return deps.getContainerEl();
  }

  /**
   * The two "do not turn yet" windows — a position restore in flight, or a
   * chapter not laid out yet. Owned by the scheduler module with the other
   * frame-timing helpers; see `createTurnGate` there for the measured why.
   */
  const turnGate = createTurnGate({
    isRestoring: () => deps.isRestoring?.() ?? false,
    getScroller: () => scroller(),
    getPageBox: () => pageBox,
  });

  /**
   * Re-derive the page count from the chapter's live inline extent. Returns true
   * when the count changed.
   *
   * The count always comes from the LIVE extent. Two early-returns used to guard
   * this on a cached `scrollWidth`, and both were unsound: the chapter document
   * is replaced while its assets settle, and a new document can lay out to the
   * SAME width as the previous one (measured 1110 -> 11100 -> 1110 across one
   * opening's srcdoc swaps). The cached match then returned with `pageCount`
   * describing the PREVIOUS document, which made `isOnLastPage` true at a page
   * that was not the end.
   *
   * The page index is only CLAMPED, never re-derived from the offset. The
   * content observer fires on every layout tick, including the tick where the
   * fresh document is briefly a single column (measured 1110 at t=1379ms before
   * growing to 18870); a count taken there would clamp a reader who had already
   * paged forward back to page 1 and, via the re-render restore, write
   * `scrollLeft = 0` — throwing them to the chapter's front.
   */
  function recount(): boolean {
    const el = scroller();
    const box = pageBox;
    if (!el || !box) return false;
    const width = el.scrollWidth;
    const count = derivePageCount(width, box.width);
    const previous = pageCount;
    const changed = count !== previous || width !== lastContentWidth;
    lastContentWidth = width;
    pageCount = count;
    if (currentPage > count && count > 0) currentPage = count;
    if (currentPage < 1) currentPage = 1;
    if (changed && previous !== count) {
      logger.debug(
        'epub-pagination: page count changed',
        { previous, pageCount: count, scrollWidth: width, currentPage },
        'reader',
      );
    }
    return changed;
  }

  /**
   * Re-measure the box and, when it actually changed, restyle and recount.
   * The key comparison is what makes this cheap enough to call from a resize
   * observer: a repeat observe of the same box is a no-op, so a page turn —
   * which fires the container's scroll listener, not a resize — never triggers
   * a restyle.
   */
  function refresh(): void {
    if (!deps.isPaginated()) return;
    const container = deps.getContainerEl();
    const box = measurePageBox(container);
    const key = pageBoxKey(box);
    if (key === lastKey) {
      // The box is unchanged, but the chapter may have grown under it.
      recount();
      return;
    }
    const first = lastKey === '';
    lastKey = key;
    pageBox = box;
    deps.setPageBox(box);
    const el = scroller();
    if (el) {
      const count = derivePageCount(el.scrollWidth, box.width);
      pageCount = count;
      lastContentWidth = el.scrollWidth;
      currentPage = pageFromScrollLeft(el.scrollLeft, box.width, count);
    }
    logger.debug(
      'epub-pagination: page box changed',
      {
        width: box.width,
        height: box.height,
        pageCount,
        currentPage,
        firstMeasure: first,
      },
      'reader',
    );
    deps.refreshReaderStyles();
  }

  /** Enter paginated mode: pin the height, measure, observe, restyle. */
  function enter(): void {
    deps.onHeightModeChange(true);
    lastKey = '';
    lastContentWidth = 0;
    refresh();
    observers.attach(deps.getContainerEl(), deps.getChapterRoot?.() ?? null);
  }

  /**
   * Put the offset back on the page the reader chose, when a restore has moved
   * it. The CFI/fragment restore scrolls the chapter to a stored position on up
   * to three nested animation frames, and `restoreAfterRerender` runs on every
   * asset-driven srcdoc swap — both can land after the reader has started
   * turning pages, and with a page layout that is a visible teleport (measured:
   * a turn to page 6 was followed by a restore to page 10). The claim is only
   * set by an actual turn, so entering a chapter still honours the stored
   * position, and it is cleared by `resetForChapter` so a new chapter starts
   * free.
   */
  function reassertClaimedPage(): void {
    if (claimedPage <= 0 || !deps.isPaginated()) return;
    const el = scroller();
    const box = pageBox;
    if (!el || !box || pageCount <= 0) return;
    const target = scrollLeftForPage(Math.min(claimedPage, pageCount), box.width, pageCount);
    if (el.scrollLeft === target) return;
    el.scrollLeft = target;
    currentPage = pageFromScrollLeft(el.scrollLeft, box.width, pageCount);
    deps.emitPreciseLocation();
  }

  /**
   * Re-measure after a re-render of the chapter the reader is ALREADY in.
   *
   * The chapter document is replaced wholesale when its assets settle (measured:
   * three swaps for one opening), and a new document starts at inline offset 0.
   * Re-deriving the count alone would leave the reader on the new count's page
   * 1 — which is exactly the "threw me back to page 1" behaviour. So the page
   * index is captured before the measure and restored after it, clamped to the
   * new count, which is the phase-1 substitute for phase 3's CFI-anchored
   * restore.
   */
  function restoreAfterRerender(): void {
    if (!deps.isPaginated()) return;
    const box = pageBox;
    const el = scroller();
    // A page the reader chose wins over wherever a restore wants to put them.
    const wanted = claimedPage > 0 ? claimedPage : currentPage;
    lastKey = '';
    lastContentWidth = 0;
    if (el && box) el.scrollLeft = scrollLeftForPage(wanted, box.width, pageCount || wanted);
    refresh();
    // Re-apply now that the count is known: the clamp depends on the new count.
    const after = scroller();
    if (after && pageBox && wanted > 1) {
      after.scrollLeft = scrollLeftForPage(wanted, pageBox.width, pageCount);
      currentPage = pageFromScrollLeft(after.scrollLeft, pageBox.width, pageCount);
    }
  }

  /** Leave paginated mode: drop the pin, stop observing, restore vertical. */
  function exit(): void {
    observers.detach();
    lastKey = '';
    lastContentWidth = 0;
    pageBox = null;
    deps.setPageBox(null);
    pageCount = 0;
    currentPage = 1;
    claimedPage = 0;
    deps.onHeightModeChange(false);
    deps.refreshReaderStyles();
  }

  /**
   * After a chapter render the container is back at offset 0 and the layout is
   * new, so the count must be re-derived and the reader returned to page 1.
   * Entering a chapter at its first page is the only defensible behaviour: the
   * previous chapter's page number is meaningless here, and phase 1 has no CFI
   * to page mapping to restore from (that is phase 3's "position must not be
   * lost on reflow").
   */
  function resetForChapter(): void {
    if (!deps.isPaginated()) return;
    lastKey = '';
    lastContentWidth = 0;
    currentPage = 1;
    // A new chapter clears the claim: the reader has not turned a page here yet,
    // so the CFI/fragment restore that places them within this chapter is free
    // to land wherever the book says.
    claimedPage = 0;
    // A fresh chapter starts at its first page. Anything a `scrollIntoView`
    // from the CFI/fragment restore path left behind (a mid-column offset) is
    // snapped back onto the page grid, so "page 2" always means the second page
    // box and not a third of one.
    const el = scroller();
    if (el) el.scrollLeft = 0;
    refresh();
    // The frame's content is a new document now; re-observe it, because the old
    // observation pointed at the previous chapter's root.
    observers.reobserveContent(deps.getChapterRoot?.() ?? null);
  }

  /**
   * Put page `page` (1-based) at the top of the box.
   *
   * The reader's index is set to the page that was ASKED for, then corrected to
   * wherever the offset actually landed — the two can differ while the chapter
   * is mid-reflow, the browser clamping the assignment to the extent it knows
   * at that instant. `currentPage` must describe the page on screen, because
   * that is what the header shows and what the next turn steps from.
   */
  function applyPage(page: number): boolean {
    const container = scroller();
    const box = pageBox;
    if (!container || !box) return false;
    const clamped = Math.min(Math.max(page, 1), Math.max(pageCount, 1));
    const target = scrollLeftForPage(clamped, box.width, pageCount);
    const moved = container.scrollLeft !== target;
    // A direct assignment, deliberately: a page turn does not touch Rust, so
    // there is nothing to wait for and a debounce or a smooth scroll would make
    // it feel slower than the scrolling mode it replaces.
    container.scrollLeft = target;
    // The assignment is not guaranteed to stick: while the chapter is still
    // laying out (the srcdoc swap and the column reflow), the browser can clamp
    // or re-anchor the offset to the extent it knows at that instant, landing
    // the reader between two pages. Snapping against the live client box is what
    // upholds the acceptance criterion — `scrollLeft % clientWidth === 0` after
    // every turn.
    container.scrollLeft = snapToGrid(container.scrollLeft, container.clientWidth || box.width);
    currentPage = pageFromScrollLeft(container.scrollLeft, box.width, pageCount);
    // The reader has now chosen a page in this chapter, so this page is claimed
    // for the rest of the chapter: a restore that lands afterwards (the CFI /
    // fragment scroll is scheduled on up to three nested animation frames, and
    // a re-render restore can arrive a second later) must not move them off it.
    // Measured on the real app: a turn to page 6 (`scrollLeft` 5550) was then
    // moved to 11100 — five pages past its target — by a restore that ran after
    // the assignment, which is exactly the "Next jumps pages" defect.
    claimedPage = currentPage;
    // Re-assert over the next few frames, for a restore already in flight that
    // lands after the claim. Several frames, not one: the CFI/fragment restore
    // schedules itself on up to three nested animation frames, so a single
    // re-assert can be overtaken by a restore that lands on frame 2 or 3
    // (measured: a turn to page 2 followed by the entry restore to page 9).
    turnReassert.claim(target, (claimed) => {
      if (!deps.isPaginated()) return;
      const el = scroller();
      if (el && el.scrollLeft !== claimed) {
        el.scrollLeft = claimed;
        currentPage = pageFromScrollLeft(el.scrollLeft, pageBox?.width ?? 0, pageCount);
        deps.emitPreciseLocation();
      }
    });
    deps.onPageTurn?.();
    deps.emitPreciseLocation();
    return moved;
  }

  /**
   * One page in `delta` direction, from the page the reader is ON.
   *
   * The origin comes from `resolveTurnOrigin`, which trusts the live offset only
   * when it sits on the page grid — see that function for why. Deriving the
   * origin from the raw offset unconditionally is what produced the jumps this
   * fixes. The count is refreshed first so the chapter's edge is known.
   *
   * Returns false when the turn would leave the chapter in that direction, so
   * the caller can fall through to chapter navigation.
   */
  function turn(delta: 1 | -1): boolean {
    if (!deps.isPaginated()) return false;
    // A turn pressed while the reader's stored position is still being restored
    // must not step from the pre-restore offset: the restore lands afterwards
    // and both write `scrollLeft` in the same window. The viewer QUEUES the
    // input (see `deferTurn`); this is the composable-side backstop for any
    // caller that does not.
    if (turnGate.isRestoring()) return false;
    const container = scroller();
    const box = pageBox;
    if (!container || !box || box.width <= 0) return false;
    recount();
    if (pageCount <= 0) return false;
    const live = container.clientWidth || box.width;
    if (live <= 0) return false;
    const origin = resolveTurnOrigin(container.scrollLeft, live, pageCount, currentPage);
    const next = pageAfterTurn(origin, delta, pageCount);
    return next === null ? false : applyPage(next);
  }

  /** One screen forward, inside the chapter. False at the chapter's end. */
  function goToNextPage(): boolean {
    return turn(1);
  }

  /** One screen back, inside the chapter. False at the chapter's start. */
  function goToPrevPage(): boolean {
    return turn(-1);
  }

  /** Jump to an absolute page, clamped into the chapter. */
  function goToPage(page: number): boolean {
    if (!deps.isPaginated() || pageCount <= 0) return false;
    return applyPage(page);
  }

  /**
   * Re-read the page from the container's offset, for a genuine user scroll.
   *
   * Called from the container's and the chapter's scroll listeners, so a
   * pointer/trackpad drag that lands mid-page still updates the number the
   * header shows. It deliberately IGNORES an offset that is not on the page
   * grid: the CFI restore, `snapToPageGrid` and the srcdoc swaps all write
   * `scrollLeft` while a chapter is settling, and adopting one of those
   * transient offsets moved the reader's page index to a value that had nothing
   * to do with where they were reading (measured: a settle left the offset at
   * 0 while the reader was on page 7, and the reader was then walked back to
   * page 1). A settled, user-driven position is always a whole number of page
   * boxes, so anything off-grid is treated as a reflow artifact and the page
   * index is left where navigation put it.
   */
  function syncPageFromScroll(): void {
    const container = scroller();
    const box = pageBox;
    if (!container || !box || pageCount <= 0) return;
    const live = container.clientWidth || box.width;
    if (live <= 0 || !isOnGrid(container.scrollLeft, live)) return;
    currentPage = pageFromScrollLeft(container.scrollLeft, box.width, pageCount);
    // A user scroll is a claim too: the reader chose this page, so a later
    // restore must not move them, and the re-assert guard is kept in step.
    claimedPage = currentPage;
  }

  function cleanup(): void {
    observers.detach();
  }

  return {
    get pageBox(): PageBox | null {
      return pageBox;
    },
    get pageCount(): number {
      return pageCount;
    },
    get currentPage(): number {
      return currentPage;
    },
    refresh,
    recount,
    enter,
    exit,
    goToNextPage,
    goToPrevPage,
    goToPage,
    isRestoring: turnGate.isRestoring,
    isLayoutSettling: turnGate.isLayoutSettling,
    syncPageFromScroll,
    resetForChapter,
    restoreAfterRerender,
    cleanup,
  };
}

export type EpubPaginationState = ReturnType<typeof createEpubPagination>;
