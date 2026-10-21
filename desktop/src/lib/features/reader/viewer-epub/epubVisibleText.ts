/**
 * Which text node is the reader actually looking at?
 *
 * ## The defect this exists for
 *
 * `emitPreciseLocation` picked the first text node satisfying
 * `rect.bottom >= 0 && rect.top <= iframeEl.clientHeight`. Measured on the live
 * app, that expression is TRUE for the first text node of the chapter at every
 * scroll position, because the chapter iframe is sized to its whole content
 * (`syncIframeHeight` sets the frame height to `documentElement.scrollHeight`)
 * and the real scroller is the parent's `[data-testid="epub-scroll-container"]`
 * (`clientHeight` 571 against an iframe `clientHeight` of 27382 for *Don
 * Quijote*). So "is it within `clientHeight`" asked whether the node was
 * anywhere in an eight-screen-tall frame, which it always is.
 *
 * Consequence, measured: the emitted CFI and the within-chapter progression were
 * pinned to the chapter's first character for the whole chapter. On a book whose
 * chapters each carry an intro fragment the percentage still moved (it is
 * dominated by the spine index), which is why *Prisioneros* looked healthy; on
 * *Don Quijote* three of its four spine items are targets of several TOC entries,
 * so the frozen percentage stayed at exactly `0` for four consecutive turns.
 *
 * ## The rule
 *
 * A node is visible when it intersects the **viewport band**, expressed in the
 * chapter document's own coordinates. In scrolling mode the band is the scroller's
 * `scrollTop` plus its `clientHeight`; in paginated mode the chapter root scrolls
 * itself, so the band is the root's `scrollLeft` plus its `clientWidth`.
 *
 * `visibleBandFor` / `intersectsBand` / `firstVisibleIndex` are pure, so the rule
 * is tested with plain rects and no browser. `collectVisibleTextNodes` and
 * `visibleTextIndex` are the two thin DOM adapters over it.
 */

import { isInlineColumnLayout } from '$lib/features/reader/viewer-epub/readerPagination';

/** The rectangle band, in the document's own coordinate space, that is on screen. */
export type VisibleBand =
  { axis: 'vertical'; start: number; end: number } | { axis: 'inline'; start: number; end: number };

/** A text node's measuring rect, in the chapter document's coordinate space. */
export interface NodeRect {
  top: number;
  bottom: number;
  left: number;
  right: number;
}

/**
 * The scroll offset and extent that describe the reader's viewport.
 *
 * `null` is a real answer: the caller may not have a scroller yet (before the
 * container mounts), and guessing one would re-introduce the defect.
 */
export function visibleBandFor(options: {
  paginated: boolean;
  /** The chapter root's `scrollLeft` (paginated mode). */
  rootScrollLeft?: number;
  /** The chapter root's `clientWidth` (paginated mode). */
  rootClientWidth?: number;
  /** The parent scroller's `scrollTop` (scrolling mode). */
  containerScrollTop?: number;
  /** The parent scroller's `clientHeight` (scrolling mode). */
  containerClientHeight?: number;
}): VisibleBand | null {
  if (options.paginated) {
    const start = options.rootScrollLeft ?? 0;
    const extent = options.rootClientWidth ?? 0;
    if (!Number.isFinite(start) || !Number.isFinite(extent) || extent <= 0) return null;
    return { axis: 'inline', start, end: start + extent };
  }
  const start = options.containerScrollTop ?? 0;
  const extent = options.containerClientHeight ?? 0;
  if (!Number.isFinite(start) || !Number.isFinite(extent) || extent <= 0) return null;
  return { axis: 'vertical', start, end: start + extent };
}

/** True when `rect` intersects `band` on the band's own axis. */
export function intersectsBand(rect: NodeRect, band: VisibleBand): boolean {
  if (band.axis === 'inline') return rect.right > band.start && rect.left < band.end;
  return rect.bottom > band.start && rect.top < band.end;
}

/**
 * Index of the first node intersecting `band`, or `-1` when none does.
 *
 * First rather than "most visible": the emitted location is the top of the
 * viewport, which is what a resume should restore. Ties are impossible on the
 * top edge, and a node that merely straddles the bottom edge is still the one the
 * reader reaches first.
 *
 * Each node is measured in the environment `rectOf` provides, so the rule can be
 * exercised with plain rects (see the tests) as well as against a live document.
 */
export function firstVisibleIndex(
  count: number,
  rectAt: (index: number) => NodeRect,
  band: VisibleBand | null,
): number {
  if (!band) return -1;
  for (let i = 0; i < count; i += 1) {
    if (intersectsBand(rectAt(i), band)) return i;
  }
  return -1;
}

/** Every text node of a chapter with something other than whitespace in it. */
export function collectVisibleTextNodes(doc: Document): Text[] {
  const nodes: Text[] = [];
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
  let node = walker.nextNode();
  while (node) {
    if ((node.nodeValue ?? '').trim().length > 0) nodes.push(node as Text);
    node = walker.nextNode();
  }
  return nodes;
}

/**
 * Index of the on-screen text node in a live chapter, or `-1` when none is.
 *
 * Reads the viewport from the two elements that own it — the chapter root when
 * paginated, the parent scroller otherwise — so "visible" is never the frame's
 * own height.
 */
export function visibleTextIndex(
  doc: Document,
  nodes: ReadonlyArray<Text>,
  container: HTMLElement | null,
): number {
  const root = doc.documentElement;
  const band = visibleBandFor({
    paginated: isInlineColumnLayout(doc),
    rootScrollLeft: root?.scrollLeft ?? 0,
    rootClientWidth: root?.clientWidth ?? 0,
    containerScrollTop: container?.scrollTop ?? 0,
    containerClientHeight: container?.clientHeight ?? 0,
  });
  const range = doc.createRange();
  return firstVisibleIndex(
    nodes.length,
    (i) => {
      range.selectNodeContents(nodes[i]!);
      const r = range.getBoundingClientRect();
      return { top: r.top, bottom: r.bottom, left: r.left, right: r.right };
    },
    band,
  );
}
