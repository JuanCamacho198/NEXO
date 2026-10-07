/**
 * SupabaseAuthService — wraps supabase-js auth for desktop.
 *
 * Replaces the custom PKCE OAuth flow with Supabase Auth's built-in OAuth +
 * session management.
 *
 * Flow:
 * 1. signInWithGoogle() opens Supabase OAuth URL in system browser
 * 2. User authenticates with Google (hosted by Supabase)
 * 3. Supabase redirects to loopback URL (http://127.0.0.1:{port}/callback)
 * 4. @tauri-apps/plugin-oauth captures the callback URL
 * 5. We extract the PKCE code and call exchangeCodeForSession()
 * 6. supabase-js stores the session internally (via TauriStorage adapter)
 *
 * Identity-only (login-drive-separation): sign-in establishes the Supabase
 * identity session and nothing else. Google Drive authorization is an
 * independent on-demand flow owned by DriveConnectService with its own
 * durable drive.json grant — login never requests Drive scope and the
 * session carries no Drive tokens.
 */

import { start, cancel, onUrl } from '@fabianlars/tauri-plugin-oauth';
import { openUrl } from '@tauri-apps/plugin-opener';
import type { Session } from '@supabase/supabase-js';

import { getSessionClient, getLiveSession } from '$lib/services/supabase';
import { savePersistedAuth } from '$lib/shared/stores/authPersistence';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { isAnonymousSession } from '$lib/shared/services/anonymousSession';
import { createErrorEvent } from '$lib/shared/events/ErrorEvent';
import { logger } from '$lib/shared/logger/Logger';

let currentPort: number | null = null;
let urlUnlisten: (() => void) | null = null;

/**
 * Custom HTML shown in the browser tab after Supabase redirects back.
 * The user sees "You're signed in" and can close the tab.
 */
const LOOPBACK_SUCCESS_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Signed in — Nexo</title>
  <style>
    :root { color-scheme: light dark; }
    * { box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      display: flex; align-items: center; justify-content: center;
      min-height: 100vh; margin: 0; padding: 24px;
      background: #f0fdf4; color: #14532d;
    }
    .card {
      max-width: 420px; width: 100%;
      background: #fff; border: 1px solid #bbf7d0; border-radius: 16px;
      padding: 32px 24px; text-align: center;
    }
    .check {
      width: 64px; height: 64px; margin: 0 auto 16px;
      background: #22c55e; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
    }
    h1 { margin: 0 0 8px; font-size: 22px; }
    p  { margin: 0; font-size: 14px; color: #4b5563; }
    @media (prefers-color-scheme: dark) {
      body { background: #052e16; color: #dcfce7; }
      .card { background: #0f172a; border-color: #14532d; }
      p { color: #94a3b8; }
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="check">
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 13l4 4L19 7"/></svg>
    </div>
    <h1>You're signed in</h1>
    <p>You can close this tab and return to Nexo.</p>
  </div>
</body>
</html>`;

/**
 * Listen for OAuth callbacks on the loopback URL (http://127.0.0.1:{port}/callback).
 * Must be called once at app init (in main.ts).
 */
export async function registerSupabaseCallbackHandler(): Promise<void> {
  if (urlUnlisten) return;

  urlUnlisten = await onUrl(async (url) => {
    try {
      const parsed = new URL(url);

      // Try PKCE flow first: ?code=... in query params
      const code = parsed.searchParams.get('code');

      if (code) {
        const supabase = getSessionClient();
        const { data, error } = await supabase.auth.exchangeCodeForSession(code);

        if (error) {
          logger.error(
            createErrorEvent({
              severity: 'medium',
              category: 'runtime',
              code: 'SUPABASE_CODE_EXCHANGE_FAILED',
              message: error.message,
              context: { error: error.message },
              source: 'sync',
              recoverable: true,
            }),
          );
          return;
        }

        if (data.session) {
          await handleSession(data.session);
        }
        return;
      }

      // Try implicit/hybrid flow: #access_token=... in hash fragment
      const hashParams = new URLSearchParams(parsed.hash.slice(1));
      const accessToken = hashParams.get('access_token');
      const refreshToken = hashParams.get('refresh_token');

      if (accessToken && refreshToken) {
        const supabase = getSessionClient();
        const { data, error } = await supabase.auth.setSession({
          access_token: accessToken,
          refresh_token: refreshToken,
        });

        if (error) {
          logger.error(
            createErrorEvent({
              severity: 'medium',
              category: 'runtime',
              code: 'SUPABASE_SESSION_SET_FAILED',
              message: error.message,
              context: { error: error.message },
              source: 'sync',
              recoverable: true,
            }),
          );
          return;
        }

        if (data.session) {
          await handleSession(data.session);
        }
        return;
      }

      // No recognizable auth payload in the URL
      logger.warn(
        createErrorEvent({
          severity: 'low',
          category: 'validation',
          code: 'SUPABASE_OAUTH_NO_PAYLOAD',
          message: 'OAuth callback missing both authorization code and session tokens',
          context: { url },
          source: 'sync',
          recoverable: true,
        }),
      );
    } catch (err) {
      logger.error(
        createErrorEvent({
          severity: 'high',
          category: 'runtime',
          code: 'SUPABASE_OAUTH_CALLBACK_FAILED',
          message: err instanceof Error ? err.message : String(err),
          context: {},
          source: 'sync',
          recoverable: true,
        }),
      );
    }
  });
}

/**
 * Start Google sign-in via Supabase Auth.
 * Opens Supabase's hosted Google OAuth page in the system browser.
 * The redirect goes to a loopback URL on 127.0.0.1 where plugin-oauth captures it.
 */
export async function signInWithGoogle(): Promise<void> {
  // Cancel any previous loopback
  if (currentPort !== null) {
    try {
      await cancel(currentPort);
    } catch {
      /* best-effort */
    }
    currentPort = null;
  }

  // Start loopback server
  let port: number;
  try {
    port = await start({ response: LOOPBACK_SUCCESS_HTML });
  } catch (err) {
    throw new Error(`Failed to start loopback server: ${String(err)}`);
  }
  currentPort = port;

  const supabase = getSessionClient();
  const redirectTo = `http://127.0.0.1:${port}/callback`;

  const { data, error } = await supabase.auth.signInWithOAuth({
    provider: 'google',
    options: {
      redirectTo,
      skipBrowserRedirect: true,
      // Identity-only (login-drive-separation): no Drive scope here. Drive
      // authorization is an independent on-demand flow (DriveConnectService).
      // `prompt: select_account` lets multi-account users pick an identity
      // without re-granting Drive consent at login.
      queryParams: {
        prompt: 'select_account',
      },
    },
  });

  if (error) {
    throw new Error(`Supabase OAuth error: ${error.message}`);
  }

  if (data.url) {
    await openUrl(data.url);
  }
}

/**
 * Handle a Supabase session — store the identity in authState and persist it.
 *
 * Identity-only (login-drive-separation): the session carries no Drive
 * grant. `provider_token` / `provider_refresh_token` are deliberately
 * ignored — Drive tokens live in drive.json under DriveConnectService, and
 * sign-out must preserve them (see AppState.signOutAndReturnToWelcome).
 *
 * Q1 verified: stripping login scopes does not affect GoTrue identity
 * refresh — `refreshSession()` rotates the Supabase access/refresh token
 * pair independent of any provider scopes granted at sign-in.
 */
async function handleSession(session: Session): Promise<void> {
  authState.setSupabaseSession({
    accessToken: session.access_token,
    refreshToken: session.refresh_token,
    expiresAt: session.expires_at ? session.expires_at * 1000 : null,
    userId: session.user.id,
    email: session.user.email ?? null,
    displayName: session.user.user_metadata?.full_name ?? session.user.user_metadata?.name ?? null,
    photoUrl: session.user.user_metadata?.avatar_url ?? session.user.user_metadata?.picture ?? null,
    isAnonymous: isAnonymousSession(session),
  });

  await savePersistedAuth({
    kind: 'supabase',
    session: session as unknown as Record<string, unknown>,
  });
}

/**
 * Identity provider of the current Supabase session (login-drive-separation).
 *
 * Used by Drive-gated callers to decide whether the Google-Drive connect
 * offer applies. Reads `app_metadata.provider` (the GoTrue server-set OAuth
 * provider) with a `user_metadata.provider` fallback — Q2 resolved by
 * supporting both, preferring `app_metadata`. Returns null when signed out.
 */
export async function getIdentityProvider(): Promise<string | null> {
  const supabase = getSessionClient();
  const { data } = await supabase.auth.getSession();
  const user = data.session?.user as
    { app_metadata?: { provider?: unknown }; user_metadata?: { provider?: unknown } } | undefined;
  const appProvider = user?.app_metadata?.provider;
  if (typeof appProvider === 'string' && appProvider.length > 0) return appProvider;
  const userProvider = user?.user_metadata?.provider;
  if (typeof userProvider === 'string' && userProvider.length > 0) return userProvider;
  return null;
}

/**
 * Sign the user out: clear Supabase session, reset state, clear persistence.
 *
 * Identity-only: only the Supabase session is cleared. The independent
 * Drive grant in drive.json is preserved (spec: sign-out keeps Drive
 * connected) — use DriveConnectService.disconnectDrive() to disconnect Drive.
 */
export async function signOut(): Promise<void> {
  if (currentPort !== null) {
    try {
      await cancel(currentPort);
    } catch {
      /* best-effort */
    }
    currentPort = null;
  }

  const supabase = getSessionClient();
  const { error } = await supabase.auth.signOut();
  if (error) {
    logger.warn('Supabase signOut error:', { error: error.message }, 'app_shell');
  }

  authState.clearSupabaseSession();
  // Persistence is cleared via AppState signOutAndReturnToWelcome
}

/**
 * Sign in anonymously to get an auth context for RLS.
 * Used on app start when no session exists.
 *
 * DA-3: anonymous fallback applies ONLY when no real session can be restored.
 * If any live session exists (real or anon), this no-ops — an anonymous
 * sign-in must never clobber a real persisted session.
 */
export async function signInAnonymously(): Promise<void> {
  if (getLiveSession() !== null) return;

  const supabase = getSessionClient();
  const { data, error } = await supabase.auth.signInAnonymously();

  if (error) {
    logger.warn('Anon sign-in failed:', { error: error.message }, 'app_shell');
    return;
  }

  // Re-check after the await: a real session may have landed while the
  // anonymous sign-in was in flight (e.g. OAuth callback completing) — in
  // that case discard the anon result and keep the real session (DA-3.1).
  if (data.session && getLiveSession() === null) {
    authState.setSupabaseSession({
      accessToken: data.session.access_token,
      refreshToken: data.session.refresh_token,
      expiresAt: data.session.expires_at ? data.session.expires_at * 1000 : null,
      userId: data.session.user.id,
      email: null,
      displayName: null,
      photoUrl: null,
      isAnonymous: true,
    });
  }
}

/**
 * Restore a persisted Supabase session on app start.
 * Returns the session if restored, or null.
 */
export async function restoreSession(): Promise<Session | null> {
  const supabase = getSessionClient();
  const { data } = await supabase.auth.getSession();
  return data.session;
}

/**
 * Remove OAuth callback handler (for cleanup).
 */
export function unregisterCallbackHandler(): void {
  if (urlUnlisten) {
    urlUnlisten();
    urlUnlisten = null;
  }
}
