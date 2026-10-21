import { describe, expect, it } from 'vitest';
import { createEpubNavigation } from '$lib/features/reader/viewer-epub/useEpubNavigation.svelte';
import {
  spineIndexForToc,
  tocIndexForSpine,
  normalizeHref,
  stripFragment,
} from '$lib/features/reader/viewer-epub/epubViewerHelpers';
import { buildReadingOrder } from '$lib/features/reader/viewer-epub/epubReadingOrder';

/**
 * An outline naming the last `len` documents of a `spineLength`-document book —
 * *Historia*'s shape: the spine opens with documents the NCX never names, so
 * outline position `i` is document `spineLength - len + i`.
 */
function makeToc(len = 20, spineLength = 24) {
  const offset = spineLength - len;
  return Array.from({ length: len }, (_, i) => ({
    index: i + offset,
    id: `ch-${i}`,
    label: `Chapter ${i + 1}`,
    href: `OEBPS/Text/ch-${i + offset}.xhtml`,
    depth: 0,
  }));
}

function makeNav(spineLength: number, toc: ReturnType<typeof makeToc>, totalChapters?: number) {
  return createEpubNavigation({
    getToc: () => toc as never,
    getSpineHrefs: () => Array.from({ length: spineLength }, (_, i) => `spine-${i}.xhtml`),
    getTotalChapters: () => totalChapters ?? spineLength,
  });
}

/** Documents in the measured book: 43 documents, an outline naming 21 of them. */
const MEASURED_TOC_INDEXES = [
  0, 3, 4, 5, 6, 7, 10, 13, 16, 19, 22, 25, 28, 31, 34, 37, 38, 39, 40, 41, 42,
];

function measuredToc() {
  return MEASURED_TOC_INDEXES.map((index) => ({
    index,
    id: `ch-${index}`,
    label: `Chapter ${index}`,
    href: `OEBPS/Text/ch-${index}.xhtml`,
    depth: 0,
  }));
}

describe('useEpubNavigation — one index space (the reading order)', () => {
  describe('pure helpers parity (still exported for the TOC panel)', () => {
    it('spineIndexForToc maps outline position to its document', () => {
      const toc = makeToc(20, 24);
      expect(spineIndexForToc(toc as any, 5)).toBe(9);
      expect(spineIndexForToc(toc as any, 0)).toBe(4);
      expect(spineIndexForToc(toc as any, 19)).toBe(23);
    });

    it('tocIndexForSpine maps a document back and returns null when not in TOC', () => {
      const toc = makeToc(20, 24);
      expect(tocIndexForSpine(toc as any, 9)).toBe(5);
      expect(tocIndexForSpine(toc as any, 4)).toBe(0);
      expect(tocIndexForSpine(toc as any, 0)).toBeNull();
      expect(tocIndexForSpine(toc as any, 99)).toBeNull();
    });

    it('normalizeHref + stripFragment handle backslashes and fragments', () => {
      expect(normalizeHref('OEBPS\\Text\\ch-1.xhtml')).toBe('OEBPS/Text/ch-1.xhtml');
      expect(stripFragment('a.xhtml#frag')).toBe('a.xhtml');
      expect(stripFragment('a.xhtml')).toBe('a.xhtml');
    });
  });

  describe('reading order', () => {
    it('covers every spine document exactly once', () => {
      const toc = makeToc(20, 24);
      const nav = makeNav(24, toc);
      expect(nav.readingOrder).toEqual(buildReadingOrder(toc, 24));
      expect([...nav.readingOrder].sort((a, b) => a - b)).toEqual(
        Array.from({ length: 24 }, (_, i) => i),
      );
      expect(nav.totalPages).toBe(24);
    });

    it('an outline that starts late leaves the earlier documents appended after it', () => {
      const nav = makeNav(24, makeToc(20, 24));
      // Outline order leads; the four documents the NCX never names are still
      // reachable, appended in ascending order after the outline closes.
      expect(nav.readingOrder.slice(0, 2)).toEqual([4, 5]);
      expect(nav.readingOrder.slice(-4)).toEqual([0, 1, 2, 3]);
      expect([...nav.readingOrder].sort((a, b) => a - b)).toEqual(
        Array.from({ length: 24 }, (_, i) => i),
      );
    });
  });

  describe('goToPrev / goToNext', () => {
    it('goToPrev does not go below 0', () => {
      const nav = makeNav(24, makeToc(20, 24));
      expect(nav.currentChapterIndex).toBe(0);
      nav.goToPrev();
      expect(nav.currentChapterIndex).toBe(0);
    });

    it('goToNext advances one reading-order position at a time to the end of the spine', () => {
      const nav = makeNav(24, makeToc(20, 24));
      for (let expected = 1; expected < 24; expected++) {
        nav.goToNext();
        expect(nav.currentChapterIndex).toBe(expected);
      }
      // The 24th position is the last one; the turn past it is a no-op.
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(23);
      expect(nav.currentSpineIndex).toBe(nav.readingOrder[23]);
    });

    it('goToNext reaches the last spine page of a partial-NCX book and stops there', () => {
      // The measured shape: 21 navPoints, 43 spine documents. Every chapter body
      // sits in a document the NCX does not name.
      const nav = makeNav(43, makeToc(21, 43));
      expect(nav.totalPages).toBe(43);
      for (let i = 1; i <= 42; i++) nav.goToNext();
      expect(nav.currentChapterIndex).toBe(42);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(42);
    });

    it('goToNext allows spine fallback when toc empty', () => {
      const nav = makeNav(3, []);
      expect(nav.readingOrder).toEqual([0, 1, 2]);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(1);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(2);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(2); // limit
    });

    it('goToChapter clamps to the order and sets pendingFragment from the outline', () => {
      const toc = makeToc(5, 5);
      toc[2]!.href = 'OEBPS/Text/ch-2.xhtml#myfrag';
      const nav = makeNav(5, toc);
      const position = nav.orderForSpine(2);
      nav.goToChapter(position);
      expect(nav.currentChapterIndex).toBe(position);
      expect(nav.pendingFragment).toBe('myfrag');
      nav.goToChapter(99); // out of bounds
      expect(nav.currentChapterIndex).toBe(position);
      nav.goToChapter(-1);
      expect(nav.currentChapterIndex).toBe(position);
    });

    it('a document the outline never names has no fragment to land on', () => {
      // 43 documents, outline naming 0, 3, 4, … — document 1 is unnamed.
      const toc = measuredToc();
      toc[0]!.href = 'OEBPS/Text/ch-0.xhtml#frag0';
      const nav = makeNav(43, toc);
      // Document 1 is the unnamed run after document 0: it must not inherit
      // document 0's fragment, and must not read past the outline array.
      nav.goToChapter(nav.orderForSpine(1));
      expect(nav.currentChapterIndex).toBe(1);
      expect(nav.pendingFragment).toBeNull();
      nav.goToChapter(nav.orderForSpine(0));
      expect(nav.pendingFragment).toBe('frag0');
    });
  });

  describe('handleGoToPage (page N is reading-order position N-1)', () => {
    it('resolves every page to a distinct position and stops at the total', async () => {
      const nav = makeNav(24, makeToc(20, 24));
      expect(await nav.handleGoToPage(9)).toBe(true);
      expect(nav.currentChapterIndex).toBe(8);
      expect(nav.currentSpineIndex).toBe(nav.readingOrder[8]);
      expect(await nav.handleGoToPage(1)).toBe(true);
      expect(nav.currentChapterIndex).toBe(0);
      expect(await nav.handleGoToPage(24)).toBe(true);
      expect(nav.currentChapterIndex).toBe(23);
      expect(await nav.handleGoToPage(25)).toBe(false);
      expect(await nav.handleGoToPage(0)).toBe(false);
    });

    it('page 30 of a partial-NCX book is order position 29, and page 43 is the last one', async () => {
      const nav = makeNav(43, makeToc(21, 43));
      expect(await nav.handleGoToPage(30)).toBe(true);
      expect(nav.currentChapterIndex).toBe(29);
      expect(await nav.handleGoToPage(43)).toBe(true);
      expect(nav.currentChapterIndex).toBe(42);
      expect(await nav.handleGoToPage(44)).toBe(false);
    });

    it('falls back to spine when toc empty', async () => {
      const nav = makeNav(3, []);
      expect(await nav.handleGoToPage(2)).toBe(true);
      expect(nav.currentChapterIndex).toBe(1);
      expect(await nav.handleGoToPage(4)).toBe(false);
    });
  });

  describe('currentSpineIndex', () => {
    it('maps an offset outline through the order, with no TOC-length branch', () => {
      // 8 documents whose outline names only 5..7.
      const nav = makeNav(8, makeToc(3, 8));
      expect(nav.readingOrder).toEqual([5, 6, 7, 0, 1, 2, 3, 4]);
      // Every position resolves to its own document — the branch that used to
      // answer "outline entry N" below `toc.length` and "document N" above it is
      // gone, so both sides of that seam agree.
      expect(nav.resolve(0).spine).toBe(5);
      expect(nav.resolve(2).spine).toBe(7);
      expect(nav.resolve(5).spine).toBe(2);
    });

    it('clamps a raw position past the end instead of returning undefined', () => {
      const nav = makeNav(24, makeToc(20, 24));
      expect(nav.resolve(99)).toEqual({ order: 23, spine: 3 });
      expect(nav.resolve(-5)).toEqual({ order: 0, spine: 4 });
    });

    it('falls back to totalChapters while the spine is not yet readable', () => {
      // Before `parse_epub` lands, `spineHrefs` is empty but the chapter count is
      // already known; the order must still cover the book.
      const nav = makeNav(0, [], 12);
      expect(nav.totalPages).toBe(12);
      expect(nav.resolve(11)).toEqual({ order: 11, spine: 11 });
    });
  });

  describe('pendingCfi / pendingFragment state', () => {
    it('pendingCfiScroll and pendingFragment are mutable state', () => {
      const nav = makeNav(5, makeToc(5, 5));
      nav.pendingCfiScroll = 'epubcfi(/6/4!...)';
      nav.pendingFragment = 'frag1';
      expect(nav.pendingCfiScroll).toBe('epubcfi(/6/4!...)');
      expect(nav.pendingFragment).toBe('frag1');
      nav.clearPendingCfiScroll();
      nav.clearPendingFragment();
      expect(nav.pendingCfiScroll).toBeNull();
      expect(nav.pendingFragment).toBeNull();
    });
  });

  describe('externalTocNavigate + searchTargetLocator', () => {
    it('handleExternalTocNavigate finds by id and sets fragment', () => {
      const toc = makeToc(5, 5);
      toc[3]!.id = 'target-id';
      toc[3]!.href = 'OEBPS/Text/ch-3.xhtml#sec1';
      const nav = makeNav(5, toc);
      const hit = nav.handleExternalTocNavigate('target-id');
      expect(hit).toBe(true);
      expect(nav.orderForSpine(3)).toBe(3);
      expect(nav.pendingFragment).toBe('sec1');
      const miss = nav.handleExternalTocNavigate('missing');
      expect(miss).toBe(false);
    });

    it('handleSearchTargetLocator converts a CFI spine index to a reading position', () => {
      const nav = makeNav(24, makeToc(20, 24));
      nav.currentChapterIndex = 0;
      const cfi = 'epubcfi(/6/10!/4/2)'; // 1-based spine 10 → document 9
      const res = nav.handleSearchTargetLocator(cfi, {
        currentChapterIndex: nav.currentChapterIndex,
      });
      expect(res?.navigated).toBe(true);
      expect(res?.chapterIdx).toBe(nav.orderForSpine(9));
      expect(nav.currentChapterIndex).toBe(nav.orderForSpine(9));
      expect(nav.pendingCfiScroll).toBe(cfi);
      // same position should signal needsScroll
      const res2 = nav.handleSearchTargetLocator(cfi, {
        currentChapterIndex: nav.currentChapterIndex,
      });
      expect(res2?.needsScroll).toBe(true);
      expect(res2?.navigated).toBe(false);
    });

    it('handleSearchTargetLocator returns null for invalid CFI and out-of-range spine', () => {
      const nav = makeNav(5, makeToc(5, 5));
      expect(nav.handleSearchTargetLocator('not-a-cfi', { currentChapterIndex: 0 })).toBeNull();
      expect(
        nav.handleSearchTargetLocator('epubcfi(/6/999!/4/2)', { currentChapterIndex: 0 }),
      ).toBeNull();
    });

    it('resolves a chapter body the outline never names', () => {
      const nav = makeNav(43, measuredToc());
      // Document 9 is chapter 5's body — no NCX entry, so a CFI is the only thing
      // that can name it. It must still be reachable.
      const res = nav.handleSearchTargetLocator('epubcfi(/6/10!/4/2)', {
        currentChapterIndex: 0,
      });
      expect(res?.navigated).toBe(true);
      expect(nav.currentSpineIndex).toBe(9);
    });
  });

  describe('goToSpine', () => {
    it('jumps to the reading position of a document, and ignores unknown ones', () => {
      const nav = makeNav(43, measuredToc());
      nav.orderForSpine(9);
      nav.goToChapter(nav.orderForSpine(9));
      expect(nav.readingOrder[nav.currentChapterIndex]).toBe(9);
      const before = nav.currentChapterIndex;
      nav.goToSpine(999);
      expect(nav.currentChapterIndex).toBe(before);
      nav.goToSpine(9);
      expect(nav.readingOrder[nav.currentChapterIndex]).toBe(9);
    });
  });
});
