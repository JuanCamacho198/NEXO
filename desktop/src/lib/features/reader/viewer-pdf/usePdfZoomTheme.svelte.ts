/**
 * usePdfZoomTheme — clamp 0.5-3 + resolveThemePalette (PR-P2-3).
 * Extracts zoom clamping, theme palette and visual-filter derived state from
 * PdfViewer. Mirrors `useEpubZoomTheme`.
 *
 * Wheel and keyboard zoom are NOT owned here: the single zoom pipeline lives
 * in `chrome/useReaderZoom` (one step for wheel and keys, applied once per
 * gesture). This module only clamps the scale the pipeline writes and derives
 * the theme visuals.
 */
import {
  clamp,
  resolveThemePalette,
  type ReaderThemePalette,
} from '$lib/features/reader/viewer-pdf/pdfState.svelte';
import { clampPdfScale as navClampPdfScale } from '$lib/features/reader/viewer-pdf/pdfNavigation';
import type { ReaderSettings } from '$lib/shared/types';

export { navClampPdfScale as clampPdfScale };
export { resolveThemePalette };
export type { ReaderThemePalette };
export const PDF_SCALE_MIN = 0.5;
export const PDF_SCALE_MAX = 3.0;
export const ZOOM_EPSILON = 0.001;

export function clampPdfScaleRaw(value: number): number {
  return Math.min(PDF_SCALE_MAX, Math.max(PDF_SCALE_MIN, value));
}

export type PdfZoomThemeDeps = {
  getReaderSettings: () => ReaderSettings;
};

export function createPdfZoomThemeState(deps: PdfZoomThemeDeps): {
  readonly readerThemePalette: ReaderThemePalette;
  readonly visualFilterStyle: string;
  clampPdfScale: typeof navClampPdfScale;
  clampPdfScaleRaw: typeof clampPdfScaleRaw;
  resolveThemePalette: typeof resolveThemePalette;
} {
  const readerThemePalette = $derived(resolveThemePalette(deps.getReaderSettings().themeMode));
  const visualFilterStyle = $derived(
    `brightness(${clamp(deps.getReaderSettings().brightness, 50, 150)}%) contrast(${clamp(deps.getReaderSettings().contrast, 50, 150)}%)`,
  );

  return {
    get readerThemePalette(): ReaderThemePalette {
      return readerThemePalette;
    },
    get visualFilterStyle(): string {
      return visualFilterStyle;
    },
    clampPdfScale: navClampPdfScale,
    clampPdfScaleRaw,
    resolveThemePalette,
  };
}

export type PdfZoomThemeState = ReturnType<typeof createPdfZoomThemeState>;
