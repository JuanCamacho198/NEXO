import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

vi.mock('$lib/shared/api/tauriClient', async () => {
  const actual = await vi.importActual<typeof import('$lib/shared/api/tauriClient')>(
    '$lib/shared/api/tauriClient',
  );
  return {
    ...actual,
    upsertReaderSettings: vi.fn().mockResolvedValue({}),
    getDefaultReaderSettings: actual.getDefaultReaderSettings,
  };
});

vi.mock('$lib/features/reader/viewer-epub/keyboardNav', () => ({
  hasEditableContext: vi.fn().mockReturnValue(false),
}));

import { createReaderZoom } from '$lib/features/reader/chrome/useReaderZoom.svelte';
import { READER_ZOOM_STEP_PERCENT } from '$lib/features/reader/chrome/useReaderZoom.svelte';
import { clampZoomPercent } from '$lib/features/reader/viewer-pdf/pdfNavigation';
import { hasEditableContext } from '$lib/features/reader/viewer-epub/keyboardNav';
import type { ViewerHandle } from '$lib/features/reader/viewer-shared/Viewer';

function makeViewerMock(
  kind: 'pdf' | 'epub',
  scaleOrZoom: number,
): { viewer: ViewerHandle; setScaleOrZoom: ReturnType<typeof vi.fn> } {
  const setScaleOrZoom = vi.fn();
  const viewer: ViewerHandle = {
    kind,
    navigatePrev: () => false,
    navigateNext: () => false,
    goToPage: () => Promise.resolve(false),
    setScaleOrZoom,
    getScaleOrZoom: () => scaleOrZoom,
    getCurrentPage: () => 1,
    getTotalForHeader: () => 10,
  };
  return { viewer, setScaleOrZoom };
}

describe('useReaderZoom', () => {
  let rafSpy: ReturnType<typeof vi.spyOn>;
  let cancelSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.useFakeTimers();
    rafSpy = vi
      .spyOn(window, 'requestAnimationFrame')
      .mockImplementation((cb: FrameRequestCallback) => {
        return setTimeout(() => cb(0), 16) as unknown as number;
      });
    cancelSpy = vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((id: number) => {
      clearTimeout(id as unknown as NodeJS.Timeout);
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('clamp 75-200 and adjustZoom persists via 500ms debounce', async () => {
    const persistMock = vi.fn().mockResolvedValue({});
    const pdfMock = {
      setScale: vi.fn().mockResolvedValue(undefined),
    } as unknown as import('$lib/features/reader/viewer-pdf/PdfViewer.svelte').default;
    const epubMock = {
      setZoom: vi.fn().mockResolvedValue(undefined),
    } as unknown as import('$lib/features/reader/viewer-epub/EpubNativeViewer.svelte').default;
    const zoom = createReaderZoom({
      getActiveBook: () => ({ format: 'epub', filePath: 'a.epub', id: 'b1' }) as never,
      getRefs: () => ({ pdf: pdfMock as never, epub: epubMock as never }),
      persist: persistMock,
    });
    expect(zoom.localReaderSettings.epub.fontSize).toBe(100);
    zoom.adjustZoom(10);
    expect(zoom.localReaderSettings.epub.fontSize).toBe(110);
    expect(persistMock).not.toHaveBeenCalled();
    vi.advanceTimersByTime(500);
    expect(persistMock).toHaveBeenCalledTimes(1);
    // clamp upper
    zoom.localReaderSettings = {
      ...zoom.localReaderSettings,
      epub: { ...zoom.localReaderSettings.epub, fontSize: 200 },
    };
    zoom.adjustZoom(10);
    expect(zoom.localReaderSettings.epub.fontSize).toBe(200);
    // debounce not yet
    expect(persistMock).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(500);
    // no new persist because next === current (clamped)
    expect(persistMock).toHaveBeenCalledTimes(1);
    // clamp lower
    zoom.localReaderSettings = {
      ...zoom.localReaderSettings,
      epub: { ...zoom.localReaderSettings.epub, fontSize: 75 },
    };
    zoom.adjustZoom(-10);
    expect(zoom.localReaderSettings.epub.fontSize).toBe(75);
    vi.advanceTimersByTime(500);
    expect(persistMock).toHaveBeenCalledTimes(1);
    // pure clamp helper
    expect(clampZoomPercent(300)).toBe(200);
    expect(clampZoomPercent(10)).toBe(75);
    zoom.cleanup();
  });

  it('wheel rAF coalesces pendingWheelDelta and routes PDF through the viewer scale', () => {
    const setScale = vi.fn().mockResolvedValue(undefined);
    const pdfMock = { setScale };
    const persistMock = vi.fn().mockResolvedValue({});
    const zoom = createReaderZoom({
      getActiveBook: () => ({ format: 'pdf', filePath: 'a.pdf', id: 'b1' }) as never,
      getRefs: () => ({ pdf: pdfMock, epub: null }) as never,
      persist: persistMock,
    });
    const start = zoom.localReaderSettings.epub.fontSize;
    // ctrl+wheel down (deltaY positive => zoom out -10)
    const e1 = {
      ctrlKey: true,
      metaKey: false,
      deltaY: 100,
      preventDefault: vi.fn(),
    } as unknown as WheelEvent;
    zoom.handleGlobalWheel(e1);
    expect(e1.preventDefault).toHaveBeenCalled();
    expect(rafSpy).toHaveBeenCalledTimes(1);
    expect(zoom._pendingWheelFrame).not.toBeNull();
    // second wheel before rAF should coalesce, not new frame
    const e2 = {
      ctrlKey: true,
      metaKey: false,
      deltaY: 100,
      preventDefault: vi.fn(),
    } as unknown as WheelEvent;
    zoom.handleGlobalWheel(e2);
    expect(rafSpy).toHaveBeenCalledTimes(1);
    expect(zoom._pendingWheelDelta).toBe(200);
    vi.advanceTimersByTime(16);
    expect(zoom._pendingWheelFrame).toBeNull();
    expect(zoom._pendingWheelDelta).toBe(0);
    // single pipeline, single step: the viewer scale moves exactly one step…
    expect(setScale).toHaveBeenCalledTimes(1);
    expect(setScale).toHaveBeenCalledWith((start - READER_ZOOM_STEP_PERCENT) / 100);
    // …and the EPUB setting is untouched for a PDF.
    expect(zoom.localReaderSettings.epub.fontSize).toBe(start);
    vi.advanceTimersByTime(500);
    expect(persistMock).not.toHaveBeenCalled();
    // wheel without ctrl ignored
    const e3 = {
      ctrlKey: false,
      metaKey: false,
      deltaY: 100,
      preventDefault: vi.fn(),
    } as unknown as WheelEvent;
    zoom.handleGlobalWheel(e3);
    expect(e3.preventDefault).not.toHaveBeenCalled();
    expect(rafSpy).toHaveBeenCalledTimes(1);
    zoom.cleanup();
  });

  it('keydown ctrl+/- adjusts zoom and respects editable context', () => {
    const zoom = createReaderZoom({
      getActiveBook: () => ({ format: 'epub', filePath: 'a.epub', id: 'b1' }) as never,
      getRefs: () => ({ pdf: null, epub: { setZoom: vi.fn() } as unknown as never }),
      persist: vi.fn().mockResolvedValue({}),
    });
    const start = zoom.localReaderSettings.epub.fontSize;
    const ePlus = {
      key: '+',
      ctrlKey: true,
      metaKey: false,
      target: document.createElement('div'),
      preventDefault: vi.fn(),
    } as unknown as KeyboardEvent;
    zoom.handleGlobalKeydown(ePlus);
    expect(ePlus.preventDefault).toHaveBeenCalled();
    expect(zoom.localReaderSettings.epub.fontSize).toBe(start + READER_ZOOM_STEP_PERCENT);
    const eMinus = {
      key: '-',
      ctrlKey: true,
      metaKey: false,
      target: document.createElement('div'),
      preventDefault: vi.fn(),
    } as unknown as KeyboardEvent;
    zoom.handleGlobalKeydown(eMinus);
    expect(zoom.localReaderSettings.epub.fontSize).toBe(start);
    // editable context should be ignored
    vi.mocked(hasEditableContext).mockReturnValueOnce(true);
    const eEditable = {
      key: '+',
      ctrlKey: true,
      metaKey: false,
      target: document.createElement('input'),
      preventDefault: vi.fn(),
    } as unknown as KeyboardEvent;
    zoom.handleGlobalKeydown(eEditable);
    expect(eEditable.preventDefault).not.toHaveBeenCalled();
    expect(zoom.localReaderSettings.epub.fontSize).toBe(start);
    zoom.cleanup();
  });

  it('500ms persist debounce resets on rapid changes', () => {
    const persistMock = vi.fn().mockResolvedValue({});
    const zoom = createReaderZoom({
      getActiveBook: () => ({ format: 'pdf', filePath: 'a.pdf', id: 'b1' }) as never,
      getRefs: () => ({ pdf: null, epub: null }),
      persist: persistMock,
    });
    zoom.handleTextSettingsChange({
      ...zoom.localReaderSettings,
      epub: { fontSize: 110, fontFamily: 'serif' },
    });
    expect(zoom._persistTimer).not.toBeNull();
    vi.advanceTimersByTime(200);
    expect(persistMock).not.toHaveBeenCalled();
    zoom.handleTextSettingsChange({
      ...zoom.localReaderSettings,
      epub: { fontSize: 120, fontFamily: 'serif' },
    });
    vi.advanceTimersByTime(200);
    expect(persistMock).not.toHaveBeenCalled();
    vi.advanceTimersByTime(300);
    expect(persistMock).toHaveBeenCalledTimes(1);
    zoom.cleanup();
  });

  it('cleanup clears persistTimer and pendingWheelFrame', () => {
    const persistMock = vi.fn().mockResolvedValue({});
    const zoom = createReaderZoom({
      getActiveBook: () => ({ format: 'pdf', filePath: 'a.pdf', id: 'b1' }) as never,
      getRefs: () => ({ pdf: null, epub: null }),
      persist: persistMock,
    });
    zoom.handleTextSettingsChange({
      ...zoom.localReaderSettings,
      epub: { fontSize: 110, fontFamily: 'serif' },
    });
    expect(zoom._persistTimer).not.toBeNull();
    const e = {
      ctrlKey: true,
      metaKey: false,
      deltaY: 100,
      preventDefault: vi.fn(),
    } as unknown as WheelEvent;
    zoom.handleGlobalWheel(e);
    expect(zoom._pendingWheelFrame).not.toBeNull();
    zoom.cleanup();
    expect(zoom._persistTimer).toBeNull();
    expect(zoom._pendingWheelFrame).toBeNull();
    expect(zoom._pendingWheelDelta).toBe(0);
    vi.advanceTimersByTime(500);
    expect(persistMock).not.toHaveBeenCalled();
  });

  it('PDF zoom reads and writes the viewer scale, never epub.fontSize', () => {
    const persistMock = vi.fn().mockResolvedValue({});
    const { viewer, setScaleOrZoom } = makeViewerMock('pdf', 150);
    const zoom = createReaderZoom({ getViewer: () => viewer, persist: persistMock });
    zoom.adjustZoom(READER_ZOOM_STEP_PERCENT);
    expect(setScaleOrZoom).toHaveBeenCalledTimes(1);
    expect(setScaleOrZoom).toHaveBeenCalledWith(150 + READER_ZOOM_STEP_PERCENT);
    expect(zoom.localReaderSettings.epub.fontSize).toBe(100);
    zoom.handleHeaderFontSizeChange(125);
    expect(setScaleOrZoom).toHaveBeenCalledWith(125);
    expect(zoom.localReaderSettings.epub.fontSize).toBe(100);
    vi.advanceTimersByTime(500);
    expect(persistMock).not.toHaveBeenCalled();
    zoom.cleanup();
  });

  it('EPUB zoom reads the viewer scale and persists fontSize', () => {
    const persistMock = vi.fn().mockResolvedValue({});
    const { viewer, setScaleOrZoom } = makeViewerMock('epub', 120);
    const zoom = createReaderZoom({ getViewer: () => viewer, persist: persistMock });
    zoom.adjustZoom(READER_ZOOM_STEP_PERCENT);
    expect(zoom.localReaderSettings.epub.fontSize).toBe(120 + READER_ZOOM_STEP_PERCENT);
    expect(setScaleOrZoom).toHaveBeenCalledWith(120 + READER_ZOOM_STEP_PERCENT);
    vi.advanceTimersByTime(500);
    expect(persistMock).toHaveBeenCalledTimes(1);
    zoom.cleanup();
  });

  it('syncFromProps copies settings via JSON', () => {
    const zoom = createReaderZoom({
      getActiveBook: () => null,
      getRefs: () => ({ pdf: null, epub: null }),
      persist: vi.fn(),
    });
    const next = { ...zoom.localReaderSettings, epub: { fontSize: 150, fontFamily: 'serif' } };
    zoom.syncFromProps(next);
    expect(zoom.localReaderSettings.epub.fontSize).toBe(150);
    zoom.cleanup();
  });
});
