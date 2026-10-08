import { getCurrentWebviewWindow } from '@tauri-apps/api/webviewWindow';

/**
 * Toggle the real OS/window fullscreen through the Tauri webview API — the same
 * mechanism the reader used for Ctrl+Shift+F. Outside Tauri (or on an engine
 * without the API) this is a no-op: the app has no other fullscreen channel, so
 * we do not fake one.
 */
export async function toggleAppFullscreen(): Promise<void> {
  try {
    const appWindow = getCurrentWebviewWindow();
    const current = await appWindow.isFullscreen();
    await appWindow.setFullscreen(!current);
  } catch {
    // Not running inside Tauri, or the webview does not expose fullscreen.
  }
}
