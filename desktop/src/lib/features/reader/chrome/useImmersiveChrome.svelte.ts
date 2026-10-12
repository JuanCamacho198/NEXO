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
  readonly cursorHidden: boolean;
  readonly mouseX: number;
  readonly mouseY: number;
  readonly _idleTimer: ReturnType<typeof setTimeout> | null;
  readonly _pendingFrame: number | null;
  resetIdleTimer(): void;
  updateEdgeNav(x: number): void;
  handleWorkspaceMouseMove(e: MouseEvent): void;
  handleForwardedMouse(x: number, y: number): void;
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
  // U3 trick: cursor is visible on every move, hidden only by the idle clock.
  // Derived state alone would keep it hidden across content-area moves
  // (header stays hidden there), so this is explicit state driven by the
  // same 2.5s timer.
  let cursorHidden = $state(false);

  function resetIdleTimer(): void {
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = null;
    cursorHidden = false;
    if (!isFullscreen || hoverTop || deps.getPanelOpen()) {
      headerVisible = true;
      return;
    }
    idleTimer = setTimeout(() => {
      if (isFullscreen && !hoverTop && !deps.getPanelOpen()) headerVisible = false;
      if (isFullscreen && !hoverTop && !edgeNavVisible && !deps.getPanelOpen()) cursorHidden = true;
    }, 2500);
  }

  function updateEdgeNav(x: number): void {
    if (!isFullscreen) {
      edgeNavVisible = false;
      return;
    }
    const w = typeof window !== 'undefined' ? window.innerWidth : 9999;
    const nearEdge = x < 120 || x > w - 120;
    edgeNavVisible = nearEdge;
  }

  function queueMove(x: number, y: number): void {
    if (pendingFrame !== null) return;
    pendingFrame = requestAnimationFrame(() => {
      pendingFrame = null;
      mouseX = x;
      mouseY = y;
      updateEdgeNav(x);
      if (isFullscreen && y < 72) headerVisible = true;
      resetIdleTimer();
    });
  }

  function handleWorkspaceMouseMove(e: MouseEvent): void {
    queueMove(e.clientX, e.clientY);
  }

  function handleForwardedMouse(x: number, y: number): void {
    queueMove(x, y);
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
    cursorHidden = false;
    if (next) {
      // U6 discoverability: always open visible. The 2.5s idle clock fades
      // header + cursor; edges fade on the first move back to center.
      headerVisible = true;
      edgeNavVisible = true;
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
    get cursorHidden() {
      return cursorHidden;
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
    handleForwardedMouse,
    handleWorkspaceMouseLeave,
    handleGlobalKeydown,
    toggleFullscreen,
    toggleWindowFullscreen,
    cleanup,
  };
}

export type ImmersiveChromeState = ReturnType<typeof createImmersiveChrome>;
