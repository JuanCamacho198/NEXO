/**
 * Operating systems for which `CustomTitlebar.svelte` renders its own window controls.
 *
 * `@tauri-apps/plugin-os` `type()` returns lowercase values (`'windows'`,
 * `'macos'`, `'linux'`, ...). Matching is exact and lowercase: case variants and
 * unknown values fail closed.
 */
const CUSTOM_TITLEBAR_OS_TYPES: ReadonlySet<string> = new Set(['windows', 'macos', 'linux']);

/**
 * Decides whether the app draws its own titlebar for a given OS type.
 *
 * `tauri.conf.json` sets `app.windows[0].decorations: false`, and Tauri's config
 * is not platform-conditional: every desktop target launches borderless. The
 * custom titlebar is therefore the *only* set of window controls on every
 * platform, not a Windows-only convenience, and the answer must be `true`
 * everywhere the bundle ships. Returning `false` on a borderless window leaves
 * the user with no way to move, minimise, maximise or close it.
 */
export function isCustomTitlebarPlatform(osType: string | undefined): boolean {
  return typeof osType === 'string' && CUSTOM_TITLEBAR_OS_TYPES.has(osType);
}

/**
 * Decides whether the custom titlebar follows the macOS window-button layout.
 *
 * macOS puts the traffic lights on the LEFT and calls the green button "zoom"
 * rather than "maximize". The layout and labels must follow the platform the
 * user is looking at, not the one the bar was originally built for.
 */
export function usesMacTitlebarLayout(osType: string | undefined): boolean {
  return osType === 'macos';
}
