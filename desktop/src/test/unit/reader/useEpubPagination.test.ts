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

  it('holds a press on a one-column chapter only until its layout settles', () => {
    // The gate's whole test used to be "one column", which is also the final
    // layout of a 60-character chapter title: every press on one queued behind a
    // gate that could never open (measured: 4.1s on 13 of this book's 43
    // documents). The composable has to hand the gate a live clock for the
    // single-column case to ever resolve.
    vi.useFakeTimers({ toFake: ['performance'] });
    try {
      const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      const { deps } = makeDeps(root, container);
      const p = createEpubPagination(deps);
      p.enter();
      expect(p.isLayoutSettling()).toBe(true);
      vi.advanceTimersByTime(700);
      expect(p.isLayoutSettling(), 'a one-column chapter IS settled once it stops moving').toBe(
        false,
      );
    } finally {
      vi.useRealTimers();
    }
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

  it('re-reads the count once the layout commits, after restyling', () => {
    // A restyle does not always commit its column layout by the next statement:
    // measured on the real app, a 100% -> 75% zoom read scrollWidth 41070
    // synchronously and 42180 one frame later. Without the re-read the field
    // kept the first number indefinitely, because a multicolumn reflow leaves
    // the root's border box alone and the ResizeObserver never fires.
    //
    // A real rAF is stubbed so the synchronous read and the committed re-read
    // are two distinct observable instants; jsdom would otherwise collapse them.
    const frames: Array<() => void> = [];
    vi.stubGlobal('requestAnimationFrame', (cb: () => void) => {
      frames.push(cb);
      return frames.length;
    });

    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const made = makeDeps(root, container);
    // Model the measured ordering: the stylesheet write lands, the browser has
    // NOT committed the column layout yet, so the synchronous read still sees
    // the old extent; the committed layout appears only on the next read.
    let committed = 5;
    Object.defineProperty(root, 'scrollWidth', {
      configurable: true,
      get: () => {
        const value = committed;
        committed = 7;
        return 1100 * value;
      },
    });
    made.refreshReaderStyles.mockImplementation(() => {});
    // A settings key that moves on the restyle's read, so the write is granted
    // by the reason `refresh` requires (a settings change), not by the box.
    let stylesKey = 'fontSize:100';
    const p = createEpubPagination({
      ...made.deps,
      getStylesKey: () => {
        const current = stylesKey;
        stylesKey = 'fontSize:125';
        return current;
      },
    });
    p.enter();
    // The synchronous read saw the OLD extent: the reflow is not committed yet.
    expect(p.pageCount).toBe(7);

    // Flush the queued frame, which reads the committed layout.
    for (const frame of frames.splice(0)) frame();
    expect(p.pageCount).toBe(7);
  });

  it('a commit re-read clamps a shrunk count instead of moving the reader', () => {
    // The rule the re-read must not break: `recount()` only CLAMPS. A new count
    // larger than the reader's page leaves them alone, and one smaller moves
    // them to the last page — never back to page 1.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 10 });
    const { deps } = makeDeps(root, container);
    const p = createEpubPagination(deps);
    p.enter();
    p.goToPage(6);
    expect(p.currentPage).toBe(6);

    // Grows: the page must stay.
    (root as unknown as { scrollWidth: number }).scrollWidth = 1100 * 14;
    p.refresh();
    expect(p.pageCount).toBe(14);
    expect(p.currentPage).toBe(6);

    // Shrinks below the page: clamp to the last page, not to 1.
    (root as unknown as { scrollWidth: number }).scrollWidth = 1100 * 3;
    p.refresh();
    expect(p.pageCount).toBe(3);
    expect(p.currentPage).toBe(3);
  });

  it('does not restyle for an observer re-run that changed nothing', () => {
    // The measured regression: a `ResizeObserver` delivery with the same page
    // box and no settings change made `refresh()` write the injected
    // stylesheet, the browser laid the chapter out again, and that write went
    // back through the same graph. Measured on the real app: the console
    // reported `ResizeObserver loop completed with undelivered notifications`
    // and the reader stopped being able to scroll. A restyle has to be able to
    // name a reason, and a repeat observe is not one.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const made = makeDeps(root, container);
    const p = createEpubPagination({ ...made.deps, getStylesKey: () => 'unchanged' });
    p.enter();
    const afterEnter = made.refreshReaderStyles.mock.calls.length;

    p.refresh();
    p.refresh();
    expect(made.refreshReaderStyles.mock.calls.length).toBe(afterEnter);
    // The count is still re-read: an observer tick can be the first sight of a
    // chapter that grew without moving the box.
    expect(p.pageCount).toBe(5);
  });

  it('restyles when a styles input changed but the page box did not', () => {
    // A font-size step reflows the chapter but leaves the container's box at
    // 1110x583 (measured). The box comparison alone cannot be the trigger, which
    // is exactly why the settings key exists.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    let stylesKey = 'fontSize:100';
    const made = makeDeps(root, container);
    const p = createEpubPagination({ ...made.deps, getStylesKey: () => stylesKey });
    p.enter();
    const afterEnter = made.refreshReaderStyles.mock.calls.length;

    // The font grows: same box, new stylesheet inputs.
    stylesKey = 'fontSize:125';
    (root as unknown as { scrollWidth: number }).scrollWidth = 1100 * 6;
    p.refresh();
    expect(made.refreshReaderStyles.mock.calls.length).toBe(afterEnter + 1);
    expect(p.pageCount).toBe(6);
  });

  it('restyles on a page box change even when the settings did not move', () => {
    // `column-width` is pinned to the box, so a resize has to reach the
    // stylesheet or the chapter keeps the previous page width.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const made = makeDeps(root, container);
    // No settings key: a caller without one always restyles, so this isolates
    // the box comparison.
    const p = createEpubPagination(made.deps);
    p.enter();
    const afterEnter = made.refreshReaderStyles.mock.calls.length;

    (container as unknown as { clientWidth: number }).clientWidth = 900;
    (container as unknown as { clientHeight: number }).clientHeight = 500;
    p.refresh();
    expect(made.refreshReaderStyles.mock.calls.length).toBe(afterEnter + 1);
    expect(p.pageBox).toEqual({ width: 900, height: 500 });
  });

  it('restyles a fresh chapter document even when no setting moved', () => {
    // A re-render replaces the chapter's document, and the injected stylesheet
    // goes with it. The "already written" cursor describes a document that no
    // longer exists, so it has to be invalidated or the new chapter is unpaginated.
    const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
    const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 5 });
    const { deps, refreshReaderStyles } = makeDeps(root, container);
    const p = createEpubPagination({ ...deps, getStylesKey: () => 'unchanged' });
    p.enter();
    const afterEnter = refreshReaderStyles.mock.calls.length;

    p.restoreAfterRerender();
    expect(refreshReaderStyles.mock.calls.length).toBeGreaterThan(afterEnter);
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

  /**
   * A chapter is usually several documents (a title page the outline names, then
   * an image spread and a body it does not), and only the one on screen has
   * columns. Measured in the live app on *Prisioneros de la geografía*: the
   * 60-character title page of "1. Rusia" lays out as ONE column, so a total
   * answered from columns alone read "1 / 1" and disabled Next — the
   * 57 356-character body could not be reached from the title page at all.
   */
  describe('a chapter made of several documents', () => {
    it('counts the later documents in its total, so "of 1" is not the answer', () => {
      const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      const { deps } = makeDeps(root, container);
      const p = createEpubPagination({ ...deps, getLaterDocuments: () => 2 });
      p.enter();
      expect(p.pageCount, 'the document on screen still has ONE column').toBe(1);
      expect(p.chapterTotal, 'but the chapter has three pages to turn through').toBe(3);
    });

    it('reports the columns alone for a chapter of one document', () => {
      const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 * 7 });
      const { deps } = makeDeps(root, container);
      const p = createEpubPagination({ ...deps, getLaterDocuments: () => 0 });
      p.enter();
      expect(p.chapterTotal).toBe(7);
    });

    it('ends the chapter at its LAST document, not at its last column', () => {
      // The host renders the next document when a turn is refused, so a title
      // page with two documents after it must let the reader turn (the host
      // then shows the image spread) instead of falling through to the next
      // chapter on the first press.
      const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      const { deps } = makeDeps(root, container);
      const p = createEpubPagination({ ...deps, getLaterDocuments: () => 2 });
      p.enter();
      expect(p.currentPage).toBe(1);
      // Past the only column: refused here, and the host renders the next
      // document of the chapter rather than the next chapter.
      expect(p.goToNextPage()).toBe(false);
      // On the chapter's LAST document with no columns left, the turn leaves the
      // chapter — which is what ends it.
      const last = createEpubPagination({
        ...deps,
        getLaterDocuments: () => 0,
      });
      last.enter();
      expect(last.goToNextPage()).toBe(false);
    });

    it('re-reads the extent when the chapter changes or re-renders', () => {
      const container = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      const root = makeScroller({ clientWidth: 1100, scrollWidth: 1100 });
      let later = 2;
      const { deps } = makeDeps(root, container);
      const p = createEpubPagination({ ...deps, getLaterDocuments: () => later });
      p.enter();
      expect(p.chapterTotal).toBe(3);

      later = 0;
      p.resetForChapter();
      expect(p.chapterTotal, 'a new chapter is a different document').toBe(1);

      later = 1;
      p.restoreAfterRerender();
      expect(p.chapterTotal, 'the reading order can arrive after the first frame').toBe(2);
    });
  });
});
