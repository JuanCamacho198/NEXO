import { extractFragment } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import type { EpubChapterMeta } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import {
  buildReadingOrder,
  readingOrderIndexFor,
} from '$lib/features/reader/viewer-epub/epubReadingOrder';
import { logger } from '$lib/shared/logger/Logger';

export type EpubNavigationDeps = {
  getToc: () => EpubChapterMeta[];
  getSpineHrefs: () => string[];
  getTotalChapters: () => number;
};

/**
 * A position in the book, expressed in both index spaces at once.
 *
 * Callers routinely need "the position I am at" as a spine document (render,
 * highlights, CFI, progress) and as a page number (footer, page field). Handing
 * both back from one call is what keeps the two from drifting apart.
 */
export type EpubNavigationPosition = {
  /** Index into the reading order — the space the page field, pages and turns use. */
  order: number;
  /** Spine document at that position — the space rendering and highlights use. */
  spine: number;
};

export function createEpubNavigation(deps: EpubNavigationDeps): {
  currentChapterIndex: number;
  pendingCfiScroll: string | null;
  pendingFragment: string | null;
  readonly currentSpineIndex: number;
  readonly readingOrder: number[];
  readonly totalPages: number;
  /** Reading-order position and spine document for a position; spine is -1 if impossible. */
  resolve(rawOrder: number): EpubNavigationPosition;
  /** Reading-order position of a spine document. */
  orderForSpine(spineIndex: number): number;
  goToPrev(): void;
  goToNext(): void;
  goToChapter(index: number): void;
  goToSpine(spineIndex: number): void;
  handleGoToPage(page: number): Promise<boolean>;
  handleExternalTocNavigate(targetId: string | null): boolean;
  handleSearchTargetLocator(
    target: string | null,
    options: { currentChapterIndex: number },
  ): { navigated: boolean; needsScroll: boolean; chapterIdx: number | null } | null;
  clearPendingCfiScroll(): void;
  clearPendingFragment(): void;
} {
  // The reading order is the only index space this module navigates in. An
  // earlier version kept one number for two namespaces — below `toc.length` it
  // was an outline entry, at or above it a spine document — which is exactly
  // the seam that made a 43-document book read chapter 7, then the conclusion,
  // then chapter 5's body. One list, one space, no branch.
  let currentChapterIndex = $state(0);
  let pendingCfiScroll = $state<string | null>(null);
  let pendingFragment = $state<string | null>(null);

  /**
   * Spine documents in the order a reader reads them.
   *
   * `getTotalChapters` is a floor because the window between a book opening and
   * its spine being read has no spine hrefs yet; without it the reader would
   * report one page for a book it has not finished describing.
   */
  const readingOrder = $derived.by(() =>
    buildReadingOrder(
      deps.getToc(),
      Math.max(deps.getSpineHrefs().length, deps.getTotalChapters()),
    ),
  );

  function resolve(rawOrder: number): EpubNavigationPosition {
    const order = readingOrder;
    if (order.length === 0) return { order: 0, spine: 0 };
    const at = Number.isFinite(rawOrder) ? Math.trunc(rawOrder) : 0;
    const clamped = Math.min(Math.max(at, 0), order.length - 1);
    return { order: clamped, spine: order[clamped] ?? -1 };
  }

  function orderForSpine(spineIndex: number): number {
    return readingOrderIndexFor(readingOrder, spineIndex);
  }

  /**
   * Spine position of the current page — the one number highlights, the CFI
   * bridge and the renderer are keyed on.
   */
  const currentSpineIndex = $derived(resolve(currentChapterIndex).spine);

  /**
   * Fragment to land on when moving to reading-order position `order`, or null.
   *
   * Only outline entries carry fragments. A spine document the outline never
   * names has no fragment — returning one from past the end of the array would
   * leak `undefined` into the render as a fragment to scroll to.
   */
  function fragmentAt(order: number): string | null {
    const spineIndex = readingOrder[order];
    if (spineIndex === undefined) return null;
    const entry = deps.getToc().find((candidate) => candidate.index === spineIndex);
    return entry ? extractFragment(entry.href) : null;
  }

  function goToPrev(): void {
    if (currentChapterIndex > 0) {
      const prevIdx = currentChapterIndex - 1;
      const frag = fragmentAt(prevIdx);
      if (frag) pendingFragment = frag;
      currentChapterIndex = prevIdx;
    }
  }

  function goToNext(): void {
    const nextIdx = currentChapterIndex + 1;
    if (nextIdx < readingOrder.length) {
      const frag = fragmentAt(nextIdx);
      if (frag) pendingFragment = frag;
      currentChapterIndex = nextIdx;
    }
  }

  function goToChapter(index: number): void {
    if (index >= 0 && index < readingOrder.length) {
      pendingFragment = fragmentAt(index);
      currentChapterIndex = index;
    }
  }

  /**
   * Jump to the document a caller holds by spine index (a search hit, a
   * restored CFI, an outline entry). Resolves through the order so the page
   * number the reader lands on is the document's own reading position.
   */
  function goToSpine(spineIndex: number): void {
    const position = orderForSpine(spineIndex);
    if (position >= 0) goToChapter(position);
  }

  /** A page number is a position in the reading order, so page N is position N-1. */
  async function handleGoToPage(page: number): Promise<boolean> {
    goToChapter(page - 1);
    return currentChapterIndex === page - 1;
  }

  function handleExternalTocNavigate(targetId: string | null): boolean {
    if (!targetId) return false;
    const toc = deps.getToc();
    const entry = toc.find((c) => c.id === targetId);
    if (!entry) return false;
    const frag = extractFragment(entry.href);
    const position = orderForSpine(entry.index);
    if (frag) pendingFragment = frag;
    goToChapter(position);
    return true;
  }

  /**
   * Handles CFI-driven navigation (searchTargetLocator / initialLocation).
   *
   * A CFI names a spine document, but every position this module compares and
   * navigates by is a reading-order position, so the spine index is converted
   * once here and the recorded `chapterIdx` is already in the caller's space.
   */
  function handleSearchTargetLocator(
    target: string | null,
    options: { currentChapterIndex: number },
  ): { navigated: boolean; needsScroll: boolean; chapterIdx: number | null } | null {
    if (!target || !target.startsWith('epubcfi(')) return null;
    const spineMatch = /epubcfi\(\/6\/(\d+)!/.exec(target);
    if (!spineMatch) return null;
    const spineOneBased = Number.parseInt(spineMatch[1]!, 10);
    const spineIdx = spineOneBased - 1;
    if (spineIdx < 0) return null;
    const chapterIdx = orderForSpine(spineIdx);
    if (chapterIdx < 0 || chapterIdx >= readingOrder.length) {
      logger.debug(
        'epub-toc: searchTargetLocator spine not in reading order',
        { spineIdx, totalChapters: deps.getTotalChapters(), orderLength: readingOrder.length },
        'reader',
      );
      return null;
    }
    if (chapterIdx !== options.currentChapterIndex) {
      pendingCfiScroll = target;
      goToChapter(chapterIdx);
      return { navigated: true, needsScroll: false, chapterIdx };
    }
    // Already at chapter — caller should scroll
    return { navigated: false, needsScroll: true, chapterIdx };
  }

  function clearPendingCfiScroll(): void {
    pendingCfiScroll = null;
  }

  function clearPendingFragment(): void {
    pendingFragment = null;
  }

  return {
    get currentChapterIndex(): number {
      return currentChapterIndex;
    },
    set currentChapterIndex(v: number) {
      currentChapterIndex = v;
    },
    get pendingCfiScroll(): string | null {
      return pendingCfiScroll;
    },
    set pendingCfiScroll(v: string | null) {
      pendingCfiScroll = v;
    },
    get pendingFragment(): string | null {
      return pendingFragment;
    },
    set pendingFragment(v: string | null) {
      pendingFragment = v;
    },
    get currentSpineIndex(): number {
      return currentSpineIndex;
    },
    get readingOrder(): number[] {
      return readingOrder;
    },
    get totalPages(): number {
      return readingOrder.length;
    },
    resolve,
    orderForSpine,
    goToPrev,
    goToNext,
    goToChapter,
    goToSpine,
    handleGoToPage,
    handleExternalTocNavigate,
    handleSearchTargetLocator,
    clearPendingCfiScroll,
    clearPendingFragment,
  };
}

export type EpubNavigationState = ReturnType<typeof createEpubNavigation>;
