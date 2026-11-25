/**
 * EPUB reading time in `AppState`: a reading visit is saved as one session
 * when the reader is left, and a saved position is mirrored into the library
 * list so Home shows the new "last read" on return. Domain calls that would
 * reach Tauri are stubbed on the singletons; the tracker itself is real.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MockActiveUserAdapter } from '$lib/shared/ports/adapters/mock/MockActiveUserAdapter';
import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
import type { ReaderBook } from '$lib/shared/types';

vi.mock('$lib/shared/api/tauriClient', () => {
  return new Proxy(
    {},
    {
      get(_target, key: string) {
        if (key === '__esModule' || key === 'then') return undefined;
        return vi.fn(async () => undefined);
      },
    },
  );
});
vi.mock('$lib/shared/services/SyncService', () => ({
  SyncService: { setupOutboxProcessor: vi.fn(), resetOutboxBreaker: vi.fn() },
}));
vi.mock('$lib/services/supabase', () => ({
  getSessionClient: vi.fn(() => ({ auth: { onAuthStateChange: vi.fn() } })),
  setLiveSession: vi.fn(),
  clearLiveSession: vi.fn(),
  getLiveSession: vi.fn(() => null),
  hasLiveSession: vi.fn(() => false),
  recheckLiveSession: vi.fn(async () => false),
  resetSessionClient: vi.fn(),
}));
vi.mock('$lib/shared/stores/theme', () => ({ initTheme: vi.fn() }));
vi.mock('$lib/shared/i18n', () => ({
  i18n: { t: (_locale: string, key: string) => key, initializeLocale: vi.fn(async () => 'es') },
}));
vi.mock('$lib/shared/services/pdfThumbnail', () => ({
  extractPdfMetadata: vi.fn(async () => null),
}));

const { AppState } = await import('$lib/shared/stores/AppState.svelte');

const T0 = new Date('2026-11-25T10:00:00.000Z');

function book(overrides: Partial<ReaderBook> = {}): ReaderBook {
  return {
    id: 'epub-1',
    title: 'Prisioneros de la geografía',
    author: 'Tim Marshall',
    format: 'epub',
    currentPage: 0,
    totalPages: 0,
    progressPercentage: 37,
    coverPath: null,
    minutesRead: 0,
    updatedAt: '2026-10-01T00:00:00.000Z',
    createdAt: '2026-10-01T00:00:00.000Z',
    progressUpdatedAt: '2026-11-01T00:00:00.000Z',
    filePath: '/books/p.epub',
    collectionIds: [],
    ...overrides,
  } as ReaderBook;
}

describe('AppState — EPUB reading sessions', () => {
  let app: InstanceType<typeof AppState>;
  let saveSession: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(T0);
    navigationState.route = 'home';
    app = new AppState(new MockActiveUserAdapter());
    vi.spyOn(app.reader, 'startReading').mockResolvedValue(undefined);
    vi.spyOn(app.statsDomain, 'loadStats').mockResolvedValue(undefined);
    vi.spyOn(app.reader, 'handleEpubLocationChange').mockImplementation(async (_id, _cfi, pct) => {
      app.reader.percentage = pct;
    });
    saveSession = vi.spyOn(app.reader, 'saveReadingSession').mockResolvedValue(undefined);
    app.library.books = [book()];
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('saves one session for the whole visit when the reader is left', async () => {
    await app.startReading(app.library.books[0]);
    for (let i = 1; i <= 10; i += 1) {
      vi.setSystemTime(new Date(T0.getTime() + i * 60_000));
      await app.handleEpubLocationChange(`epubcfi(/6/${i}!/4)`, 37 + i);
    }
    expect(saveSession).not.toHaveBeenCalled();

    app.endReadingSession();

    expect(saveSession).toHaveBeenCalledTimes(1);
    expect(saveSession).toHaveBeenCalledWith('epub-1', {
      startedAt: '2026-11-25T10:00:00.000Z',
      endedAt: '2026-11-25T10:10:00.000Z',
      durationSeconds: 600,
      startPercentage: 37,
      endPercentage: 47,
    });
  });

  it('mirrors each saved position into the library so Home is current on return', async () => {
    await app.startReading(app.library.books[0]);
    vi.setSystemTime(new Date(T0.getTime() + 60_000));
    await app.handleEpubLocationChange('epubcfi(/6/4!/4)', 41);
    const updated = app.library.getBookById('epub-1');
    expect(updated?.progressPercentage).toBe(41);
    expect(updated?.progressUpdatedAt).toBe('2026-11-25T10:01:00.000Z');
  });

  it('does not track a PDF (its viewer reports its own sessions)', async () => {
    app.library.books = [book({ id: 'pdf-1', format: 'pdf', filePath: '/books/c.pdf' })];
    await app.startReading(app.library.books[0]);
    vi.setSystemTime(new Date(T0.getTime() + 10 * 60_000));
    app.endReadingSession();
    expect(saveSession).not.toHaveBeenCalled();
  });

  it('closes the session in the background and reopens it on return', async () => {
    await app.startReading(app.library.books[0]);
    vi.setSystemTime(new Date(T0.getTime() + 2 * 60_000));
    app.endReadingSession();
    expect(saveSession).toHaveBeenCalledTimes(1);

    vi.setSystemTime(new Date(T0.getTime() + 60 * 60_000));
    app.resumeReadingSession();
    vi.setSystemTime(new Date(T0.getTime() + 63 * 60_000));
    app.endReadingSession();

    expect(saveSession).toHaveBeenCalledTimes(2);
    expect(saveSession.mock.calls[1][1]).toMatchObject({
      startedAt: '2026-11-25T11:00:00.000Z',
      durationSeconds: 180,
    });
  });

  it('does not resume once the reader was left', async () => {
    await app.startReading(app.library.books[0]);
    navigationState.route = 'home';
    app.endReadingSession();
    app.resumeReadingSession();
    expect(app.readingSessions.activeBookId).toBeNull();
  });
});
