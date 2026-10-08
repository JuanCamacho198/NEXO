/**
 * syncAlert — global auth alert surfaced by sync/Drive failures (D7, SR-3).
 *
 * Typed `AUTH_REQUIRED` / `AUTH_EXPIRED` errors from sync paths are reported
 * here instead of being console.error-only; `SyncAuthBanner` renders the global
 * re-auth banner with a "Sign in with Google" CTA. The banner clears
 * automatically when the user re-authenticates (the banner component watches
 * `authState.isAuthenticated` — D7) and via explicit dismiss.
 *
 * The store never schedules retries: it is a pure state holder, so repeated
 * auth-class reports just refresh the banner (no hot retry loop — SR-2).
 *
 * Classification (NOTIF-01): this is a domain status store, not a notification
 * store — the banner keeps its own state and UI. It ALSO emits one deduped
 * `notify` so the same user-visible event reaches the unified tray.
 */
import { notify } from './notificationCenter.svelte';

export type SyncAlertType = 'auth_required' | 'auth_expired';

export interface SyncAlert {
  type: SyncAlertType;
  message: string;
}

let current: SyncAlert | null = $state(null);

export const syncAlertStore = {
  get current(): SyncAlert | null {
    return current;
  },
  set(alert: SyncAlert | null): void {
    current = alert;
  },
  clear(): void {
    current = null;
  },
};

/**
 * Surface a typed auth error to the banner. Recognizes the stable
 * `AUTH_REQUIRED` / `AUTH_EXPIRED` codes (SyncError / DriveError) only —
 * a PostgrestError (status 400/401, Postgres code) is NOT an auth-class typed
 * error here and is left to the WU4 breaker. Messages from syncError() are
 * already redacted (never tokens/JWTs). Returns true when surfaced.
 */
export function reportAuthError(error: unknown): boolean {
  const code = (error as { code?: unknown } | null)?.code;
  if (code !== 'AUTH_REQUIRED' && code !== 'AUTH_EXPIRED') return false;
  const type: SyncAlertType = code === 'AUTH_REQUIRED' ? 'auth_required' : 'auth_expired';
  const fallback = 'Google Drive access expired. Please sign in with Google again.';
  current = {
    type,
    message: error instanceof Error && error.message ? error.message : fallback,
  };
  // Unified tray entry, deduped per auth state so a retry loop cannot spam it.
  notify({
    source: 'sync',
    severity: 'error',
    i18nKey:
      type === 'auth_required' ? 'notifications.auth.required' : 'notifications.auth.expired',
    dedupKey: `sync-auth:${type}`,
    target: { kind: 'route', route: 'sync' },
  });
  return true;
}
