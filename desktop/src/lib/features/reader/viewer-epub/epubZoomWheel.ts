/**
 * Ctrl+wheel inside a chapter, forwarded to the reader's single zoom pipeline.
 *
 * ## Why this module exists at all
 *
 * The chapter is a `srcdoc` iframe. A wheel gesture dispatched inside that
 * document never bubbles into the parent window, so the parent's `wheel`
 * listener in `chrome/useReaderZoom.ts` cannot see it. Keyboard events DO reach
 * the parent, which is exactly why Ctrl+`+`/`-` worked while Ctrl+wheel over the
 * chapter did nothing (measured live: 75% -> 75% over the frame, 75% -> 85% over
 * the outer chrome).
 *
 * The frame therefore has to listen for `wheel` on itself and call the parent's
 * zoom entry point. This module holds the two decisions that makes non-trivial,
 * so they are testable without a browser and cannot be re-derived by accident at
 * a second call site:
 *
 *  1. **Which gestures are ours.** Only ctrl (or meta) + wheel. A plain wheel
 *     MUST fall through untouched: it is the reader's scroll, and hijacking it
 *     would be a worse bug than the one being fixed.
 *  2. **What one notch means.** Exactly one {@link READER_ZOOM_STEP_PERCENT}
 *     step, in the direction of `deltaY`, and nothing at all at the clamp — so a
 *     wheel tick at 300% cannot persist a settings change that renders the plate
 *     differently.
 *
 * ## The one-step guarantee, stated precisely
 *
 * A forwarded event must not be counted twice. It cannot be, and the reason is
 * structural rather than a guard: the gesture is dispatched INSIDE the frame's
 * document, so the parent window listener never receives it in the first place.
 * The frame's `attach()` in `EpubNativeViewer.svelte` is the only listener that
 * sees it, and it calls {@link forwardZoomWheel}, which is the parent's own
 * `handleGlobalWheel` — the same entry point the menu, the keys and the outer
 * chrome use. One gesture, one call, one rAF-coalesced step.
 *
 * `preventDefault()` is called here, inside the frame, for a second reason:
 * WebView2 applies its OWN page zoom (Ctrl+wheel is the built-in browser zoom)
 * to the whole window if an unhandled ctrl+wheel reaches it. That would scale
 * the chrome around the chapter and fight the reader's own zoom value.
 */
import { clampZoomPercent } from '../viewer-pdf/pdfNavigation';

/**
 * The single zoom step (percentage points) for every zoom gesture.
 *
 * Lives here rather than in `chrome/useReaderZoom.svelte` because that module
 * imports a Svelte rune file, which a plain `.ts` consumer (and a unit test that
 * only wants this arithmetic) cannot pull in cleanly. `useReaderZoom` re-exports
 * it, so the value has exactly one definition and its existing importers are
 * unchanged.
 */
export const READER_ZOOM_STEP_PERCENT = 10;

/** True for a wheel gesture the reader's zoom owns, i.e. ctrl/meta + wheel. */
export function isZoomWheel(event: { ctrlKey: boolean; metaKey: boolean }): boolean {
  return event.ctrlKey || event.metaKey;
}

/**
 * The signed step a wheel notch asks for, or `0` when it must do nothing.
 *
 * `deltaY > 0` is a wheel toward the reader (scroll down), which zooms OUT, the
 * same mapping `handleGlobalWheel` already applies to the outer chrome.
 *
 * Returning `0` at the clamp is what keeps a no-op gesture from persisting: a
 * settings write at 300% re-renders the stylesheet for no visible change, and
 * `adjustZoom` is the one place that refuses it, so the refusal is decided here
 * for the forwarded gesture rather than duplicated downstream.
 */
export function zoomIntentStep(currentPercent: number, deltaY: number): number {
  const delta = deltaY > 0 ? -READER_ZOOM_STEP_PERCENT : READER_ZOOM_STEP_PERCENT;
  const current = clampZoomPercent(currentPercent);
  const next = clampZoomPercent(current + delta);
  return next === current ? 0 : next - current;
}

/**
 * The channel the chapter frame reads to reach the parent's zoom step.
 *
 * Why a channel rather than a prop: `EpubNativeViewer.svelte` is already the
 * largest reader module (909 lines), and the wheel rule is not a viewer concern
 * — it belongs to the chrome-owned zoom pipeline. A single module-level slot
 * lets `ReaderWorkspace` publish `zoom.handleGlobalStep` once and lets the
 * viewer's frame `attach()` subscribe, without either component growing a prop
 * that exists only to pass a callback through.
 *
 * The value is a GETTER, not the number: the wheel handler must read the zoom
 * that is current at the moment of the gesture, not the one captured when the
 * reader mounted.
 */
let zoomStepResolver: (() => ((delta: number) => void) | null) | null = null;

/**
 * Publishes the resolver `ReaderWorkspace` owns. Returns an unregister function
 * so the publishing effect can clean up without clobbering a newer registration.
 */
export function registerZoomStepResolver(
  getStep: () => ((delta: number) => void) | null,
): () => void {
  zoomStepResolver = getStep;
  return () => {
    if (zoomStepResolver === getStep) zoomStepResolver = null;
  };
}

/**
 * Attaches the frame-side wheel listener. Returns its `detach` function.
 *
 * Every gesture is resolved fresh: the current zoom comes from the reader (so a
 * step taken by the keys or the menu since the last notch is honoured), and the
 * step sink is looked up per event so a remounted workspace is picked up without
 * re-attaching anything in the frame.
 */
export function attachFrameZoomWheel(doc: Document, getCurrentPercent: () => number): () => void {
  const onWheel = (event: Event): void => {
    const wheel = event as WheelEvent;
    if (!isZoomWheel(wheel)) return;
    // Called inside the frame, always — even at the clamp. WebView2's own page
    // zoom is triggered by an UNHANDLED ctrl+wheel, so letting one through at
    // 300% would scale the whole window instead of doing nothing.
    wheel.preventDefault();
    forwardZoomWheel(getCurrentPercent, wheel.deltaY);
  };
  doc.addEventListener('wheel', onWheel, { passive: false });
  return () => doc.removeEventListener('wheel', onWheel);
}

/**
 * The single entry point a chapter frame uses for a ctrl+wheel gesture.
 *
 * Returns true when a reader was there to handle it. `false` means no reader is
 * mounted (a frame that outlived its workspace), in which case the caller must
 * not pretend the gesture did anything.
 */
export function forwardZoomWheel(currentPercent: () => number, deltaY: number): boolean {
  const step = zoomStepResolver?.();
  if (!step) return false;
  // The step is computed from the CURRENT zoom and handed to `adjustZoom`, which
  // clamps again. That is deliberate duplication of a pure, cheap decision, not
  // of the rule: `adjustZoom` owns the persisted value and must stay the only
  // writer, while the refusual to step at 300% has to happen here so no settings
  // write is issued for a gesture that cannot change anything.
  const delta = zoomIntentStep(currentPercent(), deltaY);
  if (delta !== 0) step(delta);
  return true;
}
