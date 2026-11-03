/**
 * Active-user wiring in `AppState` (A5).
 *
 * The backend's active-user slot is what DB-layer queries scope on, so it must
 * track the session exactly. These tests pin the three transitions that matter:
 * session restore sets it, sign-out clears it, and a local profile clears it
 * (local profiles have no Supabase id, so keeping a previous session's id would
 * leak one account's scope into another).
 *
 * `AppState` takes the port as its only constructor argument, so a double is
 * injected directly — no module mock and no mounted app needed. The rest of the
 * auth surface is mocked the same way `appStateAuthGate.test.ts` does it.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MockActiveUserAdapter } from '$lib/shared/ports/adapters/mock/MockActiveUserAdapter';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';

const mocks = vi.hoisted(() => {
  let authHandler: ((event: string, session: unknown) => void) | null = null;
  return {
    getAuthHandler: (): ((event: string, session: unknown) => void) | null => authHandler,
    restoreSession: vi.fn(),
    signInAnonymously: vi.fn(),
    signOut: vi.fn(async () => undefined),
    loadPersistedAuth: vi.fn(),
    setLiveSession: vi.fn(),
    clearLiveSession: vi.fn(),
    getLiveSession: vi.fn<() => unknown>(() => null),
    onAuthStateChange: vi.fn((handler: (event: string, session: unknown) => void) => {
      authHandler = handler;
      return { data: { subscription: null } };
    }),
  };
});

// tauriClient: permissive proxy so anything reached during init resolves to a
// sane, non-throwing value without enumerating the whole surface.
vi.mock('$lib/shared/api/tauriClient', () => {
  const arrays = new Set([
    'listLibraryBooks',
    'listBooks',
    'listCollections',
    'getBookCollections',
    'listHighlights',
    'listTags',
    'listTagsForHighlight',
    'listBookmarks',
    'getSettings',
    'getLogs',
    'getFileBytes',
    'readFileRange',
    'getReadingActivity',
  ]);
  let prop = '';
  const value = (): unknown => {
    if (prop === 'getReaderSettings' || prop === 'getDefaultReaderSettings')
      return { epub: { fontSize: 100, fontFamily: 'serif' } };
    if (prop === 'getReadingStats')
      return {
        totalMinutesRead: 0,
        totalSessions: 0,
        booksStarted: 0,
        booksCompleted: 0,
        avgProgressPercentage: 0,
      };
    if (
      prop === 'getReadingStreak' ||
      prop === 'getTodayMinutes' ||
      prop === 'getDailyGoal' ||
      prop === 'getFileSize'
    )
      return 0;
    if (prop === 'getProgress' || prop === 'getLocaleSetting' || prop === 'diagnose') return null;
    if (prop === 'fileExists') return false;
    if (prop === 'scanFolder') return { files: [] };
    if (arrays.has(prop)) return [];
    return undefined;
  };
  return new Proxy(
    {},
    {
      get(_target, key: string) {
        if (key === '__esModule' || key === 'then') return undefined;
        prop = key;
        return vi.fn(async () => value());
      },
    },
  );
});

vi.mock('$lib/shared/services/SyncService', () => ({
  SyncService: {
    setupOutboxProcessor: vi.fn(),
    setupAutoSync: vi.fn(),
    teardownAutoSync: vi.fn(),
    syncMetadata: vi.fn(async () => undefined),
    syncBookCatalog: vi.fn(async () => undefined),
    resetOutboxBreaker: vi.fn(),
    getSyncHealth: vi.fn(async () => null),
  },
}));

vi.mock('$lib/shared/services/SupabaseAuthService', () => ({
  restoreSession: mocks.restoreSession,
  signInAnonymously: mocks.signInAnonymously,
  signOut: mocks.signOut,
  signInWithGoogle: vi.fn(async () => undefined),
  registerSupabaseCallbackHandler: vi.fn(async () => undefined),
  unregisterCallbackHandler: vi.fn(),
}));

vi.mock('$lib/services/supabase', () => ({
  getSessionClient: vi.fn(() => ({ auth: { onAuthStateChange: mocks.onAuthStateChange } })),
  setLiveSession: mocks.setLiveSession,
  clearLiveSession: mocks.clearLiveSession,
  getLiveSession: mocks.getLiveSession,
  hasLiveSession: vi.fn(() => false),
  recheckLiveSession: vi.fn(async () => false),
  resetSessionClient: vi.fn(),
}));

vi.mock('$lib/shared/stores/SyncHealthState.svelte', () => ({
  syncHealthState: {
    refresh: vi.fn(async () => undefined),
    startPoll: vi.fn(),
    stopPoll: vi.fn(),
  },
}));

vi.mock('$lib/shared/stores/authPersistence', () => ({
  loadPersistedAuth: mocks.loadPersistedAuth,
  savePersistedAuth: vi.fn(async () => undefined),
  clearPersistedAuth: vi.fn(async () => undefined),
}));

vi.mock('$lib/shared/stores/theme', () => ({ initTheme: vi.fn() }));

const loggedErrors = vi.hoisted(() => [] as Array<{ message: string; context: unknown }>);
vi.mock('$lib/shared/logger/Logger', () => ({
  logger: {
    error: (message: string, context: unknown) => {
      loggedErrors.push({ message, context });
    },
    warn: vi.fn(),
    info: vi.fn(),
    debug: vi.fn(),
  },
}));

vi.mock('$lib/shared/i18n', () => ({
  i18n: {
    t: (_locale: string, key: string) => key,
    initializeLocale: vi.fn(async () => 'es'),
  },
}));

vi.mock('$lib/shared/services/FilePicker', () => ({
  pickFile: vi.fn(),
  pickFolder: vi.fn(),
}));

vi.mock('$lib/shared/services/pdfThumbnail', () => ({
  extractPdfMetadata: vi.fn(async () => null),
}));

const { AppState } = await import('$lib/shared/stores/AppState.svelte');

function realSession(id = 'user-1') {
  return {
    access_token: `at-${id}`,
    refresh_token: `rt-${id}`,
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    user: { id, email: 'user@example.com', user_metadata: {}, is_anonymous: false },
  };
}

function localProfile() {
  return { name: 'Test User', email: null, avatarUrl: null, localOnly: true as const };
}

describe('AppState — active-user wiring', () => {
  let activeUser: MockActiveUserAdapter;
  let app: InstanceType<typeof AppState>;

  beforeEach(() => {
    vi.clearAllMocks();
    authState.clearSupabaseSession();
    authState.clearLocalUser();
    navigationState.route = 'home';
    activeUser = new MockActiveUserAdapter();
    app = new AppState(activeUser);
    mocks.getLiveSession.mockReturnValue(null);
    mocks.restoreSession.mockResolvedValue(null);
    mocks.loadPersistedAuth.mockResolvedValue(null);
    mocks.signInAnonymously.mockImplementation(async () => {
      authState.setSupabaseSession({
        accessToken: 'at-anon',
        refreshToken: 'rt-anon',
        expiresAt: Date.now() + 3_600_000,
        userId: 'anon-1',
        email: null,
        displayName: null,
        photoUrl: null,
        isAnonymous: true,
      });
    });
  });

  it('sets the active user when a real session is restored', async () => {
    mocks.restoreSession.mockResolvedValue(realSession('user-42'));

    await app.init();

    expect(activeUser.activeUserId).toBe('user-42');
    expect(activeUser.calls).toContain('set:user-42');
  });

  it('sets the active user when a real session signs in', async () => {
    await app.init();

    mocks.getAuthHandler()!('SIGNED_IN', realSession('user-7'));
    await Promise.resolve();

    expect(activeUser.activeUserId).toBe('user-7');
  });

  it('clears the active user on sign-out', async () => {
    mocks.restoreSession.mockResolvedValue(realSession('user-42'));
    await app.init();
    expect(activeUser.activeUserId).toBe('user-42');

    await app.signOutAndReturnToWelcome();

    expect(activeUser.activeUserId).toBeNull();
    expect(activeUser.calls).toContain('clear');
  });

  it('clears the active user on a SIGNED_OUT event', async () => {
    mocks.restoreSession.mockResolvedValue(realSession('user-42'));
    await app.init();

    mocks.getAuthHandler()!('SIGNED_OUT', null);
    await Promise.resolve();

    expect(activeUser.activeUserId).toBeNull();
  });

  it('clears the active user when a cached local profile is restored', async () => {
    mocks.loadPersistedAuth.mockResolvedValue({ kind: 'local', profile: localProfile() });

    await app.init();

    expect(authState.isLocalUser).toBe(true);
    expect(activeUser.activeUserId).toBeNull();
    expect(activeUser.calls).toContain('set:null');
  });

  it('does not abort init when the port rejects', async () => {
    const failing = {
      setActiveUser: vi.fn(async () => {
        throw new Error('ACTIVE_USER_UNAVAILABLE');
      }),
      clearActiveUser: vi.fn(async () => undefined),
    };
    const failingApp = new AppState(failing);
    mocks.restoreSession.mockResolvedValue(realSession('user-42'));

    await expect(failingApp.init()).resolves.toBeUndefined();

    expect(failing.setActiveUser).toHaveBeenCalledWith('user-42');
    expect(navigationState.route).toBe('home');
  });

  it('does not report a slot write failure as an auth-cache failure', async () => {
    const failing = {
      setActiveUser: vi.fn(async () => {
        throw new Error('ACTIVE_USER_UNAVAILABLE');
      }),
      clearActiveUser: vi.fn(async () => undefined),
    };
    const failingApp = new AppState(failing);
    mocks.restoreSession.mockResolvedValue(realSession('user-42'));
    loggedErrors.length = 0;

    await failingApp.init();

    const messages = loggedErrors.map((entry) => entry.message);
    // The slot failure is reported as a slot failure...
    expect(messages.some((m) => m.includes('Failed to set the active user'))).toBe(true);
    // ...and never blamed on auth. Before the fix this read
    // "Failed to read auth cache during init:".
    expect(messages.some((m) => m.toLowerCase().includes('auth cache'))).toBe(false);
    expect(messages.some((m) => m.includes('Auth restore'))).toBe(false);
  });

  it('still restores the session and route when the slot write explodes', async () => {
    const failing = {
      setActiveUser: vi.fn(async () => {
        throw new Error('ACTIVE_USER_UNAVAILABLE');
      }),
      clearActiveUser: vi.fn(async () => undefined),
    };
    const failingApp = new AppState(failing);
    mocks.restoreSession.mockResolvedValue(realSession('user-42'));

    await failingApp.init();

    // The session landed; only the query-scoping hint is stale.
    expect(authState.userId).toBe('user-42');
    expect(navigationState.route).toBe('home');
  });
});
