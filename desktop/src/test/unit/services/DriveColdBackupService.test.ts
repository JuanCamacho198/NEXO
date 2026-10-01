import { describe, it, expect, vi, beforeEach } from 'vitest';

const mockUpload = vi.fn();
const mockDownload = vi.fn();
const mockList = vi.fn();
let mockHasLiveSession = vi.fn(() => true);
const mockUpsertBook = vi.fn();
const mockUpsertProgress = vi.fn();
const mockUpsertHighlight = vi.fn();
const mockUpsertBookmark = vi.fn();
const mockUpsertReadingSession = vi.fn();
const mockFetchReadingSessions = vi.fn(() => Promise.resolve([]));
const mockIsDriveAuthorized = vi.hoisted(() => vi.fn());

vi.mock('$lib/shared/services/DriveConnectService', () => ({
  isDriveAuthorized: mockIsDriveAuthorized,
}));

vi.mock('$lib/shared/services/storage/GDriveProvider', () => ({
  GDriveProvider: vi.fn(function () {
    return { upload: mockUpload, download: mockDownload, list: mockList };
  }),
}));

vi.mock('$lib/services/supabase', () => ({
  hasLiveSession: () => mockHasLiveSession(),
  recheckLiveSession: () => Promise.resolve(true),
  getSessionClient: () => ({ from: vi.fn(), channel: vi.fn(), storage: { from: vi.fn() } }),
}));

vi.mock('$lib/shared/sync/SupabaseProgressSync', () => ({
  SupabaseProgressSync: vi.fn(function () {
    return {
      upsertProgress: mockUpsertProgress,
      upsertHighlight: mockUpsertHighlight,
      upsertBookmark: mockUpsertBookmark,
      upsertReadingSession: mockUpsertReadingSession,
      fetchReadingSessions: mockFetchReadingSessions,
    };
  }),
}));

vi.mock('$lib/shared/sync/SupabaseBookCatalogSync', () => ({
  SupabaseBookCatalogSync: vi.fn(function () {
    return { upsertBook: mockUpsertBook };
  }),
}));

vi.mock('$lib/shared/api/tauriClient', () => ({
  listLibraryBooks: vi.fn(() => Promise.resolve([])),
  listBooks: vi.fn(() => Promise.resolve([])),
  getProgress: vi.fn(() => Promise.resolve(null)),
  listHighlights: vi.fn(() => Promise.resolve([])),
  listBookmarks: vi.fn(() => Promise.resolve([])),
}));

// Import after mocks
let DriveColdBackupService: typeof import('$lib/shared/services/DriveColdBackupService').DriveColdBackupService;
beforeEach(async () => {
  vi.clearAllMocks();
  mockUpsertBook.mockReset();
  mockHasLiveSession.mockReturnValue(true);
  mockIsDriveAuthorized.mockResolvedValue(true);
  mockUpload.mockResolvedValue('id');
  mockDownload.mockResolvedValue(
    new TextEncoder().encode(
      JSON.stringify({
        version: 1,
        exportedAt: Date.now(),
        books: [],
        progress: [],
        highlights: [],
        bookmarks: [],
        sessions: [],
      }),
    ),
  );
  mockList.mockResolvedValue([]);
  const mod = await import('$lib/shared/services/DriveColdBackupService');
  DriveColdBackupService = mod.DriveColdBackupService;
});

describe('DriveColdBackupService — cold export/import', () => {
  it('exportColdBackup writes JSON via GDriveProvider.upload (parents only on create — delegated)', async () => {
    await DriveColdBackupService.exportColdBackup('u1');
    expect(mockUpload).toHaveBeenCalled();
    const [id, bytes] = mockUpload.mock.calls[0];
    expect(id).toBe('nexo_cold_backup.json');
    const json = JSON.parse(new TextDecoder().decode(bytes as Uint8Array));
    expect(json.version).toBe(1);
    expect(Array.isArray(json.books)).toBe(true);
  });

  it('importColdBackup is FK-ordered books→progress→highlights→bookmarks→sessions chunk100 idempotent', async () => {
    const books = Array.from({ length: 250 }, (_, i) => ({
      id: `b${i}`,
      userId: 'u1',
      title: `Book ${i}`,
      author: null,
      format: 'epub',
      importedAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }));
    const progress = books.slice(0, 250).map((b) => ({
      userId: 'u1',
      bookId: b.id,
      cfiLocation: '/6/4',
      percentage: 42,
      updatedAt: new Date().toISOString(),
    }));
    const highlights = Array.from({ length: 5 }, (_, i) => ({
      id: `h${i}`,
      userId: 'u1',
      bookId: 'b0',
      cfiRange: '/6/4',
      textContent: 'x',
      color: 'yellow',
      updatedAt: new Date().toISOString(),
    }));
    const bookmarks = Array.from({ length: 3 }, (_, i) => ({
      id: `bm${i}`,
      userId: 'u1',
      bookId: 'b0',
      cfiLocation: '/6/4',
      updatedAt: new Date().toISOString(),
    }));
    const backup = {
      version: 1,
      exportedAt: Date.now(),
      books,
      progress,
      highlights,
      bookmarks,
      sessions: [],
    };
    mockDownload.mockResolvedValue(new TextEncoder().encode(JSON.stringify(backup)));

    const result = await DriveColdBackupService.importColdBackup('u1');

    // chunk 100: books 250 → 3 chunks, progress 250 → 3 chunks, etc.
    expect(mockUpsertBook).toHaveBeenCalledTimes(250);
    expect(mockUpsertProgress).toHaveBeenCalledTimes(250);
    expect(mockUpsertHighlight).toHaveBeenCalledTimes(5);
    expect(mockUpsertBookmark).toHaveBeenCalledTimes(3);
    // FK order: books first, then progress, etc. Verify first call is book, last is bookmark/highlight
    expect(mockUpsertBook.mock.invocationCallOrder[0]).toBeLessThan(
      mockUpsertProgress.mock.invocationCallOrder[0],
    );
    expect(result.totalImported).toBe(250 + 250 + 5 + 3);
  });

  it('importColdBackup second run is idempotent (onConflict) — zero FK errors', async () => {
    const backup = {
      version: 1,
      exportedAt: Date.now(),
      books: [
        {
          id: 'b1',
          userId: 'u1',
          title: 'T',
          author: null,
          format: 'epub',
          importedAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      ],
      progress: [
        {
          userId: 'u1',
          bookId: 'b1',
          cfiLocation: '/6/4',
          percentage: 10,
          updatedAt: new Date().toISOString(),
        },
      ],
      highlights: [],
      bookmarks: [],
      sessions: [],
    };
    mockDownload.mockResolvedValue(new TextEncoder().encode(JSON.stringify(backup)));
    const r1 = await DriveColdBackupService.importColdBackup('u1');
    const r2 = await DriveColdBackupService.importColdBackup('u1');
    expect(r1.totalImported).toBe(2);
    expect(r2.totalImported).toBe(2);
    expect(mockUpsertBook).toHaveBeenCalledTimes(2);
  });

  it('partial failure collects typed failures (record id + stable code) and still imports survivors', async () => {
    const iso = new Date().toISOString();
    const backup = {
      version: 1,
      exportedAt: Date.now(),
      books: ['b1', 'b2', 'b3'].map((id) => ({
        id,
        userId: 'u1',
        title: `Book ${id}`,
        author: null,
        format: 'epub',
        importedAt: iso,
        updatedAt: iso,
      })),
      progress: [],
      highlights: [],
      bookmarks: [],
      sessions: [],
    };
    mockDownload.mockResolvedValue(new TextEncoder().encode(JSON.stringify(backup)));
    mockUpsertBook.mockImplementation((row: { id: string }) => {
      if (row.id === 'b2') return Promise.reject(new Error('401 unauthorized token=abc'));
      return Promise.resolve();
    });

    const result = await DriveColdBackupService.importColdBackup('u1');

    // Resilience: every row was attempted, the two survivors imported.
    expect(mockUpsertBook).toHaveBeenCalledTimes(3);
    expect(result.books).toBe(2);
    // Visibility: the failing record is reported once with its id + stable code.
    expect(result.failures).toHaveLength(1);
    expect(result.failures[0].entity).toBe('book');
    expect(result.failures[0].id).toBe('b2');
    expect(result.failures[0].error.code).toBe('AUTH_REQUIRED');
    // Redaction: the token value never survives into the failure message.
    expect(result.failures[0].error.message).toContain('[REDACTED]');
    expect(result.failures[0].error.message).not.toContain('abc');
  });

  it('fully successful import reports no failures', async () => {
    const iso = new Date().toISOString();
    const backup = {
      version: 1,
      exportedAt: Date.now(),
      books: [
        {
          id: 'b1',
          userId: 'u1',
          title: 'Book',
          author: null,
          format: 'epub',
          importedAt: iso,
          updatedAt: iso,
        },
      ],
      progress: [],
      highlights: [],
      bookmarks: [],
      sessions: [],
    };
    mockDownload.mockResolvedValue(new TextEncoder().encode(JSON.stringify(backup)));

    const result = await DriveColdBackupService.importColdBackup('u1');

    expect(result.totalImported).toBe(1);
    expect(result.failures).toEqual([]);
  });

  it('import gated by hasLiveSession — no request when no live session', async () => {
    mockHasLiveSession.mockReturnValue(false);
    const result = await DriveColdBackupService.importColdBackup('u1');
    expect(result.totalImported).toBe(0);
    expect(mockDownload).not.toHaveBeenCalled();
    expect(mockUpsertBook).not.toHaveBeenCalled();
  });
});

describe('DriveColdBackupService — Drive authorization gate (login-drive-separation)', () => {
  it('export unauthorized → typed DRIVE_NOT_CONNECTED (connect path), no upload fires', async () => {
    mockIsDriveAuthorized.mockResolvedValue(false);

    const err = await DriveColdBackupService.exportColdBackup('u1').catch((e) => e);

    expect(err).toMatchObject({ code: 'DRIVE_NOT_CONNECTED', retryable: false });
    expect(String(err.message)).toMatch(/connect google drive in settings/i);
    expect(mockUpload).not.toHaveBeenCalled();
  });

  it('import unauthorized → typed DRIVE_NOT_CONNECTED, no download fires', async () => {
    mockIsDriveAuthorized.mockResolvedValue(false);

    const err = await DriveColdBackupService.importColdBackup('u1').catch((e) => e);

    expect(err).toMatchObject({ code: 'DRIVE_NOT_CONNECTED', retryable: false });
    expect(mockDownload).not.toHaveBeenCalled();
    expect(mockUpsertBook).not.toHaveBeenCalled();
  });

  it('backfill unauthorized → typed DRIVE_NOT_CONNECTED, Drive never listed', async () => {
    mockIsDriveAuthorized.mockResolvedValue(false);

    const err = await DriveColdBackupService.backfillFromDrive('u1').catch((e) => e);

    expect(err).toMatchObject({ code: 'DRIVE_NOT_CONNECTED', retryable: false });
    expect(mockDownload).not.toHaveBeenCalled();
    expect(mockList).not.toHaveBeenCalled();
  });

  it('signed-in but Drive-disconnected degrades to the connect path (not silent zero)', async () => {
    mockHasLiveSession.mockReturnValue(true);
    mockIsDriveAuthorized.mockResolvedValue(false);

    await expect(DriveColdBackupService.importColdBackup('u1')).rejects.toMatchObject({
      code: 'DRIVE_NOT_CONNECTED',
    });
  });

  it('signed-out and disconnected routes to the connect path regardless of session state', async () => {
    mockHasLiveSession.mockReturnValue(false);
    mockIsDriveAuthorized.mockResolvedValue(false);

    await expect(DriveColdBackupService.importColdBackup('u1')).rejects.toMatchObject({
      code: 'DRIVE_NOT_CONNECTED',
    });
  });

  it('authorized + live session proceeds (gate is additive, existing behavior intact)', async () => {
    await DriveColdBackupService.exportColdBackup('u1');
    expect(mockIsDriveAuthorized).toHaveBeenCalled();
    expect(mockUpload).toHaveBeenCalled();
  });
});
