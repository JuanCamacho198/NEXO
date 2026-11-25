/**
 * Paging backwards across a chapter boundary lands on the previous chapter's
 * LAST page, as in a printed book — not on its first page, which made reading
 * backwards skip whole chapters.
 *
 * The chapter's columns keep arriving after it is first laid out (measured on
 * the real app: 9 -> 15 -> 25 pages over three re-renders), so "the last page"
 * is a moving target until the reader turns: the anchor follows the count, and
 * the first turn releases it.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createEpubPagination } from '$lib/features/reader/viewer-epub/useEpubPagination.svelte';

const BOX = 1100;

function makeScroller(scrollWidth: number) {
  return {
    clientWidth: BOX,
    clientHeight: 583,
    scrollWidth,
    scrollLeft: 0,
    ownerDocument: null as Document | null,
  } as unknown as HTMLElement & { scrollLeft: number; scrollWidth: number };
}

function setup(pages: number) {
  const container = makeScroller(BOX);
  const root = makeScroller(BOX * pages);
  const p = createEpubPagination({
    getContainerEl: () => container,
    getChapterRoot: () => root,
    isPaginated: () => true,
    refreshReaderStyles: vi.fn(),
    getPageBox: () => null,
    setPageBox: vi.fn(),
    onHeightModeChange: vi.fn(),
    emitPreciseLocation: vi.fn(),
  });
  p.enter();
  return { p, root };
}

/** The next document is swapped in: a new document starts at offset 0. */
function swapDocument(root: { scrollLeft: number; scrollWidth: number }, pages: number): void {
  root.scrollWidth = BOX * pages;
  root.scrollLeft = 0;
}

describe('pagination: entering a chapter from its end', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        disconnect(): void {}
      },
    );
  });

  it('a backward step lands on the last page', () => {
    const { p, root } = setup(4);
    swapDocument(root, 9);
    p.resetForChapter({ atEnd: true });
    expect(p.currentPage).toBe(9);
    expect(root.scrollLeft).toBe(BOX * 8);
  });

  it('a forward step still starts at page one', () => {
    const { p, root } = setup(4);
    p.goToPage(3);
    swapDocument(root, 9);
    p.resetForChapter();
    expect(p.currentPage).toBe(1);
    expect(root.scrollLeft).toBe(0);
  });

  it('stays on the last page while the columns keep arriving', () => {
    const { p, root } = setup(4);
    swapDocument(root, 9);
    p.resetForChapter({ atEnd: true });

    swapDocument(root, 15);
    p.restoreAfterRerender();
    expect(p.currentPage).toBe(15);

    // A growth seen by a plain re-measure, with no document swap.
    root.scrollWidth = BOX * 25;
    p.refresh();
    expect(p.currentPage).toBe(25);
    expect(root.scrollLeft).toBe(BOX * 24);
  });

  it('the first turn releases the anchor', () => {
    const { p, root } = setup(4);
    swapDocument(root, 9);
    p.resetForChapter({ atEnd: true });
    expect(p.goToPrevPage()).toBe(true);
    expect(p.currentPage).toBe(8);

    // The chapter grows afterwards: the reader stays where they turned to.
    swapDocument(root, 12);
    p.restoreAfterRerender();
    expect(p.currentPage).toBe(8);
  });

  it('a later chapter change forgets the anchor', () => {
    const { p, root } = setup(4);
    swapDocument(root, 9);
    p.resetForChapter({ atEnd: true });
    swapDocument(root, 6);
    p.resetForChapter();
    root.scrollWidth = BOX * 10;
    p.refresh();
    expect(p.currentPage).toBe(1);
  });
});

describe('pagination: entering a document on a named page', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        disconnect(): void {}
      },
    );
  });

  it('lands on the page once the columns reach it, and holds it as they grow', () => {
    const { p, root } = setup(4);
    swapDocument(root, 2);
    p.resetForChapter({ atPage: 7 });
    expect(p.isAnchored()).toBe(true);
    expect(p.currentPage).toBe(2);

    swapDocument(root, 20);
    p.restoreAfterRerender();
    expect(p.currentPage).toBe(7);
    expect(root.scrollLeft).toBe(BOX * 6);

    root.scrollWidth = BOX * 30;
    p.refresh();
    expect(p.currentPage).toBe(7);
  });
});
