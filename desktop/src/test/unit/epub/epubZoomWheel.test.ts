/**
 * The in-frame ctrl+wheel forwarder.
 *
 * Two claims are load-bearing here and both are easy to break silently:
 *
 *  1. **One notch, one step.** If a gesture were counted by both the frame's
 *     listener and the parent window's, zoom would advance twice per tick — a
 *     plausible-looking control that moves twice as fast as the reader's hand.
 *  2. **A plain wheel is not ours.** Hijacking ordinary scrolling would be a
 *     worse defect than the one this module fixes.
 *
 * Neither needs a browser: the frame-side listener runs against a `Document`,
 * and the step decision is pure arithmetic.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  READER_ZOOM_STEP_PERCENT,
  attachFrameZoomWheel,
  forwardZoomWheel,
  isZoomWheel,
  registerZoomStepResolver,
  zoomIntentStep,
} from '$lib/features/reader/viewer-epub/epubZoomWheel';

/** A wheel event as the frame's document would deliver it. */
function wheelEvent(init: { ctrlKey?: boolean; metaKey?: boolean; deltaY?: number }): WheelEvent {
  const event = new WheelEvent('wheel', {
    ctrlKey: init.ctrlKey ?? false,
    metaKey: init.metaKey ?? false,
    deltaY: init.deltaY ?? 0,
    cancelable: true,
    bubbles: true,
  });
  return event;
}

describe('epubZoomWheel', () => {
  let unregister: (() => void) | null = null;

  afterEach(() => {
    unregister?.();
    unregister = null;
    vi.restoreAllMocks();
  });

  it('recognises ctrl and meta as zoom, and nothing else', () => {
    expect(isZoomWheel({ ctrlKey: true, metaKey: false })).toBe(true);
    expect(isZoomWheel({ ctrlKey: false, metaKey: true })).toBe(true);
    expect(isZoomWheel({ ctrlKey: false, metaKey: false })).toBe(false);
  });

  it('maps a notch to exactly one step, in the direction of the scroll', () => {
    // Wheel down (positive deltaY) zooms out; the same mapping the outer
    // chrome's handler already uses.
    expect(zoomIntentStep(100, 100)).toBe(-READER_ZOOM_STEP_PERCENT);
    expect(zoomIntentStep(100, -100)).toBe(READER_ZOOM_STEP_PERCENT);
    // A large delta is still ONE step: the gesture is a notch, not a magnitude.
    expect(zoomIntentStep(100, 4000)).toBe(-READER_ZOOM_STEP_PERCENT);
  });

  it('refuses to step past the clamp instead of persisting a no-op', () => {
    expect(zoomIntentStep(300, -100)).toBe(0);
    expect(zoomIntentStep(50, 100)).toBe(0);
    // The step is whatever reaches the clamp exactly, so a menu-chosen 295%
    // zooms in to 300% rather than overshooting to 305%.
    expect(zoomIntentStep(295, -100)).toBe(5);
    // On the step grid a notch stays a notch.
    expect(zoomIntentStep(290, -100)).toBe(READER_ZOOM_STEP_PERCENT);
  });

  it('forwards a ctrl+wheel from inside the frame as a single step', () => {
    const step = vi.fn();
    unregister = registerZoomStepResolver(() => step);
    const detached = attachFrameZoomWheel(document, () => 100);
    try {
      const event = wheelEvent({ ctrlKey: true, deltaY: -100 });
      document.body.dispatchEvent(event);
      expect(step).toHaveBeenCalledTimes(1);
      expect(step).toHaveBeenCalledWith(READER_ZOOM_STEP_PERCENT);
      // preventDefault INSIDE the frame: without it WebView2 zooms the whole
      // window, chrome included.
      expect(event.defaultPrevented).toBe(true);
    } finally {
      detached();
    }
  });

  it('counts one notch once, however many frames are open', () => {
    // The real duplicate would be a second listener on the SAME document, which
    // is what a `load`-triggered re-attach creates. Attaching twice and
    // detaching once must already leave a single listener.
    const step = vi.fn();
    unregister = registerZoomStepResolver(() => step);
    const first = attachFrameZoomWheel(document, () => 100);
    first();
    const second = attachFrameZoomWheel(document, () => 100);
    try {
      document.body.dispatchEvent(wheelEvent({ ctrlKey: true, deltaY: -100 }));
      expect(
        step,
        'one wheel notch advanced zoom more than once — a duplicate listener is attached',
      ).toHaveBeenCalledTimes(1);
    } finally {
      second();
    }
  });

  it('leaves a plain wheel alone: it still scrolls and does not zoom', () => {
    const step = vi.fn();
    unregister = registerZoomStepResolver(() => step);
    const detached = attachFrameZoomWheel(document, () => 100);
    try {
      const event = wheelEvent({ ctrlKey: false, deltaY: 120 });
      document.body.dispatchEvent(event);
      expect(step).not.toHaveBeenCalled();
      expect(
        event.defaultPrevented,
        'a plain wheel was preventDefault-ed inside the chapter — that breaks scrolling',
      ).toBe(false);
    } finally {
      detached();
    }
  });

  it('reports when no reader is mounted instead of pretending to zoom', () => {
    unregister = registerZoomStepResolver(() => null);
    expect(forwardZoomWheel(() => 100, -100)).toBe(false);
  });

  it('does nothing at the clamp, but still swallows the gesture', () => {
    const step = vi.fn();
    unregister = registerZoomStepResolver(() => step);
    const detached = attachFrameZoomWheel(document, () => 300);
    try {
      const event = wheelEvent({ ctrlKey: true, deltaY: -100 });
      document.body.dispatchEvent(event);
      expect(step).not.toHaveBeenCalled();
      // Swallowed even though nothing moved: an unhandled ctrl+wheel is what
      // makes WebView2 zoom the window.
      expect(event.defaultPrevented).toBe(true);
    } finally {
      detached();
    }
  });
});
