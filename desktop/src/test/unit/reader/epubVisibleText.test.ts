/**
 * The reader's "which text node is on screen" rule.
 *
 * The defect these lock down was measured on the live app, not imagined: the
 * chapter iframe is sized to its whole content, so its `clientHeight` is the
 * chapter's height and every node of the chapter satisfies
 * "`rect.top <= iframe.clientHeight`". The emitted location then never left
 * character 0, and *Don Quijote* — whose four spine items are each named by
 * several TOC entries — reported `0%` across four consecutive turns.
 */
import { describe, expect, it } from 'vitest';
import {
  firstVisibleIndex,
  intersectsBand,
  visibleBandFor,
  type NodeRect,
  type VisibleBand,
} from '$lib/features/reader/viewer-epub/epubVisibleText';

const rect = (top: number, bottom: number, left = 0, right = 100): NodeRect => ({
  top,
  bottom,
  left,
  right,
});

describe('visibleBandFor', () => {
  it('uses the parent scroller viewport in scrolling mode, NOT the frame height', () => {
    // Measured on Don Quijote: container clientHeight 571, frame clientHeight 27382.
    const band = visibleBandFor({
      paginated: false,
      containerScrollTop: 6000,
      containerClientHeight: 571,
      rootClientWidth: 1266,
    });
    expect(band).toEqual({ axis: 'vertical', start: 6000, end: 6571 });
  });

  it('uses the chapter root inline viewport in paginated mode', () => {
    const band = visibleBandFor({
      paginated: true,
      rootScrollLeft: 2532,
      rootClientWidth: 1266,
      containerScrollTop: 0,
      containerClientHeight: 571,
    });
    expect(band).toEqual({ axis: 'inline', start: 2532, end: 3798 });
  });

  it('returns null when the extent is unmeasurable rather than guessing one', () => {
    expect(visibleBandFor({ paginated: false, containerScrollTop: 0 })).toBeNull();
    expect(
      visibleBandFor({ paginated: false, containerScrollTop: 0, containerClientHeight: 0 }),
    ).toBeNull();
    expect(visibleBandFor({ paginated: true, rootScrollLeft: 0, rootClientWidth: 0 })).toBeNull();
  });
});

describe('firstVisibleIndex', () => {
  const chapter = [rect(0, 40), rect(200, 260), rect(6000, 6100), rect(6800, 7000)];
  const pick = (band: VisibleBand | null, rects = chapter): number =>
    firstVisibleIndex(rects.length, (i) => rects[i]!, band);

  it('picks nothing at a scroll position nothing reaches', () => {
    const band = visibleBandFor({
      paginated: false,
      containerScrollTop: 20000,
      containerClientHeight: 571,
    });
    expect(band).not.toBeNull();
    expect(pick(band)).toBe(-1);
  });

  it('picks nothing when there is no measurable band at all', () => {
    expect(pick(null)).toBe(-1);
    expect(pick(visibleBandFor({ paginated: false, containerScrollTop: 0 }))).toBe(-1);
  });

  it('advances with the scroll offset instead of sticking to the first node', () => {
    const at = (scrollTop: number): number =>
      pick(
        visibleBandFor({
          paginated: false,
          containerScrollTop: scrollTop,
          containerClientHeight: 571,
        }),
      );

    expect(at(0)).toBe(0);
    expect(at(6000)).toBe(2);
    expect(at(6700)).toBe(3);
  });

  it('reproduces the defect it replaces: a frame-height band matches node 0 at every scroll', () => {
    // The old test was `rect.top <= 27382`, i.e. its band was the whole chapter.
    const frameBand = { axis: 'vertical' as const, start: -27382, end: 27382 };
    expect(pick(frameBand)).toBe(0);
  });

  it('measures a node straddling an edge as visible in both directions', () => {
    expect(intersectsBand(rect(500, 700), { axis: 'vertical', start: 600, end: 1000 })).toBe(true);
    expect(intersectsBand(rect(900, 1200), { axis: 'vertical', start: 600, end: 1000 })).toBe(true);
    expect(intersectsBand(rect(1000, 1200), { axis: 'vertical', start: 600, end: 1000 })).toBe(
      false,
    );
  });

  it('selects on the inline axis when paginated', () => {
    const columns = [rect(0, 40, 0, 1200), rect(0, 40, 1266, 2400), rect(0, 40, 2532, 3700)];
    expect(pick({ axis: 'inline', start: 1300, end: 1866 }, columns)).toBe(1);
    expect(pick({ axis: 'inline', start: 2532, end: 3798 }, columns)).toBe(2);
  });
});
