/**
 * usePdfZoomTheme — clamp 0.5-3 RAF + resolveThemePalette (PR-P2-3).
 * Extracts zoom clamping, wheel RAF coalescing, theme palette and
 * visual-filter derived state from PdfViewer. Mirrors `useEpubZoomTheme`.
 */
import {
  clamp,
  resolveThemePalette,
  type ReaderThemePalette,
} from '$lib/features/reader/viewer-pdf/pdfState.svelte';
import {
  adjustPdfScaleForWheel,
  clampPdfScale as navClampPdfScale,
  PDF_SCALE_STEP,
} from '$lib/features/reader/viewer-pdf/pdfNavigation';
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
  getScale: () => number;
  setScale: (v: number) => Promise<void> | void;
  getCanvasContainer: () => HTMLDivElement | undefined;
  getPdfDoc: () => unknown | null;
};

export function createPdfZoomThemeState(deps: PdfZoomThemeDeps): {
  readonly readerThemePalette: ReaderThemePalette;
  readonly visualFilterStyle: string;
  readonly pendingWheelFrame: number | null;
  readonly pendingWheelDelta: number;
  clampPdfScale: typeof navClampPdfScale;
  clampPdfScaleRaw: typeof clampPdfScaleRaw;
  resolveThemePalette: typeof resolveThemePalette;
  handleViewerWheel(event: WheelEvent): void;
  handleKeyZoom(event: KeyboardEvent): boolean;
  cleanup(): void;
  attachWheelListener(): () => void;
} {
  let pendingWheelFrame: number | null = null;
  let pendingWheelDelta = 0;

  const readerThemePalette = $derived(resolveThemePalette(deps.getReaderSettings().themeMode));
  const visualFilterStyle = $derived(
    `brightness(${clamp(deps.getReaderSettings().brightness, 50, 150)}%) contrast(${clamp(deps.getReaderSettings().contrast, 50, 150)}%)`,
  );

  function handleViewerWheel(event: WheelEvent): void {
    if (!deps.getPdfDoc()) return;
    if (!event.ctrlKey && !event.metaKey) return;
    if (event.deltaY === 0) return;
    event.preventDefault();
    pendingWheelDelta += event.deltaY;
    if (pendingWheelFrame !== null) return;
    pendingWheelFrame = window.requestAnimationFrame(() => {
      pendingWheelFrame = null;
      const nextScale = adjustPdfScaleForWheel(deps.getScale(), pendingWheelDelta);
      pendingWheelDelta = 0;
      if (nextScale !== deps.getScale()) void deps.setScale(nextScale);
    });
  }

  function handleKeyZoom(event: KeyboardEvent): boolean {
    if (
      (event.ctrlKey || event.metaKey) &&
      (event.key === '=' || event.key === '+' || event.key === '-')
    ) {
      event.preventDefault();
      const step = event.key === '-' ? -PDF_SCALE_STEP : PDF_SCALE_STEP;
      void deps.setScale(deps.getScale() + step);
      return true;
    }
    return false;
  }

  function cleanup(): void {
    if (pendingWheelFrame !== null) {
      window.cancelAnimationFrame(pendingWheelFrame);
      pendingWheelFrame = null;
    }
    pendingWheelDelta = 0;
  }

  // Wheel listener effect (passive:false) — caller wires via bind:this
  function attachWheelListener(): () => void {
    const el = deps.getCanvasContainer();
    if (!el) return () => {};
    const handler: EventListener = (event) => handleViewerWheel(event as WheelEvent);
    el.addEventListener('wheel', handler, { passive: false });
    return () => el.removeEventListener('wheel', handler);
  }

  return {
    get readerThemePalette(): ReaderThemePalette {
      return readerThemePalette;
    },
    get visualFilterStyle(): string {
      return visualFilterStyle;
    },
    get pendingWheelFrame(): number | null {
      return pendingWheelFrame;
    },
    get pendingWheelDelta(): number {
      return pendingWheelDelta;
    },
    clampPdfScale: navClampPdfScale,
    clampPdfScaleRaw,
    resolveThemePalette,
    handleViewerWheel,
    handleKeyZoom,
    cleanup,
    attachWheelListener,
  };
}

export type PdfZoomThemeState = ReturnType<typeof createPdfZoomThemeState>;
