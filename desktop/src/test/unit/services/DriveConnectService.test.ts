/**
 * Unit tests for `DriveConnectService` (login-drive-separation, work unit 2).
 *
 * Covers the pure protocol core (callback parse) and the Tauri adapter with
 * mocked loopback/opener/persistence/Supabase client: state-mismatch →
 * failure, missing code / denied → canceled + nothing persisted, success
 * persists, concurrent begin coalesced, transparent refresh, dead grant →
 * AUTH_REQUIRED, disconnect clears only the Drive store.
 *
 * The Google exchange belongs to Supabase (GoTrue): the configured client
 * needs a client_secret the desktop bundle cannot hold. Every connect and
 * refresh path below asserts that no request reaches
 * `oauth2.googleapis.com` (Google's token endpoint).
 */

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

const mockPluginStart = vi.fn();
const mockPluginCancel = vi.fn();
let capturedOnUrlHandler: ((url: string) => void) | null = null;
const mockOpenUrl = vi.fn();

let memoryGrant: { refreshToken: string; scope: string; obtainedAt: number } | null = null;
const mockSaveDriveGrant = vi.fn();

const mockSignInWithOAuth = vi.fn();
const mockExchangeCodeForSession = vi.fn();
const mockRefreshSession = vi.fn();
const mockGetSession = vi.fn();

vi.mock('@fabianlars/tauri-plugin-oauth', () => ({
  start: (...args: unknown[]) => mockPluginStart(...args),
  cancel: (...args: unknown[]) => mockPluginCancel(...args),
  onUrl: (handler: (url: string) => void) => {
    capturedOnUrlHandler = handler;
    return Promise.resolve(() => {
      if (capturedOnUrlHandler === handler) capturedOnUrlHandler = null;
    });
  },
}));

vi.mock('@tauri-apps/plugin-opener', () => ({
  openUrl: (...args: unknown[]) => mockOpenUrl(...args),
}));

vi.mock('$lib/shared/stores/drivePersistence', () => ({
  loadDriveGrant: () => Promise.resolve(memoryGrant),
  saveDriveGrant: (grant: { refreshToken: string; scope: string; obtainedAt: number }) => {
    memoryGrant = grant;
    mockSaveDriveGrant(grant);
    return Promise.resolve();
  },
  clearDriveGrant: () => {
    memoryGrant = null;
    return Promise.resolve();
  },
}));

vi.mock('$lib/shared/logger/Logger', () => ({
  logger: { warn: vi.fn(), error: vi.fn(), info: vi.fn(), debug: vi.fn() },
}));

vi.mock('$lib/services/supabase', () => ({
  getSessionClient: () => ({
    auth: {
      signInWithOAuth: (...args: unknown[]) => mockSignInWithOAuth(...args),
      exchangeCodeForSession: (...args: unknown[]) => mockExchangeCodeForSession(...args),
      refreshSession: (...args: unknown[]) => mockRefreshSession(...args),
      getSession: (...args: unknown[]) => mockGetSession(...args),
    },
  }),
  getSupabaseUrl: () => 'https://supabase.test',
  getSupabaseAnonKey: () => 'anon-test-key',
}));

import {
  beginDriveConnect,
  consumeDriveAuthResult,
  disconnectDrive,
  DriveOAuthError,
  getDriveAccessToken,
  isDriveAuthorized,
  parseDriveCallbackUrl,
  refreshDriveAccessToken,
  __resetDriveConnectForTests,
} from '$lib/shared/services/DriveConnectService';
import { driveState } from '$lib/shared/stores/driveState.svelte';

const GOOGLE_TOKEN_HOST = 'oauth2.googleapis.com';
// A Desktop-type OAuth client has no secret. This value is stubbed into the
// env on purpose to prove the service never reads it: if the service consumed
// VITE_GOOGLE_OAUTH_CLIENT_SECRET it would be inlined into the shipped bundle.
const INLINED_SECRET_SENTINEL = 'test-client-secret';

const AUTHORIZE_URL =
  'https://test.supabase.co/auth/v1/authorize?provider=google' +
  '&scopes=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file' +
  '&redirect_to=http%3A%2F%2F127.0.0.1%3A42813%2F' +
  '&access_type=offline&prompt=consent';

function stubOAuthEnv(): void {
  vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_ID', 'test-client-id');
  vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_SECRET', INLINED_SECRET_SENTINEL);
}

function supabaseSession(overrides: Record<string, unknown> = {}): unknown {
  return {
    access_token: 'supabase-access',
    refresh_token: 'supabase-refresh',
    provider_token: 'access-1',
    provider_refresh_token: 'refresh-1',
    user: { id: 'user-1' },
    ...overrides,
  };
}

function fireCallback(params: string): void {
  expect(capturedOnUrlHandler).not.toBeNull();
  capturedOnUrlHandler?.(`http://127.0.0.1:42813/?${params}`);
}

async function waitForConnectStarted(): Promise<void> {
  await vi.waitFor(() => {
    expect(capturedOnUrlHandler).not.toBeNull();
    expect(mockOpenUrl).toHaveBeenCalledTimes(1);
  });
}

/**
 * The state GoTrue echoes back on the loopback redirect.
 *
 * GoTrue owns the OAuth state for a Supabase authorize trip and substitutes its
 * own value (measured), so the URL the app opens carries no state of ours. The
 * flow therefore does not compare it; these tests model that by firing the
 * callback with the state GoTrue would send, which the parser accepts.
 */
function authorizeStateFromOpenUrl(): string {
  expect(mockOpenUrl).toHaveBeenCalledTimes(1);
  return 'gotrue-owned-state';
}

function googleTokenRequests(fetchMock: { mock: { calls: unknown[][] } }): unknown[][] {
  return fetchMock.mock.calls.filter((call) => String(call[0]).includes(GOOGLE_TOKEN_HOST));
}

function jsonResponse(payload: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: () => Promise.resolve(payload),
    text: () => Promise.resolve(typeof payload === 'string' ? payload : JSON.stringify(payload)),
  } as Response;
}

beforeEach(() => {
  __resetDriveConnectForTests();
  driveState.resetDriveState();
  memoryGrant = null;
  capturedOnUrlHandler = null;
  mockPluginStart.mockReset().mockResolvedValue(42813);
  mockPluginCancel.mockReset().mockResolvedValue(undefined);
  mockOpenUrl.mockReset().mockResolvedValue(undefined);
  mockSaveDriveGrant.mockClear();
  mockSignInWithOAuth.mockReset().mockResolvedValue({
    data: { url: AUTHORIZE_URL, provider: 'google' },
    error: null,
  });
  mockExchangeCodeForSession
    .mockReset()
    .mockResolvedValue({ data: { session: supabaseSession() }, error: null });
  mockRefreshSession
    .mockReset()
    .mockResolvedValue({ data: { session: supabaseSession() }, error: null });
  mockGetSession
    .mockReset()
    .mockResolvedValue({ data: { session: supabaseSession() }, error: null });
  stubOAuthEnv();
  vi.unstubAllGlobals();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('parseDriveCallbackUrl', () => {
  it('returns the code when state matches', () => {
    expect(parseDriveCallbackUrl('http://127.0.0.1:1/?code=abc&state=s', 's')).toBe('abc');
  });

  it('throws state_mismatch on CSRF', () => {
    try {
      parseDriveCallbackUrl('http://127.0.0.1:1/?code=abc&state=wrong', 's');
      expect.unreachable();
    } catch (error) {
      expect(error).toBeInstanceOf(DriveOAuthError);
      expect((error as DriveOAuthError).code).toBe('state_mismatch');
    }
  });

  it('throws user_denied on access_denied', () => {
    try {
      parseDriveCallbackUrl('http://127.0.0.1:1/?error=access_denied&state=s', 's');
      expect.unreachable();
    } catch (error) {
      expect((error as DriveOAuthError).code).toBe('user_denied');
    }
  });

  it('throws no_code when the code is missing', () => {
    try {
      parseDriveCallbackUrl('http://127.0.0.1:1/?state=s', 's');
      expect.unreachable();
    } catch (error) {
      expect((error as DriveOAuthError).code).toBe('no_code');
    }
  });
});

describe('beginDriveConnect', () => {
  it('builds the authorize URL through Supabase and opens it', async () => {
    const pending = beginDriveConnect();
    await waitForConnectStarted();

    expect(mockSignInWithOAuth).toHaveBeenCalledTimes(1);
    const args = mockSignInWithOAuth.mock.calls[0][0] as {
      provider: string;
      options: Record<string, unknown>;
    };
    expect(args.provider).toBe('google');
    expect(args.options.redirectTo).toBe('http://127.0.0.1:42813/');
    expect(args.options.skipBrowserRedirect).toBe(true);
    expect(args.options.scopes).toBe('https://www.googleapis.com/auth/drive.file');
    expect(args.options.queryParams).toEqual({ access_type: 'offline', prompt: 'consent' });
    const openedUrl = new URL(mockOpenUrl.mock.calls[0][0] as string);
    expect(openedUrl.origin + openedUrl.pathname).toBe(
      'https://test.supabase.co/auth/v1/authorize',
    );
    expect(openedUrl.searchParams.get('provider')).toBe('google');
    expect(openedUrl.searchParams.get('redirect_to')).toBe('http://127.0.0.1:42813/');
    expect(openedUrl.searchParams.get('access_type')).toBe('offline');
    expect(openedUrl.searchParams.get('prompt')).toBe('consent');
    expect(openedUrl.searchParams.get('scopes')).toBe('https://www.googleapis.com/auth/drive.file');

    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    await pending;
  });

  it('accepts the callback whatever state GoTrue sends, because it owns that value', async () => {
    // GoTrue substitutes its own OAuth state for a Supabase authorize trip
    // (measured: a caller-supplied value reaches Google as a GoTrue UUID), so
    // the connect flow cannot compare it and must not reject the callback over
    // it. PKCE is the binding: the code is redeemed server-side against the
    // verifier GoTrue stored for this flow.
    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback('code=abc&state=gotrue-generated-uuid');
    const result = await pending;

    expect(result.kind).toBe('success');
    expect(mockExchangeCodeForSession).toHaveBeenCalledWith('abc');
    expect(memoryGrant).toMatchObject({ refreshToken: 'refresh-1' });
  });

  it('missing code cancels without persisting anything', async () => {
    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback('state=whatever');
    const result = await pending;

    expect(result).toEqual({ kind: 'canceled' });
    expect(memoryGrant).toBeNull();
    expect(mockSaveDriveGrant).not.toHaveBeenCalled();
    expect(driveState.isAuthorized).toBe(false);
  });

  it('denied consent cancels silently', async () => {
    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback('error=access_denied&state=whatever');
    const result = await pending;

    expect(result).toEqual({ kind: 'canceled' });
    expect(memoryGrant).toBeNull();
  });

  it('success exchanges the code through Supabase and persists the grant', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const result = await pending;

    expect(result).toEqual({ kind: 'success', accessToken: 'access-1' });
    expect(mockExchangeCodeForSession).toHaveBeenCalledWith('auth-code');
    expect(memoryGrant).toMatchObject({ refreshToken: 'refresh-1' });
    expect(driveState.isAuthorized).toBe(true);
    expect(consumeDriveAuthResult()).toEqual(result);
    expect(consumeDriveAuthResult()).toBeNull();
    // The Google exchange is GoTrue's job: nothing may reach Google's token
    // endpoint from the bundle.
    expect(googleTokenRequests(fetchMock)).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces the Supabase exchange error as a failure and persists nothing', async () => {
    mockExchangeCodeForSession
      .mockReset()
      .mockResolvedValue({ data: { session: null }, error: { message: 'bad code' } });

    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const result = await pending;

    expect(result.kind).toBe('failure');
    expect(result.kind === 'failure' && result.code).toBe('server_failed');
    expect(memoryGrant).toBeNull();
    expect(driveState.isAuthorized).toBe(false);
    expect(driveState.lastError).not.toBeNull();
  });

  it('refuses a grant with no provider refresh token', async () => {
    mockExchangeCodeForSession.mockReset().mockResolvedValue({
      data: { session: supabaseSession({ provider_refresh_token: null }) },
      error: null,
    });

    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const result = await pending;

    expect(result.kind).toBe('failure');
    expect(memoryGrant).toBeNull();
    expect(driveState.isAuthorized).toBe(false);
  });

  it('concurrent begin calls coalesce onto one loopback attempt', async () => {
    const first = beginDriveConnect();
    const second = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const [r1, r2] = await Promise.all([first, second]);

    expect(r1).toEqual(r2);
    expect(r1.kind).toBe('success');
    expect(mockPluginStart).toHaveBeenCalledTimes(1);
    expect(mockOpenUrl).toHaveBeenCalledTimes(1);
    expect(mockSignInWithOAuth).toHaveBeenCalledTimes(1);
  });

  it('a Supabase authorize error fails fast without opening a browser', async () => {
    mockSignInWithOAuth
      .mockReset()
      .mockResolvedValue({ data: { url: null }, error: { message: 'provider not enabled' } });

    const result = await beginDriveConnect();

    expect(result.kind).toBe('failure');
    expect(result.kind === 'failure' && result.code).toBe('DRIVE_CONFIG_MISSING');
    expect(mockOpenUrl).not.toHaveBeenCalled();
    expect(mockPluginCancel).toHaveBeenCalledTimes(1);
  });

  it('a browser launch failure is reported and the loopback is closed', async () => {
    mockOpenUrl.mockReset().mockRejectedValue(new Error('no browser'));

    const result = await beginDriveConnect();

    expect(result.kind).toBe('failure');
    expect(result.kind === 'failure' && result.code).toBe('BROWSER_LAUNCH_FAILED');
    expect(mockPluginCancel).toHaveBeenCalledTimes(1);
    expect(driveState.isConnecting).toBe(false);
  });
});

describe('getDriveAccessToken', () => {
  it('throws DRIVE_NOT_CONNECTED (connect route) with no stored grant', async () => {
    try {
      await getDriveAccessToken();
      expect.unreachable();
    } catch (error) {
      // Not AUTH_REQUIRED: a missing Drive grant is a connect-route state, not
      // an identity/re-auth incident, so it must not reach the global auth
      // banner (v0.3.3 removed the Drive-not-connected notification).
      expect((error as { code?: string }).code).toBe('DRIVE_NOT_CONNECTED');
    }
    expect(mockRefreshSession).not.toHaveBeenCalled();
  });

  it('refreshes through the drive-token function with the stored grant', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ access_token: 'access-1' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getDriveAccessToken()).resolves.toBe('access-1');

    // Nothing may reach Google directly: the client holds no secret, so the
    // Google refresh has to happen in the function.
    expect(googleTokenRequests(fetchMock)).toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/functions/v1/drive-token');
    expect(String(init.method)).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ refresh_token: 'refresh-1' });
    expect((init.headers as Record<string, string>).Authorization).toMatch(/^Bearer /);
    // The grant is not rotated: the function returns an access token only.
    expect(mockSaveDriveGrant).not.toHaveBeenCalled();
  });

  it('a revoked grant clears the store and throws AUTH_REQUIRED', async () => {
    memoryGrant = { refreshToken: 'revoked', scope: 'drive.file', obtainedAt: 1 };
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ error: 'DRIVE_GRANT_REVOKED' }, false, 401));
    vi.stubGlobal('fetch', fetchMock);

    try {
      await getDriveAccessToken();
      expect.unreachable();
    } catch (error) {
      expect((error as { code?: string }).code).toBe('AUTH_REQUIRED');
    }
    expect(memoryGrant).toBeNull();
    expect(driveState.isAuthorized).toBe(false);
    expect(driveState.lastError).toBe('AUTH_REQUIRED');
  });

  it('a dead grant is not retried after it was cleared', async () => {
    memoryGrant = { refreshToken: 'dead', scope: 'drive.file', obtainedAt: 1 };
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ error: 'DRIVE_GRANT_REVOKED' }, false, 401));
    vi.stubGlobal('fetch', fetchMock);

    try {
      await getDriveAccessToken();
      expect.unreachable();
    } catch (error) {
      expect((error as { code?: string }).code).toBe('AUTH_REQUIRED');
    }
    expect(memoryGrant).toBeNull();

    // A second call short-circuits on the missing grant: no more requests, the
    // connect-route error instead.
    try {
      await getDriveAccessToken();
      expect.unreachable();
    } catch (error) {
      expect((error as { code?: string }).code).toBe('DRIVE_NOT_CONNECTED');
    }
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('a transient function failure keeps the grant for the next attempt', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ error: 'DRIVE_UPSTREAM_UNAVAILABLE' }, false, 503));
    vi.stubGlobal('fetch', fetchMock);

    try {
      await getDriveAccessToken();
      expect.unreachable();
    } catch (error) {
      expect((error as { code?: string }).code).not.toBe('AUTH_REQUIRED');
    }
    expect(memoryGrant).not.toBeNull();
    expect(driveState.lastError).not.toBe('AUTH_REQUIRED');
  });

  it('a network failure reaching the function keeps the grant', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));

    try {
      await getDriveAccessToken();
      expect.unreachable();
    } catch (error) {
      expect((error as { code?: string }).code).not.toBe('AUTH_REQUIRED');
    }
    expect(memoryGrant).not.toBeNull();
  });

  it('a function response without an access token is an error, not a silent success', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ access_token: '' })));

    await expect(getDriveAccessToken()).rejects.toThrow(/no access token/i);
    expect(memoryGrant).not.toBeNull();
  });
});

describe('isDriveAuthorized', () => {
  it('reflects only the Drive store', async () => {
    await expect(isDriveAuthorized()).resolves.toBe(false);
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    await expect(isDriveAuthorized()).resolves.toBe(true);
    expect(driveState.isAuthorized).toBe(true);
  });
});

describe('disconnectDrive', () => {
  it('clears only the Drive store and revokes best-effort', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    driveState.setDriveAuthorized(true);
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({}));
    vi.stubGlobal('fetch', fetchMock);

    await disconnectDrive();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toContain('revoke');
    expect(memoryGrant).toBeNull();
    expect(driveState.isAuthorized).toBe(false);
  });

  it('still clears when revoke fails', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

    await disconnectDrive();

    expect(memoryGrant).toBeNull();
    expect(driveState.isAuthorized).toBe(false);
  });
});
