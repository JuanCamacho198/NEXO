import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createSettingsData } from '$lib/features/settings/useSettingsData.svelte';
import { parseExportEnvelope } from '$lib/features/settings/exportEnvelope';
import type { CollectionDto, LibraryBookDto } from '$lib/shared/types';

function makeBook(overrides: Partial<LibraryBookDto> = {}): LibraryBookDto {
  return {
    id: 'b1',
    title: 'Dune',
    author: 'Frank Herbert',
    format: 'epub',
    currentPage: 42,
    totalPages: 412,
    progressPercentage: 10.2,
    coverPath: '/covers/dune.jpg',
    minutesRead: 120,
    updatedAt: '2026-09-01T10:00:00Z',
    createdAt: '2026-08-01T10:00:00Z',
    collectionIds: [1, 2],
    genre: 'sci-fi',
    publicationDate: '1965-08-01',
    language: 'en',
    coverUserDeleted: false,
    readingStatus: 'reading',
    startedAt: '2026-08-02T10:00:00Z',
    completedAt: null,
    progressUpdatedAt: '2026-09-01T10:00:00Z',
    stateVersion: 3,
    ...overrides,
  };
}

describe('useSettingsData', () => {
  it('defaults and isDirty false', () => {
    const d = createSettingsData();
    expect(d.selectedExportBook).toBe('all');
    expect(d.selectedExportFormat).toBe('json');
    expect(d.isDirty).toBe(false);
    expect(d.isSaving).toBe(false);
  });

  it('export book/format change marks dirty', () => {
    const d = createSettingsData();
    d.handleSelectedExportBookChange('book-1');
    expect(d.isDirty).toBe(true);
    d.handleSelectedExportBookChange('all');
    d.handleSelectedExportFormatChange('markdown');
    expect(d.isDirty).toBe(true);
  });

  it('handleClearCache calls storageState.clearCache and toasts success', async () => {
    const clearCache = vi.fn().mockResolvedValue({ freedBytes: 123 });
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const storageState = { clearCache } as unknown as Parameters<
      typeof createSettingsData
    >[0] extends { storageState?: infer S }
      ? S
      : never;
    const d = createSettingsData({
      storageState: storageState as never,
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleClearCache();
    expect(clearCache).toHaveBeenCalledWith('temp', false);
    expect(d.cacheCleared).toBe(true);
    expect(pushToast).toHaveBeenCalledWith('success', expect.any(String));
  });

  it('handleClearCache toasts the translated permission-denied message on that error', async () => {
    const clearCache = vi.fn().mockRejectedValue(new Error('storage.permission_denied'));
    const pushToast = vi.fn();
    const d = createSettingsData({
      storageState: { clearCache } as never,
      pushToast: pushToast as never,
    });
    await d.handleClearCache();
    // BUG-2: the raw backend code used to be pushed straight to the toast, so
    // the user saw the untranslated literal. It is resolved through `t` now.
    expect(pushToast).toHaveBeenCalledWith('error', 'storage.permissionDenied');
  });

  it('handleExportColdBackup calls Drive service when userId present and Drive authorized', async () => {
    const exportColdBackup = vi.fn().mockResolvedValue(undefined);
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const authState = { userId: 'user-1' } as never;
    const DriveColdBackupService = { exportColdBackup } as never;
    const d = createSettingsData({
      authState,
      DriveColdBackupService,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleExportColdBackup();
    expect(exportColdBackup).toHaveBeenCalledWith('user-1');
    expect(pushToast).toHaveBeenCalledWith('success', expect.any(String));
  });

  it('handleImportColdBackup calls import when userId present and Drive authorized', async () => {
    const importColdBackup = vi.fn().mockResolvedValue(undefined);
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const authState = { userId: 'u1' } as never;
    const DriveColdBackupService = { importColdBackup } as never;
    const d = createSettingsData({
      authState,
      DriveColdBackupService,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleImportColdBackup();
    expect(importColdBackup).toHaveBeenCalledWith('u1');
  });

  it('handleImportColdBackup surfaces ONE aggregated toast when some rows fail', async () => {
    const importColdBackup = vi.fn().mockResolvedValue({
      books: 1,
      progress: 0,
      highlights: 0,
      bookmarks: 0,
      sessions: 0,
      totalImported: 1,
      failures: [
        { entity: 'book', id: 'b2', error: { code: 'AUTH_REQUIRED' } },
        { entity: 'book', id: 'b3', error: { code: 'UNAVAILABLE' } },
      ],
    });
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'u1' } as never,
      DriveColdBackupService: { importColdBackup } as never,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleImportColdBackup();
    expect(importColdBackup).toHaveBeenCalledWith('u1');
    // Exactly one notification for the whole operation — not one per row.
    expect(pushToast).toHaveBeenCalledTimes(1);
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.importPartialFailure');
    expect(t).toHaveBeenCalledWith('settings.data.importPartialFailure', {
      count: 2,
      codes: 'AUTH_REQUIRED, UNAVAILABLE',
    });
  });

  it('handleImportColdBackup stays silent about failures on a fully successful run', async () => {
    const importColdBackup = vi.fn().mockResolvedValue({ totalImported: 3, failures: [] });
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'u1' } as never,
      DriveColdBackupService: { importColdBackup } as never,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleImportColdBackup();
    // No failure notification; the existing success toast is unchanged.
    expect(pushToast).toHaveBeenCalledTimes(1);
    expect(pushToast).toHaveBeenCalledWith('success', 'settings.data.importSuccess');
  });

  it('handleExportColdBackup routes unauthorized to the connect CTA toast without Drive I/O', async () => {
    const exportColdBackup = vi.fn().mockResolvedValue(undefined);
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'user-1' } as never,
      DriveColdBackupService: { exportColdBackup } as never,
      drive: { isAuthorized: async () => false },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleExportColdBackup();
    expect(exportColdBackup).not.toHaveBeenCalled();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.driveNotConnected');
    expect(d.isExportingColdBackup).toBe(false);
  });

  it('handleImportColdBackup routes unauthorized to the connect CTA toast without Drive I/O', async () => {
    const importColdBackup = vi.fn().mockResolvedValue(undefined);
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'u1' } as never,
      DriveColdBackupService: { importColdBackup } as never,
      drive: { isAuthorized: async () => false },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleImportColdBackup();
    expect(importColdBackup).not.toHaveBeenCalled();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.driveNotConnected');
    expect(d.isImportingColdBackup).toBe(false);
  });

  it('handleExportColdBackup routes a mid-flight DRIVE_NOT_CONNECTED to the connect CTA toast', async () => {
    const exportColdBackup = vi
      .fn()
      .mockRejectedValue(
        Object.assign(new Error('not connected'), { code: 'DRIVE_NOT_CONNECTED' }),
      );
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const d = createSettingsData({
      authState: { userId: 'user-1' } as never,
      DriveColdBackupService: { exportColdBackup } as never,
      drive: { isAuthorized: async () => true },
      pushToast: pushToast as never,
      t: t as never,
    });
    await d.handleExportColdBackup();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.driveNotConnected');
  });

  it('handleExportColdBackup errors when no userId', async () => {
    const pushToast = vi.fn();
    const t = vi.fn((k: string) => k);
    const authState = { userId: null } as never;
    const d = createSettingsData({ authState, pushToast: pushToast as never, t: t as never });
    await d.handleExportColdBackup();
    expect(pushToast).toHaveBeenCalledWith('error', expect.any(String));
  });
});

describe('useSettingsData dictionary transfer', () => {
  let downloaded: string | null;

  beforeEach(() => {
    downloaded = null;
    (URL as unknown as Record<string, unknown>).createObjectURL = vi.fn(() => 'blob:mock');
    (URL as unknown as Record<string, unknown>).revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloaded = this.download;
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('exports JSON through the dictionary store and downloads dictionary.json', async () => {
    const exportData = vi.fn().mockResolvedValue('{"words":[]}');
    const d = createSettingsData({
      dictionaryState: { exportData, importData: vi.fn() } as never,
    });

    await d.handleExportDictionary('json');

    expect(exportData).toHaveBeenCalledWith('json');
    expect(downloaded).toBe('dictionary.json');
    expect(d.isExportingDictionary).toBe(false);
    expect(d.dictionaryExportError).toBeNull();
  });

  it('exports CSV through the dictionary store and downloads dictionary.csv', async () => {
    const exportData = vi.fn().mockResolvedValue('word,tags');
    const d = createSettingsData({
      dictionaryState: { exportData, importData: vi.fn() } as never,
    });

    await d.handleExportDictionary('csv');

    expect(exportData).toHaveBeenCalledWith('csv');
    expect(downloaded).toBe('dictionary.csv');
    expect(d.isExportingDictionary).toBe(false);
  });

  it('imports a csv file, reports counts and joins row errors with the row formatter', async () => {
    const importData = vi.fn().mockResolvedValue({
      imported: 3,
      errors: [
        { row: 2, reason: 'bad word' },
        { row: 5, reason: 'bad tags' },
      ],
    });
    const t = vi.fn((k: string, params?: Record<string, string | number>) =>
      params ? `${k}:${JSON.stringify(params)}` : k,
    );
    const d = createSettingsData({
      dictionaryState: { exportData: vi.fn(), importData } as never,
      t: t as never,
    });
    const file = { name: 'words.csv', text: async () => 'a,b' } as unknown as File;

    await d.handleImportDictionary(file);

    expect(importData).toHaveBeenCalledWith('a,b', 'csv');
    expect(d.dictionaryImportResult).toContain('settings.data.dictionary.imported');
    expect(d.dictionaryImportResult).toContain('"imported":3');
    expect(d.dictionaryImportResult).toContain('"errors":2');
    expect(d.dictionaryImportError).toContain('settings.data.dictionary.rowError');
    expect(d.dictionaryImportError).toContain('; ');
    expect(d.isImportingDictionary).toBe(false);
  });

  it('defaults a non-csv file to json and leaves no error when the import is clean', async () => {
    const importData = vi.fn().mockResolvedValue({ imported: 1, errors: [] });
    const d = createSettingsData({
      dictionaryState: { exportData: vi.fn(), importData } as never,
    });
    const file = { name: 'words.json', text: async () => '[]' } as unknown as File;

    await d.handleImportDictionary(file);

    expect(importData).toHaveBeenCalledWith('[]', 'json');
    expect(d.dictionaryImportError).toBeNull();
    expect(d.isImportingDictionary).toBe(false);
  });

  it('surfaces the store error message when the import rejects', async () => {
    const importData = vi.fn().mockRejectedValue(new Error('bad payload'));
    const d = createSettingsData({
      dictionaryState: { exportData: vi.fn(), importData } as never,
    });
    const file = { name: 'words.json', text: async () => '[]' } as unknown as File;

    await d.handleImportDictionary(file);

    expect(d.dictionaryImportError).toBe('bad payload');
    expect(d.dictionaryImportResult).toBeNull();
  });
});

describe('useSettingsData real library and highlight exports', () => {
  let capturedBlob: Blob | null;
  let downloaded: string | null;

  beforeEach(() => {
    capturedBlob = null;
    downloaded = null;
    (URL as unknown as Record<string, unknown>).createObjectURL = vi.fn((blob: Blob) => {
      capturedBlob = blob;
      return 'blob:mock';
    });
    (URL as unknown as Record<string, unknown>).revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloaded = this.download;
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('serializes the real books to a dated JSON envelope and downloads it', async () => {
    const pushToast = vi.fn();
    const d = createSettingsData({ pushToast: pushToast as never });

    await d.handleExportLibrary([
      makeBook(),
      makeBook({ id: 'b2', title: 'Neuromancer', author: 'William Gibson' }),
    ]);

    expect(downloaded).toMatch(/^nexo-library-\d{4}-\d{2}-\d{2}\.json$/);
    expect(capturedBlob).not.toBeNull();
    const parsed = parseExportEnvelope(await capturedBlob!.text());
    expect(parsed.manifest.schema).toBe(1);
    expect(parsed.manifest.modules).toEqual(['books']);
    expect(parsed.books).toHaveLength(2);

    const first = parsed.books![0];
    // The rich, portable fields survive — no more lossy { id, title }.
    expect(first).toMatchObject({
      id: 'b1',
      title: 'Dune',
      author: 'Frank Herbert',
      format: 'epub',
      currentPage: 42,
      totalPages: 412,
      progressPercentage: 10.2,
      minutesRead: 120,
      createdAt: '2026-08-01T10:00:00Z',
      updatedAt: '2026-09-01T10:00:00Z',
      collectionIds: [1, 2],
      genre: 'sci-fi',
      language: 'en',
      readingStatus: 'reading',
      stateVersion: 3,
    });
    // Decision 3: the local path and the presentation flag never leave.
    expect(first).not.toHaveProperty('coverPath');
    expect(first).not.toHaveProperty('coverUserDeleted');
    // The local file path of ReaderBook is not a DTO field and is not exported.
    expect(first).not.toHaveProperty('filePath');

    expect(pushToast).toHaveBeenCalledWith('success', 'settings.data.libraryExported');
    expect(d.isExportingLibrary).toBe(false);
  });

  it('surfaces a clear message instead of an empty file when there are no books', async () => {
    const pushToast = vi.fn();
    const d = createSettingsData({ pushToast: pushToast as never });

    await d.handleExportLibrary([]);

    expect(capturedBlob).toBeNull();
    expect(downloaded).toBeNull();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.libraryExportEmpty');
  });

  it('keeps the full HighlightDto in the annotations module, filtered by book', async () => {
    const listHighlights = vi.fn().mockResolvedValue([
      {
        id: 'h1',
        bookId: 'b1',
        text: 'A line',
        note: 'my note',
        color: 'yellow',
        pageNumber: 3,
        createdAt: '2026-10-03T00:00:00Z',
        updatedAt: '2026-10-04T00:00:00Z',
        cfi: '/6/4!/4/2:0',
      },
    ]);
    const pushToast = vi.fn();
    const d = createSettingsData({
      listHighlights: listHighlights as never,
      pushToast: pushToast as never,
    });
    d.handleSelectedExportBookChange('b1');

    await d.handleExportHighlights([makeBook()]);

    expect(listHighlights).toHaveBeenCalledWith('b1');
    expect(downloaded).toMatch(/^nexo-highlights-\d{4}-\d{2}-\d{2}\.json$/);
    const parsed = parseExportEnvelope(await capturedBlob!.text());
    expect(parsed.manifest.schema).toBe(1);
    expect(parsed.manifest.modules).toEqual(['annotations']);
    expect(parsed.books).toBeUndefined();
    expect(parsed.annotations).toEqual([
      {
        id: 'h1',
        bookId: 'b1',
        text: 'A line',
        note: 'my note',
        color: 'yellow',
        pageNumber: 3,
        createdAt: '2026-10-03T00:00:00Z',
        updatedAt: '2026-10-04T00:00:00Z',
        cfi: '/6/4!/4/2:0',
      },
    ]);
    expect(pushToast).toHaveBeenCalledWith('success', 'settings.data.highlightsExported');
  });

  it('reads all highlights when "all" is selected', async () => {
    const listHighlights = vi.fn().mockResolvedValue([
      {
        id: 'h1',
        bookId: 'b1',
        text: 'A line',
        color: 'yellow',
        pageNumber: 3,
        createdAt: '2026-10-03T00:00:00Z',
        updatedAt: '2026-10-03T00:00:00Z',
      },
    ]);
    const d = createSettingsData({ listHighlights: listHighlights as never });

    await d.handleExportHighlights([]);

    expect(listHighlights).toHaveBeenCalledWith(undefined);
  });

  it('renders markdown highlights grouped by title and author with note, page and date', async () => {
    const listHighlights = vi.fn().mockResolvedValue([
      {
        id: 'h1',
        bookId: 'b1',
        text: 'A line',
        note: 'my note',
        color: 'yellow',
        pageNumber: 3,
        createdAt: '2026-10-03T00:00:00Z',
        updatedAt: '2026-10-03T00:00:00Z',
      },
    ]);
    const d = createSettingsData({ listHighlights: listHighlights as never });
    d.handleSelectedExportFormatChange('markdown');

    await d.handleExportHighlights([makeBook()]);

    expect(downloaded).toMatch(/^nexo-highlights-\d{4}-\d{2}-\d{2}\.md$/);
    const markdown = await capturedBlob!.text();
    expect(markdown).toContain('# Highlights');
    expect(markdown).toContain('## Dune — Frank Herbert');
    expect(markdown).toContain('> A line');
    expect(markdown).toContain('Note: my note');
    expect(markdown).toContain('Page 3 · yellow · 2026-10-03T00:00:00Z');
  });

  it('falls back to a labelled raw id when a highlight book is missing from the list', async () => {
    const listHighlights = vi.fn().mockResolvedValue([
      {
        id: 'h9',
        bookId: 'b9',
        text: 'Orphan line',
        color: 'blue',
        pageNumber: 1,
        createdAt: '2026-10-03T00:00:00Z',
        updatedAt: '2026-10-03T00:00:00Z',
      },
    ]);
    const d = createSettingsData({ listHighlights: listHighlights as never });
    d.handleSelectedExportFormatChange('markdown');

    await d.handleExportHighlights([makeBook()]);

    const markdown = await capturedBlob!.text();
    expect(markdown).toContain('## settings.unknownBook (b9)');
    expect(markdown).toContain('> Orphan line');
  });

  it('surfaces a clear message instead of an empty file when there are no highlights', async () => {
    const listHighlights = vi.fn().mockResolvedValue([]);
    const pushToast = vi.fn();
    const d = createSettingsData({
      listHighlights: listHighlights as never,
      pushToast: pushToast as never,
    });

    await d.handleExportHighlights([makeBook()]);

    expect(capturedBlob).toBeNull();
    expect(downloaded).toBeNull();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.highlightsExportEmpty');
  });

  it('surfaces a highlight read failure as an error instead of failing silently', async () => {
    const listHighlights = vi.fn().mockRejectedValue(new Error('db offline'));
    const pushToast = vi.fn();
    const d = createSettingsData({
      listHighlights: listHighlights as never,
      pushToast: pushToast as never,
    });

    await d.handleExportHighlights([]);

    expect(pushToast).toHaveBeenCalledWith('error', 'db offline');
    expect(capturedBlob).toBeNull();
    expect(d.isExportingHighlights).toBe(false);
  });
});

describe('useSettingsData export modules and scopes (EXP-04/06/07)', () => {
  let capturedBlob: Blob | null;
  let downloaded: string | null;

  const collections: CollectionDto[] = [
    { id: 1, name: 'Favorites', color: null, isSystem: true, createdAt: '2026-01-01T00:00:00Z' },
    { id: 2, name: 'Sci-fi', color: 'yellow', isSystem: false, createdAt: '2026-02-01T00:00:00Z' },
  ];

  const highlighted = {
    id: 'h1',
    bookId: 'b1',
    text: 'A line',
    note: 'my note',
    color: 'yellow',
    pageNumber: 3,
    createdAt: '2026-10-03T00:00:00Z',
    updatedAt: '2026-10-04T00:00:00Z',
    cfi: '/6/4!/4/2:0',
  };
  const bareHighlight = { ...highlighted, id: 'h2', note: null, text: 'No note here' };

  beforeEach(() => {
    capturedBlob = null;
    downloaded = null;
    (URL as unknown as Record<string, unknown>).createObjectURL = vi.fn((blob: Blob) => {
      capturedBlob = blob;
      return 'blob:mock';
    });
    (URL as unknown as Record<string, unknown>).revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloaded = this.download;
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('exports the collections module and round-trips every field', async () => {
    const pushToast = vi.fn();
    const d = createSettingsData({ pushToast: pushToast as never });

    await d.handleExportCollections(collections);

    expect(downloaded).toMatch(/^nexo-collections-\d{4}-\d{2}-\d{2}\.json$/);
    const parsed = parseExportEnvelope(await capturedBlob!.text());
    expect(parsed.manifest.schema).toBe(1);
    expect(parsed.manifest.modules).toEqual(['collections']);
    expect(parsed.collections).toEqual(collections);
    expect(parsed.books).toBeUndefined();
    expect(parsed.annotations).toBeUndefined();
    expect(pushToast).toHaveBeenCalledWith('success', 'settings.data.collectionsExported');
  });

  it('refuses the collections export with no collections and writes no file', async () => {
    const pushToast = vi.fn();
    const d = createSettingsData({ pushToast: pushToast as never });

    await d.handleExportCollections([]);

    expect(capturedBlob).toBeNull();
    expect(downloaded).toBeNull();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.collectionsExportEmpty');
  });

  it('exports every highlight by default and only annotated ones when the filter is on', async () => {
    const listHighlights = vi.fn().mockResolvedValue([highlighted, bareHighlight]);
    const d = createSettingsData({ listHighlights: listHighlights as never });

    await d.handleExportHighlights([]);
    const withAll = parseExportEnvelope(await capturedBlob!.text());
    expect(withAll.annotations).toHaveLength(2);

    capturedBlob = null;
    d.handleAnnotationsOnlyWithNoteChange(true);
    await d.handleExportHighlights([]);
    const withNote = parseExportEnvelope(await capturedBlob!.text());
    expect(withNote.manifest.modules).toEqual(['annotations']);
    expect(withNote.annotations).toEqual([highlighted]);
  });

  it('refuses the annotations export when the only-with-note filter empties it', async () => {
    const listHighlights = vi.fn().mockResolvedValue([bareHighlight]);
    const pushToast = vi.fn();
    const d = createSettingsData({
      listHighlights: listHighlights as never,
      pushToast: pushToast as never,
    });
    d.handleAnnotationsOnlyWithNoteChange(true);

    await d.handleExportHighlights([]);

    expect(capturedBlob).toBeNull();
    expect(downloaded).toBeNull();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.highlightsExportEmpty');
  });

  it('exports one book with its annotations, and only that book', async () => {
    const listHighlights = vi.fn().mockResolvedValue([highlighted]);
    const pushToast = vi.fn();
    const d = createSettingsData({
      listHighlights: listHighlights as never,
      pushToast: pushToast as never,
    });

    await d.handleExportBook('b1', [makeBook(), makeBook({ id: 'b2', title: 'Other' })]);

    expect(listHighlights).toHaveBeenCalledWith('b1');
    expect(downloaded).toMatch(/^nexo-book-b1-\d{4}-\d{2}-\d{2}\.json$/);
    const parsed = parseExportEnvelope(await capturedBlob!.text());
    expect(parsed.manifest.modules).toEqual(['books', 'annotations']);
    expect(parsed.books).toHaveLength(1);
    expect(parsed.books![0].id).toBe('b1');
    expect(parsed.annotations).toEqual([highlighted]);
    expect(parsed.collections).toBeUndefined();
    expect(pushToast).toHaveBeenCalledWith('success', 'settings.data.bookExported');
  });

  it('refuses the per-book export when the book is gone and writes no file', async () => {
    const listHighlights = vi.fn().mockResolvedValue([]);
    const pushToast = vi.fn();
    const d = createSettingsData({
      listHighlights: listHighlights as never,
      pushToast: pushToast as never,
    });

    await d.handleExportBook('missing', [makeBook()]);

    expect(listHighlights).not.toHaveBeenCalled();
    expect(capturedBlob).toBeNull();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.bookExportNone');
  });

  it('exports everything as one envelope with every module in canonical order', async () => {
    const listHighlights = vi.fn().mockResolvedValue([highlighted]);
    const pushToast = vi.fn();
    const d = createSettingsData({
      listHighlights: listHighlights as never,
      pushToast: pushToast as never,
    });

    await d.handleExportEverything([makeBook()], collections);

    expect(downloaded).toMatch(/^nexo-export-\d{4}-\d{2}-\d{2}\.json$/);
    const parsed = parseExportEnvelope(await capturedBlob!.text());
    expect(parsed.manifest.schema).toBe(1);
    expect(parsed.manifest.modules).toEqual(['books', 'annotations', 'collections']);
    expect(parsed.books).toHaveLength(1);
    expect(parsed.books![0]).not.toHaveProperty('coverPath');
    expect(parsed.annotations).toEqual([highlighted]);
    expect(parsed.collections).toEqual(collections);
    expect(pushToast).toHaveBeenCalledWith('success', 'settings.data.exportedEverything');
  });

  it('keeps every selected module in the manifest even when one array is empty', async () => {
    const listHighlights = vi.fn().mockResolvedValue([]);
    const d = createSettingsData({ listHighlights: listHighlights as never });

    await d.handleExportEverything([makeBook()], []);

    const parsed = parseExportEnvelope(await capturedBlob!.text());
    expect(parsed.manifest.modules).toEqual(['books', 'annotations', 'collections']);
    expect(parsed.annotations).toEqual([]);
    expect(parsed.collections).toEqual([]);
  });

  it('refuses the everything export when no module has anything and writes no file', async () => {
    const listHighlights = vi.fn().mockResolvedValue([]);
    const pushToast = vi.fn();
    const d = createSettingsData({
      listHighlights: listHighlights as never,
      pushToast: pushToast as never,
    });

    await d.handleExportEverything([], []);

    expect(capturedBlob).toBeNull();
    expect(downloaded).toBeNull();
    expect(pushToast).toHaveBeenCalledWith('error', 'settings.data.exportEverythingEmpty');
  });
});
