import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
import { getCurrentWebviewWindow } from '@tauri-apps/api/webviewWindow';
import { hasEditableContext } from '$lib/features/reader/viewer-epub/keyboardNav';
import { logger } from '$lib/shared/logger/Logger';

// Symmetric edge bands: the top band clears the titlebar / traffic-light region
// and the bottom band clears the revealed bar. Both are 72 so the same distance
// from either edge feels the same and there is no second magic number.
export const TOP_BAND = 72;
export const BOTTOM_BAND = 72;

export type ImmersiveChromeDeps = {
  getPanelOpen: () => boolean;
  appWindow?: {
    isFullscreen: () => Promise<boolean>;
    setFullscreen: (v: boolean) => Promise<void>;
  };
};

export function createImmersiveChrome(deps: ImmersiveChromeDeps): {
  isFullscreen: boolean;
  readonly chromeVisible: boolean;
  readonly headerVisible: boolean;
  readonly edgeNavVisible: boolean;
  readonly hoverTop: boolean;
  readonly hoverBottom: boolean;
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
  // Single shared visibility value. Header and footer both read this one state
  // through their getters, so their visibility can never diverge.
  let chromeVisible = $state(true);
  let edgeNavVisible = $state(false);
  let idleTimer: ReturnType<typeof setTimeout> | null = null;
  let pendingFrame: number | null = null;
  let mouseX = $state(0);
  let mouseY = $state(0);
  const hoverTop = $derived(mouseY < TOP_BAND);
  const hoverBottom = $derived(mouseY > window.innerHeight - BOTTOM_BAND);
  // U3 trick: cursor is visible on every move, hidden only by the idle clock.
  // Derived state alone would keep it hidden across content-area moves
  // (header stays hidden there), so this is explicit state driven by the
  // same 2.5s timer.
  let cursorHidden = $state(false);

  function resetIdleTimer(): void {
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = null;
    cursorHidden = false;
    // A pointer resting in either band, or an open panel, forces the shared
    // visibility. One check covers both bars because the clock is shared.
    if (!isFullscreen || hoverTop || hoverBottom || deps.getPanelOpen()) {
      chromeVisible = true;
      return;
    }
    idleTimer = setTimeout(() => {
      if (isFullscreen && !hoverTop && !hoverBottom && !deps.getPanelOpen()) chromeVisible = false;
      if (isFullscreen && !hoverTop && !hoverBottom && !edgeNavVisible && !deps.getPanelOpen())
        cursorHidden = true;
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
      if (isFullscreen && (y < TOP_BAND || y > window.innerHeight - BOTTOM_BAND))
        chromeVisible = true;
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
      chromeVisible = true;
      edgeNavVisible = true;
      resetIdleTimer();
    } else {
      chromeVisible = true;
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
    get chromeVisible() {
      return chromeVisible;
    },
    // Read-only alias for the previous header-only name. It reads the shared
    // value and deliberately exposes NO setter: having no setter is the
    // guarantee that nothing can write one bar's visibility independently of
    // the other. There is no `footerVisible` member at all — the footer reads
    // this same getter.
    get headerVisible() {
      return chromeVisible;
    },
    get edgeNavVisible() {
      return edgeNavVisible;
    },
    get hoverTop() {
      return hoverTop;
    },
    get hoverBottom() {
      return hoverBottom;
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
