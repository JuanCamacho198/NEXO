/**
 * Unit tests for the downloadableCatalog store — T-03 (Batch 2 / PR 2) plus
 * the login-drive-separation cloud-download pre-prompt gate (work unit 3).
 *
 * Covers `loadAvailableFromDrive()` (Drive listing with local-library
 * exclusion and unparseable-name filtering), `downloadBook()` with the Drive
 * store authorized, and the unauthorized pre-prompt matrix (offer vs
 * decline-suppressed vs non-Google vs clean degrade).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mockGDriveList = vi.hoisted(() => vi.fn());
const mockGDriveDownload = vi.hoisted(() => vi.fn());
const mockListLibraryBooks = vi.hoisted(() => vi.fn());
const mockSaveBookFile = vi.hoisted(() => vi.fn());
const mockCatalogUpsertBook = vi.hoisted(() => vi.fn());
const mockCatalogFindByHash = vi.hoisted(() => vi.fn());
const mockCatalogFetchCatalog = vi.hoisted(() => vi.fn());
const mockAuthUserId = vi.hoisted(() => ({ value: null as string | null }));
const mockExtractEpubMetadata = vi.hoisted(() => vi.fn());
const mockIsDriveAuthorized = vi.hoisted(() => vi.fn());
const mockGetIdentityProvider = vi.hoisted(() => vi.fn());

vi.mock('$lib/shared/services/DriveConnectService', () => ({
  isDriveAuthorized: mockIsDriveAuthorized,
}));

vi.mock('$lib/shared/services/storage/GDriveProvider', () => ({
  GDriveProvider: vi.fn(function () {
    return {
      list: mockGDriveList,
      // The store now reads the union of every `Nexo*` root. These specs model a
      // single-root account: the same names come back with one root, so the
      // existing `mockGDriveList` fixtures keep their meaning.
      listAllRoots: async () => {
        const names: string[] = await mockGDriveList('');
        return { names, rootCount: 1, emptyRoots: 0 };
      },
      download: mockGDriveDownload,
    };
  }),
}));

vi.mock('$lib/shared/api/tauriClient', () => ({
  listLibraryBooks: mockListLibraryBooks,
  saveBookFile: mockSaveBookFile,
}));

vi.mock('$lib/shared/services/epubImportMetadata', () => ({
  extractEpubMetadataFromBytes: mockExtractEpubMetadata,
}));

vi.mock('$lib/shared/sync/SupabaseBookCatalogSync', () => ({
  SupabaseBookCatalogSync: vi.fn(function () {
    return {
      upsertBook: mockCatalogUpsertBook,
      findByHash: mockCatalogFindByHash,
      fetchCatalog: mockCatalogFetchCatalog,
    };
  }),
}));

// Drive authorized by default — the provider's silent-refresh chain covers auth.
// Unauthorized paths are exercised in the pre-prompt matrix below.
mockIsDriveAuthorized.mockResolvedValue(true);

// Identity provider for the pre-prompt gate (null = anonymous/local).
vi.mock('$lib/shared/services/SupabaseAuthService', () => ({
  getIdentityProvider: mockGetIdentityProvider,
}));
mockGetIdentityProvider.mockResolvedValue(null);

// No live user session → downloadBook's catalog upsert must be a no-op.
vi.mock('$lib/shared/stores/AuthState.svelte', () => ({
  authState: {
    get userId(): string | null {
      return mockAuthUserId.value;
    },
  },
}));

import {
  downloadableCatalog,
  clearDownloadableBooks,
  clearDrivePrompt,
  loadAvailableFromDrive,
  downloadBook,
} from '$lib/stores/downloadableCatalog.svelte';

describe('downloadableCatalog — loadAvailableFromDrive (REQ-01)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuthUserId.value = null;
    clearDownloadableBooks();
    downloadableCatalog.clearDownloadError();
    mockGDriveList.mockResolvedValue([]);
    mockListLibraryBooks.mockResolvedValue([]);
  });

  it('lists Drive books absent from the local library (SCN-02)', async () => {
    mockGDriveList.mockResolvedValue(['book-1.epub', 'book-2.pdf', 'book-3.mobi']);
    mockListLibraryBooks.mockResolvedValue([{ id: 'book-2' }]);

    await loadAvailableFromDrive();

    expect(mockGDriveList).toHaveBeenCalledWith('');
    expect(downloadableCatalog.books).toEqual([
      {
        id: 'book-1',
        ext: 'epub',
        remoteName: 'book-1.epub',
        displayTitle: 'book-1',
        author: null,
        coverUrl: null,
      },
      {
        id: 'book-3',
        ext: 'mobi',
        remoteName: 'book-3.mobi',
        displayTitle: 'book-3',
        author: null,
        coverUrl: null,
      },
    ]);
  });

  it('drops unparseable names (sync state, no dot, trailing dot)', async () => {
    mockGDriveList.mockResolvedValue(['book-1.epub', 'catalog_state.json', 'noext', 'trailing.']);
    mockListLibraryBooks.mockResolvedValue([]);

    await loadAvailableFromDrive();

    expect(downloadableCatalog.books).toEqual([
      {
        id: 'book-1',
        ext: 'epub',
        remoteName: 'book-1.epub',
        displayTitle: 'book-1',
        author: null,
        coverUrl: null,
      },
    ]);
  });

  it('surfaces listing failures in the banner without clearing prior books', async () => {
    mockGDriveList.mockRejectedValue(new Error('GDrive List Failed: boom'));

    await loadAvailableFromDrive();

    expect(downloadableCatalog.error).toContain('boom');
    expect(downloadableCatalog.books).toEqual([]);
    // A genuine failure is also reachable on the EMPTY shelf (contract §3.1):
    // the banner used to live inside the `books.length > 0` block.
    expect(downloadableCatalog.loadError).toContain('boom');
  });

  it('does NOT raise a listing error when Drive was never connected', async () => {
    // "never connected" is the ordinary state, not a failure: it is typed
    // DRIVE_NOT_CONNECTED and must stay off the empty shelf.
    const notConnected = Object.assign(
      new Error('Google Drive is not connected. Connect Google Drive in Settings.'),
      { code: 'DRIVE_NOT_CONNECTED' },
    );
    mockGDriveList.mockRejectedValue(notConnected);

    await loadAvailableFromDrive();

    expect(downloadableCatalog.loadError).toBeNull();
  });

  it('dedupes the same book arriving from two roots (and a collided twin name)', async () => {
    // Two `Nexo*` roots unioned: `shared.epub` appears in both, and the lost
    // side of a same-name upload race carries Drive's ` (1)` suffix.
    mockGDriveList.mockResolvedValue(['shared.epub', 'shared (1).epub', 'only-in-second.epub']);
    mockListLibraryBooks.mockResolvedValue([]);

    await loadAvailableFromDrive();

    // ONE entry for `shared`, plus the genuinely distinct book.
    expect(downloadableCatalog.books.map((b) => b.id)).toEqual(['shared', 'only-in-second']);
  });
});

describe('downloadableCatalog — catalog metadata enrichment (REQ-01)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuthUserId.value = 'user-123';
    clearDownloadableBooks();
    downloadableCatalog.clearDownloadError();
    mockGDriveList.mockResolvedValue([]);
    mockListLibraryBooks.mockResolvedValue([]);
    mockCatalogFetchCatalog.mockResolvedValue([
      {
        id: 'book-1',
        userId: 'user-123',
        title: 'The Great Book',
        author: 'Jane Doe',
        format: 'epub',
        filePath: null,
        coverUrl: 'https://example.com/cover.jpg',
        description: null,
        totalPages: null,
        sourceDevice: null,
        importedAt: '',
        updatedAt: '',
      },
    ]);
  });

  afterEach(() => {
    mockAuthUserId.value = null;
  });

  it('uses catalog title/author/cover, falling back to filename otherwise', async () => {
    mockGDriveList.mockResolvedValue(['book-1.epub', 'book-2.pdf']);
    mockListLibraryBooks.mockResolvedValue([]);

    await loadAvailableFromDrive();

    expect(mockCatalogFetchCatalog).toHaveBeenCalledTimes(1);
    expect(downloadableCatalog.books).toEqual([
      {
        id: 'book-1',
        ext: 'epub',
        remoteName: 'book-1.epub',
        displayTitle: 'The Great Book',
        author: 'Jane Doe',
        coverUrl: 'https://example.com/cover.jpg',
      },
      {
        id: 'book-2',
        ext: 'pdf',
        remoteName: 'book-2.pdf',
        displayTitle: 'book-2',
        author: null,
        coverUrl: null,
      },
    ]);
  });

  it('skips the catalog lookup without a live user session', async () => {
    mockAuthUserId.value = null;
    mockGDriveList.mockResolvedValue(['book-1.epub']);
    mockListLibraryBooks.mockResolvedValue([]);

    await loadAvailableFromDrive();

    expect(mockCatalogFetchCatalog).not.toHaveBeenCalled();
  });

  it('falls back to filename titles when the catalog fetch fails', async () => {
    mockCatalogFetchCatalog.mockRejectedValue(new Error('catalog down'));
    mockGDriveList.mockResolvedValue(['book-1.epub']);
    mockListLibraryBooks.mockResolvedValue([]);

    await loadAvailableFromDrive();

    expect(downloadableCatalog.books).toEqual([
      {
        id: 'book-1',
        ext: 'epub',
        remoteName: 'book-1.epub',
        displayTitle: 'book-1',
        author: null,
        coverUrl: null,
      },
    ]);
    expect(downloadableCatalog.error).toBeNull();
  });
});

describe('downloadableCatalog — downloadBook (REQ-02, SCN-03)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuthUserId.value = null;
    clearDownloadableBooks();
    downloadableCatalog.clearDownloadError();
    mockGDriveList.mockResolvedValue(['Mi.Libro.PDF']);
    mockListLibraryBooks.mockResolvedValue([]);
  });

  it('downloads using the original Drive name and removes the book on success', async () => {
    await loadAvailableFromDrive();
    expect(downloadableCatalog.books).toEqual([
      {
        id: 'Mi.Libro',
        ext: 'pdf',
        remoteName: 'Mi.Libro.PDF',
        displayTitle: 'Mi.Libro',
        author: null,
        coverUrl: null,
      },
    ]);

    mockGDriveDownload.mockResolvedValue(new Uint8Array([1, 2, 3, 4]));
    mockSaveBookFile.mockResolvedValue(undefined);

    await downloadBook('Mi.Libro');

    // The download closure uses the ORIGINAL filename (non-canonical-safe),
    // not the canonical ref resolved from the synthetic row.
    expect(mockGDriveDownload).toHaveBeenCalledWith('Mi.Libro.PDF');
    expect(mockSaveBookFile).toHaveBeenCalledWith('Mi.Libro', [1, 2, 3, 4], {
      title: 'Mi.Libro',
      author: '',
      format: 'pdf',
    });
    // No live session → markImported is a no-op (no catalog upsert).
    expect(mockCatalogUpsertBook).not.toHaveBeenCalled();
    expect(downloadableCatalog.books).toEqual([]);
  });
});

describe('downloadableCatalog — real EPUB title extraction on download', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuthUserId.value = null;
    clearDownloadableBooks();
    downloadableCatalog.clearDownloadError();
    mockGDriveList.mockResolvedValue([]);
    mockListLibraryBooks.mockResolvedValue([]);
  });

  const UUID = '9f3a7d1e-6b2c-4a8f-b5c2-0d7e1f2a3b4c';

  it('extracts the real title/author from the EPUB bytes when the catalog title is a UUID', async () => {
    mockGDriveList.mockResolvedValue([`${UUID}.epub`]);
    await loadAvailableFromDrive();
    expect(downloadableCatalog.books).toEqual([
      {
        id: UUID,
        ext: 'epub',
        remoteName: `${UUID}.epub`,
        displayTitle: UUID,
        author: null,
        coverUrl: null,
      },
    ]);

    const bytes = new Uint8Array([9, 8, 7]);
    mockGDriveDownload.mockResolvedValue(bytes);
    mockSaveBookFile.mockResolvedValue(undefined);
    mockExtractEpubMetadata.mockResolvedValue({
      title: 'Don Quijote',
      author: 'Cervantes',
      subject: null,
      subjects: [],
    });

    await downloadBook(UUID);

    expect(mockExtractEpubMetadata).toHaveBeenCalledWith(bytes);
    expect(mockSaveBookFile).toHaveBeenCalledWith(UUID, [9, 8, 7], {
      title: 'Don Quijote',
      author: 'Cervantes',
      format: 'epub',
    });
    expect(downloadableCatalog.books).toEqual([]);
  });

  it('keeps the catalog title for a non-UUID title (no extraction)', async () => {
    mockAuthUserId.value = 'user-123';
    mockCatalogFetchCatalog.mockResolvedValue([
      {
        id: 'book-1',
        userId: 'user-123',
        title: 'The Great Book',
        author: 'Jane Doe',
        format: 'epub',
        filePath: null,
        coverUrl: null,
        description: null,
        totalPages: null,
        sourceDevice: null,
        importedAt: '',
        updatedAt: '',
      },
    ]);
    mockGDriveList.mockResolvedValue(['book-1.epub']);
    await loadAvailableFromDrive();

    mockGDriveDownload.mockResolvedValue(new Uint8Array([1, 2, 3]));
    mockSaveBookFile.mockResolvedValue(undefined);

    await downloadBook('book-1');

    expect(mockExtractEpubMetadata).not.toHaveBeenCalled();
    expect(mockSaveBookFile).toHaveBeenCalledWith('book-1', [1, 2, 3], {
      title: 'The Great Book',
      author: '',
      format: 'epub',
    });
  });

  it('falls back to the catalog title when EPUB extraction fails', async () => {
    mockGDriveList.mockResolvedValue([`${UUID}.epub`]);
    await loadAvailableFromDrive();

    mockGDriveDownload.mockResolvedValue(new Uint8Array([5, 6]));
    mockSaveBookFile.mockResolvedValue(undefined);
    mockExtractEpubMetadata.mockRejectedValue(new Error('epub parse boom'));

    await downloadBook(UUID);

    expect(mockSaveBookFile).toHaveBeenCalledWith(UUID, [5, 6], {
      title: UUID,
      author: '',
      format: 'epub',
    });
    expect(downloadableCatalog.books).toEqual([]);
  });
});

describe('downloadableCatalog — Drive pre-prompt gate (login-drive-separation)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuthUserId.value = 'user-123';
    clearDownloadableBooks();
    downloadableCatalog.clearDownloadError();
    clearDrivePrompt();
    localStorage.clear();
    mockGDriveList.mockResolvedValue(['book-9.epub']);
    mockListLibraryBooks.mockResolvedValue([]);
    mockCatalogFetchCatalog.mockResolvedValue([]);
    mockGetIdentityProvider.mockResolvedValue('google');
  });

  afterEach(() => {
    mockAuthUserId.value = null;
    localStorage.clear();
  });

  async function seedBook(): Promise<void> {
    await loadAvailableFromDrive();
    expect(downloadableCatalog.books.map((b) => b.id)).toEqual(['book-9']);
  }

  it('authorized download proceeds with no prompt', async () => {
    mockIsDriveAuthorized.mockResolvedValue(true);
    await seedBook();

    mockGDriveDownload.mockResolvedValue(new Uint8Array([1]));
    mockSaveBookFile.mockResolvedValue(undefined);

    await downloadBook('book-9');

    expect(mockGDriveDownload).toHaveBeenCalledWith('book-9.epub');
    expect(downloadableCatalog.drivePromptPending).toBe(false);
    expect(downloadableCatalog.books).toEqual([]);
  });

  it('unauthorized first attempt raises the connect offer before any Drive call', async () => {
    mockIsDriveAuthorized.mockResolvedValue(false);
    await seedBook();

    await downloadBook('book-9');

    expect(downloadableCatalog.drivePromptPending).toBe(true);
    expect(mockGDriveDownload).not.toHaveBeenCalled();
    expect(downloadableCatalog.error).toMatch(/not connected/i);
    expect(downloadableCatalog.books).toHaveLength(1);
  });

  it('declined prompt suppresses the re-offer and degrades cleanly', async () => {
    mockIsDriveAuthorized.mockResolvedValue(false);
    await seedBook();

    await downloadBook('book-9');
    expect(downloadableCatalog.drivePromptPending).toBe(true);

    downloadableCatalog.declineDrivePrompt();
    expect(downloadableCatalog.drivePromptPending).toBe(false);

    await downloadBook('book-9');

    expect(downloadableCatalog.drivePromptPending).toBe(false);
    expect(mockGDriveDownload).not.toHaveBeenCalled();
    expect(downloadableCatalog.error).toMatch(/not connected/i);
  });

  it('non-Google users are never prompted', async () => {
    mockIsDriveAuthorized.mockResolvedValue(false);
    mockGetIdentityProvider.mockResolvedValue('github');
    await seedBook();

    await downloadBook('book-9');

    expect(downloadableCatalog.drivePromptPending).toBe(false);
    expect(mockGDriveDownload).not.toHaveBeenCalled();
    expect(downloadableCatalog.error).toMatch(/not connected/i);
  });

  it('anonymous users degrade without a prompt', async () => {
    mockIsDriveAuthorized.mockResolvedValue(false);
    mockGetIdentityProvider.mockResolvedValue(null);
    mockAuthUserId.value = null;
    await seedBook();

    await downloadBook('book-9');

    expect(downloadableCatalog.drivePromptPending).toBe(false);
    expect(mockGDriveDownload).not.toHaveBeenCalled();
    expect(downloadableCatalog.error).toMatch(/not connected/i);
  });
});
