/**
 * epubPaginationScheduler — frame-coalesced work, offset re-assertion, and
 * layout observation for paginated mode.
 *
 * Extracted from `useEpubPagination.svelte.ts` because these concerns share one
 * idea and no other: work that must happen OFF the frame in which it was
 * requested, and the observation of the two elements whose resize drives it.
 *
 * ## Why every function here defers
 *
 * 1. **Reading `scrollWidth` inside a `ResizeObserver` callback** forces a
 *    layout flush while the observer is still delivering entries, which Chromium
 *    reports as `ResizeObserver loop completed with undelivered notifications`.
 *    On a paginated chapter this fired on every layout tick and filled the
 *    console (measured on the real app). A `requestAnimationFrame` moves the
 *    read after the delivery, where the layout is quiescent.
 * 2. **A page turn's offset assignment can be overtaken.** The chapter's
 *    `srcdoc` is replaced as its assets settle, and the CFI/fragment restore
 *    scrolls to a stored position on up to three nested animation frames. Both
 *    write `scrollLeft` after a turn has already set it — measured as a turn to
 *    page 6 being moved to 11100 (five pages past its target).
 *
 * The coalescing falls back to a direct call where `requestAnimationFrame` is
 * unavailable (jsdom under the unit tests), so the composable keeps its
 * synchronous behaviour under test.
 */

/** Schedules the same callback repeatedly on the next frames, or directly. */
export interface FrameScheduler {
  /**
   * Run `fn` on the next frame, unless an identical call is already queued —
   * a burst of observer callbacks collapses into a single run.
   */
  coalesce(key: string, fn: () => void): void;
  /** Run `fn` now and on each of the next `frames` animation frames. */
  chain(fn: () => void, frames: number): void;
}

export function createFrameScheduler(): FrameScheduler {
  const pending = new Set<string>();

  return {
    coalesce(key: string, fn: () => void): void {
      if (typeof requestAnimationFrame !== 'function') {
        fn();
        return;
      }
      if (pending.has(key)) return;
      pending.add(key);
      requestAnimationFrame(() => {
        pending.delete(key);
        fn();
      });
    },

    chain(fn: () => void, frames: number): void {
      if (typeof requestAnimationFrame !== 'function') {
        fn();
        return;
      }
      const step = (framesLeft: number): void => {
        requestAnimationFrame(() => {
          fn();
          if (framesLeft > 0) step(framesLeft - 1);
        });
      };
      step(frames);
    },
  };
}

/**
 * How many extra frames a turn's offset is re-asserted for. The CFI/fragment
 * restore schedules itself on up to three nested animation frames, so one
 * re-assert loses the race (measured: a turn to page 2 followed by the entry
 * restore to page 9); a chain of four wins it. Kept as a named constant so the
 * "why 3" is not buried in a call site.
 */
export const REASSERT_FRAMES = 3;

export interface PaginationObservers {
  /**
   * Start watching the container and the chapter root, if not already watching.
   * `onContainerResize` and `onContentResize` are invoked outside the observer
   * callback — see the module note.
   */
  attach(container: HTMLElement | null, chapterRoot: HTMLElement | null): void;
  /** Watch `observed` in place of the current chapter root (a srcdoc swap). */
  reobserveContent(chapterRoot: HTMLElement | null): void;
  /** Stop watching everything. */
  detach(): void;
}

/** The newest page turn's claimed offset, defended against later writers. */
export interface TurnReassert {
  /**
   * Claim an offset for a turn: bump the generation and re-assert `claimed` over
   * the next `REASSERT_FRAMES` frames. An older turn's chain sees a stale
   * generation and drops out, so the newest turn wins the race.
   */
  claim(claimed: number, onLanded: (scrollLeft: number) => void): void;
}

/**
 * A turn's offset is written synchronously, but the chapter's srcdoc is replaced
 * as its assets settle and the CFI/fragment restore scrolls to a stored position
 * on up to three nested animation frames — both write `scrollLeft` AFTER the
 * turn, and measured as a turn to page 6 being moved to 11100 (five pages past
 * its target). This owns the counter that lets the newest turn re-assert over
 * that window and drops every earlier turn's chain.
 */
export function createTurnReassert(scheduler: FrameScheduler): TurnReassert {
  let generation = 0;
  return {
    claim(claimed: number, onLanded: (scrollLeft: number) => void): void {
      const mine = ++generation;
      scheduler.chain(() => {
        if (mine !== generation) return;
        onLanded(claimed);
      }, REASSERT_FRAMES);
    },
  };
}

/** What the turn gate needs to know about the live layout, per call. */
export interface TurnGateProbe {
  /** True while an asynchronous position restore is still in flight. */
  isRestoring: () => boolean;
  /** The chapter document's root, the element that carries the column overflow. */
  getScroller: () => HTMLElement | null;
  /** The measured page box, or null before the first measure. */
  getPageBox: () => { width: number } | null;
  /**
   * Milliseconds since this chapter's layout last changed — a document swap, or
   * a column count that moved. The gate reads it to tell "the columns have not
   * arrived yet" from "this document IS one column", which the extent alone
   * cannot say.
   */
  msSinceLayoutChange: () => number;
}

/** The gate a page turn must pass before it may be honoured. */
export interface TurnGate {
  /** A position restore is in flight. The viewer queues the press for this. */
  isRestoring(): boolean;
  /**
   * The chapter is not laid out yet — no box, or a single column that has not had
   * time to become the real column set. A turn taken here is computed from a
   * one-page chapter and is left stranded when the real columns land.
   */
  isLayoutSettling(): boolean;
}

/**
 * How long a chapter may still report a single column before that column is
 * taken at face value.
 *
 * This is the fix for a gate that never opened. `scrollWidth <= box.width` was
 * the WHOLE test, so on a document that genuinely lays out as one page — a
 * 60-character chapter title, the acknowledgements, a body that fits — the gate
 * stayed shut forever and every press queued behind it. Measured on
 * *Prisioneros de la geografía*: 13 of its 43 documents are single column, and a
 * press on any of them reached the next document only when the viewer's own
 * 4-second deadline expired and fell through to chapter navigation (measured
 * wheel-to-next-document: 4066, 4083, 4094, 4121, 4143, 4149, 4161 ms on those
 * documents; 127-200 ms on the multi-column ones).
 *
 * The real "has it laid out yet" signal is TIME SINCE THE LAYOUT CHANGED: a
 * freshly committed `srcdoc` reports one column for the first frames (measured
 * 1110 at press time, 31080 — 28 columns — 120 ms later), and a reflow keeps
 * extending the window because every column-count change restarts it. 600 ms is
 * four times the measured settle and shorter than a reader's second press, so a
 * single-column chapter answers on the next frame instead of after 4 s.
 */
export const LAYOUT_SETTLE_GRACE_MS = 600;

/**
 * The two "do not turn yet" windows, as one concern.
 *
 * A freshly committed srcdoc reports `scrollWidth === clientWidth` (a single
 * column) for the first frames — measured live: 1110 at press time, 31080 (28
 * pages) 120 ms later. A turn taken in that window derives both its page count
 * and its origin offset from one page, and the columns that arrive afterwards
 * leave the reader wherever the reflow put them. The restore window overlaps it:
 * the CFI scroll also lands on a later frame. Both are pure layout reads, which
 * is why they live beside the other frame-timing helpers.
 */
export function createTurnGate(probe: TurnGateProbe): TurnGate {
  const isRestoring = (): boolean => probe.isRestoring();

  const isLayoutSettling = (): boolean => {
    const el = probe.getScroller();
    const box = probe.getPageBox();
    // No box or no scroller means the chapter is not laid out at all yet, which
    // is the most unsettled state there is. Returning false here let an
    // immediate press through exactly when it must not be.
    if (!el || !box || box.width <= 0) return true;
    // More than one column: the column layout arrived, whatever else is still
    // settling.
    if (el.scrollWidth > box.width) return false;
    // Exactly one column: either the document is still becoming a column set, or
    // it really is a single page. Only the clock can tell those apart, so the
    // press waits out a bounded window and is then honoured against the layout
    // the reader can actually see.
    return probe.msSinceLayoutChange() < LAYOUT_SETTLE_GRACE_MS;
  };

  return { isRestoring, isLayoutSettling };
}

/**
 * The two `ResizeObserver`s paginated mode needs, and nothing about what to do
 * with a resize beyond handing it to the caller on a clean frame.
 *
 * The container observer exists because the container is pinned to one page box
 * and never resizes when the columns grow INSIDE the chapter, so the chapter
 * document's own root and body must be watched separately — that is the one
 * that sees the column set getting taller.
 */
export function createPaginationObservers(
  scheduler: FrameScheduler,
  handlers: { onContainerResize: () => void; onContentResize: () => void },
): PaginationObservers {
  let containerObserver: ResizeObserver | null = null;
  let contentObserver: ResizeObserver | null = null;

  const observeContent = (chapterRoot: HTMLElement | null): void => {
    if (typeof ResizeObserver === 'undefined' || !chapterRoot) return;
    contentObserver?.disconnect();
    contentObserver = new ResizeObserver(() =>
      scheduler.coalesce('recount', handlers.onContentResize),
    );
    contentObserver.observe(chapterRoot);
    const body = chapterRoot.ownerDocument?.body;
    if (body) contentObserver.observe(body);
  };

  return {
    attach(container, chapterRoot): void {
      if (container && typeof ResizeObserver !== 'undefined' && !containerObserver) {
        containerObserver = new ResizeObserver(() =>
          scheduler.coalesce('refresh', handlers.onContainerResize),
        );
        containerObserver.observe(container);
      }
      if (!contentObserver) observeContent(chapterRoot);
    },
    reobserveContent(chapterRoot): void {
      observeContent(chapterRoot);
    },
    detach(): void {
      containerObserver?.disconnect();
      containerObserver = null;
      contentObserver?.disconnect();
      contentObserver = null;
    },
  };
}
