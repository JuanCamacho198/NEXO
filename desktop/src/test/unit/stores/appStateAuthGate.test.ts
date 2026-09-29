/**
 * Focused tests for the desktop auth gate (auth-gate fix).
 *
 * A fresh install landed on home with an anonymous session because the DA-3
 * RLS fallback was indistinguishable from a real login: the boot sequence and
 * the `SIGNED_IN` handler both treated "has a session" as "is logged in".
 *
 * These tests pin the route rules at the two decision points:
 *  - boot: no session → welcome; anonymous restored → welcome; real → home;
 *  - auth event: anonymous SIGNED_IN keeps welcome; real SIGNED_IN promotes.
 *
 * The existing `appState.test.ts` auth-lifecycle suite is `describe.skip` and
 * written against the removed monolithic `appState.route` API, so the rule was
 * effectively untested — this file is the active replacement.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { appState } from '$lib/shared/stores/AppState.svelte';
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
    hasLiveSession: vi.fn(() => false),
    onAuthStateChange: vi.fn((handler: (event: string, session: unknown) => void) => {
      authHandler = handler;
      return { data: { subscription: null } };
    }),
  };
});

// tauriClient: a permissive proxy so any command reached during init resolves
// to a sane, non-throwing value without enumerating the whole surface.
vi.mock('$lib/shared/api/tauriClient', () => {
  const settings = {
    themeMode: 'paper',
    brightness: 100,
    contrast: 100,
    selectionColor: '#3388ff',
    epub: { fontSize: 100, fontFamily: 'serif' },
    lineHeight: 1.8,
    letterSpacing: 0,
    paragraphSpacing: 1,
    textAlign: 'left',
    direction: 'ltr',
    hyphenation: false,
    verticalScrolling: false,
    margins: { top: 1.5, bottom: 1.5, left: 2, right: 2 },
    showHeader: true,
    showFooter: true,
    showPageNumbers: true,
    progressIndicator: 'percentage',
  };
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
    if (prop === 'getReaderSettings' || prop === 'getDefaultReaderSettings') return settings;
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
  hasLiveSession: mocks.hasLiveSession,
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

function realSession(id = 'user-1') {
  return {
    access_token: `at-${id}`,
    refresh_token: `rt-${id}`,
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    user: { id, email: 'user@example.com', user_metadata: {}, is_anonymous: false },
  };
}

function anonymousSession(id = 'anon-1') {
  return {
    access_token: `at-${id}`,
    refresh_token: `rt-${id}`,
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    user: { id, email: null, user_metadata: {}, is_anonymous: true },
  };
}

function localProfile() {
  return { name: 'Test User', email: null, avatarUrl: null, localOnly: true as const };
}

describe('AppState — auth gate at boot and on auth events', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState.clearSupabaseSession();
    authState.clearLocalUser();
    navigationState.route = 'home';
    mocks.getLiveSession.mockReturnValue(null);
    mocks.hasLiveSession.mockReturnValue(false);
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

  it('fresh install with no session lands on welcome with an anonymous marker', async () => {
    await appState.init();

    expect(navigationState.route).toBe('welcome');
    expect(mocks.signInAnonymously).toHaveBeenCalledTimes(1);
    expect(authState.isSignedIn).toBe(true);
    expect(authState.isAnonymous).toBe(true);
    expect(authState.isAuthenticated).toBe(false);
  });

  it('a restored anonymous session still lands on welcome (RLS session kept)', async () => {
    const session = anonymousSession();
    mocks.restoreSession.mockResolvedValue(session);

    await appState.init();

    expect(navigationState.route).toBe('welcome');
    // RLS/sync context preserved exactly as before: the live session is set.
    expect(mocks.setLiveSession).toHaveBeenCalledWith(session);
    expect(authState.isAnonymous).toBe(true);
    expect(authState.isAuthenticated).toBe(false);
  });

  it('a restored real session lands on home and is authenticated', async () => {
    mocks.restoreSession.mockResolvedValue(realSession());

    await appState.init();

    expect(navigationState.route).toBe('home');
    expect(authState.isSignedIn).toBe(true);
    expect(authState.isAnonymous).toBe(false);
    expect(authState.isAuthenticated).toBe(true);
  });

  it('a cached local profile ("Continuar en local") lands on home', async () => {
    mocks.loadPersistedAuth.mockResolvedValue({ kind: 'local', profile: localProfile() });

    await appState.init();

    expect(navigationState.route).toBe('home');
    expect(authState.isLocalUser).toBe(true);
    expect(mocks.signInAnonymously).not.toHaveBeenCalled();
  });

  it('a real SIGNED_IN event promotes welcome → home and clears the anonymous marker', async () => {
    await appState.init();
    expect(navigationState.route).toBe('welcome');

    mocks.getAuthHandler()!('SIGNED_IN', realSession());

    expect(navigationState.route).toBe('home');
    expect(authState.isAnonymous).toBe(false);
    expect(authState.isAuthenticated).toBe(true);
  });

  it('an anonymous SIGNED_IN event does NOT promote away from welcome', async () => {
    await appState.init();
    expect(navigationState.route).toBe('welcome');

    mocks.getAuthHandler()!('SIGNED_IN', anonymousSession());

    expect(navigationState.route).toBe('welcome');
    expect(authState.isAnonymous).toBe(true);
    expect(authState.isAuthenticated).toBe(false);
  });
});
