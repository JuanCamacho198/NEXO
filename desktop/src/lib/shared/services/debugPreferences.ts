import type { SettingsPort } from '$lib/shared/ports/SettingsPort';
import { TauriSettingsAdapter } from '$lib/shared/ports/adapters/tauri/TauriSettingsAdapter';
import type { AppSettingDto } from '$lib/shared/types';

/** Storage key inside the generic `app_settings` table. */
export const DEBUG_ENABLED_SETTING_KEY = 'debug.enabled' as const;

/** Accepts anything persisted and returns a valid flag (unknown → off). */
export function parseDebugEnabled(raw: unknown): boolean {
  return raw === true;
}

let settingsPort: SettingsPort = new TauriSettingsAdapter();

/** Replaces the settings adapter (tests, non-Tauri hosts). */
export function setDebugPreferencesPort(next: SettingsPort): void {
  settingsPort = next;
}

/** Synchronous snapshot; before any load (and in unit tests) it is off. */
let cached = false;

/** Current flag without I/O (mutate via `setDebugEnabled`). */
export function getCachedDebugEnabled(): boolean {
  return cached;
}

function findEnabledValue(settings: AppSettingDto[]): unknown {
  const entry = settings.find((item) => item.key === DEBUG_ENABLED_SETTING_KEY);
  if (!entry) return null;
  try {
    return JSON.parse(entry.valueJson) as unknown;
  } catch {
    return null;
  }
}

/**
 * Loads the persisted flag into the cache. Unknown storage content falls
 * back to off; a storage failure keeps the current cache.
 */
export async function loadDebugEnabled(): Promise<boolean> {
  try {
    const settings = await settingsPort.getAppSettings();
    cached = parseDebugEnabled(findEnabledValue(settings));
  } catch {
    // Keep the current cache: debug chrome must never appear on I/O failure.
  }
  return getCachedDebugEnabled();
}

/**
 * Updates the cache immediately and persists it. A persistence failure
 * keeps the session value and propagates to the caller.
 */
export async function setDebugEnabled(enabled: boolean): Promise<boolean> {
  cached = enabled;
  await settingsPort.upsertAppSettings([
    {
      key: DEBUG_ENABLED_SETTING_KEY,
      valueJson: JSON.stringify(enabled),
      updatedAt: new Date().toISOString(),
    },
  ]);
  return getCachedDebugEnabled();
}

/** Resets the port seam and cache between tests. */
export function resetDebugPreferencesForTests(): void {
  settingsPort = new TauriSettingsAdapter();
  cached = false;
}
