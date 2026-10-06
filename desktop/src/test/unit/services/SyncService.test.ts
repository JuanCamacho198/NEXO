/**
 * Unit tests for SyncService — Task 2.3
 * Tests auth gate, book file sync, and state sync flow.
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';
import {
  catalogSyncStatus,
  clearCatalogSyncStatus,
} from '$lib/shared/stores/catalogSyncStatus.svelte';
import { toastQueue } from '$lib/shared/stores/ToastQueue.svelte';

// ---- Mock control variables (set before each test) ----
let mockIsSignedIn = vi.fn<() => boolean>();
let mockUserId = vi.fn<() => string | null>();
let mockGDriveUpload = vi.fn();
let mockGDriveDownload = vi.fn();
let mockGDriveList = vi.fn();
let mockGDriveReadMarker = vi.fn();
let mockGDriveUploadToFolderPath = vi.fn();
let mockPushState = vi.fn();
let mockPullState = vi.fn();
let mockListStateFiles = vi.fn();
let mockListBooks = vi.fn();
let mockListLibraryBooks = vi.fn();
let mockGetProgress = vi.fn();
let mockUpsertProgress = vi.fn();
let mockUpsertBook = vi.fn();
let mockListHighlights = vi.fn();
let mockListBookmarks = vi.fn();
let mockSaveHighlight = vi.fn();
let mockSaveBookmark = vi.fn();
let mockFileExists = vi.fn();
let mockSaveBookFile = vi.fn();
let mockGetFileBytes = vi.fn();
let mockDeleteHighlight = vi.fn();
let mockDeleteBookmark = vi.fn();
let mockCatalogFindByHash = vi.fn();
let mockCatalogUploadCover = vi.fn();
let mockCatalogUpsertBook = vi.fn();
let mockCatalogTombstone = vi.fn();
let mockCatalogFetchCatalog = vi.fn();
let mockUpsertReadingSession = vi.fn();
let mockDictUpsert = vi.fn();
let mockDictDelete = vi.fn();
const capturedOutboxHandler = vi.hoisted(() => ({
  value: null as
    | ((
        entityType: string,
        entityId: string | null,
        operation: 'UPSERT' | 'DELETE',
        payloadJson: string,
      ) => Promise<void>)
    | null,
}));

// WU2 gate controls (desktop-session-persistence): live-session gate + alert store.
let mockHasLiveSession = vi.fn<() => boolean>();
let mockReportAuthError = vi.fn<(error: unknown) => boolean>();

// ---- Mock layers (must be hoisted by vitest) ----

vi.mock('$lib/services/supabase', () => ({
  hasLiveSession: () => mockHasLiveSession(),
  recheckLiveSession: async () => mockHasLiveSession(),
}));

vi.mock('$lib/shared/stores/syncAlert.svelte', () => ({
  reportAuthError: (error: unknown) => mockReportAuthError(error),
}));

vi.mock('$lib/shared/stores/AuthState.svelte', () => ({
  authState: {
    get isSignedIn(): boolean {
      return mockIsSignedIn();
    },
    get userId(): string | null {
      return mockUserId();
    },
  },
}));

vi.mock('$lib/shared/services/storage/GDriveProvider', () => {
  return {
    GDriveProvider: vi.fn(function () {
      return {
        upload: mockGDriveUpload,
        download: mockGDriveDownload,
        list: mockGDriveList,
        // FR-08 guard port: a null marker means "unknown", so the guard adopts
        // the remote version; writeBinary reuses the upload mock as the file id
        // source so existing ref-persistence assertions stay meaningful.
        readMarker: (...args: unknown[]) => mockGDriveReadMarker(...args),
        writeBinary: (name: string, bytes: Uint8Array) => mockGDriveUpload(name, bytes, name),
        uploadToFolderPath: (...args: unknown[]) => mockGDriveUploadToFolderPath(...args),
      };
    }),
  };
});

vi.mock('$lib/shared/services/GoogleDriveStateSync', () => ({
  GoogleDriveStateSync: {
    pushState: (...args: unknown[]) => mockPushState(...args),
    pullState: (...args: unknown[]) => mockPullState(...args),
    listStateFiles: () => mockListStateFiles(),
  },
}));

vi.mock('$lib/shared/api/tauriClient', () => ({
  listBooks: () => mockListBooks(),
  getProgress: (bookId: string) => mockGetProgress(bookId),
  upsertProgress: (p: unknown) => mockUpsertProgress(p),
  upsertBook: (b: unknown) => mockUpsertBook(b),
  listHighlights: (bookId?: string) => mockListHighlights(bookId),
  listBookmarks: (bookId?: string) => mockListBookmarks(bookId),
  saveHighlight: (h: unknown) => mockSaveHighlight(h),
  saveBookmark: (b: unknown) => mockSaveBookmark(b),
  deleteHighlight: (id: string) => mockDeleteHighlight(id),
  deleteBookmark: (id: string) => mockDeleteBookmark(id),
  fileExists: (path: string) => mockFileExists(path),
  saveBookFile: (id: string, data: number[]) => mockSaveBookFile(id, data),
  getFileBytes: (path: string) => mockGetFileBytes(path),
  listLibraryBooks: () => mockListLibraryBooks(),
  // NOTIF-02: the notificationCenter persists through these. No-op here; this
  // suite only asserts sync behaviour, not tray history.
  listNotifications: async () => [],
  saveNotification: async () => {},
  markNotificationRead: async () => false,
  markAllNotificationsRead: async () => 0,
  clearAllNotifications: async () => 0,
  pruneNotifications: async () => 0,
}));

vi.mock('$lib/shared/sync/SupabaseBookCatalogSync', async () => {
  const actual = await vi.importActual<typeof import('$lib/shared/sync/SupabaseBookCatalogSync')>(
    '$lib/shared/sync/SupabaseBookCatalogSync',
  );
  return {
    ...actual,
    SupabaseBookCatalogSync: vi.fn(function () {
      return {
        findByHash: mockCatalogFindByHash,
        uploadCover: mockCatalogUploadCover,
        upsertBook: mockCatalogUpsertBook,
        tombstoneBook: mockCatalogTombstone,
        fetchCatalog: mockCatalogFetchCatalog,
      };
    }),
  };
});

vi.mock('$lib/shared/sync/SupabaseProgressSync', () => ({
  SupabaseProgressSync: vi.fn(function () {
    return {
      upsertProgress: mockUpsertProgress,
      upsertReadingSession: mockUpsertReadingSession,
    };
  }),
}));

vi.mock('$lib/shared/sync/SupabaseDictionarySync', () => ({
  SupabaseDictionarySync: vi.fn(function () {
    return {
      upsert: mockDictUpsert,
      delete: mockDictDelete,
    };
  }),
}));

vi.mock('$lib/shared/outbox/SyncOutboxService', () => ({
  SyncOutboxService: vi.fn(function (this: {
    setHandler: (h: (typeof capturedOutboxHandler)['value']) => void;
    start: () => void;
  }) {
    this.setHandler = (h) => {
      capturedOutboxHandler.value = h;
    };
    this.start = () => undefined;
  }),
}));

// Dynamic import
let SyncService: typeof import('$lib/shared/services/SyncService').SyncService;

beforeAll(async () => {
  const mod = await import('$lib/shared/services/SyncService');
  SyncService = mod.SyncService;
});

beforeEach(() => {
  vi.clearAllMocks();
  // Defaults
  mockIsSignedIn.mockReturnValue(false);
  mockHasLiveSession.mockReturnValue(true);
  mockReportAuthError.mockReturnValue(false);
  mockListBooks.mockResolvedValue([]);
  mockListLibraryBooks.mockResolvedValue([]);
  mockGetProgress.mockResolvedValue(null);
  mockListHighlights.mockResolvedValue([]);
  mockListBookmarks.mockResolvedValue([]);
  mockGDriveList.mockResolvedValue([]);
  mockGDriveDownload.mockRejectedValue(new Error('not found'));
  mockGDriveUpload.mockResolvedValue('file-id');
  mockGDriveReadMarker.mockResolvedValue(null);
  mockGDriveUploadToFolderPath.mockResolvedValue('manifest-id');
  mockPushState.mockResolvedValue(undefined);
  mockPullState.mockResolvedValue({ progress: null, highlights: [], bookmarks: [] });
  mockListStateFiles.mockResolvedValue([]);
  mockFileExists.mockResolvedValue(true);
  mockUpsertProgress.mockResolvedValue(undefined);
  mockUpsertBook.mockResolvedValue(undefined);
  mockSaveHighlight.mockResolvedValue(undefined);
  mockSaveBookmark.mockResolvedValue(undefined);
  mockDeleteHighlight.mockResolvedValue(undefined);
  mockDeleteBookmark.mockResolvedValue(undefined);
  mockSaveBookFile.mockResolvedValue(undefined);
  mockGetFileBytes.mockResolvedValue([1, 2, 3]);
  // A signed-in session always carries a userId (mirrors the real authState).
  mockUserId.mockReturnValue('user-1');
  mockCatalogFindByHash.mockResolvedValue(null);
  mockCatalogUploadCover.mockResolvedValue(null);
  mockCatalogUpsertBook.mockResolvedValue(undefined);
  mockCatalogTombstone.mockResolvedValue(undefined);
  mockCatalogFetchCatalog.mockResolvedValue([]);
  mockUpsertReadingSession.mockResolvedValue(undefined);
  mockDictUpsert.mockResolvedValue(undefined);
  mockDictDelete.mockResolvedValue(undefined);
});

function makeLocalBook(id: string, title: string, filePath: string) {
  return {
    id,
    title,
    author: 'Test Author',
    filePath,
    format: 'epub',
    syncStatus: 'local',
    currentPage: 0,
    totalPages: 0,
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-06-01T00:00:00Z',
  };
}

describe('SyncService — auth gate', () => {
  it('syncMetadata does nothing when not signed in', async () => {
    mockIsSignedIn.mockReturnValue(false);

    await SyncService.syncMetadata();

    expect(mockListBooks).not.toHaveBeenCalled();
    expect(mockGDriveList).not.toHaveBeenCalled();
  });

  it('syncMetadata proceeds when signed in', async () => {
    mockIsSignedIn.mockReturnValue(true);

    await SyncService.syncMetadata();

    expect(mockListBooks).toHaveBeenCalled();
  });

  it('shares one in-flight metadata sync across concurrent callers', async () => {
    mockIsSignedIn.mockReturnValue(true);
    let releaseDriveList!: () => void;
    mockGDriveList.mockImplementation(
      () =>
        new Promise<string[]>((resolve) => {
          releaseDriveList = () => resolve([]);
        }),
    );

    const first = SyncService.syncMetadata();
    const second = SyncService.syncMetadata();

    // The async live-session gate adds microtask hops before syncBooks reaches
    // gdrive.list — flush them so the shared in-flight memo is exercised.
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();

    expect(mockGDriveList).toHaveBeenCalledTimes(1);
    releaseDriveList();
    await Promise.all([first, second]);
  });

  it('no-ops when the live session is stale even if authState claims signed-in (DA-1.2)', async () => {
    mockIsSignedIn.mockReturnValue(true);
    mockHasLiveSession.mockReturnValue(false);

    await SyncService.syncMetadata();

    expect(mockListBooks).not.toHaveBeenCalled();
    expect(mockGDriveList).not.toHaveBeenCalled();
    expect(mockReportAuthError).not.toHaveBeenCalled();
  });

  it('surfaces typed AUTH_REQUIRED from a Drive failure to the alert store (SR-3)', async () => {
    mockIsSignedIn.mockReturnValue(true);
    mockHasLiveSession.mockReturnValue(true);
    const authErr = Object.assign(
      new Error('Google Drive access expired. Please sign in with Google again.'),
      { code: 'AUTH_REQUIRED', retryable: false },
    );
    mockGDriveList.mockRejectedValue(authErr);

    await SyncService.syncMetadata();

    expect(mockReportAuthError).toHaveBeenCalledWith(authErr);
  });

  it('outbox handler silently skips rows without a live session (SR-1: no throw, no request)', async () => {
    mockHasLiveSession.mockReturnValue(false);
    mockUserId.mockReturnValue('user-1');
    SyncService.setupOutboxProcessor();

    await expect(
      capturedOutboxHandler.value!(
        'BOOK',
        'book-1',
        'UPSERT',
        JSON.stringify({ title: 'Imported Book' }),
      ),
    ).resolves.toBeUndefined();

    expect(mockCatalogUpsertBook).not.toHaveBeenCalled();
    expect(mockGDriveUpload).not.toHaveBeenCalled();
  });
});

describe('SyncService — book file sync', () => {
  it('downloads missing book files from Drive', async () => {
    mockIsSignedIn.mockReturnValue(true);

    const localBook = makeLocalBook('book-1', 'Test Book', '/tmp/book-1.epub');
    mockListBooks.mockResolvedValue([localBook]);
    mockGDriveList.mockResolvedValue(['book-1.epub']); // remote has book file
    mockFileExists.mockResolvedValue(false); // NOT locally

    // State sync shouldn't interfere — no remote state
    // pullState will download 'book-1_state.json' which fails (default reject)
    // so it returns local state. But mockGDriveDownload is shared.
    // Since syncState calls GoogleDriveStateSync (mocked), the mockPullState
    // intercepts and prevents actual download. Only syncBooks calls gdrive.download.

    // Need to make the shared mockGDriveDownload work for syncBooks but not break state sync.
    // Since state sync uses mock GoogleDriveStateSync (not real gdrive), this is fine.
    // The mockGoogleDriveStateSync's pullState doesn't call gdrive.download at all!

    // Set up download for the book file (syncBooks path)
    const bookBytes = new Uint8Array([1, 2, 3]);

    // Reset mockGDriveDownload before syncBooks runs
    let downloadCallCount = 0;
    mockGDriveDownload.mockImplementation(async (_name: string) => {
      downloadCallCount++;
      return bookBytes;
    });

    await SyncService.syncMetadata();

    // syncBooks should have called download for the book file
    expect(downloadCallCount).toBeGreaterThanOrEqual(1);
    // saveBookFile should have been called
    expect(mockSaveBookFile).toHaveBeenCalled();
  });

  it('skips download when file exists locally', async () => {
    mockIsSignedIn.mockReturnValue(true);

    const localBook = makeLocalBook('book-2', 'Existing Book', '/tmp/book-2.epub');
    mockListBooks.mockResolvedValue([localBook]);
    mockGDriveList.mockResolvedValue(['book-2.epub']);
    mockFileExists.mockResolvedValue(true);

    let downloadBookCallCount = 0;
    mockGDriveDownload.mockImplementation(async (name: string) => {
      // Only count book file downloads, not state.json downloads
      if (!name.endsWith('_state.json')) {
        downloadBookCallCount++;
      }
      return new Uint8Array();
    });

    await SyncService.syncMetadata();

    // No book file should be downloaded since it exists locally
    expect(downloadBookCallCount).toBe(0);
    expect(mockSaveBookFile).not.toHaveBeenCalled();
  });
});

describe('SyncService — outbox BOOK handler remote-ref persistence (DRP-1/DRP-4)', () => {
  const bookPayload = (overrides: Record<string, unknown> = {}) =>
    JSON.stringify({
      title: 'Imported Book',
      author: 'Author',
      format: 'epub',
      content_hash: null,
      importedAt: '2025-01-01T00:00:00Z',
      updatedAt: '2025-06-01T00:00:00Z',
      ...overrides,
    });

  beforeEach(() => {
    // Handler is registered once (setupOutboxProcessor is idempotent) and its
    // closure reads the current mock functions at call time.
    mockUserId.mockReturnValue('user-1');
    SyncService.setupOutboxProcessor();
  });

  it('RED: uploads the binary, persists remote refs, then succeeds (row delete is outbox contract)', async () => {
    mockListBooks.mockResolvedValue([makeLocalBook('book-1', 'Imported Book', '/tmp/book-1.epub')]);
    mockGDriveUpload.mockResolvedValue('drive-file-1');
    mockGetFileBytes.mockResolvedValue([9, 8, 7]);

    await capturedOutboxHandler.value!('BOOK', 'book-1', 'UPSERT', bookPayload());

    // Binary uploaded under the canonical name
    expect(mockGDriveUpload).toHaveBeenCalledWith(
      'book-1.epub',
      new Uint8Array([9, 8, 7]),
      'book-1.epub',
    );
    // Remote refs persisted with the real Drive file ID (DRP-1)
    expect(mockCatalogUpsertBook).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'book-1',
        remoteProvider: 'google_drive',
        remoteFileId: 'drive-file-1',
        remotePath: 'Nexo/books/book-1.epub',
        remoteName: 'book-1.epub',
        protocolVersion: 1,
        recoveryProtocol: 'recovery_protocol_v1',
      }),
    );
  });

  it('RED: upload failure → handler rejects, NO refs written, NO partial upsert', async () => {
    mockListBooks.mockResolvedValue([makeLocalBook('book-1', 'Imported Book', '/tmp/book-1.epub')]);
    mockGDriveUpload.mockRejectedValue(new Error('network drop'));

    await expect(
      capturedOutboxHandler.value!('BOOK', 'book-1', 'UPSERT', bookPayload()),
    ).rejects.toThrow('network drop');

    expect(mockCatalogUpsertBook).not.toHaveBeenCalled();
  });

  it('RED: content-hash dedup still short-circuits before upload (no Drive file for duplicate)', async () => {
    mockCatalogFindByHash.mockResolvedValue({ id: 'existing', title: 'Same Book' });

    await capturedOutboxHandler.value!(
      'BOOK',
      'book-1',
      'UPSERT',
      bookPayload({ content_hash: 'sha256:dup' }),
    );

    expect(mockGDriveUpload).not.toHaveBeenCalled();
    expect(mockCatalogUpsertBook).not.toHaveBeenCalled();
  });
});

describe('SyncService — syncBooks persists remote refs for local-only uploads (DRP-1)', () => {
  it('RED: captures the upload fileId and persists refs on the row', async () => {
    mockIsSignedIn.mockReturnValue(true);
    const localBook = makeLocalBook('book-9', 'Local Only', '/tmp/book-9.epub');
    mockListBooks.mockResolvedValue([localBook]);
    mockGDriveList.mockResolvedValue([]); // no remote files → upload path
    mockFileExists.mockResolvedValue(true);
    mockGetFileBytes.mockResolvedValue([1, 2, 3]);
    mockGDriveUpload.mockResolvedValue('drive-file-9');

    await SyncService.syncMetadata();

    expect(mockGDriveUpload).toHaveBeenCalledWith(
      'book-9.epub',
      new Uint8Array([1, 2, 3]),
      'book-9.epub',
    );
    expect(mockCatalogUpsertBook).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'book-9',
        remoteFileId: 'drive-file-9',
        remoteProvider: 'google_drive',
        protocolVersion: 1,
        recoveryProtocol: 'recovery_protocol_v1',
      }),
    );
  });
});

describe('SyncService — syncBookCatalog reconciles books missing Drive refs (DRP-1 gap)', () => {
  it('uploads binary + persists remote refs when the catalog row has no remoteProvider', async () => {
    mockIsSignedIn.mockReturnValue(true);
    // Local book exists with a file on disk.
    const localBook = makeLocalBook('book-gap', 'Gap Book', '/tmp/book-gap.epub');
    mockListBooks.mockResolvedValue([localBook]);
    // Library listing provides coverPath for the same book.
    mockListLibraryBooks.mockResolvedValue([{ ...localBook, coverPath: null }]);
    // Remote catalog already has the row (metadata-only import), but no Drive ref.
    mockCatalogFetchCatalog.mockResolvedValue([
      {
        id: 'book-gap',
        userId: 'user-1',
        title: 'Gap Book',
        format: 'epub',
        filePath: null,
        coverUrl: null,
        lifecycle: 'imported',
        remoteProvider: null,
        remoteFileId: null,
        remotePath: null,
        remoteName: null,
        protocolVersion: null,
        catalogVersion: 1,
        recoveryProtocol: 'legacy',
      },
    ]);
    mockGDriveUpload.mockResolvedValue('drive-file-gap');

    await SyncService.syncMetadata();

    // Binary upload under the canonical name, then refs persisted on the row.
    expect(mockGDriveUpload).toHaveBeenCalledWith(
      'book-gap.epub',
      new Uint8Array([1, 2, 3]),
      'book-gap.epub',
    );
    expect(mockCatalogUpsertBook).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'book-gap',
        remoteFileId: 'drive-file-gap',
        remoteProvider: 'google_drive',
        protocolVersion: 1,
        recoveryProtocol: 'recovery_protocol_v1',
      }),
    );
  });

  it('skips binary upload when the catalog row already has a remote ref', async () => {
    mockIsSignedIn.mockReturnValue(true);
    const localBook = makeLocalBook('book-ok', 'Ok Book', '/tmp/book-ok.epub');
    mockListBooks.mockResolvedValue([localBook]);
    mockListLibraryBooks.mockResolvedValue([{ ...localBook, coverPath: null }]);
    // File already on Drive → syncBooks() skips it; only the catalog reconcile runs.
    mockGDriveList.mockResolvedValue(['book-ok.epub']);
    mockCatalogFetchCatalog.mockResolvedValue([
      {
        id: 'book-ok',
        userId: 'user-1',
        title: 'Ok Book',
        format: 'epub',
        filePath: null,
        coverUrl: null,
        lifecycle: 'imported',
        remoteProvider: 'google_drive',
        remoteFileId: 'drive-file-ok',
        remotePath: 'Nexo/Books/book-ok.epub',
        remoteName: 'book-ok.epub',
        protocolVersion: 1,
        catalogVersion: 1,
        recoveryProtocol: 'recovery_protocol_v1',
      },
    ]);

    await SyncService.syncMetadata();

    expect(mockGDriveUpload).not.toHaveBeenCalled();
    expect(mockCatalogUpsertBook).not.toHaveBeenCalled();
  });
});

describe('SyncService — state sync (PR2 Supabase SoT — Drive hot removed)', () => {
  it('does not push/pull Drive state when Supabase is SoT (hot)', async () => {
    mockIsSignedIn.mockReturnValue(true);

    const localBook = makeLocalBook('book-3', 'State Book', '/tmp/book-3.epub');
    mockListBooks.mockResolvedValue([localBook]);

    mockGetProgress.mockResolvedValue({
      id: 'prog-1',
      bookId: 'book-3',
      cfiLocation: '/6/4',
      percentage: 42,
      updatedAt: '2025-06-01T00:00:00Z',
    });

    mockPullState.mockResolvedValue({
      progress: {
        id: 'prog-1',
        book_id: 'book-3',
        cfi_location: '/6/10',
        percentage: 75,
        updated_at: 2000000,
      },
      highlights: [],
      bookmarks: [],
    });

    mockGDriveList.mockResolvedValue([]);

    await SyncService.syncMetadata();

    // PR2: Supabase is sole hot SoT — Drive hot push/pull must not run; outbox + Realtime own state.
    expect(mockPushState).not.toHaveBeenCalled();
    expect(mockPullState).not.toHaveBeenCalled();
  });

  it('does not sync highlights via Drive state.json when Supabase SoT', async () => {
    mockIsSignedIn.mockReturnValue(true);

    const localBook = makeLocalBook('book-4', 'Rich Book', '/tmp/book-4.epub');
    mockListBooks.mockResolvedValue([localBook]);

    mockListHighlights.mockResolvedValue([
      {
        id: 'h1',
        bookId: 'book-4',
        text: 'Great quote',
        color: '#ffff00',
        pageNumber: 10,
        note: null,
        createdAt: '2025-01-01T00:00:00Z',
      },
    ]);

    mockListBookmarks.mockResolvedValue([
      {
        id: 'b1',
        bookId: 'book-4',
        pageNumber: 15,
        title: 'Chapter 2',
        createdAt: '2025-01-01T00:00:00Z',
      },
    ]);

    mockGetProgress.mockResolvedValue(null);

    mockPullState.mockResolvedValue({
      progress: null,
      highlights: [
        {
          id: 'h2',
          book_id: 'book-4',
          cfi_range: '/6/20',
          text_content: 'Remote quote',
          note: 'my note',
          color: '#00ff00',
          updated_at: 3000000,
          deleted_at: null,
          recordId: 'h2',
          updatedAtEpochMillis: 3000000,
          deletedAtEpochMillis: null,
        },
      ],
      bookmarks: [],
    });

    mockGDriveList.mockResolvedValue([]);

    await SyncService.syncMetadata();

    // Drive hot path retired — highlights go via Supabase outbox/Realtime, not state.json
    expect(mockPushState).not.toHaveBeenCalled();
    expect(mockPullState).not.toHaveBeenCalled();
  });
});

describe('SyncService — outbox READING_SESSION handler (D10)', () => {
  beforeEach(() => {
    mockUserId.mockReturnValue('user-1');
    SyncService.setupOutboxProcessor();
  });

  const sessionPayload = (overrides: Record<string, unknown> = {}) =>
    JSON.stringify({
      id: 'sess_abc123',
      bookId: 'book-1',
      startedAt: '2026-08-13T10:00:00.000Z',
      endedAt: '2026-08-13T10:02:00.000Z',
      durationMinutes: 2,
      date: '2026-08-13T00:00:00.000Z',
      userId: 'user-1',
      updatedAtEpochMillis: 1786615200000,
      startPercentage: 10,
      endPercentage: 30,
      ...overrides,
    });

  it('READING_SESSION&&UPSERT → upserts remote row with fresh userId, device desktop, payload-clock updatedAt', async () => {
    await capturedOutboxHandler.value!('READING_SESSION', 'book-1', 'UPSERT', sessionPayload());

    expect(mockUpsertReadingSession).toHaveBeenCalledWith({
      id: 'sess_abc123',
      userId: 'user-1',
      bookId: 'book-1',
      startedAt: '2026-08-13T10:00:00.000Z',
      durationMinutes: 2,
      date: '2026-08-13T00:00:00.000Z',
      device: 'desktop',
      // updated_at derived from the payload LWW clock (NOT now()) — SCEN-pull-3.
      updatedAt: new Date(1786615200000).toISOString(),
      startPercentage: 10,
      endPercentage: 30,
    });
    expect(mockUpsertReadingSession).toHaveBeenCalledTimes(1);
  });

  it('uses the FRESH live-session userId, not a stale payload userId', async () => {
    // The live session gate guarantees the cached user matches authState (D3);
    // userId is read from the session, never trusted from the outbox payload.
    mockUserId.mockReturnValue('user-2');
    await capturedOutboxHandler.value!(
      'READING_SESSION',
      'book-1',
      'UPSERT',
      sessionPayload({ userId: 'user-stale' }),
    );

    expect(mockUpsertReadingSession).toHaveBeenCalledWith(
      expect.objectContaining({ userId: 'user-2' }),
    );
  });

  it('propagates upsert failure so the existing backoff/retry handles the row (SCEN-push-3)', async () => {
    mockUpsertReadingSession.mockRejectedValueOnce(new Error('network drop'));

    await expect(
      capturedOutboxHandler.value!('READING_SESSION', 'book-1', 'UPSERT', sessionPayload()),
    ).rejects.toThrow('network drop');
  });
});

describe('SyncService — outbox DICTIONARY_WORD handler (REQ-DSI-002)', () => {
  beforeEach(() => {
    mockUserId.mockReturnValue('user-1');
    SyncService.setupOutboxProcessor();
  });

  const dictDeletePayload = (overrides: Record<string, unknown> = {}) =>
    JSON.stringify({
      userId: 'user-1',
      updatedAt: '2025-06-01T00:00:00Z',
      deletedAt: '2025-06-01T00:00:00Z',
      normalizedWord: 'abyss',
      ...overrides,
    });

  it('DELETE targets the normalized word carried in the payload, not the entity id', async () => {
    await capturedOutboxHandler.value!('DICTIONARY_WORD', 'local-1', 'DELETE', dictDeletePayload());

    expect(mockDictDelete).toHaveBeenCalledWith('abyss');
    expect(mockDictDelete).toHaveBeenCalledTimes(1);
  });

  it('DELETE falls back to an empty natural key when the payload omits normalizedWord', async () => {
    await capturedOutboxHandler.value!(
      'DICTIONARY_WORD',
      'local-1',
      'DELETE',
      dictDeletePayload({ normalizedWord: undefined }),
    );

    expect(mockDictDelete).toHaveBeenCalledWith('');
  });
});

describe('SyncService — background catalog sync failure status (aggregate, no toast)', () => {
  beforeEach(() => {
    clearCatalogSyncStatus();
  });

  it('records the typed per-book aggregate and still syncs the surviving books', async () => {
    mockIsSignedIn.mockReturnValue(true);
    const bad = makeLocalBook('book-bad', 'Bad', '/tmp/book-bad.epub');
    const good = makeLocalBook('book-good', 'Good', '/tmp/book-good.epub');
    mockListBooks.mockResolvedValue([bad, good]);
    mockListLibraryBooks.mockResolvedValue([
      { ...bad, coverPath: null },
      { ...good, coverPath: null },
    ]);
    mockCatalogFetchCatalog.mockResolvedValue([]);
    mockCatalogUpsertBook.mockImplementation((b: { id: string }) =>
      b.id === 'book-bad' ? Promise.reject(new Error('network drop')) : Promise.resolve(),
    );
    const toastsBefore = toastQueue.items.length;

    await SyncService.syncBookCatalog();

    // The surviving book still reached the catalog — one bad book never aborts the pass.
    expect(mockCatalogUpsertBook).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'book-good' }),
    );
    // Typed aggregate: one failing book with its stable code, not a log per book.
    expect(catalogSyncStatus.report).toEqual({ failedCount: 1, codes: ['UNAVAILABLE'] });
    // A background cycle is silent: the status line is the surface, never a toast.
    expect(toastQueue.items.length).toBe(toastsBefore);
  });

  it('reports nothing when every book syncs', async () => {
    mockIsSignedIn.mockReturnValue(true);
    const book = makeLocalBook('book-ok', 'Ok', '/tmp/book-ok.epub');
    mockListBooks.mockResolvedValue([book]);
    mockListLibraryBooks.mockResolvedValue([{ ...book, coverPath: null }]);
    mockCatalogFetchCatalog.mockResolvedValue([]);
    mockCatalogUpsertBook.mockResolvedValue(undefined);

    await SyncService.syncBookCatalog();

    expect(mockCatalogUpsertBook).toHaveBeenCalledWith(expect.objectContaining({ id: 'book-ok' }));
    expect(catalogSyncStatus.report).toBeNull();
  });

  it('clears a previous failure once the same book later syncs', async () => {
    mockIsSignedIn.mockReturnValue(true);
    const book = makeLocalBook('book-later', 'Later', '/tmp/book-later.epub');
    mockListBooks.mockResolvedValue([book]);
    mockListLibraryBooks.mockResolvedValue([{ ...book, coverPath: null }]);
    mockCatalogFetchCatalog.mockResolvedValue([]);

    mockCatalogUpsertBook.mockRejectedValueOnce(new Error('network drop'));
    await SyncService.syncBookCatalog();
    expect(catalogSyncStatus.report?.failedCount).toBe(1);

    mockCatalogUpsertBook.mockResolvedValue(undefined);
    await SyncService.syncBookCatalog();
    expect(catalogSyncStatus.report).toBeNull();
  });
});
