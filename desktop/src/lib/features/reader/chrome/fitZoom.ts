import { clampZoomPercent } from '$lib/features/reader/viewer-pdf/pdfNavigation';

export type FitMode = 'width' | 'page';

export type FitViewport = {
  containerWidth: number;
  containerHeight: number;
  pageWidth: number;
  pageHeight: number;
};

function isUsableSize(value: number): boolean {
  return Number.isFinite(value) && value > 0;
}

/**
 * Resolves the zoom percentage that makes the page reach the real edges of
 * the available area: the full container width for `width`, the largest scale
 * that still shows the whole page for `page`. The result always passes
 * through the single zoom clamp, so fit never offers a scale the pipeline
 * would reject. Degenerate viewports fall back to 100%.
 */
export function resolveFitScalePercent(mode: FitMode, viewport: FitViewport): number {
  const { containerWidth, containerHeight, pageWidth, pageHeight } = viewport;
  if (!isUsableSize(containerWidth) || !isUsableSize(pageWidth)) return 100;
  if (mode === 'width') return clampZoomPercent((containerWidth / pageWidth) * 100);
  if (!isUsableSize(containerHeight) || !isUsableSize(pageHeight)) return 100;
  return (
    clampZoomPercent(Math.min(containerWidth / pageWidth, containerHeight / pageHeight) * 100)
  );
}
