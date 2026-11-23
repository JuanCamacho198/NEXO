/**
 * On-demand Google Drive authorization, independent of the Supabase identity
 * session (login-drive-separation).
 *
 * Ports Android's proven separation (`DriveOAuthSession` pure core +
 * `GoogleDriveAuthHelper` thin platform layer) to desktop: login establishes
 * identity only, and this module owns the Drive grant with its own durable
 * `drive.json` file (see `drivePersistence.ts`).
 *
 * The OAuth exchange itself belongs to Supabase (GoTrue), never to this
 * bundle: the configured Google client requires a client_secret, and a
 * desktop bundle cannot hold one. GoTrue holds the secret and calls Google
 * server-side, both for the initial exchange and for every refresh.
 *
 * Structure:
 * - Pure protocol core (`parseDriveCallbackUrl`): no
 *   platform imports, driven by tests.
 * - Thin Tauri adapter (`beginDriveConnect`, `disconnectDrive`): authorize URL
 *   from `signInWithOAuth`, loopback via `@fabianlars/tauri-plugin-oauth`,
 *   browser launch via `openUrl`.
 *
 * Invariants:
 * - Tokens persist ONLY on successful completion; cancel/failure persist
 *   nothing and report `canceled` silently (never a toast, Android parity).
 * - Concurrent `beginDriveConnect` calls coalesce onto one loopback attempt
 *   (singleton `pendingConnect`); concurrent refreshes coalesce onto one
 *   Supabase refresh (moved `driveTokenRefreshInFlight` mutex pattern).
 * - Disconnect clears ONLY the Drive grant (plus best-effort Google revoke)
 *   and never touches the Supabase session.
 */

import { buildLoopbackPage } from './oauthLoopbackPage';
import { cancel, onUrl } from '@fabianlars/tauri-plugin-oauth';
import { startLoopbackServer } from './oauthLoopbackServer';
import { openUrl } from '@tauri-apps/plugin-opener';

import { createErrorEvent } from '$lib/shared/events/ErrorEvent';
import { logger } from '$lib/shared/logger/Logger';
import { redactLogLine, syncError, DRIVE_SCOPE } from '$lib/shared/protocol/DriveCatalogContract';
import {
  clearDriveGrant,
  loadDriveGrant,
  saveDriveGrant,
} from '$lib/shared/stores/drivePersistence';
import { driveState } from '$lib/shared/stores/driveState.svelte';
import { DRIVE_PROMPT_DECLINE_KEY_PREFIX } from '$lib/shared/services/driveConnectPromptGate';
import { getSessionClient, getSupabaseUrl, getSupabaseAnonKey } from '$lib/services/supabase';

const REVOKE_ENDPOINT = 'https://oauth2.googleapis.com/revoke';

export type DriveAuthResult =
  | { kind: 'canceled' }
  | { kind: 'success'; accessToken: string }
  | { kind: 'failure'; code: string; message: string };

export type DriveCallbackErrorCode = 'state_mismatch' | 'no_code' | 'user_denied' | 'server_failed';

export class DriveOAuthError extends Error {
  public readonly code: DriveCallbackErrorCode;
  constructor(message: string, code: DriveCallbackErrorCode) {
    super(message);
    this.name = 'DriveOAuthError';
    this.code = code;
  }
}

export type DriveConfigErrorCode = 'DRIVE_CONFIG_MISSING';

/**
 * Connect-route failure code for "there is no network". Kept at the connect
 * boundary (not inferred from a fetch failure) because the callback never
 * arrives offline, so there is no failure to inspect.
 */
const OFFLINE_CODE = 'DRIVE_OFFLINE';

export class DriveConfigError extends Error {
  public readonly code: DriveConfigErrorCode = 'DRIVE_CONFIG_MISSING';
  constructor(message: string) {
    super(message);
    this.name = 'DriveConfigError';
  }
}

export function parseDriveCallbackUrl(url: string, expectedState: string | null): string {
  const parsed = new URL(url);
  const code = parsed.searchParams.get('code');
  const state = parsed.searchParams.get('state');
  const error = parsed.searchParams.get('error');

  if (error === 'access_denied') {
    throw new DriveOAuthError('User denied Drive consent', 'user_denied');
  }
  if (error) {
    throw new DriveOAuthError(`OAuth provider returned error: ${error}`, 'server_failed');
  }
  if (!code) {
    throw new DriveOAuthError('No authorization code in callback URL', 'no_code');
  }
  // `null` means the caller does not own the state for this flow. That is the
  // Supabase-driven path: GoTrue generates and substitutes its own state, so
  // comparing against a local value is impossible and validating it would
  // reject every real callback. PKCE carries the binding instead — GoTrue
  // redeems the code against the verifier it stored for this trip, so a code
  // minted for a different flow cannot be exchanged.
  if (expectedState !== null && state !== expectedState) {
    throw new DriveOAuthError('State parameter mismatch — possible CSRF', 'state_mismatch');
  }
  return code;
}

let pendingConnect: Promise<DriveAuthResult> | null = null;
let driveTokenRefreshInFlight: Promise<string> | null = null;
let lastResult: DriveAuthResult | null = null;

function clearDeclineMarkers(): void {
  if (typeof localStorage === 'undefined') {
    return;
  }
  const doomed: string[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (key !== null && key.startsWith(DRIVE_PROMPT_DECLINE_KEY_PREFIX)) {
      doomed.push(key);
    }
  }
  for (const key of doomed) {
    localStorage.removeItem(key);
  }
}

function reportFailure(code: string, message: string): DriveAuthResult {
  const result: DriveAuthResult = { kind: 'failure', code, message };
  lastResult = result;
  driveState.setDriveError(code);
  logger.warn(
    createErrorEvent({
      severity: 'low',
      category: 'runtime',
      code: `DRIVE_CONNECT_${code.toUpperCase()}`,
      message: redactLogLine(message),
      context: { code },
      source: 'sync',
      recoverable: true,
    }),
  );
  return result;
}

/**
 * Ask Supabase for the Google authorize URL. GoTrue appends `DRIVE_SCOPE` to the
 * mandatory `email profile` base (never replaces it) and passes top-level
 * params through, so `access_type=offline&prompt=consent` land in the Google
 * URL. `skipBrowserRedirect` keeps supabase-js from opening a browser on its
 * own: the Tauri opener does that.
 *
 * Our CSRF `state` is appended here rather than through `options.queryParams`
 * (which supabase-js emits as one literal `query_params=...` param) so it
 * arrives as a real top-level param and GoTrue echoes it back on the loopback
 * redirect.
 *
 * PKCE is not ours to send: the Supabase client is created with
 * `flowType: 'pkce'`, so GoTrue performs the code challenge for this flow.
 */
async function buildDriveAuthorizeUrl(redirectTo: string): Promise<string> {
  const supabase = getSessionClient();
  const { data, error } = await supabase.auth.signInWithOAuth({
    provider: 'google',
    options: {
      redirectTo,
      skipBrowserRedirect: true,
      // Drive is an explicit opt-in (login-drive-separation): the sign-in flow
      // asks for identity scopes only, and this call is the only place the
      // Drive scope is requested. GoTrue appends these scopes to its mandatory
      // `email profile` base rather than replacing it (measured).
      scopes: DRIVE_SCOPE,
      // `access_type=offline` is what makes Google return a refresh token;
      // without it the grant dies with the access token. Top-level query params
      // reach the Google URL verbatim (measured); the supabase-js `queryParams`
      // option does not merge, so these must stay here as upstream params.
      queryParams: {
        access_type: 'offline',
        prompt: 'consent',
      },
    },
  });
  if (error || !data.url) {
    throw new DriveConfigError(
      `Failed to build the Drive authorize URL: ${error?.message ?? 'no URL returned'}`,
    );
  }
  // No `state` is appended: GoTrue owns the OAuth state for this trip and
  // substitutes its own (measured — a caller-supplied value is replaced), so
  // adding one would be dead weight. PKCE is the binding that matters and
  // GoTrue holds the verifier server-side.
  return data.url;
}

async function exchangeCodeForGrant(
  code: string,
): Promise<{ accessToken: string; refreshToken: string }> {
  // The Google exchange happens server-side in GoTrue, never here: the
  // configured client requires a client_secret (measured: secret-less exchange
  // answers `400 client_secret is missing`, and a wrong secret answers
  // `401 invalid_client`, so it validates rather than ignores one). A desktop
  // bundle cannot hold that secret — `8deb4e89` removed it for that reason.
  //
  // Supabase already holds the Google Web client credentials and performs the
  // exchange itself (measured: a bogus code reaches Google and comes back as
  // `invalid_grant: Malformed auth code`, which only happens when GoTrue did
  // the call with its own secret). So the code is handed to Supabase and the
  // grant is read off the resulting session.
  const supabase = getSessionClient();
  const { data, error } = await supabase.auth.exchangeCodeForSession(code);
  if (error) {
    throw new DriveOAuthError(`Drive token exchange failed: ${error.message}`, 'server_failed');
  }
  const session = data.session;
  const accessToken = session?.provider_token ?? null;
  const refreshToken = session?.provider_refresh_token ?? null;
  if (!accessToken) {
    throw new DriveOAuthError('Drive token exchange returned no access token', 'server_failed');
  }
  if (!refreshToken) {
    // Without a refresh token the grant dies when the access token expires, so
    // it is refused up front instead of failing later on the first Drive op.
    // The authorize URL asks for `access_type=offline`; a missing refresh token
    // means Google treated this as an online request.
    throw new DriveOAuthError(
      'Drive authorization returned no refresh grant. Reconnect Drive and allow offline access.',
      'server_failed',
    );
  }
  return { accessToken, refreshToken };
}

/**
 * Drive connect-route error: no grant on disk, so the feature is simply not
 * connected. Carries `DRIVE_NOT_CONNECTED` (the non-retryable connect-route
 * code) instead of `AUTH_REQUIRED`: Drive is a separate opt-in grant
 * (login-drive-separation), so "never connected" is not an identity/re-auth
 * incident. Reporting it as an auth error put a global re-auth banner
 * ("Sign in with Google") in front of every signed-in user who had not yet
 * connected Drive.
 */
function driveNotConnected(): Error {
  return syncError(
    'DRIVE_NOT_CONNECTED',
    'Google Drive is not connected. Connect Google Drive in Settings to use Drive features.',
    false,
  );
}

/**
 * Drive grant rejected mid-refresh: the user HAD connected Drive and the grant
 * is now dead, so this is a real re-connect incident and stays `AUTH_REQUIRED`
 * so the global auth banner surfaces it (SR-3.1).
 */
function driveAuthRequired(): Error {
  return syncError(
    'AUTH_REQUIRED',
    'Google Drive is not connected. Connect Google Drive in Settings to use Drive features.',
    false,
  );
}

/**
 * `refreshSession()` reuses the stored refresh token, which for a
 * provider-backed session is the Google one, and GoTrue redoes the Google
 * refresh server-side with its own secret.
 *
 * The stored grant is the precondition (no grant → connect route), because it
 * records that the user opted into Drive at all. A Supabase refresh fails for
 * two very different reasons and they must not be conflated:
 * - dead: the provider grant or the Supabase session is gone (`Auth*Error`,
 *   invalid/expired refresh token, 400/401) → clear the Drive grant and report
 *   `AUTH_REQUIRED`, so a dead token is never retried on every Drive op.
 * - transient: network or 429/5xx → keep the grant and throw a generic error.
 */
async function doRefreshDriveAccessToken(): Promise<string> {
  const grant = await loadDriveGrant();
  if (!grant) {
    throw driveNotConnected();
  }
  // The Google refresh happens server-side in the `drive-token` function, which
  // holds the client secret. It cannot happen here: the configured Google
  // client requires a client_secret on refresh (measured for both the Web and
  // Desktop clients), and a distributed bundle cannot hold one.
  //
  // It also cannot happen through `refreshSession()`: GoTrue emits
  // `provider_token` only once, immediately after sign-in
  // (`@supabase/auth-js` GoTrueClient: "The client library will emit these
  // values only once immediately after the user signs in"), so a session
  // refresh returns no Google token at all.
  const supabase = getSessionClient();
  const { data: sessionData } = await supabase.auth.getSession();
  const accessToken = sessionData.session?.access_token;
  if (!accessToken) {
    await clearDriveGrant();
    driveState.setDriveAuthorized(false);
    driveState.setDriveError('AUTH_REQUIRED');
    throw driveAuthRequired();
  }

  let response: Response;
  try {
    response = await fetch(`${getSupabaseUrl()}/functions/v1/drive-token`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`,
        apikey: getSupabaseAnonKey(),
      },
      body: JSON.stringify({ refresh_token: grant.refreshToken }),
    });
  } catch (error) {
    // Network failure is transient: the grant is kept and the caller retries.
    throw new Error(
      redactLogLine(
        `Drive token refresh failed: ${error instanceof Error ? error.message : String(error)}`,
      ),
    );
  }

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as { error?: string } | null;
    // The function reports a revoked or expired Google grant as
    // DRIVE_GRANT_REVOKED (401). Any other failure is transient: the grant
    // stays so a network blip or an upstream 5xx is not mistaken for a
    // disconnect the user has to redo.
    if (body?.error === 'DRIVE_GRANT_REVOKED') {
      await clearDriveGrant();
      driveState.setDriveAuthorized(false);
      driveState.setDriveError('AUTH_REQUIRED');
      throw driveAuthRequired();
    }
    throw new Error(redactLogLine(`Drive token refresh failed: ${response.status}`));
  }

  const payload = (await response.json().catch(() => null)) as { access_token?: unknown } | null;
  const fresh = typeof payload?.access_token === 'string' ? payload.access_token : '';
  if (!fresh) {
    throw new Error('Drive token refresh returned no access token');
  }
  return fresh;
}

export function refreshDriveAccessToken(): Promise<string> {
  if (driveTokenRefreshInFlight) return driveTokenRefreshInFlight;
  driveTokenRefreshInFlight = doRefreshDriveAccessToken().finally(() => {
    driveTokenRefreshInFlight = null;
  });
  return driveTokenRefreshInFlight;
}

export async function getDriveAccessToken(): Promise<string> {
  return refreshDriveAccessToken();
}

export async function isDriveAuthorized(): Promise<boolean> {
  const grant = await loadDriveGrant();
  const authorized = grant !== null;
  if (driveState.isAuthorized !== authorized) {
    driveState.setDriveAuthorized(authorized);
  }
  return authorized;
}

async function runDriveConnect(): Promise<DriveAuthResult> {
  // Offline, the loopback callback can never arrive: the flow would show
  // "Connecting…" forever and leave the buttons disabled. Fail up front with an
  // explicit code instead, before any loopback or browser work.
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    return reportFailure(OFFLINE_CODE, 'Connect to the internet to link Google Drive.');
  }

  driveState.setDriveConnecting(true);
  let port: number;
  try {
    port = await startLoopbackServer(buildLoopbackPage('driveConnected'));
  } catch (error) {
    driveState.setDriveConnecting(false);
    return reportFailure(
      'LOOPBACK_UNAVAILABLE',
      `Failed to start loopback server: ${String(error)}`,
    );
  }
  const redirectUri = `http://127.0.0.1:${port}/`;

  const callbackPromise = new Promise<string>((resolve, reject) => {
    let settled = false;
    void onUrl((url) => {
      if (settled) return;
      settled = true;
      try {
        // `state` is NOT checked against our own value here: GoTrue generates
        // and owns the OAuth state for this authorize trip, replacing whatever
        // is passed in (measured: a caller-supplied `state=statetest…` reaches
        // Google as a GoTrue UUID). Checking ours would reject every callback.
        //
        // CSRF protection is not lost: the code is exchanged server-side by
        // GoTrue against the PKCE verifier it stored for this flow, so a code
        // minted for another flow cannot be redeemed. The loopback itself only
        // accepts the one response GoTrue sends to this port.
        resolve(parseDriveCallbackUrl(url, null));
      } catch (error) {
        reject(error);
      }
    });
  });

  try {
    await openUrl(await buildDriveAuthorizeUrl(redirectUri));
  } catch (error) {
    await cancel(port).catch(() => {});
    driveState.setDriveConnecting(false);
    const message = error instanceof Error ? error.message : String(error);
    if (error instanceof DriveConfigError) {
      return reportFailure('DRIVE_CONFIG_MISSING', message);
    }
    return reportFailure('BROWSER_LAUNCH_FAILED', `Failed to open browser: ${message}`);
  }

  let code: string;
  try {
    code = await callbackPromise;
  } catch (error) {
    await cancel(port).catch(() => {});
    driveState.setDriveConnecting(false);
    if (
      error instanceof DriveOAuthError &&
      (error.code === 'user_denied' || error.code === 'no_code')
    ) {
      const result: DriveAuthResult = { kind: 'canceled' };
      lastResult = result;
      return result;
    }
    const codeName = error instanceof DriveOAuthError ? error.code : 'CALLBACK_FAILED';
    const message = error instanceof Error ? error.message : String(error);
    return reportFailure(codeName, message);
  }

  try {
    const tokens = await exchangeCodeForGrant(code);
    await saveDriveGrant({
      refreshToken: tokens.refreshToken,
      scope: DRIVE_SCOPE,
      obtainedAt: Date.now(),
    });
    driveState.setDriveAuthorized(true);
    clearDeclineMarkers();
    const result: DriveAuthResult = { kind: 'success', accessToken: tokens.accessToken };
    lastResult = result;
    return result;
  } catch (error) {
    const codeName = error instanceof DriveOAuthError ? error.code : 'TOKEN_EXCHANGE_FAILED';
    const message = error instanceof Error ? error.message : String(error);
    return reportFailure(codeName, message);
  } finally {
    await cancel(port).catch(() => {});
    driveState.setDriveConnecting(false);
  }
}

export function beginDriveConnect(): Promise<DriveAuthResult> {
  if (pendingConnect) return pendingConnect;
  pendingConnect = runDriveConnect().finally(() => {
    pendingConnect = null;
  });
  return pendingConnect;
}

export function consumeDriveAuthResult(): DriveAuthResult | null {
  const result = lastResult;
  lastResult = null;
  return result;
}

export async function disconnectDrive(opts: { revoke?: boolean } = {}): Promise<void> {
  const revoke = opts.revoke ?? true;
  if (revoke) {
    try {
      const grant = await loadDriveGrant();
      if (grant) {
        await fetch(REVOKE_ENDPOINT, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ token: grant.refreshToken }).toString(),
        });
      }
    } catch {
      /* best-effort: converge to disconnected regardless */
    }
  }
  await clearDriveGrant();
  driveState.setDriveAuthorized(false);
}

export function __resetDriveConnectForTests(): void {
  pendingConnect = null;
  driveTokenRefreshInFlight = null;
  lastResult = null;
}
