import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { createReaderNavigation } from '$lib/features/reader/chrome/useReaderNavigation.svelte';

const makePdfBook = (over: Record<string, unknown> = {}): any => ({
  id: 'book-1',
  title: 'Book',
  filePath: 'C:/book.pdf',
  format: 'pdf',
  currentPage: 1,
  totalPages: 10,
  ...over,
});

const makeEpubBook = (over: Record<string, unknown> = {}): any => ({
  id: 'book-2',
  title: 'EPUB',
  filePath: 'C:/book.epub',
  format: 'epub',
  currentPage: 1,
  totalPages: 10,
  ...over,
});

describe('useReaderNavigation', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('headerCurrentPage/TotalPages for pdf', () => {
    const book: any = makePdfBook();
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    nav.handlePdfPageChange(3, 10);
    expect(nav.headerCurrentPage).toBe(3);
    expect(nav.headerTotalPages).toBe(10);
    expect(nav.currentPdfPage).toBe(3);
    expect(nav.totalPdfPages).toBe(10);
  });

  it('headerCurrentPage for epub is chapter+1 and total is toc length', () => {
    const book: any = makeEpubBook();
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    nav.handleTocReady([
      { id: 'a', title: 'A', depth: 0 },
      { id: 'b', title: 'B', depth: 0 },
    ]);
    expect(nav.headerTotalPages).toBe(2);
    nav.currentEpubChapter = 1;
    expect(nav.headerCurrentPage).toBe(2);
    const nav2 = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    expect(nav2.headerTotalPages).toBe(0);
  });

  it('headerTotalPages prefers the viewer spine count over the TOC length', () => {
    // *Prisioneros de la geografía*: 21 navPoints, 43 spine documents. The
    // footer percentages the spine, so a header that reported the outline
    // disagreed with it by 22 pages and hid every chapter body from the field.
    const epub: any = {
      getTotalForHeader: vi.fn().mockReturnValue(43),
      getCurrentPage: vi.fn().mockReturnValue(1),
    };
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub }),
      getActiveBook: () => makeEpubBook(),
    });
    nav.handleTocReady(
      Array.from({ length: 21 }, (_, i) => ({ id: `c${i}`, title: `C${i}`, depth: 0 })),
    );
    expect(nav.tocEntries).toHaveLength(21);
    expect(nav.headerTotalPages).toBe(43);
  });

  it('headerTotalPages falls back to the TOC length when the viewer reports none', () => {
    const epub: any = { getTotalForHeader: vi.fn().mockReturnValue(0) };
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub }),
      getActiveBook: () => makeEpubBook(),
    });
    nav.handleTocReady([
      { id: 'a', title: 'A', depth: 0 },
      { id: 'b', title: 'B', depth: 0 },
    ]);
    expect(nav.headerTotalPages).toBe(2);
  });

  it('goPrev/Next pdf bounds disabled and delegates to pdfRef', async () => {
    const book: any = makePdfBook();
    const pdf: any = { navigateToPage: vi.fn().mockResolvedValue(true) };
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf, epub: null }),
      getActiveBook: () => book,
    });
    nav.handlePdfPageChange(1, 5);
    expect(nav.prevDisabled).toBe(true);
    expect(nav.nextDisabled).toBe(false);
    expect(nav.goPrev()).toBe(false);
    expect(pdf.navigateToPage).not.toHaveBeenCalled();
    expect(nav.goNext()).toBe(true);
    expect(pdf.navigateToPage).toHaveBeenCalledWith(2);
    nav.handlePdfPageChange(5, 5);
    expect(nav.nextDisabled).toBe(true);
    expect(nav.goNext()).toBe(false);
    const navNoRef = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    navNoRef.handlePdfPageChange(3, 5);
    expect(navNoRef.goPrev()).toBe(false);
    expect(navNoRef.goNext()).toBe(false);
  });

  it('goPrev/Next epub bounds disabled and delegates to epubRef', () => {
    const book: any = makeEpubBook();
    const epub: any = {
      goToPrev: vi.fn(),
      goToNext: vi.fn(),
      handleGoToPage: vi.fn().mockResolvedValue(true),
    };
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub }),
      getActiveBook: () => book,
    });
    nav.currentEpubChapter = 0;
    expect(nav.prevDisabled).toBe(true);
    expect(nav.goPrev()).toBe(false);
    expect(epub.goToPrev).not.toHaveBeenCalled();
    nav.currentEpubChapter = 1;
    expect(nav.prevDisabled).toBe(false);
    expect(nav.goPrev()).toBe(true);
    expect(epub.goToPrev).toHaveBeenCalled();
    expect(nav.goNext()).toBe(true);
    expect(epub.goToNext).toHaveBeenCalled();
    const navNoRef = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    navNoRef.currentEpubChapter = 1;
    expect(navNoRef.goPrev()).toBe(false);
  });

  it('handleHeaderGoToPage delegates to pdf/epub refs injected', async () => {
    let book: any = makePdfBook();
    const pdf: any = { navigateToPage: vi.fn().mockResolvedValue(true) };
    const epub: any = { handleGoToPage: vi.fn().mockResolvedValue(true) };
    const navPdf = createReaderNavigation({
      getRefs: () => ({ pdf, epub: null }),
      getActiveBook: () => book,
    });
    expect(await navPdf.handleHeaderGoToPage(2)).toBe(true);
    expect(pdf.navigateToPage).toHaveBeenCalledWith(2);
    book = makeEpubBook();
    const navEpub = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub }),
      getActiveBook: () => book,
    });
    expect(await navEpub.handleHeaderGoToPage(3)).toBe(true);
    expect(epub.handleGoToPage).toHaveBeenCalledWith(3);
    const navNone = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    expect(await navNone.handleHeaderGoToPage(1)).toBe(false);
  });

  it('handleTocReady/ handleTocNavigate updates state and closes panel', () => {
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => null,
    });
    nav.showTocPanel = true;
    nav.handleTocReady([{ id: '1', title: 'Ch1', depth: 0 }]);
    expect(nav.tocEntries).toHaveLength(1);
    const entry = { id: '1', title: 'Ch1', depth: 0 };
    nav.handleTocNavigate(entry);
    expect(nav.tocNavigate).toEqual(entry);
    expect(nav.showTocPanel).toBe(false);
    nav.toggleTocPanel();
    expect(nav.showTocPanel).toBe(true);
    nav.toggleTocPanel();
    expect(nav.showTocPanel).toBe(false);
  });

  it('handlePdfPageChange / handleEpubLocationChange callbacks', () => {
    const onPdfPageChange = vi.fn();
    const onEpubLocationChange = vi.fn();
    const book: any = makePdfBook();
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
      onPdfPageChange,
      onEpubLocationChange,
    });
    nav.handlePdfPageChange(2, 10);
    expect(onPdfPageChange).toHaveBeenCalledWith(2, 10);
    nav.handleEpubLocationChange('chapter:3', 50);
    expect(nav.currentEpubChapter).toBe(3);
    expect(onEpubLocationChange).toHaveBeenCalledWith('chapter:3', 50);
    nav.handleEpubLocationChange('epubcfi(/6/2!)', 25);
    expect(onEpubLocationChange).toHaveBeenCalledWith('epubcfi(/6/2!)', 25);
  });

  it('cleanup is no-op but callable', () => {
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => null,
    });
    expect(() => nav.cleanup()).not.toThrow();
  });

  // The header counts TOC entries, but the viewer reports a spine index (the N
  // in `epubcfi(/6/N!…)`). These two are not the same number: one spine file
  // can carry several TOC entries. Before this mapping existed the live app
  // always read "1 / T" no matter the chapter, because nothing consumed the CFI
  // the viewer actually emits.
  it('maps a spine index from the CFI to its TOC position', () => {
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => makeEpubBook(),
    });
    // Three TOC entries where the third shares entry 1's spine file — the
    // "Part heading plus its first chapter" case the extractor resolves for.
    nav.handleTocReady([
      { id: 'a', title: 'A', depth: 0, index: 0 },
      { id: 'b', title: 'B', depth: 0, index: 4 },
      { id: 'c', title: 'C', depth: 0, index: 8 },
    ]);

    // CFI is 1-based; TOC position is what the header renders.
    nav.handleEpubLocationChange('epubcfi(/6/5!', 30);
    expect(nav.currentEpubChapter).toBe(1);

    nav.handleEpubLocationChange('epubcfi(/6/9!', 70);
    expect(nav.currentEpubChapter).toBe(2);
  });

  it('keeps the last known chapter when the CFI resolves to no TOC entry', () => {
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => makeEpubBook(),
    });
    nav.handleTocReady([{ id: 'a', title: 'A', depth: 0, index: 0 }]);

    nav.handleEpubLocationChange('epubcfi(/6/1!', 10);
    expect(nav.currentEpubChapter).toBe(0);

    // Spine 42 is in no entry, and a TOC with no `index` at all is the other
    // shape of the same gap. Neither may jump the reader to the top.
    nav.handleEpubLocationChange('epubcfi(/6/42!', 90);
    expect(nav.currentEpubChapter).toBe(0);

    const noIndex = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => makeEpubBook(),
    });
    noIndex.handleTocReady([{ id: 'x', title: 'X', depth: 0 }]);
    noIndex.handleEpubLocationChange('epubcfi(/6/3!', 20);
    expect(noIndex.currentEpubChapter).toBe(0);
  });

  it('bookProgress for epub returns the received pct and updates on later events', () => {
    const book: any = makeEpubBook();
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    expect(nav.bookProgress).toBe(0);
    expect(nav.hasEpubProgress).toBe(false);
    nav.handleEpubLocationChange('epubcfi(/6/2!)', 42);
    expect(nav.bookProgress).toBe(42);
    expect(nav.hasEpubProgress).toBe(true);
    nav.handleEpubLocationChange('epubcfi(/6/2!)', 47);
    expect(nav.bookProgress).toBe(47);
  });

  it('bookProgress for epub sanitizes non-finite and out-of-range pct', () => {
    const book: any = makeEpubBook();
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    const cases: Array<[number, number]> = [
      [NaN, 0],
      [Infinity, 0],
      [-Infinity, 0],
      [-5, 0],
      [150, 100],
    ];
    for (const [pct, expected] of cases) {
      nav.handleEpubLocationChange('epubcfi(/6/2!)', pct);
      expect(nav.bookProgress).toBe(expected);
      expect(Number.isFinite(nav.bookProgress)).toBe(true);
    }
  });

  it('bookProgress for epub is correctly zero after an event carrying pct 0', () => {
    const book: any = makeEpubBook();
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    nav.handleEpubLocationChange('epubcfi(/6/2!)', 0);
    expect(nav.bookProgress).toBe(0);
    expect(nav.hasEpubProgress).toBe(true);
  });

  it('bookProgress for pdf remains the page fraction', () => {
    const book: any = makePdfBook();
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    nav.handlePdfPageChange(3, 10);
    expect(nav.bookProgress).toBe(30);
  });

  it('preserves header semantics for epub with the new progress state', () => {
    const book: any = makeEpubBook();
    const nav = createReaderNavigation({
      getRefs: () => ({ pdf: null, epub: null }),
      getActiveBook: () => book,
    });
    nav.handleTocReady([
      { id: 'a', title: 'A', depth: 0 },
      { id: 'b', title: 'B', depth: 0 },
    ]);
    nav.currentEpubChapter = 1;
    nav.handleEpubLocationChange('epubcfi(/6/2!)', 42);
    expect(nav.headerCurrentPage).toBe(2);
    expect(nav.headerTotalPages).toBe(2);
  });
});
