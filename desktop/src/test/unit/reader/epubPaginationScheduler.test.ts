/**
 * The turn gate: the two windows in which a page press must wait.
 *
 * The regression this file pins is the gate that never opened. Its whole test
 * used to be "the chapter is a single column", which is also the true, final
 * layout of a short document — so on 13 of *Prisioneros de la geografía*'s 43
 * documents every press queued behind a gate that could not open, and the page
 * only turned when the viewer's 4-second deadline fell through to chapter
 * navigation.
 */
import { describe, expect, it, vi } from 'vitest';
import {
  LAYOUT_SETTLE_GRACE_MS,
  createTurnGate,
} from '$lib/features/reader/viewer-epub/epubPaginationScheduler';

/** A gate probe with the three layout facts the gate reads. */
function makeProbe(opts: {
  scrollWidth: number;
  boxWidth?: number;
  sinceLayoutMs?: number;
  restoring?: boolean;
  noBox?: boolean;
  noScroller?: boolean;
}) {
  const el = { scrollWidth: opts.scrollWidth } as unknown as HTMLElement;
  return {
    isRestoring: () => opts.restoring ?? false,
    getScroller: () => (opts.noScroller ? null : el),
    getPageBox: () => (opts.noBox ? null : { width: opts.boxWidth ?? 1110 }),
    msSinceLayoutChange: () => opts.sinceLayoutMs ?? 0,
  };
}

describe('createTurnGate', () => {
  it('blocks a press with no page box, which is the least settled state there is', () => {
    const gate = createTurnGate(makeProbe({ scrollWidth: 1110, noBox: true }));
    expect(gate.isLayoutSettling()).toBe(true);
  });

  it('blocks a press before the chapter frame has a root', () => {
    const gate = createTurnGate(makeProbe({ scrollWidth: 1110, noScroller: true }));
    expect(gate.isLayoutSettling()).toBe(true);
  });

  it('lets a press through as soon as the columns are there', () => {
    // 28 columns measured 120ms after the srcdoc swap; nothing else matters.
    const gate = createTurnGate(makeProbe({ scrollWidth: 31080, sinceLayoutMs: 0 }));
    expect(gate.isLayoutSettling()).toBe(false);
  });

  it('holds a press while a fresh document is still one column', () => {
    const gate = createTurnGate(makeProbe({ scrollWidth: 1110, sinceLayoutMs: 20 }));
    expect(gate.isLayoutSettling()).toBe(true);
  });

  it('takes a single column at face value once the layout has stopped moving', () => {
    // The defect: a 60-character chapter title really is one column, and the gate
    // used to hold every press on it until the caller's 4s deadline expired.
    const gate = createTurnGate(
      makeProbe({ scrollWidth: 1110, sinceLayoutMs: LAYOUT_SETTLE_GRACE_MS + 1 }),
    );
    expect(gate.isLayoutSettling()).toBe(false);
  });

  it('closes again whenever the layout moves, so a reflow burst is still protected', () => {
    const probe = makeProbe({ scrollWidth: 1110, sinceLayoutMs: LAYOUT_SETTLE_GRACE_MS + 1 });
    const gate = createTurnGate(probe);
    expect(gate.isLayoutSettling()).toBe(false);
    // A column count that moved restarts the window.
    probe.msSinceLayoutChange = () => 10;
    expect(gate.isLayoutSettling()).toBe(true);
  });

  it('reports a restore in flight independently of the layout', () => {
    const gate = createTurnGate(
      makeProbe({ scrollWidth: 31080, sinceLayoutMs: 5000, restoring: true }),
    );
    expect(gate.isRestoring()).toBe(true);
    expect(gate.isLayoutSettling()).toBe(false);
  });

  it('keeps the settle grace short enough to be invisible to a reader', () => {
    // Four times the measured 120-150ms settle, and well under a second press.
    expect(LAYOUT_SETTLE_GRACE_MS).toBeGreaterThanOrEqual(400);
    expect(LAYOUT_SETTLE_GRACE_MS).toBeLessThanOrEqual(1000);
  });
});

describe('createTurnGate under a fake clock', () => {
  it('opens by itself, with no observer firing again', () => {
    vi.useFakeTimers({ toFake: ['performance'] });
    try {
      const started = performance.now();
      const gate = createTurnGate({
        isRestoring: () => false,
        getScroller: () => ({ scrollWidth: 1110 }) as unknown as HTMLElement,
        getPageBox: () => ({ width: 1110 }),
        msSinceLayoutChange: () => performance.now() - started,
      });
      expect(gate.isLayoutSettling()).toBe(true);
      vi.advanceTimersByTime(LAYOUT_SETTLE_GRACE_MS + 50);
      // Nothing about the layout changed — only time passed. That is the whole
      // point: a one-column document must become turnable on its own.
      expect(gate.isLayoutSettling()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});
