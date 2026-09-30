/**
 * On-demand Google Drive authorization, independent of the Supabase identity
 * session (login-drive-separation).
 *
 * Ports Android's proven separation (`DriveOAuthSession` pure core +
 * `GoogleDriveAuthHelper` thin platform layer) to desktop: login establishes
 * identity only, and this module owns the Drive OAuth PKCE flow with its own
 * durable `drive.json` grant (see `drivePersistence.ts`). PKCE, state, and
 * loopback helpers were ported from the deleted legacy `GoogleOAuthService.ts`
 * (its Drive-coupled session model is replaced by this module).
 *
 * Structure mirrors Android's split inside one file:
 * - Pure protocol core (`parseDriveCallbackUrl`, `buildDriveAuthorizeUrl`,
 *   exchange/refresh body builders): no platform imports, driven by tests.
 * - Thin Tauri adapter (`beginDriveConnect`, `disconnectDrive`): loopback via
 *   `@fabianlars/tauri-plugin-oauth`, browser launch via `openUrl`.
 *
 * Invariants:
 * - Tokens persist ONLY on successful completion; cancel/failure persist
 *   nothing and report `canceled` silently (never a toast, Android parity).
 * - Concurrent `beginDriveConnect` calls coalesce onto one loopback attempt
 *   (singleton `pendingConnect`); concurrent refreshes coalesce onto one
 *   exchange (moved `driveTokenRefreshInFlight` mutex pattern).
 * - Disconnect clears ONLY the Drive grant (plus best-effort Google revoke)
 *   and never touches the Supabase session.
 */

import { start, cancel, onUrl } from '@fabianlars/tauri-plugin-oauth';
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

const AUTH_ENDPOINT = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token';
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

export class DriveConfigError extends Error {
  public readonly code: DriveConfigErrorCode = 'DRIVE_CONFIG_MISSING';
  constructor(message: string) {
    super(message);
    this.name = 'DriveConfigError';
  }
}

export interface DriveOAuthConfig {
  clientId: string;
  clientSecret: string;
}

export function getDriveOAuthConfig(): DriveOAuthConfig {
  return {
    clientId: (import.meta.env.VITE_GOOGLE_OAUTH_CLIENT_ID ?? '') as string,
    clientSecret: (import.meta.env.VITE_GOOGLE_OAUTH_CLIENT_SECRET ?? '') as string,
  };
}

function requireDriveOAuthConfig(): DriveOAuthConfig {
  const config = getDriveOAuthConfig();
  if (!config.clientId || !config.clientSecret) {
    throw new DriveConfigError(
      'Google OAuth client is not configured. Set VITE_GOOGLE_OAUTH_CLIENT_ID and VITE_GOOGLE_OAUTH_CLIENT_SECRET.',
    );
  }
  return config;
}

function base64UrlEncode(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  const base64 = btoa(binary);
  return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function generateDriveCodeVerifier(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes.buffer);
}

export async function generateDriveCodeChallenge(verifier: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(verifier);
  const hash = await crypto.subtle.digest('SHA-256', data);
  return base64UrlEncode(hash);
}

export function buildDriveState(): string {
  const stateBytes = new Uint8Array(32);
  crypto.getRandomValues(stateBytes);
  return base64UrlEncode(stateBytes.buffer);
}

export interface DriveAuthorizeParams {
  clientId: string;
  redirectUri: string;
  challenge: string;
  state: string;
}

export function buildDriveAuthorizeUrl(params: DriveAuthorizeParams): string {
  const query = new URLSearchParams({
    client_id: params.clientId,
    redirect_uri: params.redirectUri,
    response_type: 'code',
    code_challenge: params.challenge,
    code_challenge_method: 'S256',
    scope: `${DRIVE_SCOPE} email profile`,
    state: params.state,
    access_type: 'offline',
    prompt: 'consent',
  });
  return `${AUTH_ENDPOINT}?${query.toString()}`;
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
  if (state !== expectedState) {
    throw new DriveOAuthError('State parameter mismatch — possible CSRF', 'state_mismatch');
  }
  return code;
}

const LOOPBACK_SUCCESS_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Drive connected — Nexo</title>
  <style>
    :root { color-scheme: light dark; }
    * { box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      min-height: 100vh; margin: 0; padding: 24px;
      background: #f0fdf4; color: #14532d;
    }
    .card {
      max-width: 420px; width: 100%;
      background: #fff; border: 1px solid #bbf7d0; border-radius: 16px;
      padding: 32px 24px; text-align: center;
      box-shadow: 0 10px 25px -10px rgba(22, 101, 52, 0.25);
    }
    .check {
      width: 64px; height: 64px; margin: 0 auto 16px;
      background: #22c55e; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
    }
    h1 { margin: 0 0 8px; font-size: 22px; line-height: 1.2; }
    p  { margin: 0; font-size: 14px; line-height: 1.5; color: #4b5563; }
    @media (prefers-color-scheme: dark) {
      body { background: #052e16; color: #dcfce7; }
      .card { background: #0f172a; border-color: #14532d; box-shadow: none; }
      p { color: #94a3b8; }
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="check">
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 13l4 4L19 7"/></svg>
    </div>
    <h1>Drive connected</h1>
    <p>You can close this tab and return to Nexo. The app will pick up where you left off.</p>
  </div>
</body>
</html>`;

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

async function exchangeCodeForGrant(
  code: string,
  verifier: string,
  redirectUri: string,
  config: DriveOAuthConfig,
): Promise<{ accessToken: string; refreshToken: string }> {
  const body = new URLSearchParams({
    code,
    client_id: config.clientId,
    client_secret: config.clientSecret,
    redirect_uri: redirectUri,
    code_verifier: verifier,
    grant_type: 'authorization_code',
  });
  const response = await fetch(TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
  if (!response.ok) {
    const errorText = await response.text();
    throw new DriveOAuthError(
      `Drive token exchange failed: ${response.status} ${errorText}`,
      'server_failed',
    );
  }
  const data = await response.json();
  if (typeof data.refresh_token !== 'string' || !data.refresh_token) {
    throw new DriveOAuthError('Drive token exchange returned no refresh grant', 'server_failed');
  }
  if (typeof data.access_token !== 'string' || !data.access_token) {
    throw new DriveOAuthError('Drive token exchange returned no access token', 'server_failed');
  }
  return { accessToken: data.access_token, refreshToken: data.refresh_token };
}

function driveAuthRequired(): Error {
  return syncError(
    'AUTH_REQUIRED',
    'Google Drive is not connected. Connect Google Drive in Settings to use Drive features.',
    false,
  );
}

async function doRefreshDriveAccessToken(): Promise<string> {
  const grant = await loadDriveGrant();
  if (!grant) {
    throw driveAuthRequired();
  }
  const config = requireDriveOAuthConfig();
  let response: Response;
  try {
    response = await fetch(TOKEN_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        client_id: config.clientId,
        client_secret: config.clientSecret,
        refresh_token: grant.refreshToken,
      }).toString(),
    });
  } catch (error) {
    throw new Error(
      redactLogLine(
        `Drive token refresh failed: ${error instanceof Error ? error.message : String(error)}`,
      ),
    );
  }
  if (!response.ok) {
    const errorText = await response.text();
    if (/invalid_grant/i.test(errorText)) {
      await clearDriveGrant();
      driveState.setDriveAuthorized(false);
      driveState.setDriveError('AUTH_REQUIRED');
      throw driveAuthRequired();
    }
    throw new Error(redactLogLine(`Drive token refresh failed: ${response.status} ${errorText}`));
  }
  const data = await response.json();
  if (typeof data.access_token !== 'string' || !data.access_token) {
    throw new Error('Drive token refresh returned no access token');
  }
  if (
    typeof data.refresh_token === 'string' &&
    data.refresh_token &&
    data.refresh_token !== grant.refreshToken
  ) {
    await saveDriveGrant({
      refreshToken: data.refresh_token,
      scope: grant.scope,
      obtainedAt: Date.now(),
    });
  }
  return data.access_token;
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
  let config: DriveOAuthConfig;
  try {
    config = requireDriveOAuthConfig();
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return reportFailure('DRIVE_CONFIG_MISSING', message);
  }

  driveState.setDriveConnecting(true);
  let port: number;
  try {
    port = await start({ response: LOOPBACK_SUCCESS_HTML });
  } catch (error) {
    driveState.setDriveConnecting(false);
    return reportFailure(
      'LOOPBACK_UNAVAILABLE',
      `Failed to start loopback server: ${String(error)}`,
    );
  }
  const verifier = generateDriveCodeVerifier();
  const challenge = await generateDriveCodeChallenge(verifier);
  const state = buildDriveState();
  const redirectUri = `http://127.0.0.1:${port}/`;

  const callbackPromise = new Promise<string>((resolve, reject) => {
    let settled = false;
    void onUrl((url) => {
      if (settled) return;
      settled = true;
      try {
        resolve(parseDriveCallbackUrl(url, state));
      } catch (error) {
        reject(error);
      }
    });
  });

  try {
    await openUrl(
      buildDriveAuthorizeUrl({ clientId: config.clientId, redirectUri, challenge, state }),
    );
  } catch (error) {
    await cancel(port).catch(() => {});
    driveState.setDriveConnecting(false);
    return reportFailure('BROWSER_LAUNCH_FAILED', `Failed to open browser: ${String(error)}`);
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
    const tokens = await exchangeCodeForGrant(code, verifier, redirectUri, config);
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
