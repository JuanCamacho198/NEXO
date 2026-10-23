import type PdfViewer from '../viewer-pdf/PdfViewer.svelte';
import type EpubNativeViewer from '../viewer-epub/EpubNativeViewer.svelte';
import type { LibraryBookDto } from '$lib/shared/types/library';

type ActiveBook = LibraryBookDto & { filePath: string };

export type ViewerKind = 'pdf' | 'epub';

export type ViewerSelection = {
  text: string;
  bounds: { left: number; top: number; right: number; bottom: number };
  container: { left: number; top: number; width: number; height: number };
  placement: string;
  rects: Array<{ left: number; top: number; width: number; height: number }>;
  pageNumber: number;
  cfi?: string | null;
  // EPUB evidence (REQ-DRE-005): the containing paragraph and chapter label the
  // iframe extracted. Both are absent for PDF and for a selection with no block
  // ancestor. No `selectionOffset`/`blockTag`: no column and no consumer exists.
  quote?: string | null;
  chapterTitle?: string | null;
};

export type ViewerHandle = {
  readonly kind: ViewerKind;
  navigatePrev(): boolean | Promise<boolean>;
  navigateNext(): boolean | Promise<boolean>;
  goToPage(n: number): Promise<boolean>;
  setScaleOrZoom(pct: number): void;
  getScaleOrZoom(): number;
  getCurrentPage(): number;
  getTotalForHeader(): number;
  /**
   * Whether a forward turn can still do something. Optional because the numbers
   * the bar already holds answer it for a fixed total; a total that GROWS while
   * the reader walks (the EPUB whole-book count) cannot be asked, so the viewer
   * answers instead.
   */
  getCanGoNext?(): boolean;
  /**
   * The mirror of `getCanGoNext`, for the same reason: in paginated mode the page
   * field's "page 1" is the first COLUMN of a chapter, which a reader can leave by
   * turning back into the previous chapter.
   */
  getCanGoPrev?(): boolean;
};

export function createViewerSelection(
  getRefs: () => { pdf: PdfViewer | null; epub: EpubNativeViewer | null },
  getBook: () => ActiveBook | null,
): ViewerHandle {
  const getKind = (): ViewerKind => {
    const fmt = getBook()?.format?.toLowerCase();
    if (fmt === 'pdf') return 'pdf';
    if (fmt === 'epub') return 'epub';
    const refs = getRefs();
    if (refs.pdf) return 'pdf';
    if (refs.epub) return 'epub';
    return 'pdf';
  };

  return {
    get kind(): ViewerKind {
      return getKind();
    },
    navigatePrev(): boolean | Promise<boolean> {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as {
          navigateToPage?: (n: number) => Promise<boolean>;
          getCurrentPage?: () => number;
        } | null;
        if (!pdf?.navigateToPage) return false;
        const cur = pdf.getCurrentPage?.() ?? 1;
        if (cur <= 1) return false;
        return pdf.navigateToPage(cur - 1);
      }
      const epub = refs.epub as unknown as {
        goToPrev?: () => void;
        getCurrentPage?: () => number;
      } | null;
      if (!epub?.goToPrev) return false;
      // No page-number guard here, deliberately. In paginated mode
      // `getCurrentPage()` is the COLUMN of the chapter on screen, so "page 1"
      // describes the first column of EVERY chapter — refusing the turn there is
      // what made Prev dead at the start of every chapter (measured: a press at
      // page 1 of a 26-column chapter moved nothing in 9s). The viewer owns the
      // boundary: `goToPrev()` turns within the chapter and falls through to the
      // previous one, and `getCanGoPrev()` says whether that is possible at all.
      epub.goToPrev();
      return true;
    },
    navigateNext(): boolean | Promise<boolean> {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as {
          navigateToPage?: (n: number) => Promise<boolean>;
          getCurrentPage?: () => number;
          getTotalPages?: () => number;
        } | null;
        if (!pdf?.navigateToPage) return false;
        const cur = pdf.getCurrentPage?.() ?? 1;
        const total = pdf.getTotalPages?.() ?? 0;
        if (total > 0 && cur >= total) return false;
        return pdf.navigateToPage(cur + 1);
      }
      const epub = refs.epub as unknown as { goToNext?: () => void } | null;
      if (!epub?.goToNext) return false;
      epub.goToNext();
      return true;
    },
    goToPage(n: number): Promise<boolean> {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as {
          navigateToPage?: (n: number) => Promise<boolean>;
        } | null;
        if (!pdf?.navigateToPage) return Promise.resolve(false);
        return pdf.navigateToPage(n);
      }
      const epub = refs.epub as unknown as {
        handleGoToPage?: (n: number) => Promise<boolean>;
      } | null;
      if (!epub?.handleGoToPage) return Promise.resolve(false);
      return epub.handleGoToPage(n);
    },
    setScaleOrZoom(pct: number): void {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as { setScale?: (v: number) => void } | null;
        pdf?.setScale?.(pct / 100);
        return;
      }
      const epub = refs.epub as unknown as { setZoom?: (v: number) => void } | null;
      epub?.setZoom?.(pct);
    },
    getScaleOrZoom(): number {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as { getScale?: () => number } | null;
        return Math.round((pdf?.getScale?.() ?? 1) * 100);
      }
      const epub = refs.epub as unknown as { getZoomPercent?: () => number } | null;
      return epub?.getZoomPercent?.() ?? 100;
    },
    getCurrentPage(): number {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as { getCurrentPage?: () => number } | null;
        return pdf?.getCurrentPage?.() ?? 1;
      }
      const epub = refs.epub as unknown as { getCurrentPage?: () => number } | null;
      return epub?.getCurrentPage?.() ?? 1;
    },
    getTotalForHeader(): number {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as { getTotalPages?: () => number } | null;
        return pdf?.getTotalPages?.() ?? 0;
      }
      const epub = refs.epub as unknown as {
        getTotalForHeader?: () => number;
        getTotalPages?: () => number;
      } | null;
      if (epub?.getTotalForHeader) return epub.getTotalForHeader();
      if (epub?.getTotalPages) return epub.getTotalPages();
      return 0;
    },
    getCanGoNext(): boolean {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as {
          getCurrentPage?: () => number;
          getTotalPages?: () => number;
        } | null;
        const cur = pdf?.getCurrentPage?.() ?? 1;
        const total = pdf?.getTotalPages?.() ?? 0;
        return !(total > 0 && cur >= total);
      }
      const epub = refs.epub as unknown as { getCanGoNext?: () => boolean } | null;
      // No answer from the viewer means no reason to block the press: the turn
      // itself is the authority and it refuses when it cannot move.
      return epub?.getCanGoNext?.() ?? true;
    },
    getCanGoPrev(): boolean {
      const kind = getKind();
      const refs = getRefs();
      if (kind === 'pdf') {
        const pdf = refs.pdf as unknown as { getCurrentPage?: () => number } | null;
        return (pdf?.getCurrentPage?.() ?? 1) > 1;
      }
      const epub = refs.epub as unknown as { getCanGoPrev?: () => boolean } | null;
      return epub?.getCanGoPrev?.() ?? true;
    },
  };
}
