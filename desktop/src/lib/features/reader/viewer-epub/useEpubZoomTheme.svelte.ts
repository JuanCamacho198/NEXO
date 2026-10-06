/**
 * useEpubZoomTheme — zoom + theme for EpubNativeViewer (PR5).
 * Extracts clampZoomPercent 75..200, wheel RAF, debounce 500ms onSettingsChange,
 * and theme helpers from EpubNativeViewer while preserving byte-identical behavior.
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
  getZoomContainerEl: () => HTMLDivElement | null;
  getReaderSettings: () => ReaderSettings;
  onSettingsChange?: (settings: ReaderSettings) => void;
  getFontSize: () => number;
  setFontSize: (v: number) => void;
  getThemeMode: () => ReaderThemeMode;
};

export function createEpubZoomTheme(deps: EpubZoomThemeDeps): {
  zoomLevel: number;
  readonly pendingWheelDelta: number;
  readonly pendingWheelFrame: number | null;
  clampZoomPercent: typeof clampZoomPercent;
  getThemeStyles: typeof getThemeStyles;
  getThemeBgColor(): string;
  setZoom(percent: number): void;
  changeZoom(delta: number): void;
  handleWheel(e: WheelEvent): void;
  cleanup(): void;
} {
  let zoomLevel = $state(100);
  let persistTimer: ReturnType<typeof setTimeout> | null = null;
  let pendingWheelDelta = 0;
  let pendingWheelFrame: number | null = null;

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

  function changeZoom(delta: number): void {
    const newZoom = Math.max(50, Math.min(200, zoomLevel + delta));
    if (newZoom !== zoomLevel) {
      zoomLevel = newZoom;
    }
  }

  function handleWheel(e: WheelEvent): void {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    pendingWheelDelta += e.deltaY;
    if (pendingWheelFrame !== null) return;
    pendingWheelFrame = requestAnimationFrame(() => {
      pendingWheelFrame = null;
      const delta = pendingWheelDelta > 0 ? -10 : 10;
      pendingWheelDelta = 0;
      const current = clampZoomPercent(deps.getFontSize());
      setZoom(current + delta);
    });
  }

  function cleanup(): void {
    if (persistTimer) {
      clearTimeout(persistTimer);
      persistTimer = null;
    }
    if (pendingWheelFrame !== null) {
      cancelAnimationFrame(pendingWheelFrame);
      pendingWheelFrame = null;
    }
    pendingWheelDelta = 0;
  }

  // Attach wheel listener to zoom container (passive:false)
  $effect(() => {
    const el = deps.getZoomContainerEl();
    if (!el) return;
    const handler = (ev: Event): void => handleWheel(ev as WheelEvent);
    el.addEventListener('wheel', handler, { passive: false });
    return () => el.removeEventListener('wheel', handler);
  });

  return {
    get zoomLevel(): number {
      return zoomLevel;
    },
    set zoomLevel(v: number) {
      zoomLevel = v;
    },
    get pendingWheelDelta(): number {
      return pendingWheelDelta;
    },
    get pendingWheelFrame(): number | null {
      return pendingWheelFrame;
    },
    clampZoomPercent,
    getThemeStyles,
    getThemeBgColor(): string {
      return getThemeBgColor(deps.getThemeMode());
    },
    setZoom,
    changeZoom,
    handleWheel,
    cleanup,
  };
}

export type EpubZoomThemeState = ReturnType<typeof createEpubZoomTheme>;
