/**
 * The wheel-as-page-turn decision for paginated mode.
 *
 * ## Why this module exists
 *
 * Paginated mode lays a chapter out as COLUMNS and moves them horizontally, and
 * the chapter root deliberately has `overflow-y: hidden` so the columns stay
 * aligned. A vertical wheel therefore has nothing to scroll and did nothing at
 * all: measured live, `scrollLeft` stayed at 1276 across a wheel gesture while
 * the Next control moved it by a column. The reader reported the missing
 * gesture; the wheel is the third way to turn a page after the control and the
 * arrow keys.
 *
 * This module holds ONLY the decision — does this event turn a page, and which
 * way — so it can be tested without a browser and so a second call site cannot
 * re-derive the rules by accident. It never touches layout: `EpubNativeViewer`
 * honours a decision by calling the same `goToNext`/`goToPrev` the Next control
 * calls, which keeps the restore gate and the claimed-page bookkeeping in the
 * one place that already owns them.
 *
 * ## The four rules, and what each one protects
 *
 *  1. **Not in scrolling mode, not before the count is known.** `pageCount === 0`
 *     means the chapter's columns have not been measured, so there is no page to
 *     turn to. Scrolling mode must keep its ordinary scroll.
 *  2. **Ctrl / meta is the zoom gesture, never a turn.** `epubZoomWheel.ts`
 *     already forwards those to the reader's zoom pipeline. Claiming them here
 *     too would either zoom AND turn on one gesture, or (if this listener ran
 *     first and cancelled) silently break the zoom that was just fixed.
 *  3. **The direction follows the sign of `deltaY`**, matching every other
 *     vertical scroll surface: down means forward.
 *  4. **One notch, one page.** A Windows mouse at high delta emits several
 *     `wheel` events for ONE physical notch, and a trackpad emits them
 *     continuously. Counting each would advance three pages for one gesture, so
 *     the events of a single gesture are coalesced into one decision and the
 *     rest of the burst is swallowed for as long as the gesture lasts.
 *
 * ## Why `deltaY === 0` is not "down"
 *
 * Windows sends `deltaY: 0` events with a non-zero `deltaX` for a horizontal
 * wheel tilt. Treating the zero as a positive delta would turn a page on a
 * sideways gesture that asked for nothing.
 */

/**
 * How long after the last event of a gesture a new notch counts as a fresh
 * gesture rather than part of the same burst.
 *
 * ## The number
 *
 * Windows emits one `wheel` event per notch at the system's WHEEL_DELTA (120),
 * but a mouse configured for a higher scroll speed sends SEVERAL events per
 * notch at fractions of that delta, and a trackpad sends a continuous stream
 * while the fingers keep moving. The events of one notch therefore arrive within
 * a few milliseconds of each other, while two deliberate notches from a hand are
 * never less than ~50ms apart.
 *
 * 150ms is above the per-notch burst (measured on this app: a burst produced by
 * one gesture arrives within a single frame, ~16ms) and below a deliberate
 * second notch, so it separates "one gesture" from "two gestures" without making
 * the wheel feel laggy: the turn itself happens on the FIRST event, so the
 * window only ever swallows repeats. 60ms is the lower bound the constant must
 * exceed — a burst produced inside one frame must never leak a second turn.
 */
export const WHEEL_GESTURE_WINDOW_MS = 150;

/** What a wheel event asks the paginated reader to do. */
export type EpubPageWheelIntent = 'next' | 'prev' | 'none';

/** The fields of a `WheelEvent` the decision reads. Structural so a test needs no DOM. */
export type EpubPageWheelEvent = {
  readonly ctrlKey: boolean;
  readonly metaKey: boolean;
  readonly deltaY: number;
};

/** The paginated-mode facts the decision needs. */
export type EpubPageWheelState = {
  /** True when the chapter is laid out as columns. */
  readonly paginated: boolean;
  /** The chapter's measured column count; `0` means "not measured yet". */
  readonly pageCount: number;
};

/**
 * The bookkeeping a burst needs: when the last event of the current gesture was
 * seen, and which direction that gesture went.
 *
 * Kept as a value the caller owns rather than module state, so two viewers (and
 * two tests) cannot share a window, and so the coalescing is observable in a
 * test instead of hidden behind a clock.
 */
export type EpubPageWheelGesture = {
  /** Timestamp of the last event counted into the current gesture, or `null`. */
  lastAtMs: number | null;
  /** The direction the current gesture already acted on. */
  lastIntent: 'next' | 'prev' | null;
};

/** A gesture that has not seen an event yet. */
export function createEpubPageWheelGesture(): EpubPageWheelGesture {
  return { lastAtMs: null, lastIntent: null };
}

/**
 * Decide what one wheel event means, and update the gesture it belongs to.
 *
 * The gesture is mutated in place: a caller that owns one gesture per viewer
 * gets coalescing for free, and a caller that wants an isolated decision can
 * pass a fresh one (see {@link createEpubPageWheelGesture}).
 *
 * Returns `'none'` for everything that must keep doing what it already does:
 * scrolling mode, an unmeasured chapter, ctrl/meta (zoom), a horizontal-only
 * event, and every repeat inside an already-counted burst.
 */
export function resolveEpubPageWheelIntent(
  event: EpubPageWheelEvent,
  state: EpubPageWheelState,
  gesture: EpubPageWheelGesture,
  nowMs: number,
): EpubPageWheelIntent {
  // Rule 2 before rule 1: the zoom gesture belongs to `epubZoomWheel.ts` no
  // matter which mode is on, and returning early here is what keeps this
  // listener from ever becoming a second handler for it.
  if (event.ctrlKey || event.metaKey) return 'none';
  if (!state.paginated) return 'none';
  if (state.pageCount <= 0) return 'none';
  // A horizontal tilt reports no vertical delta; it asked for no vertical move.
  if (event.deltaY === 0) return 'none';

  const intent: 'next' | 'prev' = event.deltaY > 0 ? 'next' : 'prev';

  // Rule 4. A repeat is part of the gesture already counted ONLY when it is the
  // same direction inside the window: a fast reversal is the reader changing
  // their mind, and swallowing it would make the wheel feel stuck.
  const sameGesture =
    gesture.lastAtMs !== null &&
    gesture.lastIntent === intent &&
    nowMs - gesture.lastAtMs < WHEEL_GESTURE_WINDOW_MS;

  if (sameGesture) {
    // Refresh the window: a trackpad stream is one gesture for as long as the
    // fingers keep moving, which is exactly what sliding the timestamp forward
    // gives. Returning 'none' is what makes the whole burst one page.
    gesture.lastAtMs = nowMs;
    return 'none';
  }

  gesture.lastAtMs = nowMs;
  gesture.lastIntent = intent;
  return intent;
}
