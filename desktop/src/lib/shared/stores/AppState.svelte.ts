import { i18n, type MessageKey } from '$lib/shared/i18n';
import { initTheme } from '$lib/shared/stores/theme';
import { reconcileHomeState } from '$lib/shared/stores/HomeState';
import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
import { searchState } from '$lib/shared/stores/SearchDomainState.svelte';
import { bulkImportState } from '$lib/shared/stores/BulkImportDomainState.svelte';
import { discoverState } from '$lib/features/discover/DiscoverDomainState.svelte';
import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
import { loadNotifications } from '$lib/shared/stores/notificationCenter.svelte';
import { loadNotificationPreferences } from '$lib/shared/services/notificationPreferences';
import { logger } from '$lib/shared/logger/Logger';
import {
  clearPersistedAuth,
  loadPersistedAuth,
  type LocalUserProfile,
} from '$lib/shared/stores/authPersistence';
import { migrateLoginGrantOnce } from '$lib/shared/stores/drivePersistence';
import { isDriveAuthorized } from '$lib/shared/services/DriveConnectService';
import {
  getSessionClient,
  setLiveSession,
  clearLiveSession,
  getLiveSession,
} from '$lib/services/supabase';
import type { Session } from '@supabase/supabase-js';
import {
  signInAnonymously,
  restoreSession,
  signOut,
  isAnonymousSession,
} from '$lib/shared/services';
import { SyncService } from '$lib/shared/services/SyncService';
import { dictionaryState } from '$lib/shared/stores/DictionaryState.svelte';
import { syncHealthState } from '$lib/shared/stores/SyncHealthState.svelte';
import type { CommandErrorDto, ReaderBook } from '$lib/shared/types';
import { createShelfQueryState, type AppRoute } from '$lib/shared/stores/HomeState';

type MaybeCommandError = Error & { commandError?: CommandErrorDto };

export class AppState {
  navigation = navigationState;
  library = libraryState;
  reader = readerState;
  search = searchState;
  bulkImport = bulkImportState;
  discover = discoverState;
  settings = settingsState;
  statsDomain = statsState;

  constructor() {
    this.bulkImport.onLibraryRefreshNeeded = () => this.loadLibrary();
    // The Discover download imports through the same pipeline as a file import;
    // without this seam the library singleton stays on its boot-time snapshot.
    this.discover.onLibraryRefreshNeeded = () => this.loadLibrary();
    this.reader.onStatsRefreshNeeded = async (bookId) => {
      await this.statsDomain.loadStats(bookId);
      await this.statsDomain.loadStreak(bookId, authState.userId ?? '');
    };
  }

  isInitialized = $state(false);

  t = (key: MessageKey, params?: Record<string, string | number>): string =>
    i18n.t(this.settings.locale, key, params);

  mapCommandError(error: unknown): CommandErrorDto {
    const typed = error as MaybeCommandError;
    if (typed.commandError) return typed.commandError;
    const fallback = error instanceof Error ? error.message : this.t('errors.commandFailure');
    return { code: 'INTERNAL_ERROR', message: fallback, recoverable: false };
  }

  async loadLibrary(): Promise<void> {
    const snapshot = {
      route: this.navigation.route,
      previewBookId: this.navigation.previewBookId,
      activeReadingBookId: this.reader.activeReadingBookId,
      shelfDetailsBookId: this.navigation.shelfDetailsBookId,
    };
    await this.library.loadLibrary();
    if (this.library.consumeBooksJustChanged()) {
      const reconciled = reconcileHomeState(this.library.books, snapshot);
      this.navigation.route = reconciled.route;
      this.navigation.previewBookId = reconciled.previewBookId;
      this.reader.activeReadingBookId = reconciled.activeReadingBookId;
      this.navigation.shelfDetailsBookId = reconciled.shelfDetailsBookId;
    }
    const error = this.library.consumeLastRecoverableError();
    if (error) this.navigation.setDomainUnavailable('library', error.message);
  }

  async startReading(book: ReaderBook): Promise<void> {
    this.library.promoteBookForReading(book.id);
    this.reader.activeReadingBookId = book.id;
    this.navigation.shelfDetailsBookId = null;
    this.navigation.route = 'reader';
    this.search.resetSearch();
    this.library.recordReaderOpenMetric(book.format);
    await this.reader.startReading(book);
    void this.statsDomain.loadStats(book.id);
  }

  handleEpubLocationChange = async (
    nextLocation: string,
    nextPercentage: number,
  ): Promise<void> => {
    if (!this.reader.activeReadingBookId) return;
    await this.reader.handleEpubLocationChange(
      this.reader.activeReadingBookId,
      nextLocation,
      nextPercentage,
    );
  };

  handlePdfPageChange = async (page: number, total: number): Promise<void> => {
    if (!this.reader.activeReadingBookId) return;
    const activeId = this.reader.activeReadingBookId;
    this.library.updateBookPage(activeId, page, total);
    await this.reader.handlePdfPageChange(activeId, page, total);
  };

  handlePdfSessionProgress = async (event: {
    startedAt: string;
    endedAt?: string;
    durationSeconds: number;
    startPercentage?: number;
    endPercentage?: number;
  }): Promise<void> => {
    if (!this.reader.activeReadingBookId) return;
    await this.reader.handlePdfSessionProgress(this.reader.activeReadingBookId, event);
  };

  handleReaderLocationContext = (ctx?: unknown): void => {
    this.reader.handleReaderLocationContext(ctx);
  };

  handleSearch = async (query: string, page: number): Promise<void> => {
    if (!this.reader.activeReadingBookId) return;
    await this.search.handleSearch(this.reader.activeReadingBookId, query, page);
    if (this.search.searchUnavailableReason)
      this.navigation.setDomainUnavailable('search', this.search.searchUnavailableReason);
  };

  handleSearchJump = (target: import('$lib/shared/types').SearchNavigationTarget): void => {
    this.search.handleSearchJump(target);
  };

  navigateToWelcome = (): void => {
    this.navigation.route = 'welcome';
  };

  async init(): Promise<void> {
    initTheme();
    this.navigation.shelfDetailsBookId = null;
    this.library.shelfQueryState = createShelfQueryState();
    this.navigation.previewBookId = null;
    this.library.readerError = null;
    this.bulkImport.isImporting = false;
    this.bulkImport.importProgress = null;
    let initialRoute: AppRoute = 'welcome';
    let restoredSupabaseSession = false;
    try {
      const supabaseSession = await restoreSession();
      if (supabaseSession) {
        // DA-3: an anonymous session is restored only as an RLS auth context.
        // It still gets the live-session cache and the authenticated sync
        // wiring (unchanged sync behaviour), but it must NOT count as a
        // login: a fresh install lands on the welcome screen.
        restoredSupabaseSession = true;
        setLiveSession(supabaseSession);
        this.hydrateAuthState(supabaseSession);
        this.startAuthenticatedSync();
        initialRoute = isAnonymousSession(supabaseSession) ? 'welcome' : 'home';
      } else {
        const cached = await loadPersistedAuth();
        if (cached) {
          if (cached.kind === 'local') {
            authState.setLocalUser(cached.profile satisfies LocalUserProfile);
            initialRoute = 'home';
          }
        } else {
          await signInAnonymously();
        }
      }
    } catch (error) {
      logger.error(
        'Failed to read auth cache during init:',
        { error: error instanceof Error ? error.message : String(error) },
        'app_shell',
      );
      if (getLiveSession() === null && authState.userId === null) {
        try {
          await signInAnonymously();
        } catch {}
      }
    }
    // Login–Drive separation: seed drive.json once from any pre-separation
    // login-coupled grant, then sync the reactive Drive state from disk. This
    // runs on every launch independent of sign-in state (spec: migration
    // requires at most one re-connect; sign-out never clears the grant).
    // Best-effort: a disk failure degrades to unauthorized, never to a crash.
    try {
      await migrateLoginGrantOnce();
    } catch {}
    try {
      await isDriveAuthorized();
    } catch {}
    this.navigation.route = initialRoute;
    SyncService.setupOutboxProcessor();
    if (authState.userId && !restoredSupabaseSession) SyncService.syncBookCatalog();
    getSessionClient().auth.onAuthStateChange((event, session) => {
      if (event === 'SIGNED_IN' && session) {
        const anonymous = isAnonymousSession(session);
        setLiveSession(session);
        SyncService.resetOutboxBreaker();
        this.startAuthenticatedSync();
        try {
          SyncService.setupAutoSync();
          void syncHealthState.refresh();
          syncHealthState.startPoll();
        } catch {}
        try {
          dictionaryState.subscribeToRemoteChanges();
        } catch {}
        if (!anonymous) {
          // A real session landing clears the anonymous marker (DA-3): refresh
          // authState from the session so `isAuthenticated` flips true.
          this.hydrateAuthState(session);
          if (this.navigation.route === 'welcome') {
            this.navigation.route = 'home';
            this.loadLibrary();
            this.statsDomain.loadStats(undefined);
          }
        }
        void this.loadDailyGoalForCurrentUser();
        return;
      }
      if (event === 'SIGNED_OUT') {
        clearLiveSession();
        authState.clearSupabaseSession();
        try {
          SyncService.teardownAutoSync();
          syncHealthState.stopPoll();
        } catch {}
        this.reader.unsubscribeFromAllRemoteChanges();
        try {
          dictionaryState.unsubscribe();
        } catch {}
        this.settings.clearDailyGoal();
        this.statsDomain.clearTodayMinutes();
        this.statsDomain.syncDailyGoal(this.settings.dailyGoalMinutes);
        this.navigateToWelcome();
        return;
      }
      if (event === 'TOKEN_REFRESHED' && session) {
        setLiveSession(session);
        SyncService.resetOutboxBreaker();
        this.hydrateAuthState(session);
        return;
      }
      if (event === 'INITIAL_SESSION') {
        if (session) {
          setLiveSession(session);
          if (authState.userId === null) this.hydrateAuthState(session);
        } else clearLiveSession();
      }
    });
    try {
      const [nextLocale] = await Promise.all([
        i18n.initializeLocale(),
        this.settings.loadReaderSettings(),
        this.loadLibrary(),
        this.statsDomain.loadStats(undefined),
        loadNotifications(),
        loadNotificationPreferences(),
      ]);
      this.settings.locale = nextLocale;
    } catch (error) {
      logger.error(
        'Initialization error:',
        { error: error instanceof Error ? error.message : String(error) },
        'app_shell',
      );
      try {
        this.settings.locale = await i18n.initializeLocale();
      } catch {}
      this.settings.loadReaderSettings();
      this.loadLibrary();
      this.statsDomain.loadStats(undefined);
    } finally {
      void this.loadDailyGoalForCurrentUser();
      this.statsDomain.syncDailyGoal(this.settings.dailyGoalMinutes);
      try {
        const uid = authState.userId;
        if (uid) void this.statsDomain.loadTodayMinutes(uid);
      } catch {}
      this.isInitialized = true;
    }
  }

  signOutAndReturnToWelcome = async (): Promise<void> => {
    authState.clearLocalUser();
    authState.clearSupabaseSession();
    await clearPersistedAuth();
    await signOut();
    try {
      SyncService.teardownAutoSync();
      syncHealthState.stopPoll();
    } catch {}
    this.reader.unsubscribeFromAllRemoteChanges();
    try {
      dictionaryState.unsubscribe();
    } catch {}
    this.settings.clearDailyGoal();
    this.statsDomain.clearTodayMinutes();
    this.statsDomain.syncDailyGoal(this.settings.dailyGoalMinutes);
    this.navigateToWelcome();
    pushToast('success', this.t('welcome.signedOutToast'));
  };

  private startAuthenticatedSync(): void {
    SyncService.setupOutboxProcessor();
    try {
      SyncService.setupAutoSync();
      void syncHealthState.refresh();
      syncHealthState.startPoll();
    } catch {}
    this.reader.subscribeToAllRemoteChanges();
    try {
      dictionaryState.subscribeToRemoteChanges();
    } catch {}
    void SyncService.syncMetadata().catch((error: unknown) => {
      logger.error(
        'Startup sync failed; continuing offline:',
        { error: error instanceof Error ? error.message : String(error) },
        'sync',
      );
    });
  }

  private async loadDailyGoalForCurrentUser(): Promise<void> {
    const uid = authState.userId;
    if (!uid || uid.trim().length === 0) {
      // No session: read the global key so a goal saved on this device is
      // shown again on the next launch instead of being reset to the default.
      try {
        await this.settings.loadDailyGoalMinutes();
      } catch {}
      this.statsDomain.clearTodayMinutes();
      this.statsDomain.syncDailyGoal(this.settings.dailyGoalMinutes);
      return;
    }
    try {
      await this.settings.loadDailyGoalMinutes(uid);
    } catch {}
    this.statsDomain.syncDailyGoal(this.settings.dailyGoalMinutes);
    try {
      await this.statsDomain.loadTodayMinutes(uid);
    } catch {}
  }

  async saveDailyGoalMinutes(minutes: number): Promise<void> {
    const uid = authState.userId;
    if (!uid || uid.trim().length === 0) {
      // Session-less save: the backend persists the goal to the global key so
      // it survives restarts and carries over if the user signs in later.
      await this.settings.saveDailyGoalMinutes(minutes);
      this.statsDomain.syncDailyGoal(this.settings.dailyGoalMinutes);
      return;
    }
    await this.settings.saveDailyGoalMinutes(minutes, uid);
    this.statsDomain.syncDailyGoal(this.settings.dailyGoalMinutes);
    try {
      await this.statsDomain.loadTodayMinutes(uid);
    } catch {}
  }

  async refreshTodayMinutes(): Promise<void> {
    const uid = authState.userId;
    if (!uid) {
      this.statsDomain.clearTodayMinutes();
      return;
    }
    await this.statsDomain.loadTodayMinutes(uid);
  }

  private hydrateAuthState(session: Session): void {
    authState.setSupabaseSession({
      accessToken: session.access_token,
      refreshToken: session.refresh_token,
      expiresAt: session.expires_at ? session.expires_at * 1000 : null,
      userId: session.user.id,
      email: session.user.email ?? null,
      displayName:
        session.user.user_metadata?.full_name ?? session.user.user_metadata?.name ?? null,
      photoUrl:
        session.user.user_metadata?.avatar_url ?? session.user.user_metadata?.picture ?? null,
      isAnonymous: isAnonymousSession(session),
    });
  }
}

export const appState = new AppState();
