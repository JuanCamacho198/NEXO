/**
 * usePdfDocument — pdfjs load + pdfStreaming cache + outline deferred (PR-P2-3).
 * Mirrors `useEpubSpine` granularity: owns pdfDoc lifecycle, totalPages,
 * loading state and stale-load guard. Outline deferred is handled by
 * `usePdfOutline`, but this composable owns the document cache.
 */
import * as pdfjsLib from 'pdfjs-dist';
import {
  createPdfDocument,
  clearDocumentCache,
  removeCachedDocument,
  setCachedDocument,
} from '$lib/features/reader/viewer-pdf/pdfStreaming';

export type PdfDocumentDeps = {
  getFilePath: () => string;
  getPreloadedBytes: () => Uint8Array | null;
  onProgress?: (loaded: number, total: number) => void;
};

export function createPdfDocumentState(deps: PdfDocumentDeps): {
  pdfDoc: pdfjsLib.PDFDocumentProxy | null;
  totalPages: number;
  isLoading: boolean;
  loadProgress: number;
  loadProgressMax: number;
  error: string | null;
  lastLoadedFilePath: string | null;
  readonly activeLoadRequestId: number;
  isStaleLoad: (requestId: number) => boolean;
  loadPdf(): Promise<{
    pdfDoc: pdfjsLib.PDFDocumentProxy | null;
    loadRequestId: number;
    totalPages: number;
    error: string | null;
  }>;
  destroyCurrentDocument(): Promise<void>;
  destroyActiveLoadingTask(): void;
  cleanup(): void;
  clearDocumentCache: typeof clearDocumentCache;
  removeCachedDocument: typeof removeCachedDocument;
} {
  let pdfDoc = $state<pdfjsLib.PDFDocumentProxy | null>(null);
  let totalPages = $state(0);
  let isLoading = $state(true);
  let loadProgress = $state(0);
  let loadProgressMax = $state(0);
  let error = $state<string | null>(null);
  let lastLoadedFilePath = $state<string | null>(null);

  let activeLoadRequestId = 0;
  let activeLoadingTask: pdfjsLib.PDFDocumentLoadingTask | null = null;
  const USE_PRELOAD_THRESHOLD = 5 * 1024 * 1024;

  const isStaleLoad = (requestId: number): boolean => requestId !== activeLoadRequestId;

  function resetForNewLoad(): number {
    const loadRequestId = ++activeLoadRequestId;
    isLoading = true;
    loadProgress = 0;
    loadProgressMax = 0;
    error = null;
    return loadRequestId;
  }

  async function destroyCurrentDocument(): Promise<void> {
    if (!pdfDoc) return;
    const current = pdfDoc;
    pdfDoc = null;
    const cachedPath = lastLoadedFilePath ?? deps.getFilePath();
    removeCachedDocument(cachedPath);
    try {
      await current.loadingTask.destroy();
    } catch {
      /* swallow */
    }
  }

  function destroyActiveLoadingTask(): void {
    if (!activeLoadingTask) return;
    activeLoadingTask.destroy();
    activeLoadingTask = null;
  }

  async function loadPdf(): Promise<{
    pdfDoc: pdfjsLib.PDFDocumentProxy | null;
    loadRequestId: number;
    totalPages: number;
    error: string | null;
  }> {
    const filePath = deps.getFilePath();
    if (!filePath) {
      return { pdfDoc: null, loadRequestId: activeLoadRequestId, totalPages, error };
    }
    const loadRequestId = resetForNewLoad();
    const preloadedBytes = deps.getPreloadedBytes();

    try {
      let loadedDoc: pdfjsLib.PDFDocumentProxy;

      if (
        preloadedBytes &&
        preloadedBytes.length > 0 &&
        preloadedBytes.length <= USE_PRELOAD_THRESHOLD
      ) {
        const loadingTask = pdfjsLib.getDocument({ data: new Uint8Array(preloadedBytes) });
        loadingTask.onProgress = (progress: { loaded: number; total: number }) => {
          loadProgress = progress.loaded;
          loadProgressMax = progress.total;
        };
        loadedDoc = await loadingTask.promise;
        setCachedDocument(filePath, { document: loadedDoc, outline: [], outlineLoaded: false });
      } else {
        const result = await createPdfDocument(filePath, {
          onProgress: (loaded, total) => {
            loadProgress = loaded;
            loadProgressMax = total;
            deps.onProgress?.(loaded, total);
          },
        });
        loadedDoc = result.document;
      }

      if (isStaleLoad(loadRequestId)) {
        try {
          await loadedDoc.loadingTask.destroy();
        } catch {
          /* swallow stale */
        }
        return { pdfDoc: null, loadRequestId, totalPages, error: null };
      }

      pdfDoc = loadedDoc;
      totalPages = loadedDoc.numPages;
      lastLoadedFilePath = filePath;
      error = null;

      return { pdfDoc: loadedDoc, loadRequestId, totalPages, error: null };
    } catch (err) {
      if (isStaleLoad(loadRequestId)) {
        return { pdfDoc: null, loadRequestId, totalPages, error: null };
      }
      const msg = err instanceof Error ? err.message : 'Failed to load PDF';
      error = msg;
      return { pdfDoc: null, loadRequestId, totalPages, error: msg };
    } finally {
      if (!isStaleLoad(loadRequestId)) {
        isLoading = false;
        activeLoadingTask = null;
      }
    }
  }

  function cleanup(): void {
    activeLoadRequestId += 1;
    destroyActiveLoadingTask();
    void destroyCurrentDocument();
    clearDocumentCache();
  }

  return {
    get pdfDoc(): pdfjsLib.PDFDocumentProxy | null {
      return pdfDoc;
    },
    set pdfDoc(v: pdfjsLib.PDFDocumentProxy | null) {
      pdfDoc = v;
    },
    get totalPages(): number {
      return totalPages;
    },
    set totalPages(v: number) {
      totalPages = v;
    },
    get isLoading(): boolean {
      return isLoading;
    },
    set isLoading(v: boolean) {
      isLoading = v;
    },
    get loadProgress(): number {
      return loadProgress;
    },
    set loadProgress(v: number) {
      loadProgress = v;
    },
    get loadProgressMax(): number {
      return loadProgressMax;
    },
    set loadProgressMax(v: number) {
      loadProgressMax = v;
    },
    get error(): string | null {
      return error;
    },
    set error(v: string | null) {
      error = v;
    },
    get lastLoadedFilePath(): string | null {
      return lastLoadedFilePath;
    },
    set lastLoadedFilePath(v: string | null) {
      lastLoadedFilePath = v;
    },
    get activeLoadRequestId(): number {
      return activeLoadRequestId;
    },
    isStaleLoad,
    loadPdf,
    destroyCurrentDocument,
    destroyActiveLoadingTask,
    cleanup,
    clearDocumentCache,
    removeCachedDocument,
  };
}

export type PdfDocumentState = ReturnType<typeof createPdfDocumentState>;
