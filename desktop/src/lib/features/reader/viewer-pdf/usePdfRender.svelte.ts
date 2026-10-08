/**
 * usePdfRender — canvas / SafeTextLayer lifecycle + renderPage stale guard (PR-P2-3).
 * Extracts render cancellation, text-layer lifecycle and device-pixel canvas
 * sizing from PdfViewer while preserving requestId / isStaleNavigation guards.
 */
import * as pdfjsLib from 'pdfjs-dist';
import { SafeTextLayer } from '$lib/features/reader/viewer-pdf/safeTextLayer';
import { debugState } from '$lib/shared/debug/debugState.svelte';
import { handleError } from '$lib/shared/utils/errors';

export type PdfRenderDeps = {
  getPdfDoc: () => pdfjsLib.PDFDocumentProxy | null;
  getScale: () => number;
  getCanvas: () => HTMLCanvasElement | undefined;
  getTextLayer: () => HTMLDivElement | undefined;
  getCanvasContainer: () => HTMLDivElement | undefined;
  getCurrentPage: () => number;
  getTotalPages: () => number;
  getShowToc: () => boolean;
  getIsFullscreen: () => boolean;
};

export function createPdfRenderState(deps: PdfRenderDeps): {
  currentPageObj: pdfjsLib.PDFPageProxy | null;
  readonly activeNavigationRequestId: number;
  activeRenderTask: pdfjsLib.RenderTask | null;
  readonly textLayerInstance: SafeTextLayer | null;
  isStaleNavigation(requestId: number): boolean;
  nextNavigationRequestId(): number;
  bumpNavigationId(): number;
  cancelTextLayer(): void;
  cancelActiveRenderTask(): Promise<void>;
  renderPage(
    pageNum: number,
    options?: { requestId?: number; renderScale?: number },
  ): Promise<boolean | void>;
  renderTextLayer(
    textContent: {
      items: Array<{ str: string; transform: number[]; width: number; height: number }>;
    },
    viewport: pdfjsLib.PageViewport,
    requestId?: number,
  ): Promise<void>;
  cleanup(): void;
} {
  let currentPageObj: pdfjsLib.PDFPageProxy | null = null;
  let activeRenderTask: pdfjsLib.RenderTask | null = null;
  let textLayerInstance: SafeTextLayer | null = null;
  let activeNavigationRequestId = 0;

  const isStaleNavigation = (requestId: number): boolean => requestId !== activeNavigationRequestId;

  function nextNavigationRequestId(): number {
    return ++activeNavigationRequestId;
  }

  function bumpNavigationId(): number {
    return ++activeNavigationRequestId;
  }

  const cancelTextLayer = (): void => {
    textLayerInstance?.cancel();
    textLayerInstance = null;
  };

  const cancelActiveRenderTask = async (): Promise<void> => {
    if (!activeRenderTask) return;
    const task = activeRenderTask;
    activeRenderTask = null;
    task.cancel();
    try {
      await task.promise;
    } catch {
      /* expected */
    }
  };

  async function renderTextLayer(
    textContent: {
      items: Array<{ str: string; transform: number[]; width: number; height: number }>;
    },
    viewport: pdfjsLib.PageViewport,
    requestId = activeNavigationRequestId,
  ): Promise<void> {
    const textLayer = deps.getTextLayer();
    if (!textLayer) return;
    if (isStaleNavigation(requestId)) return;

    textLayerInstance?.cancel();
    textLayerInstance = null;

    textLayer.innerHTML = '';
    textLayer.style.pointerEvents = 'auto';

    try {
      if (pdfjsLib.TextLayer) {
        textLayerInstance = new SafeTextLayer({
          container: textLayer,
          viewport,
          textContentSource:
            textContent as unknown as import('$lib/features/reader/viewer-pdf/safeTextLayer').SafeTextLayerParams['textContentSource'],
        });
        await textLayerInstance.render();
      } else {
        const task = (
          pdfjsLib as unknown as {
            renderTextLayer?: (opts: Record<string, unknown>) => { promise: Promise<void> };
          }
        ).renderTextLayer?.({
          container: textLayer,
          viewport,
          textDivs: [],
          enhanceTextSelection: true,
          textContentSource: textContent,
        });
        if (task?.promise) await task.promise;
      }

      // Preserve pdfViewer's letter-spacing handling only; line-height stays
      // at pdfjs default (1) to avoid rect inflation — see PdfViewer comment.
      for (const span of textLayer.querySelectorAll('span')) {
        span.style.pointerEvents = 'auto';
      }
    } catch (err) {
      handleError(err, 'reader', {
        format: 'pdf',
        pageNumber: deps.getCurrentPage(),
        action: 'render_text_layer',
      });
      textLayerInstance = null;
    }
  }

  async function renderPage(
    pageNum: number,
    options: { requestId?: number; renderScale?: number } = {},
  ): Promise<boolean | void> {
    const pdfDoc = deps.getPdfDoc();
    const canvas = deps.getCanvas();
    const textLayer = deps.getTextLayer();
    if (!pdfDoc || !canvas || !textLayer) return;

    if (typeof document !== 'undefined') await document.fonts.ready;

    const requestId = options.requestId ?? activeNavigationRequestId;
    const renderScale = options.renderScale ?? deps.getScale();

    const page = await pdfDoc.getPage(pageNum);
    if (isStaleNavigation(requestId)) return false;

    currentPageObj = page;
    const viewport = page.getViewport({ scale: renderScale });

    const outputScale = window.devicePixelRatio || 1;
    canvas.width = Math.round(viewport.width * outputScale);
    canvas.height = Math.round(viewport.height * outputScale);
    canvas.style.width = `${viewport.width}px`;
    canvas.style.height = `${viewport.height}px`;

    if (debugState.enabled) {
      debugState.readerInfo = {
        format: 'pdf',
        isTocOpen: deps.getShowToc(),
        isSearchOpen: false,
        isFullscreen: deps.getIsFullscreen(),
        pageInfo: `${deps.getCurrentPage()} / ${deps.getTotalPages()}`,
        scale: deps.getScale(),
      };
    }

    const context = canvas.getContext('2d');
    if (!context) return;

    context.setTransform(1, 0, 0, 1, 0, 0);
    const transform = outputScale !== 1 ? [outputScale, 0, 0, outputScale, 0, 0] : undefined;
    const renderContext = { canvasContext: context, viewport, canvas, transform };

    await cancelActiveRenderTask();
    const renderTask = page.render(renderContext);
    activeRenderTask = renderTask;

    try {
      await renderTask.promise;
    } catch (err) {
      if (activeRenderTask === renderTask) activeRenderTask = null;
      const isCancelled =
        typeof err === 'object' &&
        err !== null &&
        'name' in err &&
        String((err as { name?: string }).name) === 'RenderingCancelledException';
      if (isCancelled) return false;
      throw err;
    }

    if (activeRenderTask === renderTask) activeRenderTask = null;
    if (isStaleNavigation(requestId)) return false;

    const textContent = await page.getTextContent();
    if (isStaleNavigation(requestId)) return false;

    await renderTextLayer(
      textContent as {
        items: Array<{ str: string; transform: number[]; width: number; height: number }>;
      },
      viewport,
      requestId,
    );
    return true;
  }

  function cleanup(): void {
    activeNavigationRequestId += 1;
    cancelTextLayer();
    void cancelActiveRenderTask();
    currentPageObj = null;
  }

  return {
    get currentPageObj(): pdfjsLib.PDFPageProxy | null {
      return currentPageObj;
    },
    set currentPageObj(v: pdfjsLib.PDFPageProxy | null) {
      currentPageObj = v;
    },
    get activeNavigationRequestId(): number {
      return activeNavigationRequestId;
    },
    get activeRenderTask(): pdfjsLib.RenderTask | null {
      return activeRenderTask;
    },
    set activeRenderTask(v: pdfjsLib.RenderTask | null) {
      activeRenderTask = v;
    },
    get textLayerInstance(): SafeTextLayer | null {
      return textLayerInstance;
    },
    isStaleNavigation,
    nextNavigationRequestId,
    bumpNavigationId,
    cancelTextLayer,
    cancelActiveRenderTask,
    renderPage,
    renderTextLayer,
    cleanup,
  };
}

export type PdfRenderState = ReturnType<typeof createPdfRenderState>;
