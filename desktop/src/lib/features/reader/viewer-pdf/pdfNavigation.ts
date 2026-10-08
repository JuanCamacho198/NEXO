export const DEFAULT_PDF_SCALE = 1.0;
export const PDF_SCALE_MIN = 0.5;
export const PDF_SCALE_MAX = 3.0;
export const PDF_SCALE_STEP = 0.1;

export type NavigationResolution = {
  committedPage: number;
  didCommit: boolean;
  shouldShowError: boolean;
};

type ResolveNavigationOptions = {
  previousPage: number;
  targetPage: number;
  rendered: boolean;
  stale: boolean;
};

export const isPageWithinBounds = (page: number, totalPages: number): boolean => {
  return Number.isInteger(page) && page >= 1 && page <= totalPages;
};

export const clampPdfScale = (scale: number): number => {
  const clamped = Math.min(PDF_SCALE_MAX, Math.max(PDF_SCALE_MIN, scale));
  return Math.round(clamped * 10) / 10;
};

export const adjustPdfScaleForWheel = (currentScale: number, deltaY: number): number => {
  const step = deltaY < 0 ? PDF_SCALE_STEP : -PDF_SCALE_STEP;
  return clampPdfScale(currentScale + step);
};

export const clampZoomPercent = (value: number): number => {
  if (!Number.isFinite(value)) return 100;
  return Math.min(ZOOM_MAX_PERCENT, Math.max(ZOOM_MIN_PERCENT, Math.round(value)));
};

export const ZOOM_MIN_PERCENT = 50;
export const ZOOM_MAX_PERCENT = 300;
export const ZOOM_OPTIONS = [50, 75, 90, 100, 110, 125, 150, 175, 200, 250, 300] as const;
export const DEFAULT_ZOOM_PERCENT = 100;

export const resolveNavigationTransaction = ({
  previousPage,
  targetPage,
  rendered,
  stale,
}: ResolveNavigationOptions): NavigationResolution => {
  if (stale) {
    return {
      committedPage: previousPage,
      didCommit: false,
      shouldShowError: false,
    };
  }

  if (!rendered) {
    return {
      committedPage: previousPage,
      didCommit: false,
      shouldShowError: true,
    };
  }

  return {
    committedPage: targetPage,
    didCommit: true,
    shouldShowError: false,
  };
};
