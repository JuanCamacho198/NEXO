import { getCurrentWebviewWindow } from '@tauri-apps/api/webviewWindow';
import { type as osType } from '@tauri-apps/plugin-os';
import { isCustomTitlebarPlatform } from '$lib/shared/utils/platform';

export interface TitlebarState {
  get isMaximized(): boolean;
  get isCustomTitlebar(): boolean;
  init(): Promise<void>;
  destroy(): void;
  handleMinimize(): Promise<void>;
  handleMaximize(): Promise<void>;
  handleClose(): Promise<void>;
}

/**
 * Resolve the platform gate synchronously, before the store is handed out.
 *
 * `osType()` is synchronous, so this runs at module load and the first paint is
 * already correct. Waiting for a mount effect left a window where the `{#if}`
 * was false; with `decorations: false` the OS draws no chrome, so that window
 * had no controls at all. Resolving here — rather than relying on a consumer to
 * assign it — keeps the decision next to the state it drives, so no import
 * order or mount timing can leave the titlebar unrendered.
 *
 * Direct `console`, not `logger`: this runs at module load, before `main.ts`
 * registers the logger sinks, so a logger call here would be swallowed.
 */
function resolveCustomTitlebar(): boolean {
  try {
    const os = osType();
    const enabled = isCustomTitlebarPlatform(os);
    console.log('[custom-titlebar] os =', os, '| enabled =', enabled);
    return enabled;
  } catch (err) {
    console.warn('[custom-titlebar] osType() threw:', err);
    return false;
  }
}

export function createTitlebarState(): TitlebarState {
  let isMaximized = $state(false);
  /**
   * Whether the custom titlebar should render (Windows only). Seeded at
   * creation by `resolveCustomTitlebar()` and read by App.svelte to gate the
   * render.
   *
   * Exposed through a closure getter rather than a bare exported `$state`:
   * Svelte 5 cannot re-export a `$state` binding across modules (the compiler
   * only transforms runes inside the declaring file), so the signal must stay
   * in this module and be read here for reactivity to hold on the consumer.
   */
  let isCustomTitlebar = $state(resolveCustomTitlebar());
  let unlistenResize: (() => void) | null = null;

  async function init(): Promise<void> {
    const appWindow = getCurrentWebviewWindow();
    isMaximized = await appWindow.isMaximized();
    unlistenResize = await appWindow.onResized(async () => {
      isMaximized = await appWindow.isMaximized();
    });
  }

  function destroy(): void {
    unlistenResize?.();
    unlistenResize = null;
  }

  async function handleMinimize(): Promise<void> {
    await getCurrentWebviewWindow().minimize();
  }

  async function handleMaximize(): Promise<void> {
    await getCurrentWebviewWindow().toggleMaximize();
  }

  async function handleClose(): Promise<void> {
    await getCurrentWebviewWindow().close();
  }

  return {
    get isMaximized() {
      return isMaximized;
    },
    get isCustomTitlebar() {
      return isCustomTitlebar;
    },
    init,
    destroy,
    handleMinimize,
    handleMaximize,
    handleClose,
  };
}

export const titlebarState = createTitlebarState();
