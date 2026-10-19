/**
 * readerPagination — the geometry of paginated mode, and nothing else.
 *
 * Pagination is CSS multicolumn inside the existing chapter iframe: the chapter
 * document is given `column-width` equal to the page box and a bounded height,
 * so the inline content flows into columns the browser creates, one column per
 * screen. "Next" then advances the scroll container by exactly one page box.
 *
 * This module owns the two numbers that model needs — how wide a page is, and
 * how many pages a chapter has — and it owns them as pure functions so the
 * arithmetic can be tested directly instead of through a live layout. The risk
 * the feature doc names is page-count drift: a count that looks right at one
 * zoom and is off by one at another. Keeping the derivation in one tested
 * function is the mitigation.
 *
 * WHY THE COUNT IS NOT `Math.round(scrollWidth / pageWidth)`:
 * `column-fill: auto` packs columns left to right and the browser does NOT
 * guarantee `scrollWidth` is an exact multiple of the column width — a
 * partially filled trailing column still contributes its real width, and a
 * rounding error there becomes a visible wrong number. Measured on the real app
 * (2026-10-17, `Supercomunicamentos`, page box 1100x583): `scrollWidth` 28600
 * against `clientWidth` 1100 gave an exact ratio of 26. The floor-plus-remainder
 * form below returns the same 26 there and stays correct when the remainder is
 * non-zero, which `Math.round` would silently round down.
 */

/** A viewport page box, in CSS pixels. */
export interface PageBox {
  width: number;
  height: number;
}

/**
 * The smallest box that can hold a page. A zero or degenerate box means the
 * container is not laid out yet (display:none, a hidden tab, a mid-render
 * frame); every derivation below returns 0 for it rather than 1, so a caller
 * can tell "no layout" from "one page".
 */
function isUsableBox(box: PageBox | null | undefined): box is PageBox {
  return !!box && Number.isFinite(box.width) && box.width > 0;
}

/**
 * Pages in a chapter, from the laid-out inline extent and the page box.
 *
 * `scrollWidth` is the container's full inline extent (every column the browser
 * created); `pageWidth` is one screen. The count is the ceiling of the ratio —
 * a partially filled trailing column is still a page the reader must turn to.
 * Returns 0 when the box is unusable, never 1: an unmeasured chapter is not a
 * one-page chapter.
 */
export function derivePageCount(scrollWidth: number, pageWidth: number): number {
  if (!Number.isFinite(scrollWidth) || !Number.isFinite(pageWidth) || pageWidth <= 0) return 0;
  if (scrollWidth <= 0) return 0;
  return Math.ceil(scrollWidth / pageWidth - PAGE_EPSILON);
}

/**
 * Sub-pixel slack for the ceiling above. Fractional layout means a chapter that
 * ends flush can measure `scrollWidth` a few hundredths of a pixel past an
 * exact multiple; without this, 26 exact pages would report 27. The value is
 * far below one page and far above the accumulated rounding of a real layout.
 */
export const PAGE_EPSILON = 1e-3;

/**
 * Which page a reader is on, 1-based, from the container's inline scroll
 * offset. Clamped into `[1, pageCount]` so a bounce or an overshoot can never
 * render "page 0" or "page 27 of 26".
 */
export function pageFromScrollLeft(
  scrollLeft: number,
  pageWidth: number,
  pageCount: number,
): number {
  if (pageWidth <= 0 || pageCount <= 0) return 1;
  const index = Math.floor(scrollLeft / pageWidth + PAGE_EPSILON);
  return Math.min(Math.max(index + 1, 1), pageCount);
}

/** The inline offset that puts page `page` (1-based) at the top of the box. */
export function scrollLeftForPage(page: number, pageWidth: number, pageCount: number): number {
  if (pageWidth <= 0 || pageCount <= 0) return 0;
  const clamped = Math.min(Math.max(page, 1), pageCount);
  return (clamped - 1) * pageWidth;
}

/** True when a forward turn would leave the chapter. */
export function isOnLastPage(page: number, pageCount: number): boolean {
  return pageCount <= 0 || page >= pageCount;
}

/** True when a backward turn would leave the chapter. */
export function isOnFirstPage(page: number): boolean {
  return page <= 1;
}

/**
 * Reads the page box off the scroll container. `clientWidth`/`clientHeight` are
 * the content box — the space a page actually has — so they are the right pair
 * and the container's padding is already excluded. Falls back to the window when
 * the container is not mounted, which keeps first paint from computing against a
 * zero box.
 */
export function measurePageBox(container: HTMLElement | null): PageBox {
  if (container) {
    const width = container.clientWidth;
    const height = container.clientHeight;
    if (isUsableBox({ width, height })) return { width, height };
  }
  if (typeof window !== 'undefined') {
    return { width: window.innerWidth, height: window.innerHeight };
  }
  return { width: 0, height: 0 };
}

/**
 * True when a chapter document is laid out as inline columns (paginated mode).
 *
 * Read from the document's own computed overflow rather than from a mode flag:
 * the chapter root is what the pagination stylesheet sets to `overflow-x: auto`
 * with `column-width` pinned, so the document is the single source of truth and
 * the location-emission axis can never drift out of phase with the layout that
 * actually produced it. A `srcdoc` frame whose stylesheet failed to apply reads
 * as scrolling, which is the safe direction to fail in: the vertical test that
 * follows is the one every existing spec was written against.
 */
export function isInlineColumnLayout(doc: Document | null | undefined): boolean {
  if (!doc) return false;
  const root = doc.documentElement;
  if (!root) return false;
  const style = doc.defaultView?.getComputedStyle(root);
  if (!style) return false;
  const scrollsInline = style.overflowX === 'auto' || style.overflowX === 'scroll';
  const scrollsVertically = style.overflowY === 'auto' || style.overflowY === 'scroll';
  return scrollsInline && !scrollsVertically;
}

/**
 * A stable key for the measured box, so a caller can tell a real relayout from
 * a no-op resize (a 1px scrollbar flicker, a repeat observe of the same box).
 * Rounded to whole pixels: sub-pixel jitter in `clientWidth` must not trigger a
 * recount, and every page turn would otherwise look like a relayout.
 */
export function pageBoxKey(box: PageBox): string {
  return `${Math.round(box.width)}x${Math.round(box.height)}`;
}

/**
 * The inline offset snapped down onto the page grid, or the input when the
 * width is unusable. This is the invariant a turn must uphold after it lands:
 * `scrollLeft % clientWidth === 0`. A partial offset is a reflow artifact (the
 * CFI restore and the srcdoc swaps write `scrollLeft` mid-settle), so snapping
 * down keeps the reader on the page they are actually looking at rather than a
 * fraction of one.
 */
export function snapToGrid(scrollLeft: number, pageWidth: number): number {
  if (pageWidth <= 0 || !Number.isFinite(scrollLeft)) return scrollLeft;
  const remainder = scrollLeft % pageWidth;
  return remainder === 0 ? scrollLeft : scrollLeft - remainder;
}

/** True when an offset sits exactly on the page grid (a settled position). */
export function isOnGrid(scrollLeft: number, pageWidth: number): boolean {
  if (pageWidth <= 0) return false;
  return scrollLeft % pageWidth === 0;
}

/**
 * Resolves which page a turn should step FROM, given the live offset, the
 * offset's grid alignment, and the page index navigation owns.
 *
 * An ON-GRID offset is trusted — it is a settled position, and honoring it is
 * what lets a pointer drag move the reader without the composable having seen
 * a turn. An OFF-GRID offset is a reflow artifact, so the navigation-owned
 * index wins instead. This one rule is the fix for the "Next jumps pages"
 * defect: re-deriving the origin from the raw offset unconditionally meant a
 * press turned from whatever intermediate value it happened to sample — on the
 * real app a single press moved `scrollLeft` 0 -> 6660 (six pages) and another
 * teleported to the chapter's last page.
 */
export function resolveTurnOrigin(
  scrollLeft: number,
  pageWidth: number,
  pageCount: number,
  navigationPage: number,
): number {
  if (isOnGrid(scrollLeft, pageWidth)) {
    return pageFromScrollLeft(scrollLeft, pageWidth, pageCount);
  }
  return navigationPage;
}

/**
 * The page a turn of `delta` lands on, or `null` when it would leave the
 * chapter in that direction. Clamped into `[1, pageCount]` first so an out-of
 * range navigation index (a chapter change caught mid-flux) cannot produce an
 * illegal target.
 */
export function pageAfterTurn(origin: number, delta: 1 | -1, pageCount: number): number | null {
  if (pageCount <= 0) return null;
  const from = Math.min(Math.max(origin, 1), pageCount);
  if (delta > 0 && from >= pageCount) return null;
  if (delta < 0 && from <= 1) return null;
  return from + delta;
}
