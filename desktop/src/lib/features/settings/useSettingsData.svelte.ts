import { storageState as defaultStorageState } from '$lib/shared/stores/StorageState.svelte';
import { DriveColdBackupService as DefaultDriveColdBackupService } from '$lib/shared/services';
import { isDriveAuthorized as defaultIsDriveAuthorized } from '$lib/shared/services/DriveConnectService';
import { authState as defaultAuthState } from '$lib/shared/stores/AuthState.svelte';
import { pushToast as defaultPushToast } from '$lib/shared/stores/ToastQueue.svelte';
import {
  dictionaryState as defaultDictionaryState,
  type DictionaryStateApi,
} from '$lib/shared/stores/DictionaryState.svelte';
import type { CollectionDto, HighlightDto, LibraryBookDto } from '$lib/shared/types';
import { TauriViewerAdapter } from '$lib/shared/ports';
import { buildExportEnvelope, projectBookForExport } from './exportEnvelope';

export type DriveAuthDep = {
  isAuthorized: () => Promise<boolean>;
};

/** The two store operations the dictionary transfer needs; nothing else. */
export type DictionaryTransferDep = Pick<DictionaryStateApi, 'exportData' | 'importData'>;

/** The real highlight read path (ViewerPort), injected so tests stay pure. */
export type HighlightLister = (bookId?: string) => Promise<HighlightDto[]>;

export type DataDeps = {
  storageState?: typeof defaultStorageState;
  DriveColdBackupService?: typeof DefaultDriveColdBackupService;
  drive?: DriveAuthDep;
  authState?: typeof defaultAuthState;
  pushToast?: typeof defaultPushToast;
  t?: (key: string, params?: Record<string, string | number>) => string;
  dictionaryState?: DictionaryTransferDep;
  listHighlights?: HighlightLister;
};

export function createSettingsData(deps: DataDeps = {}): {
  isClearingCache: boolean;
  cacheCleared: boolean;
  selectedExportBook: string;
  selectedExportFormat: 'json' | 'markdown';
  annotationsOnlyWithNote: boolean;
  isExportingLibrary: boolean;
  isExportingHighlights: boolean;
  isExportingCollections: boolean;
  isExportingBook: boolean;
  isExportingEverything: boolean;
  isExportingColdBackup: boolean;
  isImportingColdBackup: boolean;
  isExportingDictionary: boolean;
  isImportingDictionary: boolean;
  dictionaryExportError: string | null;
  dictionaryImportResult: string | null;
  dictionaryImportError: string | null;
  isSaving: boolean;
  isDirty: boolean;
  handleClearCache: () => Promise<void>;
  handleExportLibrary: (books?: LibraryBookDto[]) => Promise<void>;
  handleExportHighlights: (books?: LibraryBookDto[]) => Promise<void>;
  handleExportCollections: (collections?: CollectionDto[]) => Promise<void>;
  handleExportBook: (bookId: string, books?: LibraryBookDto[]) => Promise<void>;
  handleExportEverything: (
    books?: LibraryBookDto[],
    collections?: CollectionDto[],
  ) => Promise<void>;
  handleExportColdBackup: () => Promise<void>;
  handleImportColdBackup: () => Promise<void>;
  handleExportDictionary: (format: 'json' | 'csv') => Promise<void>;
  handleImportDictionary: (file: File) => Promise<void>;
  handleSelectedExportBookChange: (value: string) => void;
  handleSelectedExportFormatChange: (value: 'json' | 'markdown') => void;
  handleAnnotationsOnlyWithNoteChange: (value: boolean) => void;
} {
  const storage = deps.storageState ?? defaultStorageState;
  const ColdBackup = deps.DriveColdBackupService ?? DefaultDriveColdBackupService;
  const drive: DriveAuthDep = deps.drive ?? { isAuthorized: defaultIsDriveAuthorized };
  const auth = deps.authState ?? defaultAuthState;
  const pushToast = deps.pushToast ?? defaultPushToast;
  const t = deps.t ?? ((k: string) => k);
  const dictionary = deps.dictionaryState ?? defaultDictionaryState;
  const listHighlights: HighlightLister =
    deps.listHighlights ?? ((bookId?: string) => new TauriViewerAdapter().listHighlights(bookId));

  let isClearingCache = $state(false);
  let cacheCleared = $state(false);
  let selectedExportBook = $state('all');
  let selectedExportFormat = $state<'json' | 'markdown'>('json');
  let annotationsOnlyWithNote = $state(false);
  let isExportingLibrary = $state(false);
  let isExportingHighlights = $state(false);
  let isExportingCollections = $state(false);
  let isExportingBook = $state(false);
  let isExportingEverything = $state(false);
  let isExportingColdBackup = $state(false);
  let isImportingColdBackup = $state(false);
  let isExportingDictionary = $state(false);
  let isImportingDictionary = $state(false);
  let dictionaryExportError = $state<string | null>(null);
  let dictionaryImportResult = $state<string | null>(null);
  let dictionaryImportError = $state<string | null>(null);

  const isSaving = $derived(
    isClearingCache ||
      isExportingLibrary ||
      isExportingHighlights ||
      isExportingCollections ||
      isExportingBook ||
      isExportingEverything ||
      isExportingColdBackup ||
      isImportingColdBackup,
  );
  const isDirty = $derived(selectedExportBook !== 'all' || selectedExportFormat !== 'json');

  /** `YYYY-MM-DD`, so exported files carry the day they were produced. */
  function fileStamp(): string {
    return new Date().toISOString().slice(0, 10);
  }

  /**
   * The single local file sink. The dictionary export already used this exact
   * object-URL + anchor pattern; the library and highlights exports reuse it
   * instead of inventing a second mechanism.
   */
  function downloadTextFile(content: string, filename: string, mime: string): void {
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  /**
   * Markdown rendering of the real HighlightDto fields: text, note,
   * pageNumber, color and createdAt, grouped under the book title and author
   * resolved from the same `books` prop the panel already receives. A
   * highlight whose book is absent falls back to a labelled raw id rather
   * than crashing or printing an unlabelled id.
   */
  function highlightsToMarkdown(rows: HighlightDto[], books: LibraryBookDto[]): string {
    const meta = new Map(books.map((b) => [b.id, { title: b.title, author: b.author }]));
    const byBook = new Map<string, HighlightDto[]>();
    for (const row of rows) {
      const list = byBook.get(row.bookId) ?? [];
      list.push(row);
      byBook.set(row.bookId, list);
    }
    const lines: string[] = ['# Highlights', ''];
    for (const [bookId, items] of byBook) {
      const book = meta.get(bookId);
      const heading = book
        ? book.author
          ? `${book.title} — ${book.author}`
          : book.title
        : `${t('settings.unknownBook')} (${bookId})`;
      lines.push(`## ${heading}`, '');
      for (const row of items) {
        lines.push(`> ${row.text.replace(/\n/g, '\n> ')}`, '');
        if (row.note) lines.push(`Note: ${row.note}`, '');
        lines.push(`Page ${row.pageNumber} · ${row.color} · ${row.createdAt}`, '');
      }
    }
    return lines.join('\n');
  }

  async function handleClearCache(): Promise<void> {
    isClearingCache = true;
    try {
      await storage.clearCache('temp', false);
      cacheCleared = true;
      pushToast('success', t('settings.data.cacheClearedToast'));
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes('storage.permission_denied')) {
        pushToast('error', t('storage.permissionDenied'));
      } else {
        pushToast('error', msg);
      }
    } finally {
      isClearingCache = false;
    }
  }

  async function handleExportLibrary(books: LibraryBookDto[] = []): Promise<void> {
    if (books.length === 0) {
      pushToast('error', t('settings.data.libraryExportEmpty'));
      return;
    }
    isExportingLibrary = true;
    try {
      const payload = buildExportEnvelope({
        books: books.map(projectBookForExport),
      });
      downloadTextFile(
        JSON.stringify(payload, null, 2),
        `nexo-library-${fileStamp()}.json`,
        'application/json',
      );
      pushToast('success', t('settings.data.libraryExported'));
    } catch (e) {
      pushToast('error', e instanceof Error ? e.message : t('settings.data.libraryExportFailed'));
    } finally {
      isExportingLibrary = false;
    }
  }

  async function handleExportHighlights(books: LibraryBookDto[] = []): Promise<void> {
    isExportingHighlights = true;
    try {
      const bookId = selectedExportBook === 'all' ? undefined : selectedExportBook;
      const allRows = await listHighlights(bookId);
      // "Only with a note" is a filter over the annotations module, not a
      // separate exporter: a note cannot exist without its highlight.
      const rows = annotationsOnlyWithNote ? allRows.filter((row) => Boolean(row.note)) : allRows;
      if (rows.length === 0) {
        pushToast('error', t('settings.data.highlightsExportEmpty'));
        return;
      }
      if (selectedExportFormat === 'markdown') {
        downloadTextFile(
          highlightsToMarkdown(rows, books),
          `nexo-highlights-${fileStamp()}.md`,
          'text/markdown',
        );
      } else {
        const payload = buildExportEnvelope({ annotations: rows });
        downloadTextFile(
          JSON.stringify(payload, null, 2),
          `nexo-highlights-${fileStamp()}.json`,
          'application/json',
        );
      }
      pushToast('success', t('settings.data.highlightsExported'));
    } catch (e) {
      pushToast(
        'error',
        e instanceof Error ? e.message : t('settings.data.highlightsExportFailed'),
      );
    } finally {
      isExportingHighlights = false;
    }
  }

  /**
   * The collections module on its own — one of the granular per-domain
   * scopes. Collections have no book files and no notes, so this is the whole
   * payload.
   */
  async function handleExportCollections(collections: CollectionDto[] = []): Promise<void> {
    if (collections.length === 0) {
      pushToast('error', t('settings.data.collectionsExportEmpty'));
      return;
    }
    isExportingCollections = true;
    try {
      const payload = buildExportEnvelope({ collections });
      downloadTextFile(
        JSON.stringify(payload, null, 2),
        `nexo-collections-${fileStamp()}.json`,
        'application/json',
      );
      pushToast('success', t('settings.data.collectionsExported'));
    } catch (e) {
      pushToast(
        'error',
        e instanceof Error ? e.message : t('settings.data.collectionsExportFailed'),
      );
    } finally {
      isExportingCollections = false;
    }
  }

  /**
   * The per-book scope: the book's own `books` entry plus the `annotations`
   * module filtered to that book. A book that is no longer in the list is a
   * refusal, not an empty file.
   */
  async function handleExportBook(bookId: string, books: LibraryBookDto[] = []): Promise<void> {
    const book = books.find((b) => b.id === bookId);
    if (!book) {
      pushToast('error', t('settings.data.bookExportNone'));
      return;
    }
    isExportingBook = true;
    try {
      const annotations = await listHighlights(book.id);
      const payload = buildExportEnvelope({
        books: [projectBookForExport(book)],
        annotations,
      });
      downloadTextFile(
        JSON.stringify(payload, null, 2),
        `nexo-book-${book.id}-${fileStamp()}.json`,
        'application/json',
      );
      pushToast('success', t('settings.data.bookExported'));
    } catch (e) {
      pushToast('error', e instanceof Error ? e.message : t('settings.data.bookExportFailed'));
    } finally {
      isExportingBook = false;
    }
  }

  /**
   * The everything scope: every module of the same envelope in one file. It
   * refuses only when there is nothing at all to write; a module that is empty
   * while another carries data is still listed as selected.
   */
  async function handleExportEverything(
    books: LibraryBookDto[] = [],
    collections: CollectionDto[] = [],
  ): Promise<void> {
    isExportingEverything = true;
    try {
      const annotations = await listHighlights();
      if (books.length === 0 && collections.length === 0 && annotations.length === 0) {
        pushToast('error', t('settings.data.exportEverythingEmpty'));
        return;
      }
      const payload = buildExportEnvelope({
        books: books.map(projectBookForExport),
        annotations,
        collections,
      });
      downloadTextFile(
        JSON.stringify(payload, null, 2),
        `nexo-export-${fileStamp()}.json`,
        'application/json',
      );
      pushToast('success', t('settings.data.exportedEverything'));
    } catch (e) {
      pushToast(
        'error',
        e instanceof Error ? e.message : t('settings.data.exportEverythingFailed'),
      );
    } finally {
      isExportingEverything = false;
    }
  }

  // Q4 decided: CTA-only. Export/import handlers never raise a modal —
  // the DriveConnectPrompt dialog stays scoped to the cloud-download
  // pre-prompt. Unauthorized cold backup routes to the Data-tab connect CTA
  // via this toast; the tab CTA performs the connect.
  function driveNotConnectedToast(): void {
    pushToast('error', t('settings.data.driveNotConnected'));
  }

  function isDriveConnectError(e: unknown): boolean {
    const code = (e as { code?: unknown } | null)?.code;
    return code === 'DRIVE_NOT_CONNECTED' || code === 'AUTH_REQUIRED';
  }

  async function handleExportColdBackup(): Promise<void> {
    const userId = auth.userId;
    if (!userId) {
      pushToast('error', t('errors.commandFailure'));
      return;
    }
    let authorized = false;
    try {
      authorized = await drive.isAuthorized();
    } catch {
      authorized = false;
    }
    if (!authorized) {
      driveNotConnectedToast();
      return;
    }
    isExportingColdBackup = true;
    try {
      await ColdBackup.exportColdBackup(userId);
      pushToast('success', t('settings.data.exportSuccess'));
    } catch (e) {
      if (isDriveConnectError(e)) driveNotConnectedToast();
      else pushToast('error', e instanceof Error ? e.message : t('errors.commandFailure'));
    } finally {
      isExportingColdBackup = false;
    }
  }

  async function handleImportColdBackup(): Promise<void> {
    const userId = auth.userId;
    if (!userId) {
      pushToast('error', t('errors.commandFailure'));
      return;
    }
    let authorized = false;
    try {
      authorized = await drive.isAuthorized();
    } catch {
      authorized = false;
    }
    if (!authorized) {
      driveNotConnectedToast();
      return;
    }
    isImportingColdBackup = true;
    try {
      const result = await ColdBackup.importColdBackup(userId);
      const failures = result?.failures ?? [];
      if (failures.length > 0) {
        const codes = [...new Set(failures.map((f) => f.error.code))].join(', ');
        pushToast(
          'error',
          t('settings.data.importPartialFailure', { count: failures.length, codes }),
        );
      } else {
        pushToast('success', t('settings.data.importSuccess'));
      }
    } catch (e) {
      if (isDriveConnectError(e)) driveNotConnectedToast();
      else pushToast('error', e instanceof Error ? e.message : t('errors.importCommandFailed'));
    } finally {
      isImportingColdBackup = false;
    }
  }

  /**
   * The dictionary transfer lives here (not on the dictionary screen) and is
   * deliberately independent from the cold-backup handlers above: it never
   * touches Drive, auth or the library — it only calls the dictionary store.
   */
  async function handleExportDictionary(format: 'json' | 'csv'): Promise<void> {
    dictionaryExportError = null;
    isExportingDictionary = true;
    try {
      const data = await dictionary.exportData(format);
      downloadTextFile(
        data,
        `dictionary.${format}`,
        format === 'json' ? 'application/json' : 'text/csv',
      );
    } catch (e) {
      dictionaryExportError =
        e instanceof Error ? e.message : t('settings.data.dictionary.exportFailed');
    } finally {
      isExportingDictionary = false;
    }
  }

  async function handleImportDictionary(file: File): Promise<void> {
    dictionaryImportError = null;
    dictionaryImportResult = null;
    isImportingDictionary = true;
    try {
      const text = await file.text();
      const format = file.name.endsWith('.csv') ? 'csv' : 'json';
      const res = await dictionary.importData(text, format);
      dictionaryImportResult = t('settings.data.dictionary.imported', {
        imported: res.imported,
        errors: res.errors.length,
      });
      if (res.errors.length) {
        dictionaryImportError = res.errors
          .map((x) => t('settings.data.dictionary.rowError', { row: x.row, reason: x.reason }))
          .join('; ');
      }
    } catch (err) {
      dictionaryImportError =
        err instanceof Error ? err.message : t('settings.data.dictionary.importFailed');
    } finally {
      isImportingDictionary = false;
    }
  }

  function handleSelectedExportBookChange(value: string): void {
    selectedExportBook = value;
  }

  function handleSelectedExportFormatChange(value: 'json' | 'markdown'): void {
    selectedExportFormat = value;
  }

  function handleAnnotationsOnlyWithNoteChange(value: boolean): void {
    annotationsOnlyWithNote = value;
  }

  return {
    get isClearingCache() {
      return isClearingCache;
    },
    set isClearingCache(v: boolean) {
      isClearingCache = v;
    },
    get cacheCleared() {
      return cacheCleared;
    },
    set cacheCleared(v: boolean) {
      cacheCleared = v;
    },
    get selectedExportBook() {
      return selectedExportBook;
    },
    set selectedExportBook(v: string) {
      selectedExportBook = v;
    },
    get selectedExportFormat() {
      return selectedExportFormat;
    },
    set selectedExportFormat(v: 'json' | 'markdown') {
      selectedExportFormat = v;
    },
    get annotationsOnlyWithNote() {
      return annotationsOnlyWithNote;
    },
    set annotationsOnlyWithNote(v: boolean) {
      annotationsOnlyWithNote = v;
    },
    get isExportingLibrary() {
      return isExportingLibrary;
    },
    set isExportingLibrary(v: boolean) {
      isExportingLibrary = v;
    },
    get isExportingHighlights() {
      return isExportingHighlights;
    },
    set isExportingHighlights(v: boolean) {
      isExportingHighlights = v;
    },
    get isExportingCollections() {
      return isExportingCollections;
    },
    set isExportingCollections(v: boolean) {
      isExportingCollections = v;
    },
    get isExportingBook() {
      return isExportingBook;
    },
    set isExportingBook(v: boolean) {
      isExportingBook = v;
    },
    get isExportingEverything() {
      return isExportingEverything;
    },
    set isExportingEverything(v: boolean) {
      isExportingEverything = v;
    },
    get isExportingColdBackup() {
      return isExportingColdBackup;
    },
    set isExportingColdBackup(v: boolean) {
      isExportingColdBackup = v;
    },
    get isImportingColdBackup() {
      return isImportingColdBackup;
    },
    set isImportingColdBackup(v: boolean) {
      isImportingColdBackup = v;
    },
    get isExportingDictionary() {
      return isExportingDictionary;
    },
    set isExportingDictionary(v: boolean) {
      isExportingDictionary = v;
    },
    get isImportingDictionary() {
      return isImportingDictionary;
    },
    set isImportingDictionary(v: boolean) {
      isImportingDictionary = v;
    },
    get dictionaryExportError() {
      return dictionaryExportError;
    },
    set dictionaryExportError(v: string | null) {
      dictionaryExportError = v;
    },
    get dictionaryImportResult() {
      return dictionaryImportResult;
    },
    set dictionaryImportResult(v: string | null) {
      dictionaryImportResult = v;
    },
    get dictionaryImportError() {
      return dictionaryImportError;
    },
    set dictionaryImportError(v: string | null) {
      dictionaryImportError = v;
    },
    get isSaving() {
      return isSaving;
    },
    get isDirty() {
      return isDirty;
    },
    handleClearCache,
    handleExportLibrary,
    handleExportHighlights,
    handleExportCollections,
    handleExportBook,
    handleExportEverything,
    handleExportColdBackup,
    handleImportColdBackup,
    handleExportDictionary,
    handleImportDictionary,
    handleSelectedExportBookChange,
    handleSelectedExportFormatChange,
    handleAnnotationsOnlyWithNoteChange,
  };
}
