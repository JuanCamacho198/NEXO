/**
 * usePdfOutline — flattenOutline + outlinePageCache + dest→page (PR-P2-3).
 * Extracts TOC flattening, outline cache and destination resolution from
 * PdfViewer. Re-exports `flattenOutline` for unit testing.
 */
import * as pdfjsLib from 'pdfjs-dist';
import { loadPdfOutline } from '$lib/features/reader/viewer-pdf/pdfStreaming';
import { isPageWithinBounds } from '$lib/features/reader/viewer-pdf/pdfNavigation';
import {
  isRefLike,
  flattenOutline as pureFlattenOutline,
} from '$lib/features/reader/viewer-pdf/pdfSelection';
import type { PdfOutlineItem } from '$lib/shared/types';

export { pureFlattenOutline as flattenOutline };
export type { PdfOutlineItem };

export type PdfOutlineDeps = {
  getPdfDoc: () => pdfjsLib.PDFDocumentProxy | null;
  getFilePath: () => string;
  getTotalPages: () => number;
  t: (key: string, params?: Record<string, string | number>) => string;
};

export function createPdfOutlineState(deps: PdfOutlineDeps): {
  outline: PdfOutlineItem[];
  tocLoading: boolean;
  tocError: string | null;
  outlineDeferred: boolean;
  readonly flatOutline: Array<{ item: PdfOutlineItem; depth: number }>;
  outlinePageCache: Map<string, number>;
  resolveDestinationPage(dest: string | unknown[] | null): Promise<number | null>;
  ensureOutlineLoaded(showToc: boolean): Promise<void>;
  clearOutlineCache(): void;
  flattenOutline: typeof pureFlattenOutline;
} {
  let outline = $state<PdfOutlineItem[]>([]);
  let tocLoading = $state(false);
  let tocError = $state<string | null>(null);
  let outlineDeferred = $state(false);
  const outlinePageCache = new Map<string, number>();

  type PdfRefProxy = Parameters<pdfjsLib.PDFDocumentProxy['getPageIndex']>[0];

  const flatOutline = $derived(pureFlattenOutline(outline));

  async function resolveDestinationPage(dest: string | unknown[] | null): Promise<number | null> {
    const pdfDoc = deps.getPdfDoc();
    const totalPages = deps.getTotalPages();
    if (!pdfDoc || !dest || totalPages <= 0) return null;
    try {
      const resolvedDest = typeof dest === 'string' ? await pdfDoc.getDestination(dest) : dest;
      if (!Array.isArray(resolvedDest) || resolvedDest.length === 0) return null;
      const target = resolvedDest[0];
      if (typeof target === 'number' && Number.isFinite(target)) {
        return isPageWithinBounds(target + 1, totalPages) ? target + 1 : null;
      }
      if (!isRefLike(target)) return null;
      const cacheKey = `${target.num}:${target.gen}`;
      const cachedPage = outlinePageCache.get(cacheKey);
      if (cachedPage && isPageWithinBounds(cachedPage, totalPages)) return cachedPage;
      const pageIndex = await pdfDoc.getPageIndex(target as PdfRefProxy);
      const pageNumber = pageIndex + 1;
      if (!isPageWithinBounds(pageNumber, totalPages)) return null;
      outlinePageCache.set(cacheKey, pageNumber);
      return pageNumber;
    } catch {
      return null;
    }
  }

  async function ensureOutlineLoaded(showToc: boolean): Promise<void> {
    const pdfDoc = deps.getPdfDoc();
    if (!showToc || !pdfDoc) return;
    if (outlineDeferred) {
      if (outline.length > 0 || tocLoading) return;
    }
    outlineDeferred = true;
    tocLoading = true;
    tocError = null;
    try {
      const items = await loadPdfOutline(pdfDoc, deps.getFilePath());
      outline = items;
      tocError = null;
      outlineDeferred = true;
    } catch {
      outline = [];
      tocError = deps.t('pdf.tocLoadFailed');
      outlineDeferred = false;
    } finally {
      tocLoading = false;
    }
  }

  function clearOutlineCache(): void {
    outlinePageCache.clear();
  }

  return {
    get outline(): PdfOutlineItem[] {
      return outline;
    },
    set outline(v: PdfOutlineItem[]) {
      outline = v;
    },
    get tocLoading(): boolean {
      return tocLoading;
    },
    set tocLoading(v: boolean) {
      tocLoading = v;
    },
    get tocError(): string | null {
      return tocError;
    },
    set tocError(v: string | null) {
      tocError = v;
    },
    get outlineDeferred(): boolean {
      return outlineDeferred;
    },
    set outlineDeferred(v: boolean) {
      outlineDeferred = v;
    },
    get flatOutline(): Array<{ item: PdfOutlineItem; depth: number }> {
      return flatOutline;
    },
    outlinePageCache,
    resolveDestinationPage,
    ensureOutlineLoaded,
    clearOutlineCache,
    flattenOutline: pureFlattenOutline,
  };
}

export type PdfOutlineState = ReturnType<typeof createPdfOutlineState>;
