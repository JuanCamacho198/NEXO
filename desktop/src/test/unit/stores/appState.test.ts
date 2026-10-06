// @ts-nocheck
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { appState } from '$lib/shared/stores/AppState.svelte';
import { bulkImportState } from '$lib/shared/stores/BulkImportDomainState.svelte';
import { authState } from '$lib/shared/stores/AuthState.svelte';

const mockReadFile = vi.hoisted(() =>
  vi.fn<(...args: unknown[]) => Promise<Uint8Array>>().mockResolvedValue(new Uint8Array([1, 2, 3])),
);
const mockHideBook = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mockGDriveDelete = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mockFetchCatalog = vi.hoisted(() => vi.fn().mockResolvedValue([]));
const mockTombstoneBook = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));

// Mock ALL AppState dependencies upfront
vi.mock('$lib/shared/services/BookImportService', () => {
  const z = vi.fn() as unknown as (...args: unknown[]) => Promise<unknown>;
  return { importBook: z };
});
vi.mock('$lib/shared/services/FilePicker', () => {
  const z = vi.fn() as unknown as (...args: unknown[]) => Promise<unknown>;
  return { pickFile: z, pickFolder: z };
});
vi.mock('$lib/shared/services/pdfThumbnail', () => ({
  extractPdfMetadata: vi.fn(function () {
    return Promise.reject(new Error('no pdf'));
  }),
}));
vi.mock('$lib/shared/stores/theme', () => {
  const z = vi.fn() as unknown as (...args: unknown[]) => unknown;
  return { initTheme: z };
});
vi.mock('$lib/shared/i18n', () => ({
  i18n: {
    t: vi.fn(function (locale: string, key: string) {
      return key;
    }),
    initializeLocale: vi.fn(function () {
      return Promise.resolve('es');
    }),
  },
}));

const mockGetProgress = vi.hoisted(() => vi.fn().mockResolvedValue(null));
const mockGetReadingStats = vi.hoisted(() => vi.fn().mockResolvedValue(null));
const mockGetReadingStreak = vi.hoisted(() => vi.fn().mockResolvedValue(0));
const mockRestoreSession = vi.hoisted(() => vi.fn().mockResolvedValue(null));
const mockSignInAnonymously = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mockSyncMetadata = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mockSetupOutboxProcessor = vi.hoisted(() => vi.fn());
const mockResetOutboxBreaker = vi.hoisted(() => vi.fn());
const mockSetLiveSession = vi.hoisted(() => vi.fn());
const mockClearLiveSession = vi.hoisted(() => vi.fn());
const mockGetLiveSession = vi.hoisted(() => vi.fn(() => null));
const mockHasLiveSession = vi.hoisted(() => vi.fn(() => false));
const mockLoadPersistedAuth = vi.hoisted(() => vi.fn().mockResolvedValue(null));
let capturedAuthHandler: ((event: string, session: unknown) => void) | null = null;
const mockOnAuthStateChange = vi.hoisted(() =>
  vi.fn(function (handler: (event: string, session: unknown) => void) {
    capturedAuthHandler = handler;
    return { data: { subscription: null } };
  }),
);

vi.mock('$lib/shared/api/tauriClient', () => {
  const rf = vi.fn(function () {
    return Promise.resolve([]);
  }) as unknown as (...args: unknown[]) => Promise<unknown[]>;
  return {
    listLibraryBooks: rf,
    listBooks: rf,
    listCollections: rf,
    getDefaultReaderSettings: vi.fn(function () {
      return {
        themeMode: 'paper',
        brightness: 100,
        contrast: 100,
        selectionColor: '#3b82f6',
        epub: { fontSize: 16, fontFamily: 'serif' },
      };
    }),
    getReaderSettings: vi.fn().mockResolvedValue(null),
    getProgress: mockGetProgress,
    getReadingStats: mockGetReadingStats,
    getReadingStreak: mockGetReadingStreak,
    saveProgress: vi.fn(function () {
      return Promise.resolve(undefined);
    }),
    saveReadingSession: vi.fn(function () {
      return Promise.resolve(undefined);
    }),
    hideBookFromLibrary: mockHideBook,
    upsertBook: vi.fn(function () {
      return Promise.resolve(undefined);
    }),
    upsertBookCover: vi.fn(function () {
      return Promise.resolve(undefined);
    }),
    extractEpubCover: vi.fn(function () {
      return Promise.resolve(false);
    }),
    updateBookProgress: vi.fn(function () {
      return Promise.resolve(undefined);
    }),
    setReadingStatus: vi.fn(function () {
      return Promise.resolve(undefined);
    }),
    searchBookText: vi.fn(function () {
      return Promise.resolve(null);
    }),
    scanFolder: vi.fn(function () {
      return Promise.resolve({ files: [] });
    }),
  };
});

vi.mock('@tauri-apps/plugin-fs', () => ({
  BaseDirectory: { AppData: 0 },
  readFile: mockReadFile,
  readTextFile: vi.fn(),
  writeTextFile: vi.fn(),
  rename: vi.fn(),
  remove: vi.fn(),
  exists: vi.fn().mockResolvedValue(false),
}));

vi.mock('$lib/shared/services/SupabaseAuthService', () => ({
  restoreSession: mockRestoreSession,
  signInAnonymously: mockSignInAnonymously,
  signOut: vi.fn(async () => undefined),
  registerSupabaseCallbackHandler: vi.fn(async () => undefined),
  unregisterCallbackHandler: vi.fn(),
}));

vi.mock('$lib/shared/services/SyncService', () => ({
  SyncService: {
    setupOutboxProcessor: mockSetupOutboxProcessor,
    syncMetadata: mockSyncMetadata,
    syncBookCatalog: vi.fn().mockResolvedValue(undefined),
    resetOutboxBreaker: mockResetOutboxBreaker,
  },
}));

vi.mock('$lib/shared/services/storage/GDriveProvider', () => ({
  GDriveProvider: vi.fn(function () {
    return { delete: mockGDriveDelete };
  }),
}));

vi.mock('$lib/shared/sync/SupabaseBookCatalogSync', () => ({
  SupabaseBookCatalogSync: vi.fn(function () {
    return {
      fetchCatalog: mockFetchCatalog,
      tombstoneBook: mockTombstoneBook,
    };
  }),
}));

vi.mock('$lib/services/supabase', () => ({
  getSessionClient: vi.fn(() => ({
    auth: { onAuthStateChange: mockOnAuthStateChange },
  })),
  setLiveSession: mockSetLiveSession,
  clearLiveSession: mockClearLiveSession,
  getLiveSession: mockGetLiveSession,
  hasLiveSession: mockHasLiveSession,
}));

vi.mock('$lib/shared/stores/authPersistence', () => ({
  loadPersistedAuth: mockLoadPersistedAuth,
  savePersistedAuth: vi.fn(async () => undefined),
  clearPersistedAuth: vi.fn(async () => undefined),
}));

vi.mock('$lib/shared/services/BulkImportService', () => {
  const mock = {
    importFolder: vi.fn().mockResolvedValue({ success: 0, skipped: 0, failed: 0, cancelled: 0 }),
    cancel: vi.fn(),
  };
  return {
    BulkImportService: vi.fn(function () {
      return mock;
    }),
  };
});

type BookLike = {
  id: string;
  title?: string;
  filePath: string;
  format: string;
  currentPage?: number;
  totalPages?: number;
  minutesRead?: number;
  coverPath: string | null;
  author: string;
  collectionIds: number[];
  progressPercentage: number;
};

type BareBook = {
  id: string;
  filePath?: string;
  format?: string;
  title?: string;
  currentPage?: number;
  totalPages?: number;
};

function asBook(obj: BareBook): BookLike {
  return {
    id: obj.id,
    title: obj.title ?? '',
    filePath: obj.filePath ?? '',
    format: obj.format ?? '',
    currentPage: obj.currentPage ?? 0,
    totalPages: obj.totalPages ?? 0,
    minutesRead: 0,
    coverPath: null,
    author: '',
    collectionIds: [],
    progressPercentage: 0,
  };
}

function resetAppState(): void {
  appState.route = 'home';
  appState.previewBookId = null;
  appState.activeReadingBookId = null;
  appState.shelfDetailsBookId = null;
  appState.libraryUnavailableReason = null;
  appState.statsUnavailableReason = null;
  appState.searchUnavailableReason = null;
  appState.isLoadingLibrary = false;
  appState.isLoadingStats = false;
  appState.isSearching = false;
  appState.isImporting = false;
  appState.importProgress = null;
  bulkImportState.importNotice = null;
  bulkImportState.dismissImportNotice();
  appState.cfiLocation = '';
  appState.percentage = 0;
  appState.stats = null;
  appState.searchResponse = null;
  appState.searchTargetLocator = null;
  appState.readerError = null;
  appState.locale = 'es';
  appState.editingBook = null;
  appState.pendingRemoveBook = null;
  appState.collections = [];
  appState.isBulkImportOpen = false;
  appState.isBulkScanning = false;
  appState.isBulkImporting = false;
  appState.bulkImportFolderPath = null;
  appState.bulkImportFolderName = null;
  appState.bulkScanResult = null;
  appState.bulkScanError = null;
  appState.bulkImportProgress = null;
  appState.bulkImportSummary = null;
  appState.books = [];
  appState.preloadedBytes = null;
}

describe.skip('AppState', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetAppState();
    authState.clearSupabaseSession();
    mockRestoreSession.mockResolvedValue(null);
    mockSignInAnonymously.mockResolvedValue(undefined);
    mockSyncMetadata.mockResolvedValue(undefined);
    mockGetLiveSession.mockReturnValue(null);
    mockHasLiveSession.mockReturnValue(false);
    mockLoadPersistedAuth.mockResolvedValue(null);
    capturedAuthHandler = null;
    // Removal-flow mocks: clearAllMocks keeps implementations, so reset the
    // defaults here to avoid leaking rejections between tests.
    mockGDriveDelete.mockResolvedValue(undefined);
    mockFetchCatalog.mockResolvedValue([]);
    mockTombstoneBook.mockResolvedValue(undefined);
  });

  it('restored authenticated startup syncs metadata and all realtime changes once', async () => {
    mockRestoreSession.mockResolvedValue({
      access_token: 'access-token',
      refresh_token: 'refresh-token',
      expires_at: 1_900_000_000,
      user: { id: 'user-1', email: 'user@example.com', user_metadata: {} },
    });
    const subscribeAll = vi
      .spyOn(appState.reader, 'subscribeToAllRemoteChanges')
      .mockImplementation(() => undefined);

    await appState.init();

    expect(mockSyncMetadata).toHaveBeenCalledTimes(1);
    expect(subscribeAll).toHaveBeenCalledTimes(1);
  });

  it('does not block startup when restored-session sync fails', async () => {
    mockRestoreSession.mockResolvedValue({
      access_token: 'access-token',
      refresh_token: 'refresh-token',
      expires_at: 1_900_000_000,
      user: { id: 'user-1', email: 'user@example.com', user_metadata: {} },
    });
    mockSyncMetadata.mockRejectedValue(new Error('offline'));
    vi.spyOn(appState.reader, 'subscribeToAllRemoteChanges').mockImplementation(() => undefined);

    await expect(appState.init()).resolves.toBeUndefined();
    expect(appState.isInitialized).toBe(true);
  });

  it('logout clears every remote realtime subscription', async () => {
    const unsubscribeAll = vi
      .spyOn(appState.reader, 'unsubscribeFromAllRemoteChanges')
      .mockImplementation(() => undefined);

    await appState.signOutAndReturnToWelcome();

    expect(unsubscribeAll).toHaveBeenCalledTimes(1);
  });

  // ─── Preload (startReading) ───

  it('startReading preloadedBytes is populated for EPUB', async () => {
    appState.preloadedBytes = { filePath: '/old.epub', data: new Uint8Array([1, 2, 3]) };
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.epub',
      format: 'epub',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    // The old preloadedBytes was cleared, then new preload populated it
    expect(appState.preloadedBytes).not.toBeNull();
    expect(appState.preloadedBytes!.filePath).toBe('/test.epub');
  });

  it('startReading sets route to reader', async () => {
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.epub',
      format: 'epub',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    expect(appState.route).toBe('reader');
  });

  it('startReading sets activeReadingBookId', async () => {
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.epub',
      format: 'epub',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    expect(appState.activeReadingBookId).toBe('b1');
  });

  it('startReading fires readFile for EPUB preload', async () => {
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.epub',
      format: 'epub',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    expect(mockReadFile).toHaveBeenCalledWith('/test.epub');
  });

  it('startReading preloadedBytes is populated asynchronously for EPUB', async () => {
    mockReadFile.mockResolvedValueOnce(new Uint8Array([10, 20, 30]));
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.epub',
      format: 'epub',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    await vi.waitFor(() => {
      expect(appState.preloadedBytes).not.toBeNull();
    });
    expect(appState.preloadedBytes!.filePath).toBe('/test.epub');
    expect(appState.preloadedBytes!.data).toEqual(new Uint8Array([10, 20, 30]));
  });

  it('startReading preload failure does not throw for EPUB', async () => {
    mockReadFile.mockRejectedValueOnce(new Error('EPUB load failed'));
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.epub',
      format: 'epub',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await expect(appState.startReading(book)).resolves.toBeUndefined();
    expect(appState.preloadedBytes).toBeNull();
  });

  it('startReading fires readFile for PDF preload', async () => {
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.pdf',
      format: 'pdf',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    expect(mockReadFile).toHaveBeenCalledWith('/test.pdf');
  });

  it('startReading preloadedBytes is populated asynchronously for PDF', async () => {
    mockReadFile.mockResolvedValueOnce(new Uint8Array([99, 98, 97]));
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.pdf',
      format: 'pdf',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    await vi.waitFor(() => {
      expect(appState.preloadedBytes).not.toBeNull();
    });
    expect(appState.preloadedBytes!.filePath).toBe('/test.pdf');
    expect(appState.preloadedBytes!.data).toEqual(new Uint8Array([99, 98, 97]));
  });

  it('startReading preload failure does not throw for PDF', async () => {
    mockReadFile.mockRejectedValueOnce(new Error('PDF load failed'));
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.pdf',
      format: 'pdf',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await expect(appState.startReading(book)).resolves.toBeUndefined();
    expect(appState.preloadedBytes).toBeNull();
  });

  it('startReading with EPUB calls getProgress for location and percentage', async () => {
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.epub',
      format: 'epub',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    expect(mockGetProgress).toHaveBeenCalledWith('b1');
  });

  it('startReading with PDF does not call getProgress', async () => {
    const book = asBook({
      id: 'b1',
      title: 'Test',
      filePath: '/test.pdf',
      format: 'pdf',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    expect(mockGetProgress).not.toHaveBeenCalled();
  });

  // ─── Navigation ───

  it('navigateToHome sets route and clears shelf details', () => {
    appState.route = 'reader';
    appState.shelfDetailsBookId = 'book-1';
    appState.navigateToHome();
    expect(appState.route).toBe('home');
    expect(appState.shelfDetailsBookId).toBeNull();
  });

  it('navigateToLibrary sets route to library', () => {
    appState.navigateToLibrary();
    expect(appState.route).toBe('library');
  });

  it('navigateToStats sets route to stats', () => {
    appState.navigateToStats();
    expect(appState.route).toBe('stats');
  });

  it('navigateToHighlights sets route to highlights', () => {
    appState.navigateToHighlights();
    expect(appState.route).toBe('highlights');
  });

  it('navigateToSettings sets route to settings', () => {
    appState.navigateToSettings();
    expect(appState.route).toBe('settings');
  });

  it('backToHome sets route to home', () => {
    appState.route = 'reader';
    appState.backToHome();
    expect(appState.route).toBe('home');
  });

  // ─── Pure utility methods ───

  it('getBookById returns null for empty/unknown id', () => {
    expect(appState.getBookById(null)).toBeNull();
    expect(appState.getBookById('nonexistent')).toBeNull();
  });

  it('getBookById returns matching book', () => {
    appState.books = [
      asBook({
        id: '1',
        title: 'Test Book',
        filePath: '',
        format: 'pdf',
        currentPage: 10,
        totalPages: 100,
      }) as unknown as Parameters<typeof appState.startReading>[0],
    ];
    const found = appState.getBookById('1');
    expect(found).toBeTruthy();
    expect(found!.id).toBe('1');
  });

  it('isValidSessionProgressEvent validates correct events', () => {
    const valid = {
      startedAt: new Date(Date.now() - 60000).toISOString(),
      endedAt: new Date().toISOString(),
      durationSeconds: 60,
      startPercentage: 10,
      endPercentage: 50,
    };
    expect(appState.isValidSessionProgressEvent(valid)).toBe(true);
  });

  it('isValidSessionProgressEvent rejects missing endedAt', () => {
    expect(
      appState.isValidSessionProgressEvent({
        startedAt: new Date().toISOString(),
        durationSeconds: 0,
      }),
    ).toBe(false);
  });

  it('isValidSessionProgressEvent rejects zero duration', () => {
    expect(
      appState.isValidSessionProgressEvent({
        startedAt: new Date().toISOString(),
        endedAt: new Date().toISOString(),
        durationSeconds: 0,
      }),
    ).toBe(false);
  });

  it('isValidSessionProgressEvent rejects invalid percentages', () => {
    expect(
      appState.isValidSessionProgressEvent({
        startedAt: new Date(Date.now() - 60000).toISOString(),
        endedAt: new Date().toISOString(),
        durationSeconds: 60,
        startPercentage: -1,
        endPercentage: 101,
      }),
    ).toBe(false);
  });

  it('mapCommandError extracts commandError when present', () => {
    const err = new Error('wrapped') as Error & {
      commandError?: { code: string; message: string; recoverable: boolean };
    };
    err.commandError = { code: 'NOT_FOUND', message: 'Book not found', recoverable: true };
    expect(appState.mapCommandError(err)).toEqual({
      code: 'NOT_FOUND',
      message: 'Book not found',
      recoverable: true,
    });
  });

  it('mapCommandError falls back for plain Error', () => {
    const result = appState.mapCommandError(new Error('something broke'));
    expect(result.code).toBe('INTERNAL_ERROR');
    expect(result.message).toBe('something broke');
  });

  it('mapCommandError handles non-Error throws', () => {
    const result = appState.mapCommandError('string error');
    expect(result.code).toBe('INTERNAL_ERROR');
    expect(result.recoverable).toBe(false);
  });

  it('hasResolvedCoverPath checks non-empty coverPath', () => {
    expect(appState.hasResolvedCoverPath({ coverPath: '/path.jpg' })).toBe(true);
    expect(appState.hasResolvedCoverPath({ coverPath: '' })).toBe(false);
    expect(appState.hasResolvedCoverPath({ coverPath: '   ' })).toBe(false);
    expect(appState.hasResolvedCoverPath({ coverPath: null })).toBe(false);
  });

  it('shouldGeneratePdfCover only for PDFs without cover', () => {
    const pdf = asBook({ id: '1', format: 'pdf', filePath: '/t.pdf' }) as unknown as Parameters<
      typeof appState.shouldGeneratePdfCover
    >[0];
    const epub = asBook({ id: '2', format: 'epub', filePath: '/t.epub' }) as unknown as Parameters<
      typeof appState.shouldGeneratePdfCover
    >[0];
    const withCover = {
      ...asBook({ id: '3', format: 'pdf', filePath: '/t.pdf' }),
      coverPath: '/c.jpg',
    } as unknown as Parameters<typeof appState.shouldGeneratePdfCover>[0];
    expect(appState.shouldGeneratePdfCover(pdf)).toBe(true);
    expect(appState.shouldGeneratePdfCover(epub)).toBe(false);
    expect(appState.shouldGeneratePdfCover(withCover)).toBe(false);
  });

  // ─── Shelf operations ───

  it('openDetails sets previewBookId', () => {
    appState.openDetails(
      asBook({ id: 'b1' }) as unknown as Parameters<typeof appState.openDetails>[0],
    );
    expect(appState.previewBookId).toBe('b1');
  });

  it('openShelfDetails sets preview and shelf details', () => {
    appState.openShelfDetails(
      asBook({ id: 'b1' }) as unknown as Parameters<typeof appState.openShelfDetails>[0],
    );
    expect(appState.previewBookId).toBe('b1');
    expect(appState.shelfDetailsBookId).toBe('b1');
  });

  it('closeShelfDetails clears shelf details', () => {
    appState.shelfDetailsBookId = 'b1';
    appState.closeShelfDetails();
    expect(appState.shelfDetailsBookId).toBeNull();
  });

  // ─── Removal flow (T-10, REQ-09/10/11) ───

  it('requestRemoveBook opens the modal state without any Drive request (SCN-12)', () => {
    const book = asBook({ id: 'b1' }) as unknown as Parameters<
      typeof appState.requestRemoveBook
    >[0];
    appState.requestRemoveBook(book);
    expect(appState.pendingRemoveBook?.id).toBe('b1');
    expect(mockGDriveDelete).not.toHaveBeenCalled();
    expect(mockTombstoneBook).not.toHaveBeenCalled();
    expect(mockHideBook).not.toHaveBeenCalled();
  });

  it('handleRemoveBookFromDrive trashes, tombstones, hides, closes modal (SCN-13)', async () => {
    hydrateSignedInAuthState('user-1');
    mockFetchCatalog.mockResolvedValue([{ id: 'b1', remoteFileId: 'drive-file-1' }]);
    const book = asBook({ id: 'b1' }) as unknown as Parameters<
      typeof appState.handleRemoveBookFromDrive
    >[0];
    appState.pendingRemoveBook = book;

    await appState.handleRemoveBookFromDrive(book);

    expect(mockGDriveDelete).toHaveBeenCalledWith('drive-file-1');
    expect(mockTombstoneBook).toHaveBeenCalledWith('b1');
    expect(mockHideBook).toHaveBeenCalledWith('b1');
    expect(appState.pendingRemoveBook).toBeNull();
    expect(appState.readerError).toBeNull();
  });

  it('handleRemoveBookFromDrive aborts when Drive fails and closes the modal', async () => {
    hydrateSignedInAuthState('user-1');
    mockFetchCatalog.mockResolvedValue([{ id: 'b1', remoteFileId: 'drive-file-1' }]);
    mockGDriveDelete.mockRejectedValue(new Error('Drive expired'));
    const book = asBook({ id: 'b1' }) as unknown as Parameters<
      typeof appState.handleRemoveBookFromDrive
    >[0];
    appState.pendingRemoveBook = book;

    await appState.handleRemoveBookFromDrive(book);

    expect(mockHideBook).not.toHaveBeenCalled();
    expect(mockTombstoneBook).not.toHaveBeenCalled();
    expect(appState.pendingRemoveBook).toBeNull();
    expect(appState.readerError).toBe('Drive expired');
  });

  it('handleRemoveBookFromDrive reports tombstone failure without blocking the hide', async () => {
    hydrateSignedInAuthState('user-1');
    mockFetchCatalog.mockResolvedValue([{ id: 'b1', remoteFileId: 'drive-file-1' }]);
    mockTombstoneBook.mockRejectedValue(new Error('Tombstone failed'));
    const book = asBook({ id: 'b1' }) as unknown as Parameters<
      typeof appState.handleRemoveBookFromDrive
    >[0];

    await appState.handleRemoveBookFromDrive(book);

    expect(mockGDriveDelete).toHaveBeenCalledWith('drive-file-1');
    expect(mockHideBook).toHaveBeenCalledWith('b1');
    expect(appState.pendingRemoveBook).toBeNull();
    expect(appState.readerError).toBe('Tombstone failed');
  });

  it('onStatsRefreshNeeded is assigned in the ctor and refreshes stats + streak (D15, REQ-refresh)', async () => {
    hydrateSignedInAuthState('user-1');

    // AppState ctor assigns the reader's stats-refresh hook (D15); firing it
    // after a session save/remote merge must reload stats AND the user-scoped
    // streak (REQ-refresh, SCEN-refresh-1).
    expect(appState.reader.onStatsRefreshNeeded).not.toBeNull();

    await appState.reader.onStatsRefreshNeeded!('b1');

    expect(mockGetReadingStats).toHaveBeenCalledWith('b1');
    expect(mockGetReadingStreak).toHaveBeenCalledWith('b1', 'user-1');
  });

  it('setShelfTab updates tab', () => {
    appState.setShelfTab('favorites');
    expect(appState.shelfQueryState.tab).toBe('favorites');
  });

  it('setShelfSort updates sort', () => {
    appState.setShelfSort('title');
    expect(appState.shelfQueryState.sortKey).toBe('title');
  });

  it('setShelfViewMode toggles grid/list', () => {
    appState.setShelfViewMode('list');
    expect(appState.shelfQueryState.viewMode).toBe('list');
    appState.setShelfViewMode('grid');
    expect(appState.shelfQueryState.viewMode).toBe('grid');
  });

  it('handleShelfQueryInput and clearShelfQuery', () => {
    appState.handleShelfQueryInput({ target: { value: 'search term' } } as unknown as Event);
    expect(appState.shelfQueryState.rawQuery).toBe('search term');
    appState.clearShelfQuery();
    expect(appState.shelfQueryState.rawQuery).toBe('');
  });

  // ─── Bulk import open/close ───

  it('openBulkImportModal sets flag', () => {
    appState.openBulkImportModal();
    expect(appState.isBulkImportOpen).toBe(true);
  });

  it('closeBulkImportModal resets bulk state', () => {
    appState.isBulkImportOpen = true;
    appState.isBulkScanning = true;
    appState.bulkScanError = 'err';
    appState.closeBulkImportModal();
    expect(appState.isBulkImportOpen).toBe(false);
    expect(appState.isBulkScanning).toBe(false);
    expect(appState.bulkScanError).toBeNull();
    expect(appState.bulkImportProgress).toBeNull();
    expect(appState.bulkImportSummary).toBeNull();
  });

  // ─── Settings / config ───

  it('handleReaderSettingsChange updates settings', () => {
    const s: Record<string, unknown> = { themeMode: 'night', brightness: 50 };
    appState.handleReaderSettingsChange(
      s as Parameters<typeof appState.handleReaderSettingsChange>[0],
    );
    expect(appState.readerSettings).toStrictEqual(s);
  });

  it('handleLocaleChange updates locale', () => {
    appState.handleLocaleChange('en');
    expect(appState.locale).toBe('en');
  });

  // ─── Search ───

  it('handleSearchJump sets target', () => {
    appState.handleSearchJump({ locator: 'page=42' } as Parameters<
      typeof appState.handleSearchJump
    >[0]);
    expect(appState.searchTargetLocator).toBe('page=42');
  });

  it('handleReaderLocationContext does not throw', () => {
    expect(() => appState.handleReaderLocationContext()).not.toThrow();
  });

  // ─── Derived state ───

  it('derived values do not throw with empty books', () => {
    expect(() => {
      void appState.continueReadingBooks;
      void appState.myShelfBooks;
      void appState.shelfBooks;
      void appState.shelfWarnings;
      void appState.shelfSortToken;
    }).not.toThrow();
  });

  it('derived selectedShelfBook is null when no details open', () => {
    expect(appState.selectedShelfBook).toBeNull();
  });

  // ─── i18n helper ───

  it('t() calls i18n.t with current locale', () => {
    const result = appState.t('home.title' as Parameters<typeof appState.t>[0]);
    expect(typeof result).toBe('string');
  });

  // ─── Constants ───

  it('SHELF_TAB_OPTIONS has expected entries', () => {
    expect(appState.SHELF_TAB_OPTIONS).toHaveLength(4);
    expect(appState.SHELF_TAB_OPTIONS[0].key).toBe('all');
  });

  it('SHELF_SORT_OPTIONS has expected entries', () => {
    expect(appState.SHELF_SORT_OPTIONS).toHaveLength(6);
  });
});

describe.skip('AppState — Preload edge cases', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetAppState();
  });

  it('preloadedBytes is null by default', () => {
    expect(appState.preloadedBytes).toBeNull();
  });

  it('preloadedBytes persists across state changes until overwritten', () => {
    appState.preloadedBytes = { filePath: '/book.pdf', data: new Uint8Array([1, 2]) };
    appState.route = 'foo' as AppStateRoute;
    expect(appState.preloadedBytes).toEqual({
      filePath: '/book.pdf',
      data: new Uint8Array([1, 2]),
    });
  });

  it('startReading clears preloadedBytes even if readFile fails for EPUB', async () => {
    mockReadFile.mockRejectedValueOnce(new Error('fail'));
    appState.preloadedBytes = { filePath: '/old.epub', data: new Uint8Array([9, 9, 9]) };
    const book = asBook({
      id: 'b1',
      filePath: '/new.epub',
      format: 'epub',
    }) as unknown as Parameters<typeof appState.startReading>[0];
    await appState.startReading(book);
    expect(appState.preloadedBytes).toBeNull();
  });
});

type AppStateRoute = 'home' | 'library' | 'stats' | 'reader' | 'highlights' | 'settings';

// ─── Live-session auth gate (WU1) ──────────────────────────────────

// Identity-only fixture: no provider or Drive tokens (login-drive-separation).
function makeSession(overrides: Record<string, unknown> = {}) {
  return {
    access_token: 'access-123',
    refresh_token: 'refresh-123',
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    user: { id: 'user-1', email: 'user@example.com', user_metadata: {} },
    ...overrides,
  };
}

function hydrateSignedInAuthState(userId = 'user-1'): void {
  authState.setSupabaseSession({
    accessToken: 'access-123',
    refreshToken: 'refresh-123',
    expiresAt: Date.now() + 3_600_000,
    userId,
    email: 'user@example.com',
    displayName: null,
    photoUrl: null,
  });
}

describe.skip('AppState — auth lifecycle (live-session gate)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetAppState();
    authState.clearSupabaseSession();
    mockRestoreSession.mockResolvedValue(null);
    mockSignInAnonymously.mockResolvedValue(undefined);
    mockSyncMetadata.mockResolvedValue(undefined);
    mockGetLiveSession.mockReturnValue(null);
    mockHasLiveSession.mockReturnValue(false);
    mockLoadPersistedAuth.mockResolvedValue(null);
    capturedAuthHandler = null;
  });

  it('SIGNED_OUT clears authState + live cache, halts realtime, routes to welcome (one cycle)', async () => {
    const unsubscribeAll = vi
      .spyOn(appState.reader, 'unsubscribeFromAllRemoteChanges')
      .mockImplementation(() => undefined);
    hydrateSignedInAuthState();
    expect(authState.isSignedIn).toBe(true);

    await appState.init();
    expect(capturedAuthHandler).not.toBeNull();

    capturedAuthHandler!('SIGNED_OUT', null);

    expect(authState.isSignedIn).toBe(false);
    expect(authState.userId).toBeNull();
    expect(mockClearLiveSession).toHaveBeenCalledTimes(1);
    expect(unsubscribeAll).toHaveBeenCalledTimes(1);
    expect(appState.route).toBe('welcome');
  });

  it('TOKEN_REFRESHED hydrates tokens only: signed-in stays true, no re-sync', async () => {
    hydrateSignedInAuthState();
    await appState.init();
    expect(capturedAuthHandler).not.toBeNull();

    capturedAuthHandler!('TOKEN_REFRESHED', makeSession({ access_token: 'access-refreshed' }));

    expect(authState.accessToken).toBe('access-refreshed');
    expect(authState.userId).toBe('user-1');
    expect(authState.isSignedIn).toBe(true);
    expect(mockSetLiveSession).toHaveBeenCalledWith(
      expect.objectContaining({ access_token: 'access-refreshed' }),
    );
    // TOKEN_REFRESHED must never re-run startup sync (no double sync)
    expect(mockSyncMetadata).not.toHaveBeenCalled();
    // D4: the token rotation is auth recovery — the breaker pause must clear
    expect(mockResetOutboxBreaker).toHaveBeenCalledTimes(1);
  });

  it('INITIAL_SESSION hydrates an empty authState without starting sync (no double sync)', async () => {
    await appState.init();
    expect(capturedAuthHandler).not.toBeNull();

    capturedAuthHandler!('INITIAL_SESSION', makeSession());

    expect(authState.userId).toBe('user-1');
    expect(authState.accessToken).toBe('access-123');
    expect(mockSetLiveSession).toHaveBeenCalledTimes(1);
    expect(mockSyncMetadata).not.toHaveBeenCalled();
  });

  it('INITIAL_SESSION never overwrites an already-hydrated authState nor double-syncs', async () => {
    const subscribeAll = vi
      .spyOn(appState.reader, 'subscribeToAllRemoteChanges')
      .mockImplementation(() => undefined);
    mockRestoreSession.mockResolvedValue(makeSession());

    await appState.init(); // restore branch: hydrate + startAuthenticatedSync once
    expect(mockSyncMetadata).toHaveBeenCalledTimes(1);
    expect(authState.accessToken).toBe('access-123');
    expect(capturedAuthHandler).not.toBeNull();

    capturedAuthHandler!('INITIAL_SESSION', makeSession({ access_token: 'from-event' }));

    expect(authState.accessToken).toBe('access-123'); // event did not clobber
    expect(authState.userId).toBe('user-1');
    expect(mockSyncMetadata).toHaveBeenCalledTimes(1); // still one sync total
    expect(subscribeAll).toHaveBeenCalledTimes(1);
  });

  it('SIGNED_IN at runtime starts authenticated sync and routes away from welcome', async () => {
    await appState.init();
    expect(appState.route).toBe('welcome');

    capturedAuthHandler!('SIGNED_IN', makeSession());

    expect(mockSetLiveSession).toHaveBeenCalledTimes(1);
    expect(mockSyncMetadata).toHaveBeenCalledTimes(1);
    // D4: fresh tokens clear any auth-class breaker pause from a stale session
    expect(mockResetOutboxBreaker).toHaveBeenCalledTimes(1);
    expect(appState.route).toBe('home');
  });

  it('restore-without-session signs in anonymously exactly once per init (no loop)', async () => {
    mockRestoreSession.mockResolvedValue(null);
    await appState.init();
    expect(mockSignInAnonymously).toHaveBeenCalledTimes(1);
  });

  it('init catch path skips anonymous sign-in when a live session exists (DA-3.1)', async () => {
    mockRestoreSession.mockRejectedValue(new Error('corrupt session file'));
    mockGetLiveSession.mockReturnValue({ user: { id: 'real-user' } } as never);

    await appState.init();

    expect(mockSignInAnonymously).not.toHaveBeenCalled();
    expect(authState.userId).toBeNull();
  });

  it('corrupt session file + real profile in auth.json does not anon-sign-in (DA-3.2/DA-4.3)', async () => {
    // restoreSession() returns null (corrupt supabase-session.json → adapter
    // getItem returns null, never throws) but auth.json holds a real profile.
    mockRestoreSession.mockResolvedValue(null);
    mockLoadPersistedAuth.mockResolvedValue({
      kind: 'supabase',
      session: { access_token: 'real-token', user: { id: 'real-user' } },
    });

    await appState.init();

    // No silent anonymous fallback under a real profile: app stays on the
    // welcome (re-auth) route with no anon session.
    expect(mockSignInAnonymously).not.toHaveBeenCalled();
    expect(authState.userId).toBeNull();
    expect(appState.route).toBe('welcome');
  });
});
