import { clampZoomPercent } from '$lib/features/reader/viewer-pdf/pdfNavigation';
import type { SettingsPort } from '$lib/shared/ports/SettingsPort';
import { TauriSettingsAdapter } from '$lib/shared/ports/adapters/tauri/TauriSettingsAdapter';
import { hasEditableContext } from '$lib/features/reader/viewer-epub/keyboardNav';
import type { ReaderSettings } from '$lib/shared/types';
import type { ViewerHandle } from '../viewer-shared/Viewer';

export type ReaderZoomDeps = {
  getViewer?: () => ViewerHandle;
  getActiveBook?: () => unknown;
  getRefs?: () => unknown;
  persist?: (s: ReaderSettings) => Promise<unknown>;
  settingsPort?: SettingsPort;
};

export function createReaderZoom(deps: ReaderZoomDeps): {
  localReaderSettings: ReaderSettings;
  readonly _persistTimer: ReturnType<typeof setTimeout> | null;
  readonly _pendingWheelFrame: number | null;
  readonly _pendingWheelDelta: number;
  handleTextSettingsChange(updated: ReaderSettings): void;
  syncFromProps(next: ReaderSettings | undefined): void;
  adjustZoom(delta: number): void;
  handleHeaderFontSizeChange(size: number): void;
  handleGlobalWheel(e: WheelEvent): void;
  handleGlobalKeydown(e: KeyboardEvent): void;
  cleanup(): void;
} {
  const settingsPort = deps.settingsPort ?? new TauriSettingsAdapter();
  const persist = deps.persist ?? ((s: ReaderSettings) => settingsPort.upsertReaderSettings(s));
  const resolveViewer = (): ViewerHandle => {
    if (deps.getViewer) return deps.getViewer();
    const refs = (deps.getRefs?.() ?? { pdf: null, epub: null }) as {
      pdf: { setScale?: (v: number) => void } | null;
      epub: { setZoom?: (v: number) => void } | null;
    };
    const book = deps.getActiveBook?.() as { format?: unknown } | null;
    const fmtRaw = book?.format;
    const fmt = typeof fmtRaw === 'string' ? String(fmtRaw).toLowerCase() : '';
    const kind = fmt === 'epub' ? 'epub' : 'pdf';
    return {
      get kind() {
        return kind as ViewerHandle['kind'];
      },
      navigatePrev() {
        return false;
      },
      navigateNext() {
        return false;
      },
      goToPage() {
        return Promise.resolve(false);
      },
      setScaleOrZoom(pct: number) {
        if (kind === 'pdf') refs.pdf?.setScale?.(pct / 100);
        else refs.epub?.setZoom?.(pct);
      },
      getCurrentPage() {
        return 1;
      },
      getTotalForHeader() {
        return 0;
      },
    } as ViewerHandle;
  };

  const getDefaultReaderSettings = (): ReaderSettings => ({
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    selectionColor: '#3388ff',
    epub: { fontSize: 100, fontFamily: 'serif' },
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    verticalScrolling: false,
    margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
    showHeader: true,
    showFooter: true,
    showPageNumbers: true,
    progressIndicator: 'percentage',
  });
  let localReaderSettings = $state<ReaderSettings>(getDefaultReaderSettings());
  let persistTimer: ReturnType<typeof setTimeout> | null = null;
  let pendingWheelDelta = 0;
  let pendingWheelFrame: number | null = null;

  function handleTextSettingsChange(updated: ReaderSettings): void {
    localReaderSettings = updated;
    if (persistTimer) clearTimeout(persistTimer);
    persistTimer = setTimeout(() => {
      void persist(updated);
    }, 500);
  }

  function syncFromProps(next: ReaderSettings | undefined): void {
    if (next) {
      localReaderSettings = JSON.parse(JSON.stringify(next));
    }
  }

  function adjustZoom(delta: number): void {
    const current = clampZoomPercent(localReaderSettings.epub.fontSize ?? 100);
    const next = clampZoomPercent(current + delta);
    if (next === current) return;
    const updated: ReaderSettings = {
      ...localReaderSettings,
      epub: { ...localReaderSettings.epub, fontSize: next },
    };
    handleTextSettingsChange(updated);
    resolveViewer().setScaleOrZoom(next);
  }

  function handleHeaderFontSizeChange(size: number): void {
    const clamped = clampZoomPercent(size);
    const updated: ReaderSettings = {
      ...localReaderSettings,
      epub: { ...localReaderSettings.epub, fontSize: clamped },
    };
    handleTextSettingsChange(updated);
    resolveViewer().setScaleOrZoom(clamped);
  }

  function handleGlobalWheel(e: WheelEvent): void {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    pendingWheelDelta += e.deltaY;
    if (pendingWheelFrame !== null) return;
    pendingWheelFrame = requestAnimationFrame(() => {
      pendingWheelFrame = null;
      const delta = pendingWheelDelta > 0 ? -10 : 10;
      pendingWheelDelta = 0;
      adjustZoom(delta);
    });
  }

  function handleGlobalKeydown(e: KeyboardEvent): void {
    if (
      (e.ctrlKey || e.metaKey) &&
      (e.key === '=' || e.key === '+' || e.key === '-' || e.key === '_')
    ) {
      if (hasEditableContext(e.target as Element | null)) return;
      e.preventDefault();
      const step = e.key === '-' || e.key === '_' ? -10 : 10;
      adjustZoom(step);
    }
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

  return {
    get localReaderSettings() {
      return localReaderSettings;
    },
    set localReaderSettings(v: ReaderSettings) {
      localReaderSettings = v;
    },
    get _persistTimer() {
      return persistTimer;
    },
    get _pendingWheelFrame() {
      return pendingWheelFrame;
    },
    get _pendingWheelDelta() {
      return pendingWheelDelta;
    },
    handleTextSettingsChange,
    syncFromProps,
    adjustZoom,
    handleHeaderFontSizeChange,
    handleGlobalWheel,
    handleGlobalKeydown,
    cleanup,
  };
}

export type ReaderZoomState = ReturnType<typeof createReaderZoom>;
