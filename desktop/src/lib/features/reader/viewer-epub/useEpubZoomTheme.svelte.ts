/**
 * useEpubZoomTheme — zoom + theme for EpubNativeViewer (PR5).
 * Extracts clampZoomPercent 75..200, setZoom with 500ms persist debounce, and
 * theme helpers from EpubNativeViewer while preserving byte-identical behavior.
 *
 * Wheel and keyboard zoom are NOT owned here: the single zoom pipeline lives
 * in `chrome/useReaderZoom` (one step for wheel and keys, applied once per
 * gesture). This module only applies the zoom the pipeline routes to the
 * viewer and derives the theme visuals.
 */
import type { ReaderSettings, ReaderThemeMode } from '$lib/shared/types';

export function clampZoomPercent(value: number): number {
  if (!Number.isFinite(value)) return 100;
  return Math.min(200, Math.max(75, Math.round(value)));
}

export function getThemeStyles(themeMode: string): string {
  const themes: Record<string, string> = {
    paper: `\n        body { background: #faf8f5; color: #333; }\n        a { color: #3366cc; }\n      `,
    sepia: `\n        body { background: #f5eedd; color: #5b4636; }\n        a { color: #8b6914; }\n      `,
    night: `\n        body { background: #0f1320; color: #c8ccd8; }\n        a { color: #7bb8ff; }\n      `,
    dark: `\n        body { background: #1a1a2e; color: #e0e0e0; }\n        a { color: #66bbff; }\n      `,
    blue: `\n        body { background: #1e3a5f; color: #d6e4f0; }\n        a { color: #88ccff; }\n      `,
  };
  return themes[themeMode] || themes.paper;
}

export function getThemeBgColor(themeMode: string): string {
  const bgs: Record<string, string> = {
    paper: '#faf8f5',
    sepia: '#f5eedd',
    night: '#0f1320',
    dark: '#1a1a2e',
    blue: '#1e3a5f',
  };
  return bgs[themeMode] || bgs.paper;
}

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
    setZoom,
    cleanup,
  };
}

export type EpubZoomThemeState = ReturnType<typeof createEpubZoomTheme>;
