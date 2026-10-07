import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
import { getCurrentWebviewWindow } from '@tauri-apps/api/webviewWindow';
import { hasEditableContext } from '$lib/features/reader/viewer-epub/keyboardNav';
import { logger } from '$lib/shared/logger/Logger';

export type ImmersiveChromeDeps = {
  getPanelOpen: () => boolean;
  appWindow?: {
    isFullscreen: () => Promise<boolean>;
    setFullscreen: (v: boolean) => Promise<void>;
  };
};

export function createImmersiveChrome(deps: ImmersiveChromeDeps): {
  isFullscreen: boolean;
  readonly headerVisible: boolean;
  readonly edgeNavVisible: boolean;
  readonly hoverTop: boolean;
  readonly mouseX: number;
  readonly mouseY: number;
  readonly _idleTimer: ReturnType<typeof setTimeout> | null;
  readonly _pendingFrame: number | null;
  resetIdleTimer(): void;
  updateEdgeNav(x: number): void;
  handleWorkspaceMouseMove(e: MouseEvent): void;
  handleWorkspaceMouseLeave(): void;
  handleGlobalKeydown(e: KeyboardEvent): void;
  toggleFullscreen(): void;
  toggleWindowFullscreen(): Promise<void>;
  cleanup(): void;
} {
  const appWindow =
    deps.appWindow ?? (getCurrentWebviewWindow() as unknown as ImmersiveChromeDeps['appWindow']);

  let isFullscreen = $state(false);
  let headerVisible = $state(true);
  let edgeNavVisible = $state(false);
  let idleTimer: ReturnType<typeof setTimeout> | null = null;
  let pendingFrame: number | null = null;
  let mouseX = $state(0);
  let mouseY = $state(0);
  const hoverTop = $derived(mouseY < 72);

  function resetIdleTimer(): void {
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = null;
    if (!isFullscreen || hoverTop || deps.getPanelOpen()) {
      headerVisible = true;
      return;
    }
    idleTimer = setTimeout(() => {
      if (isFullscreen && !hoverTop && !deps.getPanelOpen()) headerVisible = false;
    }, 2500);
  }

  function updateEdgeNav(x: number): void {
    if (!isFullscreen) {
      edgeNavVisible = false;
      return;
    }
    const w = typeof window !== 'undefined' ? window.innerWidth : 9999;
    const nearEdge = x < 80 || x > w - 80;
    edgeNavVisible = nearEdge;
  }

  function handleWorkspaceMouseMove(e: MouseEvent): void {
    if (pendingFrame !== null) return;
    const x = e.clientX;
    const y = e.clientY;
    pendingFrame = requestAnimationFrame(() => {
      pendingFrame = null;
      mouseX = x;
      mouseY = y;
      updateEdgeNav(x);
      if (isFullscreen && y < 72) headerVisible = true;
      resetIdleTimer();
    });
  }

  function handleWorkspaceMouseLeave(): void {
    if (pendingFrame !== null) {
      cancelAnimationFrame(pendingFrame);
      pendingFrame = null;
    }
    edgeNavVisible = false;
  }

  function toggleFullscreen(): void {
    const next = !isFullscreen;
    isFullscreen = next;
    readerState.isFullscreen = next;
    if (next) {
      headerVisible = hoverTop;
      updateEdgeNav(mouseX);
      resetIdleTimer();
    } else {
      headerVisible = true;
      edgeNavVisible = false;
      if (idleTimer) {
        clearTimeout(idleTimer);
        idleTimer = null;
      }
    }
  }

  async function toggleWindowFullscreen(): Promise<void> {
    try {
      const cur = await appWindow!.isFullscreen();
      await appWindow!.setFullscreen(!cur);
    } catch {
      logger.warn('Tauri window fullscreen API not available', {}, 'reader');
    }
  }

  function handleGlobalKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape' && isFullscreen) {
      if (!deps.getPanelOpen()) {
        e.preventDefault();
        toggleFullscreen();
        return;
      }
    }
    if (
      e.key.toLowerCase() === 'f' &&
      (e.ctrlKey || e.metaKey) &&
      e.shiftKey &&
      !hasEditableContext(e.target as Element | null)
    ) {
      e.preventDefault();
      void toggleWindowFullscreen();
      return;
    }
    if (e.key.toLowerCase() === 'f' && !hasEditableContext(e.target as Element | null)) {
      if (e.ctrlKey || e.metaKey || e.altKey || e.shiftKey) return;
      e.preventDefault();
      toggleFullscreen();
    }
  }

  function cleanup(): void {
    if (idleTimer) {
      clearTimeout(idleTimer);
      idleTimer = null;
    }
    if (pendingFrame !== null) {
      cancelAnimationFrame(pendingFrame);
      pendingFrame = null;
    }
  }

  return {
    get isFullscreen() {
      return isFullscreen;
    },
    set isFullscreen(v: boolean) {
      isFullscreen = v;
    },
    get headerVisible() {
      return headerVisible;
    },
    get edgeNavVisible() {
      return edgeNavVisible;
    },
    get hoverTop() {
      return hoverTop;
    },
    get mouseX() {
      return mouseX;
    },
    get mouseY() {
      return mouseY;
    },
    get _idleTimer() {
      return idleTimer;
    },
    get _pendingFrame() {
      return pendingFrame;
    },
    resetIdleTimer,
    updateEdgeNav,
    handleWorkspaceMouseMove,
    handleWorkspaceMouseLeave,
    handleGlobalKeydown,
    toggleFullscreen,
    toggleWindowFullscreen,
    cleanup,
  };
}

export type ImmersiveChromeState = ReturnType<typeof createImmersiveChrome>;
