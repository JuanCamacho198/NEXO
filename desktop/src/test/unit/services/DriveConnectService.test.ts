/**
 * Unit tests for `DriveConnectService` (login-drive-separation, work unit 2).
 *
 * Covers the pure protocol core (callback parse, authorize URL shape) and the
 * Tauri adapter with mocked loopback/opener/persistence: state-mismatch →
 * failure, missing code / denied → canceled + nothing persisted, success
 * persists, concurrent begin coalesced, transparent refresh, invalid grant →
 * AUTH_REQUIRED, disconnect clears only the Drive store.
 */

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

const mockPluginStart = vi.fn();
const mockPluginCancel = vi.fn();
let capturedOnUrlHandler: ((url: string) => void) | null = null;
const mockOpenUrl = vi.fn();

let memoryGrant: { refreshToken: string; scope: string; obtainedAt: number } | null = null;
const mockSaveDriveGrant = vi.fn();

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

import {
  beginDriveConnect,
  buildDriveAuthorizeUrl,
  consumeDriveAuthResult,
  disconnectDrive,
  DriveConfigError,
  DriveOAuthError,
  getDriveAccessToken,
  isDriveAuthorized,
  parseDriveCallbackUrl,
  refreshDriveAccessToken,
  __resetDriveConnectForTests,
} from '$lib/shared/services/DriveConnectService';
import { driveState } from '$lib/shared/stores/driveState.svelte';

const CLIENT_ID = 'test-client-id';
const CLIENT_SECRET = 'test-client-secret';

function stubOAuthEnv(): void {
  vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_ID', CLIENT_ID);
  vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_SECRET', CLIENT_SECRET);
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

function authorizeStateFromOpenUrl(): string {
  expect(mockOpenUrl).toHaveBeenCalledTimes(1);
  const url = new URL(mockOpenUrl.mock.calls[0][0] as string);
  const state = url.searchParams.get('state');
  expect(state).toBeTruthy();
  return state as string;
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

describe('buildDriveAuthorizeUrl', () => {
  it('requests drive.file with offline access and consent', () => {
    const url = new URL(
      buildDriveAuthorizeUrl({
        clientId: CLIENT_ID,
        redirectUri: 'http://127.0.0.1:1/',
        challenge: 'challenge',
        state: 'state',
      }),
    );
    expect(url.searchParams.get('scope')).toContain('https://www.googleapis.com/auth/drive.file');
    expect(url.searchParams.get('access_type')).toBe('offline');
    expect(url.searchParams.get('prompt')).toBe('consent');
  });
});

describe('beginDriveConnect', () => {
  it('state mismatch fails without persisting anything', async () => {
    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback('code=abc&state=tampered');
    const result = await pending;

    expect(result.kind).toBe('failure');
    expect(result.kind === 'failure' && result.code).toBe('state_mismatch');
    expect(memoryGrant).toBeNull();
    expect(driveState.isAuthorized).toBe(false);
    expect(mockSaveDriveGrant).not.toHaveBeenCalled();
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

  it('success persists the grant and authorizes the store', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ access_token: 'access-1', refresh_token: 'refresh-1' }));
    vi.stubGlobal('fetch', fetchMock);

    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const result = await pending;

    expect(result).toEqual({ kind: 'success', accessToken: 'access-1' });
    expect(memoryGrant).toMatchObject({ refreshToken: 'refresh-1' });
    expect(driveState.isAuthorized).toBe(true);
    expect(consumeDriveAuthResult()).toEqual(result);
    expect(consumeDriveAuthResult()).toBeNull();
  });

  it('exchange failure persists nothing and reports failure', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse('bad request', false, 400));
    vi.stubGlobal('fetch', fetchMock);

    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const result = await pending;

    expect(result.kind).toBe('failure');
    expect(memoryGrant).toBeNull();
    expect(driveState.isAuthorized).toBe(false);
    expect(driveState.lastError).not.toBeNull();
  });

  it('concurrent begin calls coalesce onto one loopback attempt', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ access_token: 'access-1', refresh_token: 'refresh-1' }));
    vi.stubGlobal('fetch', fetchMock);

    const first = beginDriveConnect();
    const second = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const [r1, r2] = await Promise.all([first, second]);

    expect(r1).toEqual(r2);
    expect(r1.kind).toBe('success');
    expect(mockPluginStart).toHaveBeenCalledTimes(1);
    expect(mockOpenUrl).toHaveBeenCalledTimes(1);
  });

  it('missing client ID fails fast without starting loopback (secret presence is irrelevant)', async () => {
    // Only the client ID is required; the secret stays configured via
    // beforeEach to prove it cannot satisfy the client-ID requirement.
    vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_ID', '');

    const result = await beginDriveConnect();

    expect(result.kind).toBe('failure');
    expect(result.kind === 'failure' && result.code).toBe('DRIVE_CONFIG_MISSING');
    expect(mockPluginStart).not.toHaveBeenCalled();
    expect(mockOpenUrl).not.toHaveBeenCalled();
  });

  it('client ID alone is enough and the token exchange omits client_secret', async () => {
    vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_SECRET', '');
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ access_token: 'access-1', refresh_token: 'refresh-1' }));
    vi.stubGlobal('fetch', fetchMock);

    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const result = await pending;

    expect(result).toEqual({ kind: 'success', accessToken: 'access-1' });
    const body = (fetchMock.mock.calls[0][1] as RequestInit).body as string;
    expect(body).toContain('client_id=test-client-id');
    expect(body).not.toContain('client_secret');
  });

  it('includes client_secret in the token exchange when one is configured', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ access_token: 'access-1', refresh_token: 'refresh-1' }));
    vi.stubGlobal('fetch', fetchMock);

    const pending = beginDriveConnect();
    await waitForConnectStarted();
    fireCallback(`code=auth-code&state=${authorizeStateFromOpenUrl()}`);
    const result = await pending;

    expect(result.kind).toBe('success');
    const body = (fetchMock.mock.calls[0][1] as RequestInit).body as string;
    expect(body).toContain('client_secret=test-client-secret');
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
  });

  it('refreshes transparently with the stored grant', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ access_token: 'fresh-access' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getDriveAccessToken()).resolves.toBe('fresh-access');
    expect(mockSaveDriveGrant).not.toHaveBeenCalled();
  });

  it('persists a rotated refresh token', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ access_token: 'fresh-access', refresh_token: 'refresh-2' }),
      );
    vi.stubGlobal('fetch', fetchMock);

    await expect(refreshDriveAccessToken()).resolves.toBe('fresh-access');
    expect(memoryGrant?.refreshToken).toBe('refresh-2');
  });

  it('invalid grant clears the store and throws AUTH_REQUIRED', async () => {
    memoryGrant = { refreshToken: 'revoked', scope: 'drive.file', obtainedAt: 1 };
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ error: 'invalid_grant', error_description: 'revoked' }, false, 400),
      );
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

  it('missing client ID throws DRIVE_CONFIG_MISSING (secret presence is irrelevant)', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    // Only the client ID is required; the secret stays configured via
    // beforeEach to prove it cannot satisfy the client-ID requirement.
    vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_ID', '');

    try {
      await getDriveAccessToken();
      expect.unreachable();
    } catch (error) {
      expect(error).toBeInstanceOf(DriveConfigError);
      expect((error as DriveConfigError).code).toBe('DRIVE_CONFIG_MISSING');
    }
  });

  it('refreshes without client_secret when the secret is empty', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    vi.stubEnv('VITE_GOOGLE_OAUTH_CLIENT_SECRET', '');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ access_token: 'fresh-access' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getDriveAccessToken()).resolves.toBe('fresh-access');
    const body = (fetchMock.mock.calls[0][1] as RequestInit).body as string;
    expect(body).toContain('client_id=test-client-id');
    expect(body).not.toContain('client_secret');
  });

  it('includes client_secret in the refresh when one is configured', async () => {
    memoryGrant = { refreshToken: 'refresh-1', scope: 'drive.file', obtainedAt: 1 };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ access_token: 'fresh-access' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getDriveAccessToken()).resolves.toBe('fresh-access');
    const body = (fetchMock.mock.calls[0][1] as RequestInit).body as string;
    expect(body).toContain('client_secret=test-client-secret');
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
