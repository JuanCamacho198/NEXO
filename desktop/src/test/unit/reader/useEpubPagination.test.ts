/**
 * The pagination composable, driven with a fake scroller.
 *
 * The geometry maths is covered directly in `readerPagination.test.ts`; this
 * file covers the behaviour on top of it — which element scrolls, that a turn is
 * exactly one page box, that a growing chapter is re-counted, and that a
 * re-render keeps the reader's page instead of throwing them to the front.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { createEpubPagination } from '$lib/features/reader/viewer-epub/useEpubPagination.svelte';

/** A stand-in for the chapter root: layout numbers the test controls directly. */
function makeScroller(opts: { clientWidth: number; scrollWidth: number; scrollLeft?: number }) {
  const el = {
    clientWidth: opts.clientWidth,
    clientHeight: 583,
    scrollWidth: opts.scrollWidth,
    scrollLeft: opts.scrollLeft ?? 0,
    ownerDocument: null as Document | null,
  };
  return el as unknown as HTMLElement & { scrollLeft: number; scrollWidth: number };
}

function makeDeps(scroller: HTMLElement, container: HTMLElement) {
  const setPageBox = vi.fn();
  const refreshReaderStyles = vi.fn();
  const emitPreciseLocation = vi.fn();
  const onHeightModeChange = vi.fn();
  return {
    setPageBox,
    refreshReaderStyles,
    emitPreciseLocation,
    onHeightModeChange,
    deps: {
      getContainerEl: () => container,
      getChapterRoot: () => scroller,
      isPaginated: () => true,
      refreshReaderStyles,
      getPageBox: () => null,
      setPageBox,
      onHeightModeChange,
      emitPreciseLocation,
    },
  };
}

describe('createEpubPagination', () => {
  beforeEach(() => {
    // jsdom has no ResizeObserver; the composable guards on it, so a stub keeps
    // `enter()` observable without making the observer itself the subject.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        disconnect(): void {}
      },
    );
  });

  it('scroller() targets the chapter root, not the pinned parent container', () => {
    // The parent is pinned to one page box and has nothing to scroll; the
    // chapter root carries the column overflow. Reading the parent produced a
    // page count of 1 in an early revision.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100, scrollLeft: 0 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 27500, scrollLeft: 0 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    expect(p.pageCount).toBe(25);
  });

  it('derives the count and publishes the page box on enter', () => {
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 28600 });
    const { deps, setPageBox, onHeightModeChange } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    expect(p.pageCount).toBe(26);
    expect(p.currentPage).toBe(1);
    expect(setPageBox).toHaveBeenCalledWith({ width: 1100, height: 583 });
    expect(onHeightModeChange).toHaveBeenCalledWith(true);
  });

  it('turns exactly one page box at a time', () => {
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    expect(p.currentPage).toBe(1);
    expect(p.goToNextPage()).toBe(true);
    expect(root.scrollLeft).toBe(1100);
    expect(p.currentPage).toBe(2);
    expect(p.goToNextPage()).toBe(true);
    expect(root.scrollLeft).toBe(2200);
    expect(p.currentPage).toBe(3);
    expect(p.goToPrevPage()).toBe(true);
    expect(root.scrollLeft).toBe(1100);
    expect(p.currentPage).toBe(2);
  });

  it('refuses to page past either end', () => {
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 3 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    expect(p.goToPrevPage()).toBe(false);
    expect(root.scrollLeft).toBe(0);
    p.goToPage(3);
    expect(p.currentPage).toBe(3);
    expect(p.goToNextPage()).toBe(false);
    expect(root.scrollLeft).toBe(2200);
  });

  it('re-counts when the chapter grows, so the last page is not wrongly reported', () => {
    // The real failure: a chapter read 8 pages on entry and 11 a moment later.
    // `isOnLastPage(8, 8)` was true, so a Next press escaped to the next chapter
    // instead of turning the page.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 8 });
    const { deps, emitPreciseLocation } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    expect(p.pageCount).toBe(8);

    // The chapter grows under the reader.
    (root as unknown as { scrollWidth: number }).scrollWidth = 1100 * 11;
    expect(p.recount()).toBe(true);
    expect(p.pageCount).toBe(11);

    // Now the reader is no longer at the end, and Next turns a page.
    p.goToPage(8);
    expect(p.goToNextPage()).toBe(true);
    expect(root.scrollLeft).toBe(1100 * 8);
    expect(emitPreciseLocation).toHaveBeenCalled();
  });

  it('recount is a no-op when nothing changed', () => {
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    expect(p.recount()).toBe(false);
    expect(p.recount()).toBe(false);
  });

  it('a re-render keeps the reader on their page instead of page one', () => {
    // A new chapter document starts at inline offset 0, so without a restore the
    // reader is thrown back to the front on every asset-driven re-render.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 10 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    p.goToPage(6);
    expect(p.currentPage).toBe(6);

    // Simulate the swap: the new document is at 0 with the same extent.
    root.scrollLeft = 0;
    p.restoreAfterRerender();
    expect(p.currentPage).toBe(6);
    expect(root.scrollLeft).toBe(1100 * 5);
  });

  it('a re-render clamps the page when the new count is smaller', () => {
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 10 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    p.goToPage(10);

    (root as unknown as { scrollWidth: number }).scrollWidth = 1100 * 4;
    root.scrollLeft = 0;
    p.restoreAfterRerender();
    expect(p.currentPage).toBe(4);
    expect(root.scrollLeft).toBe(1100 * 3);
  });

  it('resetForChapter returns to the first page', () => {
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 9 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    p.goToPage(7);
    p.resetForChapter();
    expect(p.currentPage).toBe(1);
    expect(root.scrollLeft).toBe(0);
  });

  it('honours a direct scroll before deciding a turn', () => {
    // A pointer drag or native scroll can move the container without the
    // composable knowing; a cached page number then turned to the wrong page.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 6 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    root.scrollLeft = 1100 * 4;
    expect(p.goToNextPage()).toBe(true);
    expect(root.scrollLeft).toBe(1100 * 5);
    expect(p.currentPage).toBe(6);
  });

  it('exit clears the box and unpins the height', () => {
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const { deps, setPageBox, onHeightModeChange } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    p.exit();
    expect(p.pageBox).toBeNull();
    expect(p.pageCount).toBe(0);
    expect(p.currentPage).toBe(1);
    expect(setPageBox).toHaveBeenLastCalledWith(null);
    expect(onHeightModeChange).toHaveBeenLastCalledWith(false);
  });

  it('does nothing when paginated mode is off', () => {
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const { deps } = makeDeps(root, container);
    const off = { ...deps, isPaginated: () => false };
    const p = createEpubPagination(off);
    p.refresh();
    expect(p.pageCount).toBe(0);
    expect(p.goToNextPage()).toBe(false);
    expect(p.goToPrevPage()).toBe(false);
    expect(root.scrollLeft).toBe(0);
  });

  it('refuses a turn while a position restore is in flight (Bug 2)', () => {
    // The measured defect: the chapter opens at scrollLeft 0 while the CFI
    // restore is still on its way to the stored position. A Next pressed in that
    // window stepped from 0 and the restore then landed on top of it — measured
    // live as scrollLeft 0 -> 25530 (23 pages) instead of 0 -> clientWidth.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 30 });
    let restoring = true;
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination({ ...deps, isRestoring: () => restoring });
    p.enter();

    expect(p.isRestoring()).toBe(true);
    expect(p.goToNextPage()).toBe(false);
    expect(root.scrollLeft).toBe(0);
    expect(p.currentPage).toBe(1);

    // The restore commits, and the very next turn steps exactly one page.
    restoring = false;
    expect(p.isRestoring()).toBe(false);
    expect(p.goToNextPage()).toBe(true);
    expect(root.scrollLeft).toBe(1100);
    expect(p.currentPage).toBe(2);
  });

  it('treats an absent isRestoring dep as never restoring', () => {
    // A caller with no restore path (the jsdom harness, an older host) keeps the
    // old behaviour: turns are never refused for a restore that cannot happen.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    expect(p.isRestoring()).toBe(false);
    expect(p.goToNextPage()).toBe(true);
    expect(root.scrollLeft).toBe(1100);
  });
});
