import { type as osType } from '@tauri-apps/plugin-os';
import type { ShortcutPlatform } from './registry';

/**
 * Resolve the shortcut platform. Outside Tauri (tests, plain browser) the OS
 * probe throws or returns nothing, and we fail closed to the `default`
 * (Ctrl) binding rather than guessing macOS.
 */
export function getShortcutPlatform(): ShortcutPlatform {
  try {
    return osType() === 'macos' ? 'mac' : 'default';
  } catch {
    return 'default';
  }
}
