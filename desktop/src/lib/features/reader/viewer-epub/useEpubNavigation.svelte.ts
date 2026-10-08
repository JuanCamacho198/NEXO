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

  const currentSpineIndex = $derived(deps.spineIndexForToc(currentChapterIndex));

  function goToPrev(): void {
    if (currentChapterIndex > 0) {
      const prevIdx = currentChapterIndex - 1;
      const frag = extractFragment(deps.getToc()[prevIdx]?.href ?? '');
      if (frag) pendingFragment = frag;
      currentChapterIndex = prevIdx;
    }
  }

  function goToNext(): void {
    const tocLen = deps.getToc().length;
    const nextIdx = currentChapterIndex + 1;
    const totalChapters = deps.getTotalChapters();
    const limit = tocLen > 0 ? tocLen : totalChapters;
    if (nextIdx < limit) {
      const frag = extractFragment(deps.getToc()[nextIdx]?.href ?? '');
      if (frag) pendingFragment = frag;
      currentChapterIndex = nextIdx;
    } else if (nextIdx < totalChapters) {
      currentChapterIndex = nextIdx;
    }
  }

  function goToChapter(index: number): void {
    const tocLen = deps.getToc().length;
    const totalChapters = deps.getTotalChapters();
    const limit = tocLen > 0 ? tocLen : totalChapters;
    if (index >= 0 && index < limit) {
      const frag = extractFragment(deps.getToc()[index]?.href ?? '');
      if (frag) pendingFragment = frag;
      else pendingFragment = null;
      currentChapterIndex = index;
    } else if (index >= 0 && index < totalChapters) {
      const frag2 = extractFragment(deps.getToc()[index]?.href ?? '');
      if (frag2) pendingFragment = frag2;
      currentChapterIndex = index;
    }
  }

  async function handleGoToPage(page: number): Promise<boolean> {
    const tocLen = deps.getToc().length;
    const totalChapters = deps.getTotalChapters();
    if (tocLen > 0) {
      if (page < 1 || page > tocLen) return false;
      const tocIdx = page - 1;
      goToChapter(tocIdx);
      return true;
    }
    const spineIdx = page - 1;
    if (spineIdx < 0 || spineIdx >= totalChapters) return false;
    const mapped = deps.tocIndexForSpine(spineIdx, deps.getSpineHrefs()[spineIdx]);
    if (mapped !== null && mapped >= 0 && mapped < tocLen) {
      goToChapter(mapped);
      return true;
    }
    if (spineIdx >= 0 && spineIdx < totalChapters) {
      goToChapter(spineIdx);
      return true;
    }
    return false;
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
      logger.warn(
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
