/**
 * secretProtectionNotice — surfaces the "secrets cannot be sealed here" state
 * to the user exactly once per launch (desktop-security-checklist-audit #08).
 *
 * On macOS/Linux `protectSecret` fails, so the Supabase session is never
 * written to disk: the user is signed in only until they quit. The backend
 * already prints a startup diagnostic, but that goes to a console nobody sees
 * in a shipped GUI app. This turns the same fact into one tray entry via the
 * existing `notify(...)` surface — no modal, no blocking, one dedup key so a
 * re-run of app init cannot stack banners.
 *
 * `false` is not an error state: reading, library and everything else work.
 * Only persistence across launches is lost.
 */
import { invoke } from '@tauri-apps/api/core';
import { notify } from '$lib/shared/stores/notificationCenter.svelte';
import type { MessageKey } from '$lib/shared/i18n';

/** Stable dedup key: one banner per launch, never one per notify attempt. */
export const SECRET_PROTECTION_DEDUP_KEY = 'secrets:protection-unavailable';

/** The i18n key carrying the user-facing copy. */
export function secretProtectionNoticeKey(): MessageKey {
  return 'notifications.secrets.unavailable';
}

export interface SecretProtectionNoticeDeps {
  /** Reads the backend flag; defaults to the `secretProtectionAvailable` IPC. */
  readDiagnostic?: () => Promise<boolean>;
}

/**
 * Emit the unavailability notice when `available` is false. Pure decision:
 * the caller passes the backend flag it already read, so nothing is inferred
 * from the platform string here. Returns without touching the tray when
 * protection works (Windows), so the banner can never appear there.
 */
export function surfaceSecretProtectionUnavailable(
  available: boolean,
  warn: (message: string) => void,
  deps: SecretProtectionNoticeDeps = {},
): void {
  if (available) return;
  warn(`[nexo] ${SECRET_PROTECTION_DEDUP_KEY}: session will not be saved between launches`);
  const read = deps.readDiagnostic ?? (() => Promise.resolve(available));
  void (async () => {
    try {
      await read();
    } catch {
      // The backend flag already said unavailable; a failed re-read is not a
      // reason to hide the warning, but not a reason to duplicate it either.
    }
    try {
      notify({
        source: 'sync',
        severity: 'warning',
        i18nKey: secretProtectionNoticeKey(),
        dedupKey: SECRET_PROTECTION_DEDUP_KEY,
        interruption: 'in-app',
        target: { kind: 'route', route: 'sync' },
      });
    } catch {
      // A tray failure must never break app startup.
    }
  })();
}

/**
 * Startup probe: asks the backend once and surfaces the notice when it says
 * secrets cannot be sealed. Never throws — a missing/unavailable IPC (web
 * build, older binary) simply produces no banner.
 */
export async function checkSecretProtectionAtStartup(
  warn: (message: string) => void = () => {},
  invokeFn: (cmd: string) => Promise<unknown> = invoke,
): Promise<void> {
  let available: boolean;
  try {
    available = (await invokeFn('secretProtectionAvailable')) === true;
  } catch {
    return;
  }
  surfaceSecretProtectionUnavailable(available, warn);
}
