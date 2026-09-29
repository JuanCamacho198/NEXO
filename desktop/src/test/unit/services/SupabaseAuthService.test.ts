/**
 * Unit tests for SupabaseAuthService (identity-only, login-drive-separation).
 *
 * Verifies Supabase Auth integration: identity-only sign-in via OAuth
 * loopback (no Drive scope), session handling without Drive tokens, sign-out
 * (preserves the independent Drive grant), anonymous sign-in, identity
 * provider detection, and callback registration.
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';
import { isAnonymousSession } from '$lib/shared/services/anonymousSession';

// ---- Mock control variables ----
const mockPluginStart = vi.fn();
const mockPluginCancel = vi.fn();
const mockOpenUrl = vi.fn();

let capturedOnUrlHandler: ((url: string) => void) | null = null;
const mockSetSupabaseSession = vi.fn();
const mockClearSupabaseSession = vi.fn();
const mockSavePersistedAuth = vi.fn();

const mockSignInWithOAuth = vi.fn();
const mockExchangeCodeForSession = vi.fn();
const mockGetSession = vi.fn();
const mockSignOut = vi.fn();
const mockSignInAnonymously = vi.fn();
const mockGetLiveSession = vi.fn<() => unknown>(() => null);

// ---- Mock layers ----

vi.mock('@fabianlars/tauri-plugin-oauth', () => ({
  start: (...args: unknown[]) => mockPluginStart(...args),
  cancel: (...args: unknown[]) => mockPluginCancel(...args),
  onUrl: (handler: (url: string) => void) => {
    capturedOnUrlHandler = handler;
    return Promise.resolve(() => {
      if (capturedOnUrlHandler === handler) {
        capturedOnUrlHandler = null;
      }
    });
  },
}));

vi.mock('@tauri-apps/plugin-opener', () => ({
  openUrl: (...args: unknown[]) => mockOpenUrl(...args),
}));

vi.mock('$lib/shared/stores/AuthState.svelte', () => ({
  authState: {
    setSupabaseSession: (...args: unknown[]) => mockSetSupabaseSession(...args),
    clearSupabaseSession: (...args: unknown[]) => mockClearSupabaseSession(...args),
  },
}));

vi.mock('$lib/shared/stores/authPersistence', () => ({
  savePersistedAuth: (...args: unknown[]) => mockSavePersistedAuth(...args),
}));

vi.mock('$lib/services/supabase', () => ({
  getSessionClient: () => ({
    auth: {
      signInWithOAuth: mockSignInWithOAuth,
      exchangeCodeForSession: mockExchangeCodeForSession,
      getSession: mockGetSession,
      signOut: mockSignOut,
      signInAnonymously: mockSignInAnonymously,
    },
  }),
  getLiveSession: () => mockGetLiveSession(),
}));

vi.mock('$lib/shared/logger/Logger', () => ({
  logger: {
    error: vi.fn(),
    warn: vi.fn(),
    info: vi.fn(),
    debug: vi.fn(),
  },
}));

vi.mock('$lib/shared/events/ErrorEvent', () => ({
  createErrorEvent: (params: Record<string, unknown>) => ({
    timestamp: new Date().toISOString(),
    correlationId: 'mock-corr-id',
    ...params,
  }),
}));

// ---- Dynamic import (after mocks register) ----
type Sut = typeof import('$lib/shared/services/SupabaseAuthService');
let sut: Sut;

beforeAll(async () => {
  vi.stubEnv('VITE_SUPABASE_URL', 'https://test-project.supabase.co');
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', 'test-anon-key');

  const mod = await import('$lib/shared/services/SupabaseAuthService');
  sut = mod;
});

beforeEach(async () => {
  // 1. Set default mock implementations so signOut() doesn't crash
  mockPluginCancel.mockResolvedValue(undefined);
  mockOpenUrl.mockResolvedValue(undefined);
  mockSetSupabaseSession.mockReturnValue(undefined);
  mockClearSupabaseSession.mockReturnValue(undefined);
  mockSavePersistedAuth.mockResolvedValue(undefined);
  mockSignInWithOAuth.mockResolvedValue({
    data: { url: 'https://example.supabase.co/auth/v1/callback', provider: null },
    error: null,
  });
  mockExchangeCodeForSession.mockResolvedValue({ data: { session: null }, error: null });
  mockGetSession.mockResolvedValue({ data: { session: null } });
  mockSignOut.mockResolvedValue({ error: null });
  mockSignInAnonymously.mockResolvedValue({ data: { session: null }, error: null });
  mockGetLiveSession.mockReturnValue(null);
  globalThis.fetch = vi.fn();

  // 2. Clear module-level state (currentPort may be set from previous test)
  sut.unregisterCallbackHandler();
  await sut.signOut();
  capturedOnUrlHandler = null;

  // 3. Clear all mock call history (keeps implementations)
  vi.clearAllMocks();
});

// ---- Helpers ----

// Identity-only session fixture: no provider_token / provider_refresh_token.
// The login grant never carries Drive tokens (login-drive-separation).
function makeMockSession(overrides: Record<string, unknown> = {}) {
  return {
    access_token: 'access-123',
    refresh_token: 'refresh-123',
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    user: {
      id: 'user-1',
      email: 'test@example.com',
      user_metadata: {
        full_name: 'Test User',
        avatar_url: 'https://example.com/avatar.png',
      },
    },
    ...overrides,
  };
}

function makeMockOAuthData(url?: string) {
  return {
    data: {
      url: url ?? 'https://test-project.supabase.co/auth/v1/authorize?provider=google',
      provider: null,
    },
    error: null,
  };
}

function makeMockSessionData(session: Record<string, unknown>) {
  return { data: { session }, error: null };
}

function makeMockError(message: string) {
  return { data: { session: null }, error: { message } };
}

// ---- Tests ----

describe('SupabaseAuthService — signInWithGoogle', () => {
  it('starts loopback server and opens OAuth URL in browser', async () => {
    mockPluginStart.mockResolvedValue(48723);
    mockSignInWithOAuth.mockResolvedValue(makeMockOAuthData());

    await sut.signInWithGoogle();

    expect(mockPluginStart).toHaveBeenCalledTimes(1);
    expect(mockSignInWithOAuth).toHaveBeenCalledTimes(1);
    const options = mockSignInWithOAuth.mock.calls[0]?.[0];
    expect(options).toMatchObject({
      provider: 'google',
      options: {
        redirectTo: 'http://127.0.0.1:48723/callback',
      },
    });
    expect(mockOpenUrl).toHaveBeenCalledTimes(1);
  });

  it('requests identity scopes only: no Drive scope, select_account prompt (identity-only login)', async () => {
    mockPluginStart.mockResolvedValue(48723);
    mockSignInWithOAuth.mockResolvedValue(makeMockOAuthData());

    await sut.signInWithGoogle();

    const options = mockSignInWithOAuth.mock.calls[0]?.[0];
    // Login establishes identity only. Drive authorization is the independent
    // on-demand DriveConnectService flow — no provider scopes requested here.
    expect(options.options.scopes).toBeUndefined();
    expect(options.options.queryParams).toMatchObject({
      prompt: 'select_account',
    });
    expect(options.options.queryParams.scope).toBeUndefined();
    expect(options.options.queryParams.access_type).toBeUndefined();
    expect(JSON.stringify(options)).not.toContain('drive.file');
  });

  it('throws when plugin.start() fails', async () => {
    mockPluginStart.mockRejectedValue(new Error('port bind failed'));

    await expect(sut.signInWithGoogle()).rejects.toThrow('Failed to start loopback server');
    expect(mockSignInWithOAuth).not.toHaveBeenCalled();
  });

  it('throws when Supabase OAuth URL generation fails', async () => {
    mockPluginStart.mockResolvedValue(48723);
    mockSignInWithOAuth.mockResolvedValue({
      data: { url: null },
      error: { message: 'OAuth config missing' },
    });

    await expect(sut.signInWithGoogle()).rejects.toThrow('Supabase OAuth error');
    expect(mockOpenUrl).not.toHaveBeenCalled();
  });

  it('cancels previous loopback before starting a new one', async () => {
    mockPluginStart.mockResolvedValueOnce(48000).mockResolvedValueOnce(48001);
    mockSignInWithOAuth.mockResolvedValue(makeMockOAuthData());

    await sut.signInWithGoogle();
    expect(mockPluginStart).toHaveBeenCalledTimes(1);
    expect(mockPluginCancel).not.toHaveBeenCalled();

    await sut.signInWithGoogle();
    expect(mockPluginCancel).toHaveBeenCalledWith(48000);
    expect(mockPluginStart).toHaveBeenCalledTimes(2);
  });
});

describe('SupabaseAuthService — registerSupabaseCallbackHandler', () => {
  it('subscribes via onUrl exactly once', async () => {
    await sut.registerSupabaseCallbackHandler();
    expect(capturedOnUrlHandler).not.toBeNull();

    // Second call is idempotent — no new subscription
    await sut.registerSupabaseCallbackHandler();
    expect(capturedOnUrlHandler).not.toBeNull();
  });

  it('handles callback with valid code and stores the identity session only', async () => {
    await sut.registerSupabaseCallbackHandler();
    expect(capturedOnUrlHandler).not.toBeNull();

    const mockSession = makeMockSessionData(makeMockSession());
    mockExchangeCodeForSession.mockResolvedValue(mockSession);

    await capturedOnUrlHandler!('http://127.0.0.1:48723/callback?code=valid-code');

    expect(mockExchangeCodeForSession).toHaveBeenCalledWith('valid-code');
    expect(mockSetSupabaseSession).toHaveBeenCalledTimes(1);
    expect(mockSetSupabaseSession.mock.calls[0]?.[0]).toMatchObject({
      accessToken: 'access-123',
      userId: 'user-1',
    });
    // Identity-only: no Drive tokens flow into authState or auth.json.
    const stored = mockSetSupabaseSession.mock.calls[0]?.[0] as Record<string, unknown>;
    expect('providerToken' in stored).toBe(false);
    expect('driveRefreshToken' in stored).toBe(false);
    expect(mockSavePersistedAuth).toHaveBeenCalledTimes(1);
  });

  it('ignores provider tokens even when GoTrue issues them (identity-only session)', async () => {
    await sut.registerSupabaseCallbackHandler();
    const session = makeMockSession({
      provider_token: 'ya29.stray-provider-token',
      provider_refresh_token: 'stray-google-refresh',
    });
    mockExchangeCodeForSession.mockResolvedValue(makeMockSessionData(session));

    await capturedOnUrlHandler!('http://127.0.0.1:48723/callback?code=valid-code');

    expect(mockSetSupabaseSession).toHaveBeenCalledTimes(1);
    const stored = mockSetSupabaseSession.mock.calls[0]?.[0] as Record<string, unknown>;
    expect('providerToken' in stored).toBe(false);
    expect('driveRefreshToken' in stored).toBe(false);
    expect(JSON.stringify(stored)).not.toContain('ya29.stray-provider-token');
  });

  it('ignores callback when code is missing', async () => {
    await sut.registerSupabaseCallbackHandler();

    await capturedOnUrlHandler!('http://127.0.0.1:48723/callback?error=access_denied');

    expect(mockExchangeCodeForSession).not.toHaveBeenCalled();
    expect(mockSetSupabaseSession).not.toHaveBeenCalled();
  });

  it('handles code exchange failure gracefully', async () => {
    await sut.registerSupabaseCallbackHandler();
    mockExchangeCodeForSession.mockResolvedValue(makeMockError('Invalid code'));

    await capturedOnUrlHandler!('http://127.0.0.1:48723/callback?code=bad-code');

    expect(mockExchangeCodeForSession).toHaveBeenCalledWith('bad-code');
    expect(mockSetSupabaseSession).not.toHaveBeenCalled();
  });
});

describe('SupabaseAuthService — getIdentityProvider', () => {
  it('prefers app_metadata.provider (GoTrue server-set OAuth provider)', async () => {
    mockGetSession.mockResolvedValue({
      data: {
        session: {
          user: { app_metadata: { provider: 'google' }, user_metadata: {} },
        },
      },
    });

    await expect(sut.getIdentityProvider()).resolves.toBe('google');
  });

  it('falls back to user_metadata.provider when app_metadata is absent', async () => {
    mockGetSession.mockResolvedValue({
      data: {
        session: {
          user: { app_metadata: {}, user_metadata: { provider: 'google' } },
        },
      },
    });

    await expect(sut.getIdentityProvider()).resolves.toBe('google');
  });

  it('returns non-Google providers verbatim (callers decide the Drive offer)', async () => {
    mockGetSession.mockResolvedValue({
      data: {
        session: {
          user: { app_metadata: { provider: 'github' }, user_metadata: {} },
        },
      },
    });

    await expect(sut.getIdentityProvider()).resolves.toBe('github');
  });

  it('returns null when signed out', async () => {
    mockGetSession.mockResolvedValue({ data: { session: null } });

    await expect(sut.getIdentityProvider()).resolves.toBeNull();
  });

  it('returns null when no provider is recorded', async () => {
    mockGetSession.mockResolvedValue({
      data: { session: { user: { app_metadata: {}, user_metadata: {} } } },
    });

    await expect(sut.getIdentityProvider()).resolves.toBeNull();
  });
});

describe('SupabaseAuthService — signOut', () => {
  it('calls supabase.auth.signOut and clears auth state', async () => {
    mockPluginStart.mockResolvedValue(48000);
    mockSignInWithOAuth.mockResolvedValue(makeMockOAuthData());
    await sut.signInWithGoogle();
    mockSignOut.mockResolvedValue({ error: null });

    await sut.signOut();

    expect(mockSignOut).toHaveBeenCalled();
    expect(mockClearSupabaseSession).toHaveBeenCalled();
  });

  it('cancels active loopback server on sign out', async () => {
    mockPluginStart.mockResolvedValue(48000);
    mockSignInWithOAuth.mockResolvedValue(makeMockOAuthData());
    await sut.signInWithGoogle();
    mockSignOut.mockResolvedValue({ error: null });

    await sut.signOut();

    expect(mockPluginCancel).toHaveBeenCalledWith(48000);
  });

  it('handles signOut supabase error gracefully', async () => {
    mockPluginStart.mockResolvedValue(48000);
    mockSignInWithOAuth.mockResolvedValue(makeMockOAuthData());
    await sut.signInWithGoogle();
    mockSignOut.mockResolvedValue({ error: { message: 'network error' } });

    // Should not throw — warning is logged
    await expect(sut.signOut()).resolves.toBeUndefined();
  });
});

describe('SupabaseAuthService — signInAnonymously', () => {
  it('sets anonymous session on success', async () => {
    const anonSession = makeMockSession({
      email: null,
      user: {
        id: 'anon-1',
        email: null,
        user_metadata: {},
      },
    });
    const anonData = { data: { session: anonSession }, error: null };
    mockSignInAnonymously.mockResolvedValue(anonData);

    await sut.signInAnonymously();

    expect(mockSignInAnonymously).toHaveBeenCalled();
    expect(mockSetSupabaseSession).toHaveBeenCalledWith(
      expect.objectContaining({
        userId: 'anon-1',
        email: null,
        displayName: null,
        isAnonymous: true,
      }),
    );
  });

  it('handles anonymous sign-in failure gracefully', async () => {
    mockSignInAnonymously.mockResolvedValue({
      data: { session: null },
      error: { message: 'not available' },
    });

    // Should not throw — warning is logged
    await expect(sut.signInAnonymously()).resolves.toBeUndefined();
    expect(mockSetSupabaseSession).not.toHaveBeenCalled();
  });

  it('no-ops entirely when a live session already exists (anon-clobber guard, DA-3)', async () => {
    mockGetLiveSession.mockReturnValue({ user: { id: 'real-user-1' } });

    await sut.signInAnonymously();

    // Never reaches the Supabase client, never touches authState
    expect(mockSignInAnonymously).not.toHaveBeenCalled();
    expect(mockSetSupabaseSession).not.toHaveBeenCalled();
  });

  it('discards the anonymous result if a live session lands during the await (DA-3.1)', async () => {
    const anonSession = makeMockSession({
      email: null,
      user: { id: 'anon-1', email: null, user_metadata: {} },
    });
    mockSignInAnonymously.mockResolvedValue({ data: { session: anonSession }, error: null });
    // Passes the pre-check (cache empty), then a real session lands mid-flight
    mockGetLiveSession.mockReturnValueOnce(null).mockReturnValue({ user: { id: 'real-user-1' } });

    await sut.signInAnonymously();

    expect(mockSignInAnonymously).toHaveBeenCalledTimes(1);
    expect(mockSetSupabaseSession).not.toHaveBeenCalled();
  });
});

describe('SupabaseAuthService — isAnonymousSession', () => {
  it('is true for a GoTrue anonymous user', () => {
    const session = makeMockSession({
      user: { id: 'anon-1', email: null, user_metadata: {}, is_anonymous: true },
    });
    expect(isAnonymousSession(session as never)).toBe(true);
  });

  it('is false for a real user without the anonymous claim', () => {
    expect(isAnonymousSession(makeMockSession() as never)).toBe(false);
  });
});

describe('SupabaseAuthService — restoreSession', () => {
  it('returns session from supabase.auth.getSession()', async () => {
    const session = makeMockSession();
    mockGetSession.mockResolvedValue({ data: { session } });

    const result = await sut.restoreSession();
    expect(result).toEqual(session);
  });

  it('returns null when no session', async () => {
    mockGetSession.mockResolvedValue({ data: { session: null } });

    const result = await sut.restoreSession();
    expect(result).toBeNull();
  });
});

describe('SupabaseAuthService — unregisterCallbackHandler', () => {
  it('removes the URL listener', async () => {
    await sut.registerSupabaseCallbackHandler();
    expect(capturedOnUrlHandler).not.toBeNull();

    sut.unregisterCallbackHandler();
    expect(capturedOnUrlHandler).toBeNull();
  });

  it('is safe to call when no handler is registered', () => {
    sut.unregisterCallbackHandler();
    // No throw
  });
});
