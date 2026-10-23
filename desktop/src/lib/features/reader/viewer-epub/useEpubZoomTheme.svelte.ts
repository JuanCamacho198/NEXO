/**
 * useEpubZoomTheme — zoom + theme for EpubNativeViewer (PR5).
 * Extracts the shared clampZoomPercent 50..300, setZoom with 500ms persist
 * debounce, and theme helpers from EpubNativeViewer while preserving
 * byte-identical behavior.
 *
 * Wheel and keyboard zoom are NOT owned here: the single zoom pipeline lives
 * in `chrome/useReaderZoom` (one step for wheel and keys, applied once per
 * gesture). This module only applies the zoom the pipeline routes to the
 * viewer and derives the theme visuals.
 */
import type { ReaderSettings, ReaderThemeMode } from '$lib/shared/types';
import { clampZoomPercent } from '$lib/features/reader/viewer-pdf/pdfNavigation';

export { clampZoomPercent };

// U3: single theme owner. The canonical styles live in useEpubRender (the
// only consumer that injects them); this module re-exports them so the
// zoom/theme API stays byte-compatible without a second copy to drift.
import {
  getThemeStyles,
  getThemeBgColor,
  getThemeScrollbarColor,
} from '$lib/features/reader/viewer-epub/useEpubRender.svelte';

export { getThemeStyles, getThemeBgColor, getThemeScrollbarColor };

export type EpubZoomThemeDeps = {
  getReaderSettings: () => ReaderSettings;
  onSettingsChange?: (settings: ReaderSettings) => void;
  getFontSize: () => number;
  setFontSize: (v: number) => void;
  getThemeMode: () => ReaderThemeMode;
};

export function createEpubZoomTheme(deps: EpubZoomThemeDeps): {
  zoomLevel: number;
  clampZoomPercent: typeof clampZoomPercent;
  getThemeStyles: typeof getThemeStyles;
  getThemeBgColor(): string;
  /** The thumb colour for this reading theme (see `getThemeScrollbarColor`). */
  getThemeScrollbarColor(): string;
  setZoom(percent: number): void;
  cleanup(): void;
} {
  let zoomLevel = $state(100);
  let persistTimer: ReturnType<typeof setTimeout> | null = null;

  function setZoom(percent: number): void {
    const clamped = clampZoomPercent(percent);
    deps.setFontSize(clamped);
    if (persistTimer) clearTimeout(persistTimer);
    persistTimer = setTimeout(() => {
      const updated: ReaderSettings = {
        ...deps.getReaderSettings(),
        epub: { ...deps.getReaderSettings().epub, fontSize: clamped },
      };
      deps.onSettingsChange?.(updated);
      persistTimer = null;
    }, 500);
  }

  function cleanup(): void {
    if (persistTimer) {
      clearTimeout(persistTimer);
      persistTimer = null;
    }
  }

  return {
    get zoomLevel(): number {
      return zoomLevel;
    },
    set zoomLevel(v: number) {
      zoomLevel = v;
    },
    clampZoomPercent,
    getThemeStyles,
    getThemeBgColor(): string {
      return getThemeBgColor(deps.getThemeMode());
    },
    getThemeScrollbarColor(): string {
      return getThemeScrollbarColor(deps.getThemeMode());
    },
    setZoom,
    cleanup,
  };
}

export type EpubZoomThemeState = ReturnType<typeof createEpubZoomTheme>;
