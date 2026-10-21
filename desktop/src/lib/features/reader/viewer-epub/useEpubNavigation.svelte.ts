import { extractFragment } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import type { EpubChapterMeta } from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import { logger } from '$lib/shared/logger/Logger';

export type EpubNavigationDeps = {
  getToc: () => EpubChapterMeta[];
  getSpineHrefs: () => string[];
  getTotalChapters: () => number;
  spineIndexForToc: (tocIndex: number) => number;
  tocIndexForSpine: (spineIndex: number, spineHref?: string) => number | null;
};

export function createEpubNavigation(deps: EpubNavigationDeps): {
  currentChapterIndex: number;
  pendingCfiScroll: string | null;
  pendingFragment: string | null;
  readonly currentSpineIndex: number;
  goToPrev(): void;
  goToNext(): void;
  goToChapter(index: number): void;
  handleGoToPage(page: number): Promise<boolean>;
  handleExternalTocNavigate(targetId: string | null): boolean;
  handleSearchTargetLocator(
    target: string | null,
    options: { currentChapterIndex: number; totalChapters: number; tocLength: number },
  ): { navigated: boolean; needsScroll: boolean; chapterIdx: number | null } | null;
  clearPendingCfiScroll(): void;
  clearPendingFragment(): void;
} {
  let currentChapterIndex = $state(0);
  let pendingCfiScroll = $state<string | null>(null);
  let pendingFragment = $state<string | null>(null);

  /**
   * How many pages the reader can walk.
   *
   * The TOC is not the book: a partial NCX names 21 of this book's 43 spine
   * documents, so bounding the walk by the outline stranded every chapter body
   * behind an entry that does not exist. The spine is the book, so it wins. The
   * other two are kept only as floors for the window between a book opening and
   * its spine being read.
   */
  function walkLength(): number {
    return Math.max(deps.getSpineHrefs().length, deps.getTotalChapters(), deps.getToc().length);
  }

  /**
   * Fragment to land on when moving to `index`, or null.
   *
   * Only outline entries carry fragments, and there are fewer of them than
   * pages. Reading past the end of the array would hand back `undefined` and
   * leak an "undefined" fragment into the render.
   */
  function fragmentAt(index: number): string | null {
    const entry = deps.getToc()[index];
    return entry ? extractFragment(entry.href) : null;
  }

  /**
   * Spine position of the current page.
   *
   * Below the TOC length the current index names an outline entry, and the
   * entry carries the spine document it points at. At or above it, the index IS
   * a spine index — there is no outline entry to consult, and asking the TOC
   * mapper for one would log a miss and fall back to the same number anyway.
   */
  const currentSpineIndex = $derived.by(() => {
    const tocLen = deps.getToc().length;
    if (currentChapterIndex < tocLen) return deps.spineIndexForToc(currentChapterIndex);
    return currentChapterIndex;
  });

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
    if (nextIdx < walkLength()) {
      const frag = fragmentAt(nextIdx);
      if (frag) pendingFragment = frag;
      currentChapterIndex = nextIdx;
    }
  }

  function goToChapter(index: number): void {
    if (index >= 0 && index < walkLength()) {
      const frag = fragmentAt(index);
      if (frag) pendingFragment = frag;
      else pendingFragment = null;
      currentChapterIndex = index;
    }
  }

  /**
   * A numbered page is a spine document, so page N is spine N-1. Resolving
   * through the TOC would send "9" to the ninth *outline entry* (spine 24 in
   * this book) instead of the ninth document.
   */
  async function handleGoToPage(page: number): Promise<boolean> {
    goToChapter(page - 1);
    return currentChapterIndex === page - 1;
  }

  function handleExternalTocNavigate(targetId: string | null): boolean {
    if (!targetId) return false;
    const toc = deps.getToc();
    const chapterIdx = toc.findIndex((c) => c.id === targetId);
    if (chapterIdx >= 0) {
      const href = toc[chapterIdx]?.href ?? '';
      const frag = extractFragment(href);
      if (frag) pendingFragment = frag;
      goToChapter(chapterIdx);
      return true;
    }
    return false;
  }

  /**
   * Handles CFI-driven navigation (searchTargetLocator / initialLocation).
   * Parses spine index from `epubcfi(/6/N!...)`, maps to TOC, sets pendingCfiScroll,
   * and navigates or signals that a scroll is needed.
   * Returns true if navigation/scroll was triggered, false if out-of-bounds.
   * Caller is responsible for invoking `scrollToCfi` when staying on same chapter.
   */
  function handleSearchTargetLocator(
    target: string | null,
    options: { currentChapterIndex: number; totalChapters: number; tocLength: number },
  ): { navigated: boolean; needsScroll: boolean; chapterIdx: number | null } | null {
    if (!target || !target.startsWith('epubcfi(')) return null;
    const spineMatch = /epubcfi\(\/6\/(\d+)!/.exec(target);
    if (!spineMatch) return null;
    const spineOneBased = Number.parseInt(spineMatch[1]!, 10);
    const spineIdx = spineOneBased - 1;
    if (spineIdx < 0) return null;
    const mapped = deps.tocIndexForSpine(spineIdx);
    const chapterIdx = mapped !== null ? mapped : spineIdx;
    if (mapped === null) {
      logger.debug(
        'epub-toc: searchTargetLocator spine not in TOC, fallback',
        {
          spineIdx,
          fallback: chapterIdx,
          totalChapters: options.totalChapters,
          tocLen: options.tocLength,
        },
        'reader',
      );
    }
    if (mapped !== null) {
      if (chapterIdx < 0 || chapterIdx >= options.tocLength) return null;
    } else {
      if (chapterIdx < 0 || chapterIdx >= options.totalChapters) return null;
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
    goToPrev,
    goToNext,
    goToChapter,
    handleGoToPage,
    handleExternalTocNavigate,
    handleSearchTargetLocator,
    clearPendingCfiScroll,
    clearPendingFragment,
  };
}

export type EpubNavigationState = ReturnType<typeof createEpubNavigation>;
