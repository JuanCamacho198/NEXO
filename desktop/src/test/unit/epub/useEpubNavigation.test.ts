import { describe, expect, it } from 'vitest';
import { createEpubNavigation } from '$lib/features/reader/viewer-epub/useEpubNavigation.svelte';
import {
  spineIndexForToc,
  tocIndexForSpine,
  normalizeHref,
  stripFragment,
} from '$lib/features/reader/viewer-epub/epubViewerHelpers';

function makeToc(offset = 2, len = 20) {
  return Array.from({ length: len }, (_, i) => ({
    index: i + offset,
    id: `ch-${i}`,
    label: `Chapter ${i + 1}`,
    href: `OEBPS/Text/ch-${i + offset}.xhtml`,
    depth: 0,
  }));
}

describe('useEpubNavigation — offset-2 + limits', () => {
  describe('pure helpers parity (re-exported)', () => {
    it('spineIndexForToc maps TOC 0→2 offset-2', () => {
      const toc = makeToc(2, 20);
      expect(spineIndexForToc(toc as any, 5)).toBe(7);
      expect(spineIndexForToc(toc as any, 0)).toBe(2);
      expect(spineIndexForToc(toc as any, 19)).toBe(21);
    });

    it('tocIndexForSpine maps spine 7→5 and returns null when not in TOC', () => {
      const toc = makeToc(2, 20);
      expect(tocIndexForSpine(toc as any, 7)).toBe(5);
      expect(tocIndexForSpine(toc as any, 2)).toBe(0);
      expect(tocIndexForSpine(toc as any, 0)).toBeNull();
      expect(tocIndexForSpine(toc as any, 99)).toBeNull();
    });

    it('normalizeHref + stripFragment handle backslashes and fragments', () => {
      expect(normalizeHref('OEBPS\\Text\\ch-1.xhtml')).toBe('OEBPS/Text/ch-1.xhtml');
      expect(stripFragment('a.xhtml#frag')).toBe('a.xhtml');
      expect(stripFragment('a.xhtml')).toBe('a.xhtml');
    });
  });

  describe('goToPrev / goToNext limits (tocLen vs totalChapters)', () => {
    it('goToPrev does not go below 0', () => {
      const toc = makeToc(2, 20);
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => Array.from({ length: 24 }, (_, i) => `spine-${i}.xhtml`),
        getTotalChapters: () => 24,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
      expect(nav.currentChapterIndex).toBe(0);
      nav.goToPrev();
      expect(nav.currentChapterIndex).toBe(0);
    });

    it('goToNext walks past the TOC length up to the spine length', () => {
      const toc = makeToc(2, 20);
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => Array.from({ length: 24 }, (_, i) => `spine-${i}.xhtml`),
        getTotalChapters: () => 24,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
      nav.currentChapterIndex = 19;
      nav.goToNext();
      // Past the last outline entry the index is a spine index; nothing clamps
      // it to the TOC, so the walk continues through the untitled documents.
      expect(nav.currentChapterIndex).toBe(20);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(21);
      // clamp at the spine length, not at the TOC
      nav.currentChapterIndex = 23;
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(23);
    });

    it('goToNext reaches the last spine page of a partial-NCX book and stops there', () => {
      // The measured shape: 21 navPoints, 43 spine documents. Every chapter body
      // sits in a document the NCX does not name.
      const toc = makeToc(0, 21);
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => Array.from({ length: 43 }, (_, i) => `spine-${i}.xhtml`),
        getTotalChapters: () => 43,
        spineIndexForToc: (i) => i,
        tocIndexForSpine: () => null,
      });
      nav.currentChapterIndex = 20;
      for (let i = 21; i <= 42; i++) nav.goToNext();
      expect(nav.currentChapterIndex).toBe(42);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(42);
    });

    it('goToNext allows spine fallback when toc empty', () => {
      const nav = createEpubNavigation({
        getToc: () => [],
        getSpineHrefs: () => ['a.xhtml', 'b.xhtml', 'c.xhtml'],
        getTotalChapters: () => 3,
        spineIndexForToc: (i) => i,
        tocIndexForSpine: () => null,
      });
      expect(nav.currentChapterIndex).toBe(0);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(1);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(2);
      nav.goToNext();
      expect(nav.currentChapterIndex).toBe(2); // limit
    });

    it('goToChapter clamps to limit and sets pendingFragment', () => {
      const toc = makeToc(2, 5);
      // inject href with fragment for index 2
      toc[2]!.href = 'OEBPS/Text/ch-4.xhtml#myfrag';
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => Array.from({ length: 24 }, (_, i) => `spine-${i}.xhtml`),
        getTotalChapters: () => 24,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
      nav.goToChapter(2);
      expect(nav.currentChapterIndex).toBe(2);
      expect(nav.pendingFragment).toBe('myfrag');
      nav.goToChapter(99); // out of bounds
      expect(nav.currentChapterIndex).toBe(2);
      nav.goToChapter(-1);
      expect(nav.currentChapterIndex).toBe(2);
    });
  });

  describe('handleGoToPage (page N is spine N-1)', () => {
    it('resolves every page to the spine document of the same number', async () => {
      const toc = makeToc(2, 20);
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => Array.from({ length: 24 }, (_, i) => `spine-${i}.xhtml`),
        getTotalChapters: () => 24,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
      // Page 9 is the ninth document, not the ninth outline entry (which would
      // be spine 11 in an offset-2 book).
      expect(await nav.handleGoToPage(9)).toBe(true);
      expect(nav.currentChapterIndex).toBe(8);
      expect(await nav.handleGoToPage(1)).toBe(true);
      expect(nav.currentChapterIndex).toBe(0);
      // The walk is bounded by the spine (24), not by the TOC (20).
      expect(await nav.handleGoToPage(20)).toBe(true);
      expect(nav.currentChapterIndex).toBe(19);
      expect(await nav.handleGoToPage(24)).toBe(true);
      expect(nav.currentChapterIndex).toBe(23);
      expect(await nav.handleGoToPage(25)).toBe(false);
      expect(await nav.handleGoToPage(0)).toBe(false);
    });

    it('handleGoToPage(30) lands on spine 29 of a partial-NCX book', async () => {
      const toc = makeToc(0, 21);
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => Array.from({ length: 43 }, (_, i) => `spine-${i}.xhtml`),
        getTotalChapters: () => 43,
        spineIndexForToc: (i) => i,
        tocIndexForSpine: () => null,
      });
      expect(await nav.handleGoToPage(30)).toBe(true);
      expect(nav.currentChapterIndex).toBe(29);
      // Page 43 is reachable and page 44 is not, because the spine says 43.
      expect(await nav.handleGoToPage(43)).toBe(true);
      expect(nav.currentChapterIndex).toBe(42);
      expect(await nav.handleGoToPage(44)).toBe(false);
    });

    it('falls back to spine when toc empty', async () => {
      const nav = createEpubNavigation({
        getToc: () => [],
        getSpineHrefs: () => ['a.xhtml', 'b.xhtml', 'c.xhtml'],
        getTotalChapters: () => 3,
        spineIndexForToc: (i) => i,
        tocIndexForSpine: () => null,
      });
      expect(await nav.handleGoToPage(2)).toBe(true);
      expect(nav.currentChapterIndex).toBe(1);
      expect(await nav.handleGoToPage(4)).toBe(false);
    });
  });

  describe('pendingCfi/ Fragment and currentSpineIndex offset-2', () => {
    it('currentSpineIndex derives via spineIndexForToc (TOC 5 → spine 7)', () => {
      const toc = makeToc(2, 20);
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => Array.from({ length: 24 }, (_, i) => `spine-${i}.xhtml`),
        getTotalChapters: () => 24,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
      nav.currentChapterIndex = 5;
      expect(nav.currentSpineIndex).toBe(7);
    });

    it('pendingCfiScroll and pendingFragment are mutable state', () => {
      const toc = makeToc(2, 5);
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => [],
        getTotalChapters: () => 5,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
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
      const toc = makeToc(2, 5);
      toc[3]!.id = 'target-id';
      toc[3]!.href = 'OEBPS/Text/ch-5.xhtml#sec1';
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => [],
        getTotalChapters: () => 5,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
      const hit = nav.handleExternalTocNavigate('target-id');
      expect(hit).toBe(true);
      expect(nav.currentChapterIndex).toBe(3);
      expect(nav.pendingFragment).toBe('sec1');
      const miss = nav.handleExternalTocNavigate('missing');
      expect(miss).toBe(false);
    });

    it('handleSearchTargetLocator parses spine 1-based and navigates with pendingCfi', () => {
      const toc = makeToc(2, 20); // spine 7 → toc 5
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => Array.from({ length: 24 }, (_, i) => `spine-${i}.xhtml`),
        getTotalChapters: () => 24,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
      nav.currentChapterIndex = 0;
      const cfi = 'epubcfi(/6/8!/4/2)'; // spine 8 → 1-based → spineIdx 7 → toc 5
      const res = nav.handleSearchTargetLocator(cfi, {
        currentChapterIndex: nav.currentChapterIndex,
        totalChapters: 24,
        tocLength: toc.length,
      });
      expect(res?.navigated).toBe(true);
      expect(nav.currentChapterIndex).toBe(5);
      expect(nav.pendingCfiScroll).toBe(cfi);
      // same chapter should signal needsScroll
      const res2 = nav.handleSearchTargetLocator(cfi, {
        currentChapterIndex: nav.currentChapterIndex,
        totalChapters: 24,
        tocLength: toc.length,
      });
      expect(res2?.needsScroll).toBe(true);
      expect(res2?.navigated).toBe(false);
    });

    it('handleSearchTargetLocator returns null for invalid CFI', () => {
      const toc = makeToc(2, 5);
      const nav = createEpubNavigation({
        getToc: () => toc as any,
        getSpineHrefs: () => [],
        getTotalChapters: () => 5,
        spineIndexForToc: (i) => spineIndexForToc(toc as any, i),
        tocIndexForSpine: (i, href) => tocIndexForSpine(toc as any, i, href),
      });
      expect(
        nav.handleSearchTargetLocator('not-a-cfi', {
          currentChapterIndex: 0,
          totalChapters: 5,
          tocLength: 5,
        }),
      ).toBeNull();
      expect(
        nav.handleSearchTargetLocator('epubcfi(/6/999!/4/2)', {
          currentChapterIndex: 0,
          totalChapters: 5,
          tocLength: 5,
        }),
      ).toBeNull(); // out of bounds
    });
  });
});
