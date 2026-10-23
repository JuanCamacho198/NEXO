/**
 * Pagination geometry and stylesheet.
 *
 * Every "measured" number in this file is a real reading taken from the running
 * app over CDP on 2026-10-17 (chapter `Supercomunicamentos`, page box
 * 1100x583), not an invented one — the feature doc names page-count drift as
 * this feature's most likely shipped defect, so the arithmetic is pinned to the
 * values the browser actually produced rather than to values that look tidy.
 */
import { describe, expect, it } from 'vitest';
import {
  PAGE_EPSILON,
  derivePageCount,
  isInlineColumnLayout,
  isOnFirstPage,
  isOnGrid,
  isOnLastPage,
  measurePageBox,
  pageAfterTurn,
  pageBoxKey,
  pageFromScrollLeft,
  resolveTurnOrigin,
  scrollLeftForPage,
  snapToGrid,
} from '$lib/features/reader/viewer-epub/readerPagination';
import {
  buildPaginationCss,
  buildReaderOverrideCss,
} from '$lib/features/reader/viewer-epub/readerCssBuilder';

/** The real measured layout: 28600px of columns in a 1100px page box. */
const MEASURED_SCROLL_WIDTH = 28600;
const MEASURED_PAGE_WIDTH = 1100;
const MEASURED_PAGES = 26;

describe('derivePageCount', () => {
  it('reports the exact page count measured on the real app', () => {
    expect(derivePageCount(MEASURED_SCROLL_WIDTH, MEASURED_PAGE_WIDTH)).toBe(MEASURED_PAGES);
  });

  it('counts a partially filled trailing column as a page', () => {
    // 3.2 pages of content is 4 pages: the reader must still turn to it.
    expect(derivePageCount(3520, 1100)).toBe(4);
    // Exactly 3 pages needs no phantom fourth.
    expect(derivePageCount(3300, 1100)).toBe(3);
  });

  it('does not round a flush multiple up to an extra page', () => {
    // The failure the doc warns about: sub-pixel overshoot turning 26 into 27.
    expect(derivePageCount(28600.4, 1100)).toBe(26);
    expect(derivePageCount(28600.9, 1100)).toBe(26);
    // But a real extra page's worth of content still counts.
    expect(derivePageCount(1100 * 26 + 500, 1100)).toBe(27);
  });

  it('returns 0, never 1, for an unmeasured box', () => {
    // "Not laid out" and "one page" are different answers and must not collide.
    expect(derivePageCount(28600, 0)).toBe(0);
    expect(derivePageCount(28600, -100)).toBe(0);
    expect(derivePageCount(0, 1100)).toBe(0);
    expect(derivePageCount(-5, 1100)).toBe(0);
    expect(derivePageCount(Number.NaN, 1100)).toBe(0);
    expect(derivePageCount(28600, Number.POSITIVE_INFINITY)).toBe(0);
  });

  it('a single page of content is exactly 1', () => {
    expect(derivePageCount(1100, 1100)).toBe(1);
    expect(derivePageCount(400, 1100)).toBe(1);
  });
});

describe('pageFromScrollLeft', () => {
  it('maps the measured offsets to 1-based pages', () => {
    expect(pageFromScrollLeft(0, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(1);
    expect(pageFromScrollLeft(1100, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(2);
    expect(pageFromScrollLeft(2200, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(3);
    expect(pageFromScrollLeft(27500, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(26);
  });

  it('clamps into range instead of rendering page 0 or page 27 of 26', () => {
    expect(pageFromScrollLeft(-50, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(1);
    expect(pageFromScrollLeft(99999, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(MEASURED_PAGES);
  });

  it('is stable across a page turn and back', () => {
    for (let page = 1; page <= MEASURED_PAGES; page++) {
      const left = scrollLeftForPage(page, MEASURED_PAGE_WIDTH, MEASURED_PAGES);
      expect(pageFromScrollLeft(left, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(page);
    }
  });
});

describe('scrollLeftForPage', () => {
  it('steps exactly one page box per turn', () => {
    expect(scrollLeftForPage(1, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(0);
    expect(scrollLeftForPage(2, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(1100);
    expect(scrollLeftForPage(26, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(27500);
  });

  it('clamps a page outside the chapter', () => {
    expect(scrollLeftForPage(0, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(0);
    expect(scrollLeftForPage(99, MEASURED_PAGE_WIDTH, MEASURED_PAGES)).toBe(27500);
  });
});

describe('chapter boundaries', () => {
  it('knows the last and first page', () => {
    expect(isOnLastPage(26, 26)).toBe(true);
    expect(isOnLastPage(25, 26)).toBe(false);
    expect(isOnFirstPage(1)).toBe(true);
    expect(isOnFirstPage(2)).toBe(false);
  });

  it('treats an unmeasured chapter as at its end, so Next falls through', () => {
    // A 0-page chapter cannot yield a page turn, and reporting "last page" is
    // what sends Next to the next chapter rather than nowhere.
    expect(isOnLastPage(1, 0)).toBe(true);
  });
});

describe('measurePageBox', () => {
  it('reads the container content box in pixels', () => {
    const el = { clientWidth: 1100, clientHeight: 583 } as HTMLElement;
    expect(measurePageBox(el)).toEqual({ width: 1100, height: 583 });
  });

  it('falls back to the window when the container is absent or unlaid-out', () => {
    const box = measurePageBox(null);
    expect(box.width).toBeGreaterThan(0);
    expect(box.height).toBeGreaterThan(0);
    // A zero-width container (display:none) must not be taken as a real box.
    const hidden = { clientWidth: 0, clientHeight: 0 } as HTMLElement;
    expect(measurePageBox(hidden).width).toBeGreaterThan(0);
  });
});

describe('pageBoxKey', () => {
  it('is stable against sub-pixel jitter', () => {
    expect(pageBoxKey({ width: 1100, height: 583 })).toBe(
      pageBoxKey({ width: 1100.4, height: 583.2 }),
    );
  });

  it('changes when the box really changes', () => {
    expect(pageBoxKey({ width: 1100, height: 583 })).not.toBe(
      pageBoxKey({ width: 1101, height: 583 }),
    );
    expect(pageBoxKey({ width: 1100, height: 583 })).not.toBe(
      pageBoxKey({ width: 1100, height: 584 }),
    );
  });
});

describe('isInlineColumnLayout', () => {
  const makeDoc = (overflowX: string, overflowY: string): Document =>
    ({
      documentElement: {},
      defaultView: { getComputedStyle: () => ({ overflowX, overflowY }) },
    }) as unknown as Document;

  it('recognises the pagination stylesheet shape', () => {
    expect(isInlineColumnLayout(makeDoc('auto', 'hidden'))).toBe(true);
    expect(isInlineColumnLayout(makeDoc('scroll', 'hidden'))).toBe(true);
  });

  it('reports scrolling mode for the scroll stylesheet shape', () => {
    expect(isInlineColumnLayout(makeDoc('hidden', 'visible'))).toBe(false);
    expect(isInlineColumnLayout(makeDoc('hidden', 'auto'))).toBe(false);
  });

  it('fails safe on a missing document or style', () => {
    expect(isInlineColumnLayout(null)).toBe(false);
    expect(isInlineColumnLayout(undefined)).toBe(false);
    expect(isInlineColumnLayout({ documentElement: null } as unknown as Document)).toBe(false);
  });
});

describe('buildPaginationCss', () => {
  const css = buildPaginationCss({ width: 1100, height: 583 });

  it('sizes the column to the measured page box in pixels', () => {
    // Pixels, not viewport units: inside the srcdoc frame `100vh` is the whole
    // chapter, which would produce one giant column instead of pages.
    expect(css).toContain('column-width: 1100px !important');
    expect(css).toContain('column-gap: 0 !important');
    expect(css).toContain('column-fill: auto !important');
  });

  it('bounds the column box height on both the root and the body', () => {
    expect(css).toMatch(/html \{[^}]*height: 583px !important/s);
    expect(css).toMatch(/body \{[^}]*height: 583px !important/s);
    expect(css).toContain('max-height: 583px !important');
  });

  it('moves the overflow to the inline axis', () => {
    expect(css).toContain('overflow-x: auto !important');
    expect(css).toContain('overflow-y: hidden !important');
  });

  it('drops the scrolling-mode measure so a column fills its box', () => {
    expect(css).toContain('max-width: none !important');
    expect(css).toContain('margin: 0 !important');
  });

  it('never emits a zero or negative size', () => {
    const degenerate = buildPaginationCss({ width: 0, height: -10 });
    expect(degenerate).toContain('column-width: 1px !important');
    expect(degenerate).toContain('height: 1px !important');
  });
});

describe('buildReaderOverrideCss pagination wiring', () => {
  const base = {
    fontSize: 100,
    zoomLevel: 100,
    themeMode: 'paper',
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    margins: { top: 2.5, bottom: 2.5, left: 2, right: 2 },
    fontFamily: 'serif',
  };

  it('emits no pagination rules in scrolling mode', () => {
    const scrolling = buildReaderOverrideCss(base);
    expect(scrolling).not.toContain('column-width');
    // The scroll path keeps its own overflow and measure.
    expect(scrolling).toContain('overflow-y: visible !important');
    expect(scrolling).toContain('max-width: min(65ch, 100%) !important');
  });

  it('appends pagination last, so it wins over the scroll rules', () => {
    const paginated = buildReaderOverrideCss({ ...base, pagination: { width: 1100, height: 583 } });
    const columnAt = paginated.indexOf('column-width: 1100px');
    const scrollOverflowAt = paginated.indexOf('overflow-y: visible !important');
    expect(columnAt).toBeGreaterThan(-1);
    // Later in the sheet wins at equal specificity, so pagination must follow.
    expect(columnAt).toBeGreaterThan(scrollOverflowAt);
    expect(paginated).toContain('overflow-y: hidden !important');
  });

  it('an explicit null pagination is still scrolling mode', () => {
    expect(buildReaderOverrideCss({ ...base, pagination: null })).not.toContain('column-width');
  });
});

describe('buildReaderOverrideCss paginated media ceiling', () => {
  const base = {
    fontSize: 100,
    zoomLevel: 100,
    themeMode: 'paper',
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    margins: { top: 2.5, bottom: 2.5, left: 2, right: 2 },
  };
  const box = { width: 1110, height: 583 };

  it('caps a plate to the page, minus the body padding, in prose', () => {
    // 507x754 plates in a 583px box measured `scrollHeight` 754 and the bottom
    // 171px of both maps was unreachable: the root's vertical overflow is hidden
    // and a column has no vertical scroll.
    const css = buildReaderOverrideCss({ ...base, pagination: box });
    expect(css).toContain('max-height: max(1px, calc(583px - 2.5rem - 2.5rem)) !important');
  });

  it('gives an image-only document the whole page', () => {
    // That block zeroes body's vertical padding, so nothing is reserved.
    const css = buildReaderOverrideCss({ ...base, pagination: box, layout: 'image-only' });
    expect(css).toContain('max-height: 583px !important');
  });

  it('is stated after the image-only release it has to beat', () => {
    const css = buildReaderOverrideCss({ ...base, pagination: box, layout: 'image-only' });
    const releaseAt = css.indexOf('max-height: none !important');
    const ceilingAt = css.indexOf('max-height: 583px !important');
    expect(releaseAt).toBeGreaterThan(-1);
    expect(ceilingAt).toBeGreaterThan(releaseAt);
  });

  it('emits nothing in scrolling mode, where the chapter scrolls to the plate', () => {
    expect(buildReaderOverrideCss(base)).not.toContain('max-height: max(');
    expect(buildReaderOverrideCss({ ...base, layout: 'image-only' })).not.toContain(
      'max-height: max(',
    );
  });

  it('never emits a negative ceiling', () => {
    // A box shorter than the body's own padding would resolve `calc` negative, and
    // a dropped declaration puts the clipping back.
    const css = buildReaderOverrideCss({
      ...base,
      margins: { top: 4, bottom: 4, left: 2, right: 2 },
      pagination: { width: 1110, height: 60 },
    });
    expect(css).toContain('max-height: max(1px, calc(60px - 4rem - 4rem)) !important');
  });
});

describe('PAGE_EPSILON', () => {
  it('is far below a page and above real sub-pixel noise', () => {
    expect(PAGE_EPSILON).toBeGreaterThan(0);
    expect(PAGE_EPSILON).toBeLessThan(0.01);
  });
});

describe('snapToGrid', () => {
  it('leaves an on-grid offset alone', () => {
    expect(snapToGrid(4440, 1110)).toBe(4440);
    expect(snapToGrid(0, 1110)).toBe(0);
  });

  it('snaps an off-grid offset down to the page it is inside', () => {
    // The CFI restore lands between pages (measured offset 17728 against a
    // 1100px box); snapping down keeps "page N" honest.
    expect(snapToGrid(17728, 1100)).toBe(17600);
    expect(snapToGrid(2795, 1100)).toBe(2200);
  });

  it('is a no-op for an unusable width', () => {
    expect(snapToGrid(500, 0)).toBe(500);
  });
});

describe('isOnGrid', () => {
  it('separates a settled offset from a reflow artifact', () => {
    expect(isOnGrid(4440, 1110)).toBe(true);
    expect(isOnGrid(4400, 1110)).toBe(false);
    expect(isOnGrid(0, 1110)).toBe(true);
  });

  it('is false for an unusable width', () => {
    expect(isOnGrid(0, 0)).toBe(false);
  });
});

describe('resolveTurnOrigin', () => {
  it('trusts an on-grid offset, which is how a pointer drag is honoured', () => {
    // Offset 4400 is page 5 of a 1100px box, and navigation still thinks 1.
    expect(resolveTurnOrigin(4400, 1100, 6, 1)).toBe(5);
  });

  it('uses the navigation page for an off-grid offset', () => {
    // A mid-column offset is a reflow artifact — the browser re-anchored the
    // scroll to an extent it no longer has — so the navigation index wins.
    expect(resolveTurnOrigin(5000, 1110, 15, 3)).toBe(3);
    expect(resolveTurnOrigin(17728, 1100, 20, 7)).toBe(7);
  });
});

describe('pageAfterTurn', () => {
  it('steps one page inside the chapter', () => {
    expect(pageAfterTurn(3, 1, 10)).toBe(4);
    expect(pageAfterTurn(3, -1, 10)).toBe(2);
  });

  it('refuses to leave the chapter at either end', () => {
    expect(pageAfterTurn(10, 1, 10)).toBeNull();
    expect(pageAfterTurn(1, -1, 10)).toBeNull();
  });

  it('clamps an out-of-range origin before stepping', () => {
    expect(pageAfterTurn(99, -1, 10)).toBe(9);
    expect(pageAfterTurn(0, 1, 10)).toBe(2);
  });

  it('refuses any turn when the chapter has no measurable pages', () => {
    expect(pageAfterTurn(1, 1, 0)).toBeNull();
  });
});
