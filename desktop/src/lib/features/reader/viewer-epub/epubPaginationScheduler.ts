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
   * Start watching the container, if not already watching. `onContainerResize`
   * is invoked outside the observer callback — see the module note.
   */
  attach(container: HTMLElement | null): void;
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
 * The one `ResizeObserver` paginated mode needs, and nothing about what to do
 * with a resize beyond handing it to the caller on a clean frame.
 *
 * The container observer exists because the container is the element whose box
 * defines the page, and a window resize or a fullscreen toggle moves it.
 *
 * ## Why the chapter's own document is NOT observed
 *
 * A second observer used to watch the chapter iframe's `documentElement` and
 * `body` from this (the app's) realm. That is a CROSS-DOCUMENT registration, and
 * Chromium cannot settle it: on every chapter swap it reported
 * `ResizeObserver loop completed with undelivered notifications` on the app
 * window, which the app's global error handler turned into a reader-facing error
 * toast — measured on *Prisioneros de la geografía* as 2-3 per chapter crossing,
 * 65 over 26 crossings.
 *
 * Measured attribution (no source change, live app): an observer created here
 * with an EMPTY callback, observing only the chapter's `html` or only its
 * `body`, reproduced it (47 errors on the first crossing, and 260-357 over five
 * when the app's own observer was detached) — so the app's recount logic was
 * never involved. Observing same-document targets (the `iframe` element, its
 * parent) produced none, as did observing the container alone.
 *
 * Nothing is lost by removing it. The observed boxes are pinned in paginated
 * mode (the `iframe` is one page tall and `column-width` is the page width), so
 * a growing column set changes `scrollWidth` and leaves every border box alone:
 * the observer fired exactly once per chapter — on the new document's first
 * layout — which `enter()`, `resetForChapter()` and `restoreAfterRerender()`
 * already recount through `refresh()` and `scheduleCommitRecount()`. A/B on the
 * live app: with it suppressed for the whole session, eight chapter crossings
 * produced the same page sequence and the same presses per chapter as the
 * baseline (22, 1, 3, 21, 1, 3, 21, 1) and 0 loop errors instead of 16.
 */
export function createPaginationObservers(
  scheduler: FrameScheduler,
  handlers: { onContainerResize: () => void },
): PaginationObservers {
  let containerObserver: ResizeObserver | null = null;

  return {
    attach(container): void {
      if (container && typeof ResizeObserver !== 'undefined' && !containerObserver) {
        containerObserver = new ResizeObserver(() =>
          scheduler.coalesce('refresh', handlers.onContainerResize),
        );
        containerObserver.observe(container);
      }
    },
    detach(): void {
      containerObserver?.disconnect();
      containerObserver = null;
    },
  };
}
