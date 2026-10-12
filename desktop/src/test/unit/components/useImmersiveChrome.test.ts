import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

vi.mock('$lib/shared/stores/ReaderDomainState.svelte', () => ({
  readerState: { isFullscreen: false, locatorJson: '' },
}));

vi.mock('@tauri-apps/api/webviewWindow', () => ({
  getCurrentWebviewWindow: () => ({
    isFullscreen: vi.fn().mockResolvedValue(false),
    setFullscreen: vi.fn().mockResolvedValue(undefined),
  }),
}));

vi.mock('$lib/features/reader/viewer-epub/keyboardNav', () => ({
  hasEditableContext: vi.fn().mockReturnValue(false),
}));

import { createImmersiveChrome } from '$lib/features/reader/chrome/useImmersiveChrome.svelte';
import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';

describe('useImmersiveChrome', () => {
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
    // reset readerState
    (readerState as unknown as { isFullscreen: boolean }).isFullscreen = false;
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('idle 2500 hides header when isFullscreen && !hoverTop && !panelOpen', () => {
    let panelOpen = false;
    const chrome = createImmersiveChrome({ getPanelOpen: () => panelOpen });
    expect(chrome.headerVisible).toBe(true);
    chrome.toggleFullscreen();
    expect(chrome.isFullscreen).toBe(true);
    expect((readerState as unknown as { isFullscreen: boolean }).isFullscreen).toBe(true);
    // mouseY default 0 => hoverTop true (y<72) => idle timer should keep header visible
    // move mouse to y=200 via rAF to make hoverTop false
    chrome.handleWorkspaceMouseMove({ clientX: 500, clientY: 200 } as MouseEvent);
    // rAF pending
    expect(chrome._pendingFrame).not.toBeNull();
    vi.advanceTimersByTime(16);
    expect(chrome._pendingFrame).toBeNull();
    // now hoverTop false, panelOpen false, isFullscreen true => idle timer armed
    expect(chrome._idleTimer).not.toBeNull();
    expect(chrome.headerVisible).toBe(true);
    vi.advanceTimersByTime(2500);
    expect(chrome.headerVisible).toBe(false);
    chrome.cleanup();
  });

  it('idle timer not armed when panelOpen true', () => {
    let panelOpen = true;
    const chrome = createImmersiveChrome({ getPanelOpen: () => panelOpen });
    chrome.toggleFullscreen();
    chrome.handleWorkspaceMouseMove({ clientX: 500, clientY: 200 } as MouseEvent);
    vi.advanceTimersByTime(16);
    expect(chrome.headerVisible).toBe(true);
    expect(chrome._idleTimer).toBeNull();
    vi.advanceTimersByTime(2500);
    expect(chrome.headerVisible).toBe(true);
    chrome.cleanup();
  });

  it('coalesces mousemove rAF pendingFrame', () => {
    const chrome = createImmersiveChrome({ getPanelOpen: () => false });
    chrome.toggleFullscreen();
    chrome.handleWorkspaceMouseMove({ clientX: 100, clientY: 10 } as MouseEvent);
    expect(rafSpy).toHaveBeenCalledTimes(1);
    // second call while pendingFrame not null should be ignored
    chrome.handleWorkspaceMouseMove({ clientX: 200, clientY: 200 } as MouseEvent);
    expect(rafSpy).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(16);
    expect(chrome.mouseX).toBe(100);
    // after frame cleared, next move triggers new rAF
    chrome.handleWorkspaceMouseMove({ clientX: 300, clientY: 300 } as MouseEvent);
    expect(rafSpy).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(16);
    expect(chrome.mouseX).toBe(300);
    chrome.cleanup();
  });

  it('edge 80px shows edgeNavVisible near edges, hides in center', () => {
    const chrome = createImmersiveChrome({ getPanelOpen: () => false });
    chrome.toggleFullscreen();
    // left edge
    chrome.handleWorkspaceMouseMove({ clientX: 10, clientY: 100 } as MouseEvent);
    vi.advanceTimersByTime(16);
    expect(chrome.edgeNavVisible).toBe(true);
    // center
    chrome.handleWorkspaceMouseMove({ clientX: 500, clientY: 100 } as MouseEvent);
    vi.advanceTimersByTime(16);
    expect(chrome.edgeNavVisible).toBe(false);
    // right edge
    chrome.handleWorkspaceMouseMove({ clientX: 1010, clientY: 100 } as MouseEvent);
    vi.advanceTimersByTime(16);
    expect(chrome.edgeNavVisible).toBe(true);
    // non-fullscreen always false
    chrome.toggleFullscreen();
    expect(chrome.edgeNavVisible).toBe(false);
    chrome.cleanup();
  });

  it('toggleFullscreen dual sync local + readerState and resets header', () => {
    const chrome = createImmersiveChrome({ getPanelOpen: () => false });
    expect(chrome.isFullscreen).toBe(false);
    chrome.toggleFullscreen();
    expect(chrome.isFullscreen).toBe(true);
    expect((readerState as unknown as { isFullscreen: boolean }).isFullscreen).toBe(true);
    chrome.toggleFullscreen();
    expect(chrome.isFullscreen).toBe(false);
    expect((readerState as unknown as { isFullscreen: boolean }).isFullscreen).toBe(false);
    expect(chrome.headerVisible).toBe(true);
    expect(chrome.edgeNavVisible).toBe(false);
    chrome.cleanup();
  });

  it('cleanup clears idleTimer and pendingFrame', () => {
    const chrome = createImmersiveChrome({ getPanelOpen: () => false });
    chrome.toggleFullscreen();
    // pendingFrame path: cleanup before rAF flush
    chrome.handleWorkspaceMouseMove({ clientX: 10, clientY: 10 } as MouseEvent);
    expect(chrome._pendingFrame).not.toBeNull();
    chrome.cleanup();
    expect(chrome._pendingFrame).toBeNull();
    expect(cancelSpy).toHaveBeenCalled();
    // idleTimer path: after rAF, idle armed, then cleanup (fresh instance)
    const chrome2 = createImmersiveChrome({ getPanelOpen: () => false });
    chrome2.toggleFullscreen();
    expect(chrome2.isFullscreen).toBe(true);
    chrome2.handleWorkspaceMouseMove({ clientX: 500, clientY: 200 } as MouseEvent);
    vi.advanceTimersByTime(16);
    expect(chrome2._idleTimer).not.toBeNull();
    chrome2.cleanup();
    expect(chrome2._idleTimer).toBeNull();
    expect(chrome2._pendingFrame).toBeNull();
  });

  it('handleWorkspaceMouseLeave clears pendingFrame and hides edgeNav', () => {
    const chrome = createImmersiveChrome({ getPanelOpen: () => false });
    chrome.toggleFullscreen();
    chrome.handleWorkspaceMouseMove({ clientX: 10, clientY: 100 } as MouseEvent);
    expect(chrome._pendingFrame).not.toBeNull();
    chrome.handleWorkspaceMouseLeave();
    expect(chrome._pendingFrame).toBeNull();
    expect(chrome.edgeNavVisible).toBe(false);
    chrome.cleanup();
  });

  it('handleForwardedMouse drives the same clock as workspace mousemove (iframe fix)', () => {
    const chrome = createImmersiveChrome({ getPanelOpen: () => false });
    chrome.toggleFullscreen();
    chrome.handleForwardedMouse(10, 100);
    expect(chrome._pendingFrame).not.toBeNull();
    vi.advanceTimersByTime(16);
    expect(chrome.mouseX).toBe(10);
    expect(chrome.edgeNavVisible).toBe(true);
    chrome.handleForwardedMouse(500, 200);
    vi.advanceTimersByTime(16);
    expect(chrome.mouseX).toBe(500);
    expect(chrome.edgeNavVisible).toBe(false);
    chrome.cleanup();
  });

  it('top-strip coords rescue a hidden header (U2)', () => {
    const chrome = createImmersiveChrome({ getPanelOpen: () => false });
    chrome.toggleFullscreen();
    chrome.handleForwardedMouse(500, 200);
    vi.advanceTimersByTime(16);
    vi.advanceTimersByTime(2500);
    expect(chrome.headerVisible).toBe(false);
    chrome.handleForwardedMouse(500, 4);
    vi.advanceTimersByTime(16);
    expect(chrome.headerVisible).toBe(true);
    chrome.cleanup();
  });
});
