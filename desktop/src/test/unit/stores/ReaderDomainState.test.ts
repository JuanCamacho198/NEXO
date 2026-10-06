import { describe, expect, it, vi, beforeEach } from 'vitest';
import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
import type { ReaderBook } from '$lib/shared/types';

// ─── Hoisted mock factories ───

const mockReadFile = vi.hoisted(() =>
  vi.fn<(...args: unknown[]) => Promise<Uint8Array>>().mockResolvedValue(new Uint8Array([1, 2, 3])),
);

const mockGetProgress = vi.hoisted(() => vi.fn().mockResolvedValue(null));
const mockSaveProgress = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mockSaveReadingSession = vi.hoisted(() =>
  vi.fn().mockResolvedValue({
    id: 'sess_test',
    durationMinutes: 2,
    date: '2026-08-13T00:00:00.000Z',
    updatedAtEpochMillis: 1786615200000,
  }),
);
const mockUpdateBookProgress = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mockSetReadingStatus = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mockFetchBookState = vi.hoisted(() =>
  vi.fn().mockResolvedValue({ progress: null, bookmarks: [], highlights: [] }),
);
const mockUpsertRemoteReadingSessions = vi.hoisted(() => vi.fn().mockResolvedValue(0));
const mockFetchReadingSessions = vi.hoisted(() => vi.fn().mockResolvedValue([]));
const mockSubscribeToReadingSessions = vi.hoisted(() =>
  vi.fn((_cb?: (row: unknown) => void) => () => undefined),
);
const mockGetFileBytes = vi.hoisted(() => vi.fn().mockResolvedValue([1, 2, 3]));
const mockUpsertProgress = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mockAuthState = vi.hoisted(() => ({ userId: null as string | null }));
const mockOutboxAddCoalesced = vi.hoisted(() => vi.fn().mockResolvedValue('row-id'));
const mockOutboxAdd = vi.hoisted(() => vi.fn().mockResolvedValue('row-id'));
const mockPushToast = vi.hoisted(() => vi.fn());

// ─── Module mocks ───

vi.mock('@tauri-apps/plugin-fs', () => ({
  readFile: mockReadFile,
  BaseDirectory: { AppData: 'appData', AppConfig: 'appConfig', AppCache: 'appCache' },
}));

vi.mock('$lib/shared/api/tauriClient', () => ({
  getProgress: mockGetProgress,
  saveProgress: mockSaveProgress,
  saveReadingSession: mockSaveReadingSession,
  updateBookProgress: mockUpdateBookProgress,
  setReadingStatus: mockSetReadingStatus,
  upsertRemoteReadingSessions: mockUpsertRemoteReadingSessions,
  getFileBytes: mockGetFileBytes,
  upsertProgress: mockUpsertProgress,
}));

vi.mock('$lib/shared/stores/AuthState.svelte', () => ({ authState: mockAuthState }));
vi.mock('$lib/shared/sync/SupabaseProgressSync', () => ({
  SupabaseProgressSync: class {
    fetchBookState = mockFetchBookState;
    subscribeToProgress = vi.fn();
    subscribeToBookmarks = vi.fn();
    subscribeToHighlights = vi.fn();
    fetchReadingSessions = mockFetchReadingSessions;
    subscribeToReadingSessions = mockSubscribeToReadingSessions;
  },
}));

vi.mock('$lib/shared/outbox/SyncOutboxDao', () => ({
  SyncOutboxDao: class {
    addCoalesced = mockOutboxAddCoalesced;
    add = mockOutboxAdd;
  },
}));

vi.mock('$lib/shared/stores/ToastQueue.svelte', () => ({ pushToast: mockPushToast }));

// Mock pdfStreaming for startReading PDF path
vi.mock('$lib/features/reader/viewer-pdf/pdfStreaming', () => ({
  createPdfDocument: vi.fn().mockResolvedValue({ numPages: 10 }),
}));

// ─── Helpers ───

const makeBook = (overrides: Partial<ReaderBook> = {}): ReaderBook => ({
  id: 'book-1',
  title: 'Test Book',
  author: 'Test Author',
  filePath: '/test/book.epub',
  format: 'epub',
  currentPage: 0,
  totalPages: 100,
  progressPercentage: 0,
  coverPath: null,
  minutesRead: 0,
  updatedAt: '2026-01-01T00:00:00.000Z',
  createdAt: '2026-01-01T00:00:00.000Z',
  collectionIds: [],
  ...overrides,
});

function resetReaderState(): void {
  readerState.activeReadingBookId = null;
  readerState.cfiLocation = '';
  readerState.percentage = 0;
  readerState.preloadedBytes = null;
  readerState.readerError = null;
  readerState.onStatsRefreshNeeded = null;
  readerState.onPageChangeCallback = null;
  readerState.unsubscribeFromAllRemoteChanges();
  mockAuthState.userId = null;
  mockFetchBookState.mockResolvedValue({ progress: null, bookmarks: [], highlights: [] });
  mockFetchReadingSessions.mockResolvedValue([]);
  mockSubscribeToReadingSessions.mockImplementation(() => () => undefined);
  mockUpsertRemoteReadingSessions.mockResolvedValue(0);
}

// ─── Tests ───

describe('ReaderDomainState', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetReaderState();
  });

  // ─── Initial state ───

  it('has expected initial state', () => {
    expect(readerState.activeReadingBookId).toBeNull();
    expect(readerState.cfiLocation).toBe('');
    expect(readerState.percentage).toBe(0);
    expect(readerState.preloadedBytes).toBeNull();
    expect(readerState.readerError).toBeNull();
    expect(readerState.onStatsRefreshNeeded).toBeNull();
    expect(readerState.onPageChangeCallback).toBeNull();
  });

  // ─── isValidSessionProgressEvent ───

  it('isValidSessionProgressEvent validates correct events', () => {
    const valid = {
      startedAt: new Date(Date.now() - 60000).toISOString(),
      endedAt: new Date().toISOString(),
      durationSeconds: 60,
      startPercentage: 10,
      endPercentage: 50,
    };
    expect(readerState.isValidSessionProgressEvent(valid)).toBe(true);
  });

  it('isValidSessionProgressEvent rejects missing endedAt', () => {
    expect(
      readerState.isValidSessionProgressEvent({
        startedAt: new Date().toISOString(),
        durationSeconds: 0,
      }),
    ).toBe(false);
  });

  it('isValidSessionProgressEvent rejects zero duration', () => {
    expect(
      readerState.isValidSessionProgressEvent({
        startedAt: new Date().toISOString(),
        endedAt: new Date().toISOString(),
        durationSeconds: 0,
      }),
    ).toBe(false);
  });

  it('isValidSessionProgressEvent rejects endedAt before startedAt', () => {
    expect(
      readerState.isValidSessionProgressEvent({
        startedAt: new Date(Date.now() + 60000).toISOString(),
        endedAt: new Date().toISOString(),
        durationSeconds: 60,
      }),
    ).toBe(false);
  });

  it('isValidSessionProgressEvent rejects invalid percentages', () => {
    expect(
      readerState.isValidSessionProgressEvent({
        startedAt: new Date(Date.now() - 60000).toISOString(),
        endedAt: new Date().toISOString(),
        durationSeconds: 60,
        startPercentage: -1,
        endPercentage: 101,
      }),
    ).toBe(false);
  });

  it('isValidSessionProgressEvent accepts events without percentages', () => {
    expect(
      readerState.isValidSessionProgressEvent({
        startedAt: new Date(Date.now() - 60000).toISOString(),
        endedAt: new Date().toISOString(),
        durationSeconds: 60,
      }),
    ).toBe(true);
  });

  // ─── startReading EPUB ───

  it('startReading with EPUB sets activeReadingBookId', async () => {
    const book = makeBook({ id: 'b1', format: 'epub', filePath: '/test.epub' });
    await readerState.startReading(book);
    expect(readerState.activeReadingBookId).toBe('b1');
  });

  it('startReading clears preloadedBytes for EPUB', async () => {
    readerState.preloadedBytes = { filePath: '/old.epub', data: new Uint8Array([9, 9, 9]) };
    const book = makeBook({ id: 'b1', format: 'epub', filePath: '/test.epub' });
    await readerState.startReading(book);
    expect(readerState.preloadedBytes).not.toBeNull();
    expect(readerState.preloadedBytes!.filePath).toBe('/test.epub');
  });

  it('startReading fires readFile for EPUB preload', async () => {
    const book = makeBook({ id: 'b1', format: 'epub', filePath: '/test.epub' });
    await readerState.startReading(book);
    expect(mockGetFileBytes).toHaveBeenCalledWith('/test.epub');
  });

  it('startReading preloadedBytes populated asynchronously for EPUB', async () => {
    mockGetFileBytes.mockResolvedValueOnce([10, 20, 30]);
    const book = makeBook({ id: 'b1', format: 'epub', filePath: '/test.epub' });
    await readerState.startReading(book);
    await vi.waitFor(() => {
      expect(readerState.preloadedBytes).not.toBeNull();
    });
    expect(readerState.preloadedBytes!.filePath).toBe('/test.epub');
    expect(readerState.preloadedBytes!.data).toEqual(new Uint8Array([10, 20, 30]));
  });

  it('startReading preload failure does not throw for EPUB', async () => {
    mockGetFileBytes.mockRejectedValueOnce(new Error('EPUB load failed'));
    const book = makeBook({ id: 'b1', format: 'epub', filePath: '/test.epub' });
    await expect(readerState.startReading(book)).resolves.toBeUndefined();
    expect(readerState.preloadedBytes).toBeNull();
  });

  it('startReading with EPUB calls getProgress', async () => {
    const book = makeBook({ id: 'b1', format: 'epub', filePath: '/test.epub' });
    await readerState.startReading(book);
    expect(mockGetProgress).toHaveBeenCalledWith('b1');
  });

  it('startReading with EPUB restores progress from getProgress', async () => {
    mockGetProgress.mockResolvedValueOnce({
      bookId: 'b1',
      cfiLocation: 'epubcfi(/6/2)',
      percentage: 42,
    });
    const book = makeBook({ id: 'b1', format: 'epub', filePath: '/test.epub' });
    await readerState.startReading(book);
    expect(readerState.cfiLocation).toBe('epubcfi(/6/2)');
    expect(readerState.percentage).toBe(42);
  });

  it('startReading with EPUB handles getProgress failure gracefully', async () => {
    mockGetProgress.mockRejectedValueOnce(new Error('DB error'));
    const book = makeBook({ id: 'b1', format: 'epub', filePath: '/test.epub' });
    await readerState.startReading(book);
    expect(readerState.cfiLocation).toBe('');
    expect(readerState.percentage).toBe(0);
  });

  it('applies a newer remote progress and canonical locator after local open', async () => {
    mockAuthState.userId = 'user-1';
    mockGetProgress
      .mockResolvedValueOnce({
        bookId: 'b1',
        cfiLocation: 'local',
        percentage: 10,
        updatedAt: '2026-01-01T00:00:00.000Z',
      })
      .mockResolvedValueOnce({
        bookId: 'b1',
        cfiLocation: 'local',
        percentage: 10,
        updatedAt: '2026-01-01T00:00:00.000Z',
      });
    let release!: (value: unknown) => void;
    mockFetchBookState.mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve;
      }),
    );
    await readerState.startReading(makeBook({ id: 'b1' }));
    expect(readerState.cfiLocation).toBe('local');
    release({
      progress: {
        bookId: 'b1',
        cfiLocation: 'remote',
        percentage: 80,
        updatedAt: '2026-01-02T00:00:00.000Z',
        locatorJson: '{"href":"chapter-7.xhtml"}',
      },
      bookmarks: [],
      highlights: [],
    });
    await vi.waitFor(() => expect(readerState.cfiLocation).toBe('remote'));
    expect(readerState.locatorJson).toContain('chapter-7');
  });

  it('ignores a stale response from a previous book open', async () => {
    mockAuthState.userId = 'user-1';
    const resolvers: Array<(value: unknown) => void> = [];
    mockFetchBookState.mockImplementation(() => new Promise((resolve) => resolvers.push(resolve)));
    await readerState.startReading(makeBook({ id: 'b1' }));
    await readerState.startReading(makeBook({ id: 'b2' }));
    resolvers[0]({
      progress: {
        bookId: 'b1',
        cfiLocation: 'stale',
        percentage: 90,
        updatedAt: '2026-02-01T00:00:00.000Z',
      },
      bookmarks: [],
      highlights: [],
    });
    await Promise.resolve();
    expect(readerState.activeReadingBookId).toBe('b2');
    expect(readerState.cfiLocation).not.toBe('stale');
  });

  // ─── startReading PDF ───

  it('startReading with PDF sets activeReadingBookId', async () => {
    const book = makeBook({ id: 'b1', format: 'pdf', filePath: '/test.pdf' });
    await readerState.startReading(book);
    expect(readerState.activeReadingBookId).toBe('b1');
  });

  it('startReading fires readFile for PDF preload', async () => {
    const book = makeBook({ id: 'b1', format: 'pdf', filePath: '/test.pdf' });
    await readerState.startReading(book);
    expect(mockGetFileBytes).toHaveBeenCalledWith('/test.pdf');
  });

  it('startReading preloadedBytes populated asynchronously for PDF', async () => {
    mockGetFileBytes.mockResolvedValueOnce([99, 98, 97]);
    const book = makeBook({ id: 'b1', format: 'pdf', filePath: '/test.pdf' });
    await readerState.startReading(book);
    await vi.waitFor(() => {
      expect(readerState.preloadedBytes).not.toBeNull();
    });
    expect(readerState.preloadedBytes!.filePath).toBe('/test.pdf');
    expect(readerState.preloadedBytes!.data).toEqual(new Uint8Array([99, 98, 97]));
  });

  it('startReading preload failure does not throw for PDF', async () => {
    mockGetFileBytes.mockRejectedValueOnce(new Error('PDF load failed'));
    const book = makeBook({ id: 'b1', format: 'pdf', filePath: '/test.pdf' });
    await expect(readerState.startReading(book)).resolves.toBeUndefined();
    expect(readerState.preloadedBytes).toBeNull();
  });

  it('startReading with PDF does not call getProgress', async () => {
    const book = makeBook({ id: 'b1', format: 'pdf', filePath: '/test.pdf' });
    await readerState.startReading(book);
    expect(mockGetProgress).not.toHaveBeenCalled();
  });

  // ─── startReading — reading-status persistence (DF-01) ───

  it('startReading surfaces a recoverable notice when the reading status cannot be saved', async () => {
    mockSetReadingStatus.mockRejectedValueOnce(new Error('status write failed'));
    const book = makeBook({ id: 'b1', format: 'pdf', filePath: '/test.pdf' });

    await expect(readerState.startReading(book)).resolves.toBeUndefined();

    // The reader still opens — the failure is recoverable, never blocking.
    expect(readerState.activeReadingBookId).toBe('b1');
    expect(readerState.readerError).toBeTruthy();
    expect(mockPushToast).toHaveBeenCalledWith('error', expect.any(String));
  });

  it('startReading raises no notice when the reading status saves', async () => {
    mockSetReadingStatus.mockResolvedValueOnce(undefined);

    await readerState.startReading(makeBook({ id: 'b1' }));

    expect(mockPushToast).not.toHaveBeenCalled();
    expect(readerState.readerError).toBeNull();
  });

  // ─── handleEpubLocationChange ───

  it('handleEpubLocationChange updates location and percentage', async () => {
    await readerState.handleEpubLocationChange('b1', 'epubcfi(/6/4)', 45);

    expect(readerState.cfiLocation).toBe('epubcfi(/6/4)');
    expect(readerState.percentage).toBe(45);
    expect(mockSaveProgress).toHaveBeenCalledWith({
      bookId: 'b1',
      cfiLocation: 'epubcfi(/6/4)',
      percentage: 45,
    });
  });

  it('handleEpubLocationChange clamps percentage', async () => {
    await readerState.handleEpubLocationChange('b1', 'epubcfi(/end)', 150);
    expect(readerState.percentage).toBe(100);

    await readerState.handleEpubLocationChange('b1', 'epubcfi(/start)', -10);
    expect(readerState.percentage).toBe(0);
  });

  it('handleEpubLocationChange calls onStatsRefreshNeeded', async () => {
    const refreshFn = vi.fn().mockResolvedValue(undefined);
    readerState.onStatsRefreshNeeded = refreshFn;

    await readerState.handleEpubLocationChange('b1', 'epubcfi(/6/4)', 45);

    expect(refreshFn).toHaveBeenCalledWith('b1');
  });

  it('handleEpubLocationChange does not throw on save failure', async () => {
    mockSaveProgress.mockRejectedValueOnce(new Error('Save failed'));
    await expect(
      readerState.handleEpubLocationChange('b1', 'epubcfi(/6/4)', 45),
    ).resolves.toBeUndefined();
    expect(readerState.cfiLocation).toBe('epubcfi(/6/4)');
  });

  // ─── handlePdfPageChange ───

  it('handlePdfPageChange calls onPageChangeCallback and updateBookProgress', async () => {
    const pageChangeFn = vi.fn();
    readerState.onPageChangeCallback = pageChangeFn;

    await readerState.handlePdfPageChange('b1', 50, 100);

    expect(pageChangeFn).toHaveBeenCalledWith('b1', 50, 100);
    expect(mockUpdateBookProgress).toHaveBeenCalledWith('b1', 50);
  });

  it('handlePdfPageChange calls onStatsRefreshNeeded', async () => {
    const refreshFn = vi.fn().mockResolvedValue(undefined);
    readerState.onStatsRefreshNeeded = refreshFn;

    await readerState.handlePdfPageChange('b1', 50, 100);

    expect(refreshFn).toHaveBeenCalledWith('b1');
  });

  it('handlePdfPageChange does not throw on failure', async () => {
    mockUpdateBookProgress.mockRejectedValueOnce(new Error('Progress save failed'));
    const pageChangeFn = vi.fn();
    readerState.onPageChangeCallback = pageChangeFn;

    await expect(readerState.handlePdfPageChange('b1', 50, 100)).resolves.toBeUndefined();
    expect(pageChangeFn).toHaveBeenCalled();
  });

  // ─── handlePdfSessionProgress ───

  it('handlePdfSessionProgress saves valid session', async () => {
    const refreshFn = vi.fn().mockResolvedValue(undefined);
    readerState.onStatsRefreshNeeded = refreshFn;
    const startedAt = new Date(Date.now() - 120000).toISOString();
    const endedAt = new Date().toISOString();

    await readerState.handlePdfSessionProgress('b1', {
      startedAt,
      endedAt,
      durationSeconds: 120,
      startPercentage: 10,
      endPercentage: 30,
    });

    expect(mockSaveReadingSession).toHaveBeenCalledWith({
      bookId: 'b1',
      startedAt,
      endedAt,
      durationSeconds: 120,
      startPercentage: 10,
      endPercentage: 30,
      userId: '',
    });
    expect(refreshFn).toHaveBeenCalledWith('b1');
  });

  it('handlePdfSessionProgress rejects invalid session silently', async () => {
    await readerState.handlePdfSessionProgress('b1', {
      startedAt: new Date().toISOString(),
      endedAt: new Date().toISOString(),
      durationSeconds: 0,
    });

    expect(mockSaveReadingSession).not.toHaveBeenCalled();
  });

  it('handlePdfSessionProgress does not throw on save failure', async () => {
    mockSaveReadingSession.mockRejectedValueOnce(new Error('Session save failed'));
    const startedAt = new Date(Date.now() - 60000).toISOString();
    const endedAt = new Date().toISOString();

    await expect(
      readerState.handlePdfSessionProgress('b1', {
        startedAt,
        endedAt,
        durationSeconds: 60,
      }),
    ).resolves.toBeUndefined();
  });

  // ─── handlePdfSessionProgress — 30s threshold + READING_SESSION outbox (D6/D9) ───

  it('drops sub-30s sessions — no save command, no outbox enqueue (SCEN-duration-1)', async () => {
    mockAuthState.userId = 'user-1';
    const startedAt = new Date(Date.now() - 20000).toISOString();
    const endedAt = new Date().toISOString();

    await readerState.handlePdfSessionProgress('b1', {
      startedAt,
      endedAt,
      durationSeconds: 20,
      startPercentage: 0,
      endPercentage: 5,
    });

    expect(mockSaveReadingSession).not.toHaveBeenCalled();
    expect(mockOutboxAdd).not.toHaveBeenCalled();
    expect(mockOutboxAddCoalesced).not.toHaveBeenCalled();
  });

  it('stores 30-59s sessions with durationMinutes 0 and enqueues them (SCEN-duration-2)', async () => {
    mockAuthState.userId = 'user-1';
    const startedAt = new Date(Date.now() - 45000).toISOString();
    const endedAt = new Date().toISOString();
    mockSaveReadingSession.mockResolvedValueOnce({
      id: 'sess_45s',
      durationMinutes: 0,
      date: '2026-08-13T00:00:00.000Z',
      updatedAtEpochMillis: 1786615200000,
    });

    await readerState.handlePdfSessionProgress('b1', {
      startedAt,
      endedAt,
      durationSeconds: 45,
      startPercentage: 10,
      endPercentage: 15,
    });

    expect(mockSaveReadingSession).toHaveBeenCalledTimes(1);
    expect(mockOutboxAdd).toHaveBeenCalledTimes(1);
    const [entityType, entityId, operation, payloadJson] = mockOutboxAdd.mock.calls[0];
    expect(entityType).toBe('READING_SESSION');
    expect(entityId).toBe('b1');
    expect(operation).toBe('UPSERT');
    const payload = JSON.parse(payloadJson as string);
    expect(payload.durationMinutes).toBe(0);
    expect(payload.date).toBe('2026-08-13T00:00:00.000Z');
  });

  it('enqueues the exact READING_SESSION payload via plain add(), never addCoalesced (SCEN-push-1)', async () => {
    mockAuthState.userId = 'user-1';
    const startedAt = new Date(Date.now() - 120000).toISOString();
    const endedAt = new Date().toISOString();
    mockSaveReadingSession.mockResolvedValueOnce({
      id: 'sess_abc123',
      durationMinutes: 2,
      date: '2026-08-13T00:00:00.000Z',
      updatedAtEpochMillis: 1786615200000,
    });

    await readerState.handlePdfSessionProgress('b1', {
      startedAt,
      endedAt,
      durationSeconds: 120,
      startPercentage: 10,
      endPercentage: 30,
    });

    // Plain add() — bookId-keyed coalesce would collapse distinct sessions (D9).
    expect(mockOutboxAdd).toHaveBeenCalledTimes(1);
    expect(mockOutboxAddCoalesced).not.toHaveBeenCalled();
    const [entityType, entityId, operation, payloadJson] = mockOutboxAdd.mock.calls[0];
    expect(entityType).toBe('READING_SESSION');
    expect(entityId).toBe('b1');
    expect(operation).toBe('UPSERT');
    expect(JSON.parse(payloadJson as string)).toEqual({
      id: 'sess_abc123',
      bookId: 'b1',
      startedAt,
      endedAt,
      durationMinutes: 2,
      date: '2026-08-13T00:00:00.000Z',
      userId: 'user-1',
      updatedAtEpochMillis: 1786615200000,
      startPercentage: 10,
      endPercentage: 30,
    });
  });

  it('local row survives enqueue failure — save precedes enqueue, throw swallowed (SCEN-push-5)', async () => {
    mockAuthState.userId = 'user-1';
    mockOutboxAdd.mockRejectedValueOnce(new Error('outbox write failed'));
    const startedAt = new Date(Date.now() - 60000).toISOString();
    const endedAt = new Date().toISOString();

    await expect(
      readerState.handlePdfSessionProgress('b1', {
        startedAt,
        endedAt,
        durationSeconds: 60,
      }),
    ).resolves.toBeUndefined();

    // Local save already happened before the enqueue attempt (and before the throw).
    expect(mockSaveReadingSession).toHaveBeenCalledTimes(1);
  });

  // ─── handleReaderLocationContext ───

  it('handleReaderLocationContext does not throw', () => {
    expect(() => readerState.handleReaderLocationContext()).not.toThrow();
  });

  // ─── resetReader ───

  it('resetReader clears all reader state', () => {
    readerState.activeReadingBookId = 'b1';
    readerState.cfiLocation = 'epubcfi(/6/2)';
    readerState.percentage = 42;
    readerState.preloadedBytes = { filePath: '/t.epub', data: new Uint8Array([1, 2, 3]) };
    readerState.readerError = 'Some error';

    readerState.resetReader();

    expect(readerState.activeReadingBookId).toBeNull();
    expect(readerState.cfiLocation).toBe('');
    expect(readerState.percentage).toBe(0);
    expect(readerState.preloadedBytes).toBeNull();
    expect(readerState.readerError).toBeNull();
  });

  // ─── Outbox coalesced enqueue (D5, SR-4.1) ───

  it('enqueues READING_PROGRESS via addCoalesced with latest client updatedAt (D5/D6)', async () => {
    mockAuthState.userId = 'user-1';

    await readerState.handleEpubLocationChange('b1', 'epubcfi(/6/4)', 45);

    expect(mockOutboxAdd).not.toHaveBeenCalled();
    expect(mockOutboxAddCoalesced).toHaveBeenCalledTimes(1);
    const [entityType, entityId, operation, payloadJson] = mockOutboxAddCoalesced.mock.calls[0];
    expect(entityType).toBe('READING_PROGRESS');
    expect(entityId).toBe('b1');
    expect(operation).toBe('UPSERT');
    const payload = JSON.parse(payloadJson as string);
    expect(payload.userId).toBe('user-1');
    expect(payload.bookId).toBe('b1');
    expect(payload.cfiLocation).toBe('epubcfi(/6/4)');
    expect(payload.percentage).toBe(45);
    expect(typeof payload.updatedAt).toBe('string');
    expect(Date.parse(payload.updatedAt)).not.toBeNaN();
  });

  it('one coalesced enqueue per location change — the flood stays one IPC per event (SR-4.1)', async () => {
    mockAuthState.userId = 'user-1';

    // 50 rapid location changes for one book: each is a single addCoalesced IPC
    // (the transactional UPDATE-else-INSERT in Rust collapses them to one row).
    for (let i = 0; i < 50; i++) {
      await readerState.handleEpubLocationChange('b1', `epubcfi(/6/${i + 1})`, i + 1);
    }

    expect(mockOutboxAddCoalesced).toHaveBeenCalledTimes(50);
    expect(mockOutboxAdd).not.toHaveBeenCalled();
    // Every enqueue targets the same (entityType, entityId) key — the latest
    // payload holds the newest location.
    const lastPayload = JSON.parse(mockOutboxAddCoalesced.mock.calls[49][3] as string);
    expect(lastPayload.cfiLocation).toBe('epubcfi(/6/50)');
    expect(lastPayload.percentage).toBe(50);
  });

  it('does not enqueue anything when not signed in (gate, SR-1)', async () => {
    mockAuthState.userId = null;

    await readerState.handleEpubLocationChange('b1', 'epubcfi(/6/4)', 45);

    expect(mockOutboxAddCoalesced).not.toHaveBeenCalled();
    expect(mockOutboxAdd).not.toHaveBeenCalled();
  });

  // ─── subscribeToRemoteSessions (D14 — 4th channel, REQ-pull/REQ-refresh) ───

  it('subscribeToRemoteSessions no-ops without a signed-in user', () => {
    mockAuthState.userId = null;

    readerState.subscribeToRemoteSessions();

    expect(mockFetchReadingSessions).not.toHaveBeenCalled();
    expect(mockSubscribeToReadingSessions).not.toHaveBeenCalled();
  });

  it('subscribeToRemoteSessions fetches remote sessions and merges in 500-row chunks (SCEN-pull-1)', async () => {
    mockAuthState.userId = 'user-1';
    const rows = Array.from({ length: 750 }, (_, i) => ({
      id: `sess_${i}`,
      userId: 'user-1',
      bookId: 'b1',
      startedAt: '2026-08-13T10:00:00.000Z',
      durationMinutes: 1,
      date: '2026-08-13T00:00:00.000Z',
      updatedAtEpochMillis: 1786615200000,
      startPercentage: null,
      endPercentage: null,
    }));
    mockFetchReadingSessions.mockResolvedValueOnce(rows);

    readerState.subscribeToRemoteSessions();

    await vi.waitFor(() => {
      expect(mockUpsertRemoteReadingSessions).toHaveBeenCalledTimes(2);
    });
    expect(mockUpsertRemoteReadingSessions.mock.calls[0][0]).toHaveLength(500);
    expect(mockUpsertRemoteReadingSessions.mock.calls[1][0]).toHaveLength(250);
    expect(mockSubscribeToReadingSessions).toHaveBeenCalledTimes(1);
  });

  it('subscribeToRemoteSessions merges a remote insert and refreshes stats (REQ-refresh, SCEN-refresh-1)', async () => {
    mockAuthState.userId = 'user-1';
    const refreshFn = vi.fn().mockResolvedValue(undefined);
    readerState.onStatsRefreshNeeded = refreshFn;

    let sessionCallback: ((row: unknown) => void) | null = null;
    mockSubscribeToReadingSessions.mockImplementation((cb?: (row: unknown) => void) => {
      sessionCallback = cb ?? null;
      return () => undefined;
    });

    readerState.subscribeToRemoteSessions();

    expect(mockSubscribeToReadingSessions).toHaveBeenCalledTimes(1);
    expect(sessionCallback).not.toBeNull();

    const remoteRow = {
      id: 'sess_remote',
      userId: 'user-1',
      bookId: 'b1',
      startedAt: '2026-08-13T10:00:00.000Z',
      durationMinutes: 5,
      date: '2026-08-13T00:00:00.000Z',
      updatedAtEpochMillis: 1786615200000,
      startPercentage: null,
      endPercentage: null,
    };

    sessionCallback!(remoteRow);

    await vi.waitFor(() => {
      expect(mockUpsertRemoteReadingSessions).toHaveBeenCalledWith([remoteRow]);
      expect(refreshFn).toHaveBeenCalledWith('b1');
    });
  });

  it('subscribeToAllRemoteChanges registers the reading-sessions channel too', () => {
    mockAuthState.userId = 'user-1';

    readerState.subscribeToAllRemoteChanges();

    expect(mockSubscribeToReadingSessions).toHaveBeenCalledTimes(1);
  });

  it('unsubscribeFromAllRemoteChanges tears down the reading-sessions channel', () => {
    mockAuthState.userId = 'user-1';
    const unsubscribeSessions = vi.fn();
    mockSubscribeToReadingSessions.mockImplementation(() => unsubscribeSessions);

    readerState.subscribeToAllRemoteChanges();
    readerState.unsubscribeFromAllRemoteChanges();

    expect(unsubscribeSessions).toHaveBeenCalledTimes(1);
  });
});
