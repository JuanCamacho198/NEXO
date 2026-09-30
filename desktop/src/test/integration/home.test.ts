import { render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../../App.svelte';
import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
import { stubElementRect } from '../harness/jsdomHarness';
import type {
  BookDto,
  CollectionDto,
  LibraryBookDto,
  ReadingStatsSummaryDto,
  ReaderSettings,
  SearchBookTextResponse,
} from '$lib/shared/types';

const { tauriClientMock, pickFileMock, pickFolderMock, importBookMock } = vi.hoisted(() => {
  return {
    tauriClientMock: {
      getDefaultReaderSettings: vi.fn<() => ReaderSettings>(() => ({
        themeMode: 'paper',
        brightness: 100,
        contrast: 100,
        selectionColor: '#3388ff',
        epub: {
          fontSize: 100,
          fontFamily: 'serif',
        },
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
      })),
      getProgress: vi.fn(),
      getReaderSettings: vi.fn().mockResolvedValue({
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
      }),
      hideBookFromLibrary: vi.fn(),
      getReadingStats: vi.fn(),
      listBooks: vi.fn(),
      listLibraryBooks: vi.fn(),
      saveProgress: vi.fn(),
      saveReadingSession: vi.fn(),
      searchBookText: vi.fn(),
      setReadingStatus: vi.fn().mockResolvedValue(undefined),
      upsertBook: vi.fn(),
      upsertBookCover: vi.fn(),
      updateBookProgress: vi.fn(),
      listCollections: vi.fn(),
      getBookCollections: vi.fn(),
      addBookToCollection: vi.fn(),
      removeBookFromCollection: vi.fn(),
      scanFolder: vi.fn(),
      extractEpubCover: vi.fn().mockResolvedValue(true),
      getFileBytes: vi.fn().mockResolvedValue([]),
      // Exhaustive coverage: every export of tauriClient.ts that a test
      // path might reach. Individual tests still override these with
      // mockResolvedValue / mockResolvedValueOnce in beforeEach.
      listHighlights: vi.fn().mockResolvedValue([]),
      createHighlight: vi.fn(),
      updateHighlight: vi.fn(),
      deleteHighlight: vi.fn(),
      saveHighlightTags: vi.fn().mockResolvedValue([]),
      listTags: vi.fn().mockResolvedValue([]),
      createTag: vi.fn(),
      listTagsForHighlight: vi.fn().mockResolvedValue([]),
      addDictionaryWord: vi.fn(),
      listDictionaryWords: vi.fn().mockResolvedValue([]),
      listBookmarks: vi.fn().mockResolvedValue([]),
      createCollection: vi.fn(),
      getSettings: vi.fn().mockResolvedValue([]),
      upsertSetting: vi.fn(),
      upsertSettings: vi.fn().mockResolvedValue(undefined),
      getLocaleSetting: vi.fn().mockResolvedValue(null),
      upsertLocaleSetting: vi.fn().mockResolvedValue(undefined),
      upsertReaderSettings: vi.fn().mockImplementation(async (s: unknown) => s),
      getFileSize: vi.fn().mockResolvedValue(0),
      readFileRange: vi.fn().mockResolvedValue([]),
      fileExists: vi.fn().mockResolvedValue(false),
      diagnose: vi.fn(),
      getLogs: vi.fn().mockResolvedValue([]),
    },
    pickFileMock: vi.fn(),
    pickFolderMock: vi.fn(),
    importBookMock: vi.fn(),
  };
});

vi.mock('$lib/shared/api/tauriClient', () => tauriClientMock);

// AppState.init() calls SyncService.setupOutboxProcessor() at boot; without a
// mock the real outbox processor fires invoke('listSyncOutboxReady') in jsdom
// and produces an unhandled rejection.
vi.mock('$lib/shared/services/SyncService', () => ({
  SyncService: {
    setupOutboxProcessor: vi.fn(),
    syncMetadata: vi.fn().mockResolvedValue(undefined),
    syncBookCatalog: vi.fn().mockResolvedValue(undefined),
  },
}));

// `AppState.init()` first calls `restoreSession()` from SupabaseAuthService.
// Mock it to return null so it falls through to `loadPersistedAuth()`.
vi.mock('$lib/shared/services/SupabaseAuthService', () => ({
  restoreSession: vi.fn(async () => null),
  signInAnonymously: vi.fn(async () => undefined),
  signOut: vi.fn(async () => undefined),
  registerSupabaseCallbackHandler: vi.fn(async () => undefined),
  unregisterCallbackHandler: vi.fn(),
}));

// `AppState.init()` calls `loadPersistedAuth()` to decide the initial route.
// Without a cached session it lands on `'welcome'`, which would render the
// welcome screen instead of the home shell these tests assert against. Mock
// a local profile so the app boots straight into the home route.
vi.mock('$lib/shared/stores/authPersistence', () => ({
  loadPersistedAuth: vi.fn(async () => ({
    kind: 'local',
    profile: {
      name: 'Test User',
      email: null,
      avatarUrl: null,
      localOnly: true,
    },
  })),
  clearPersistedAuth: vi.fn(async () => undefined),
  savePersistedAuth: vi.fn(async () => undefined),
}));

vi.mock('@tauri-apps/api/webviewWindow', () => ({
  getCurrentWebviewWindow: () => ({
    setFullscreen: vi.fn(),
    minimize: vi.fn(),
    toggleMaximize: vi.fn(),
    close: vi.fn(),
    isMaximized: vi.fn().mockResolvedValue(false),
    onResized: vi.fn(),
  }),
}));

vi.mock('$lib/shared/services/FilePicker', () => ({
  pickFile: pickFileMock,
  pickFolder: pickFolderMock,
}));

vi.mock('$lib/shared/services/BookImportService', () => ({
  importBook: importBookMock,
  BulkImportService: class {
    cancel = vi.fn();
    importFolder = vi.fn();
  },
}));

vi.mock('$lib/shared/services/pdfThumbnail', () => ({
  extractPdfMetadata: vi.fn(async () => ({
    author: null,
    totalPages: null,
    thumbnailBytes: null,
  })),
}));

vi.mock('$lib/features/reader/viewer-pdf/PdfViewer.svelte', async () => {
  const mod = await import('../stubs/ViewerStub.svelte');
  return { default: mod.default };
});

vi.mock('$lib/features/home/components/HomeDesktopView.svelte', async () => {
  const mod = await import('../stubs/HomeDesktopViewStub.svelte');
  return { default: mod.default };
});

const dictionary: Record<string, string> = {
  'app.title': 'Nexo Desktop',
  'app.brandPlaceholder': 'Reading workspace',
  'app.homeNavLabel': 'Home navigation',
  'app.navBookshelf': 'Bookshelf',
  'app.navFuture': 'Future',
  'app.importBook': 'Import book',
  'app.importing': 'Importing',
  'app.menu': 'Menu',
  'app.openMenu': 'Open menu',
  'app.settings': 'Settings',
  'app.read': 'Read',
  'app.backToHome': 'Back to home',
  'app.openBookPrompt': 'Open a book',
  'app.locationLabel': 'Location',
  'app.start': 'Start',
  'app.homeReadHint': 'Select Read to resume',
  'app.unknownAuthor': 'Unknown author',
  'home.highlightsTitle': 'Highlights',
  'home.highlightsPlaceholder': 'Highlights and recent notes will appear here in a future update.',
  'home.continueReadingTitle': 'Continue Reading',
  'home.continueReadingHint': 'In progress',
  'home.continueReadingPlaceholder': 'No in-progress books yet',
  'home.myShelfTitle': 'My Shelf',
  'home.myShelfHint': 'Imported books',
  'home.myShelfPlaceholder': 'Import a book to populate your shelf',
  'home.shelfTab.all': 'Todos',
  'home.shelfTab.favorites': 'Favoritos',
  'home.shelfTab.toRead': 'Planeo leer',
  'home.shelfTab.completed': 'Completado',
  'home.shelfSortLabel': 'Sort shelf',
  'home.shelfSort.progress': 'Progress',
  'home.shelfSort.date': 'Date',
  'home.shelfSort.lastRead': 'Last read',
  'home.shelfSort.author': 'Author',
  'home.shelfSort.title': 'Title',
  'home.shelfSort.fileSize': 'File size',
  'home.shelfSearchPlaceholder': 'Search or use tokens like status:favoritos',
  'home.shelfClearSearch': 'Clear shelf search',
  'home.shelfWarningsLabel': 'Query warnings',
  'home.shelfSearchInvalid': 'Ignored tokens: {{value}}',
  'home.shelfSortFromQuery': 'Sort token active: {{value}}',
  'home.shelfResults': 'Showing {{count}} of {{total}} shelf books',
  'home.shelfNoResults': 'No shelf books match the current filters.',
  'library.grid': 'Grid',
  'library.list': 'List',
  'library.hide': 'Hide from library',
  'library.optionsFor': 'Options for {{title}}',
  'library.favoriteAdd': 'Add to favorites',
  'library.favoriteRemove': 'Remove from favorites',
  'library.removeFromShelf': 'Remove from shelf',
  'library.editMetadata.title': 'Edit Metadata',
  'shelf.removeConfirmTitle': 'Remove from shelf?',
  'shelf.removeConfirmBody': '{{title}} will be removed from your shelf.',
  'shelf.removeCancel': 'Cancel',
  'shelf.removeContinue': 'Continue',
  'shelf.removeLocalOnly': 'Local only',
  'shelf.removeLocalOnlySubtitle': 'Removes the book from this library. The Drive file is kept.',
  'shelf.removeLocalAndDrive': 'Local + Drive',
  'shelf.removeLocalAndDriveSubtitle': 'Moves the file to the Drive trash and notes the deletion.',
  'home.futureTitle': 'Workspace',
  'home.futurePlaceholder': 'Future widgets and shortcuts will be added here.',
  'sidebar.home': 'Inicio',
  'sidebar.library': 'Estantería',
  'sidebar.stats': 'Estadísticas',
  'sidebar.highlights': 'Notas y resaltados',
  'sidebar.settings': 'Ajustes',
  'sidebar.expand': 'Expandir sidebar',
  'sidebar.collapse': 'Colapsar sidebar',
  'settings.close': 'Close',
  'settings.title': 'Settings',
  'errors.commandFailure': 'Command failed',
  'shelf.lessThanMinute': '< 1 min',
  'shelf.formatHoursMinutes': '{{h}}h {{m}}m',
  'shelf.formatHours': '{{h}}h',
  'shelf.formatMins': '{{m}} min',
  'shelf.now': 'Ahora',
  'shelf.minutesAgo': 'Hace {{n}} min',
  'shelf.hoursAgo': 'Hace {{n}} h',
  'shelf.yesterday': 'Ayer',
  'shelf.daysAgo': 'Hace {{n}} días',
  'shelf.weeksAgo': 'Hace {{n}} sem.',
  'shelf.monthsAgo': 'Hace {{n}} meses',
  'shelf.yearsAgo': 'Hace {{n}} años',
  'shelf.statusReading': 'Leyendo',
  'shelf.statusToRead': 'Por leer',
  'shelf.statusCompleted': 'Completado',
  'shelf.otherGenre': 'Otro',
  'shelf.titleRequired': 'El título es obligatorio',
  'shelf.genreTooLong': 'El género no puede exceder 80 caracteres',
  'shelf.genreInvalidChars': 'El género contiene caracteres inválidos',
  'shelf.saveError': 'Error al guardar',
  'shelf.viewToggleAria': 'Vista de estantería',
  'shelf.viewGrid': 'Vista en cuadrícula',
  'shelf.viewList': 'Vista en lista',
};

vi.mock('$lib/shared/i18n', () => ({
  i18n: {
    initializeLocale: vi.fn(async () => 'en'),
    t: (locale: string, key: string, params?: Record<string, string | number>) => {
      void locale;
      const template = dictionary[key] ?? key;
      if (!params) {
        return template;
      }

      return template
        .replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_match, token) => String(params[token] ?? ''))
        .replace(/\{\s*([\w.-]+)\s*\}/g, (_match, token) => String(params[token] ?? ''));
    },
  },
}));

const makeLibraryBook = (overrides: Partial<LibraryBookDto> = {}): LibraryBookDto => ({
  id: 'book-1',
  title: 'Book One',
  author: 'Author One',
  format: 'epub',
  currentPage: 2,
  totalPages: 20,
  progressPercentage: 0,
  coverPath: null,
  minutesRead: 0,
  updatedAt: new Date().toISOString(),
  createdAt: new Date().toISOString(),
  ...overrides,
});

const makeShelfBook = (
  overrides: Partial<LibraryBookDto> & {
    fileSizeBytes?: number;
    lastReadAt?: string;
    createdAt?: string;
  } = {},
): LibraryBookDto => {
  return {
    ...makeLibraryBook(overrides),
    ...overrides,
  } as LibraryBookDto;
};

const makeSourceBook = (id: string, filePath: string): BookDto => ({
  id,
  title: 'Source',
  author: '',
  filePath,
  format: filePath.toLowerCase().endsWith('.pdf') ? 'pdf' : 'epub',
  syncStatus: 'local',
  currentPage: 1,
  totalPages: 20,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
});

const defaultStats: ReadingStatsSummaryDto = {
  totalMinutesRead: 0,
  totalSessions: 0,
  booksStarted: 0,
  booksCompleted: 0,
  avgProgressPercentage: 0,
};

const collections: CollectionDto[] = [];
let libraryBooks: LibraryBookDto[] = [];

const configureLibrary = (books: LibraryBookDto[], sourceBooks?: BookDto[]): void => {
  libraryBooks = books;
  tauriClientMock.listLibraryBooks.mockResolvedValue(libraryBooks);
  tauriClientMock.listBooks.mockResolvedValue(
    sourceBooks ??
      libraryBooks.map((book) => makeSourceBook(book.id, `C:/library/${book.id}.epub`)),
  );
  tauriClientMock.listCollections.mockResolvedValue(collections);
  tauriClientMock.getBookCollections.mockResolvedValue([]);
  tauriClientMock.addBookToCollection.mockImplementation(
    ({ bookId, collectionId }: { bookId: string; collectionId: number }) => {
      const book = libraryBooks.find((b) => b.id === bookId);
      if (book) {
        book.collectionIds = [...new Set([...(book.collectionIds ?? []), collectionId])];
      }
      // Update mock resolved value so re-fetch via listLibraryBooks sees the change
      tauriClientMock.listLibraryBooks.mockResolvedValue([...libraryBooks]);
    },
  );
  tauriClientMock.removeBookFromCollection.mockImplementation(
    ({ bookId, collectionId }: { bookId: string; collectionId: number }) => {
      const book = libraryBooks.find((b) => b.id === bookId);
      if (book) {
        book.collectionIds = (book.collectionIds ?? []).filter((id) => id !== collectionId);
      }
      tauriClientMock.listLibraryBooks.mockResolvedValue([...libraryBooks]);
    },
  );
};

beforeEach(() => {
  vi.clearAllMocks();
  // The library singleton persists across tests in this file. Without a
  // reset, a test renders the previous test's books before its own
  // loadLibrary resolves (the empty-state test saw "Progress B").
  libraryState.books = [];
  libraryState.collections = [];
  tauriClientMock.getReaderSettings.mockResolvedValue(tauriClientMock.getDefaultReaderSettings());
  tauriClientMock.getReadingStats.mockResolvedValue(defaultStats);
  tauriClientMock.searchBookText.mockResolvedValue({
    items: [],
    total: 0,
    page: 1,
    pageSize: 200,
  } satisfies SearchBookTextResponse);
  tauriClientMock.getProgress.mockResolvedValue({
    cfiLocation: '',
    percentage: 0,
  });
  tauriClientMock.saveProgress.mockResolvedValue(undefined);
  tauriClientMock.saveReadingSession.mockResolvedValue(undefined);
  tauriClientMock.updateBookProgress.mockResolvedValue(undefined);
});

describe('App desktop home redesign QA scenarios', () => {
  it('renders desktop home shell and sections on first load', async () => {
    configureLibrary([
      makeLibraryBook({ id: 'shelf-a', title: 'Shelf A', progressPercentage: 0 }),
      makeLibraryBook({ id: 'continue-b', title: 'Continue B', progressPercentage: 24 }),
    ]);

    render(App);

    expect(await screen.findByTestId('home-desktop-view-stub')).toBeInTheDocument();
    expect(screen.getByTestId('continue-section')).toBeInTheDocument();
    expect(screen.getByTestId('shelf-section')).toBeInTheDocument();
  });

  it('shows in-progress books in both continue reading and shelf', async () => {
    configureLibrary([
      makeLibraryBook({ id: 'shelf-a', title: 'Shelf A', progressPercentage: 0 }),
      makeLibraryBook({ id: 'progress-b', title: 'Progress B', progressPercentage: 17 }),
      makeLibraryBook({ id: 'complete-c', title: 'Complete C', progressPercentage: 100 }),
    ]);

    render(App);

    const continueSection = await screen.findByTestId('continue-section');
    const shelfSection = await screen.findByTestId('shelf-section');

    await waitFor(() => {
      expect(continueSection).toHaveTextContent('Progress B');
      expect(continueSection).not.toHaveTextContent('Shelf A');
      expect(continueSection).not.toHaveTextContent('Complete C');

      expect(shelfSection).toHaveTextContent('Shelf A');
      expect(shelfSection).toHaveTextContent('Complete C');
      expect(shelfSection).toHaveTextContent('Progress B');
    });
  });

  it('shows empty-state messages for both sections when library is empty', async () => {
    configureLibrary([]);

    render(App);

    const continueSection = await screen.findByTestId('continue-section');
    const shelfSection = await screen.findByTestId('shelf-section');

    expect(continueSection).toHaveTextContent('No in-progress books yet');
    expect(shelfSection).toHaveTextContent('Import a book to populate your shelf');
  });

  it('opens shelf details modal and closes it via close action', async () => {
    configureLibrary([
      makeLibraryBook({
        id: 'shelf-a',
        title: 'Shelf A',
        progressPercentage: 0,
        author: 'Author A',
      }),
    ]);

    render(App);
    const user = userEvent.setup();

    const shelfButtons = await screen.findAllByRole('button', { name: /Shelf A/i });
    await user.click(shelfButtons[0]);
    expect(await screen.findByRole('dialog', { name: 'Shelf A' })).toBeInTheDocument();

    const closeButtons = screen.getAllByRole('button', { name: 'Close' });
    await user.click(closeButtons[0]);
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Shelf A' })).not.toBeInTheDocument();
    });
  });

  it('read from shelf promotes book, enters reader, and returns to home shell', async () => {
    configureLibrary([makeLibraryBook({ id: 'shelf-a', title: 'Shelf A', progressPercentage: 0 })]);

    render(App);
    const user = userEvent.setup();

    const shelfButtons = await screen.findAllByRole('button', { name: /Shelf A/i });
    await user.click(shelfButtons[0]);
    expect(await screen.findByRole('dialog', { name: 'Shelf A' })).toBeInTheDocument();
    const dialog = screen.getByRole('dialog', { name: 'Shelf A' });
    await user.click(within(dialog).getByRole('button', { name: 'Read' }));

    expect(await screen.findByRole('button', { name: 'reader.biblioteca' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Shelf A' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'reader.biblioteca' }));
    expect(await screen.findByTestId('home-desktop-view-stub')).toBeInTheDocument();

    const continueSection = screen.getByTestId('continue-section');
    const shelfSection = screen.getByTestId('shelf-section');
    expect(continueSection).toHaveTextContent('Shelf A');
    expect(shelfSection).toHaveTextContent('Shelf A');
  });

  it('updates preview context when a continue-reading card is selected', async () => {
    configureLibrary([
      makeLibraryBook({ id: 'progress-b', title: 'Progress B', progressPercentage: 17 }),
    ]);

    render(App);
    const user = userEvent.setup();

    // The card content button contains "Progress B" in its accessible name;
    // scoped to the continue section to avoid matching the ShelfActionMenu trigger.
    // Use the async findAllByRole so we wait for the library load to populate
    // the section (the mock resolves on the next microtask).
    const continueSection = await screen.findByTestId('continue-section');
    const cardButtons = await within(continueSection).findAllByRole('button', {
      name: /Progress B/i,
    });
    // Pick the card content button (first one is the card, second is the actions menu trigger)
    await user.click(cardButtons[0]);

    await waitFor(() => {
      expect(screen.getByTestId('selected-book-title')).toHaveTextContent('Progress B');
    });
  });

  it('navigates Home -> Highlights -> Settings -> Reader and back deterministically', async () => {
    configureLibrary([
      makeLibraryBook({ id: 'shelf-a', title: 'Shelf A', progressPercentage: 0 }),
      makeLibraryBook({ id: 'shelf-b', title: 'Shelf B', progressPercentage: 0 }),
    ]);

    render(App);
    const user = userEvent.setup();

    expect(await screen.findByTestId('home-desktop-view-stub')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Notas y resaltados/ }));
    expect(screen.getByRole('heading', { name: 'Highlights' })).toBeInTheDocument();
    expect(screen.queryByTestId('home-desktop-view-stub')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Inicio/ }));
    expect(await screen.findByTestId('home-desktop-view-stub')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Ajustes/ }));
    expect(await screen.findByRole('tablist', { name: 'Settings' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Inicio/ }));
    expect(await screen.findByTestId('home-desktop-view-stub')).toBeInTheDocument();

    const homeShelfButtons = screen.getAllByRole('button', { name: /Shelf A/i });
    await user.click(homeShelfButtons[0]);
    const shelfDialog = await screen.findByRole('dialog', { name: 'Shelf A' });
    await user.click(within(shelfDialog).getByRole('button', { name: 'Read' }));
    expect(await screen.findByRole('button', { name: 'reader.biblioteca' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'reader.biblioteca' }));
    expect(await screen.findByTestId('home-desktop-view-stub')).toBeInTheDocument();
  });

  it('applies shelf tabs, smart-query search, and token warnings', async () => {
    configureLibrary([
      makeShelfBook({
        id: 'fav-1',
        title: 'Favorites Book',
        author: 'Asimov',
        progressPercentage: 0,
        collectionIds: [1],
      }),
      makeShelfBook({ id: 'todo-1', title: 'Todo Book', author: 'Borges', progressPercentage: 0 }),
      makeShelfBook({
        id: 'plan-1',
        title: 'Plan Book',
        author: 'Le Guin',
        progressPercentage: 0,
        readingStatus: 'to_read',
      }),
    ]);

    render(App);
    const user = userEvent.setup();

    const shelfSection = await screen.findByTestId('shelf-section');
    await waitFor(() => {
      expect(shelfSection).toHaveTextContent('Favorites Book');
      expect(shelfSection).toHaveTextContent('Todo Book');
      expect(shelfSection).toHaveTextContent('Plan Book');
    });

    await user.click(screen.getByTestId('shelf-tab-favorites'));
    await waitFor(() => {
      expect(shelfSection).toHaveTextContent('Favorites Book');
      expect(shelfSection).not.toHaveTextContent('Todo Book');
      expect(shelfSection).not.toHaveTextContent('Plan Book');
    });

    const searchInput = screen.getByTestId('shelf-search');
    await user.clear(searchInput);
    await user.type(searchInput, 'author:asimov foo:bar');

    await waitFor(() => {
      expect(shelfSection).toHaveTextContent('Favorites Book');
      expect(shelfSection).toHaveTextContent('Ignored tokens: foo:bar');
      expect(screen.getByTestId('shelf-warnings')).toBeInTheDocument();
    });
  });

  it('switches shelf between grid and list modes', async () => {
    configureLibrary([
      makeShelfBook({ id: 'a-1', title: 'Alpha', author: 'Author A', progressPercentage: 0 }),
      makeShelfBook({ id: 'b-1', title: 'Beta', author: 'Author B', progressPercentage: 0 }),
    ]);

    render(App);
    const user = userEvent.setup();

    const shelfSection = await screen.findByTestId('shelf-section');
    await waitFor(() => {
      expect(shelfSection.querySelector('ul.grid')).not.toBeNull();
    });

    const viewToggle = screen.getByTestId('shelf-view-toggle');
    await user.click(within(viewToggle).getByRole('button', { name: 'Vista en lista' }));

    await waitFor(() => {
      expect(shelfSection.querySelector('ul.space-y-2')).not.toBeNull();
    });
  });

  it('applies shelf sort control to the current filtered results', async () => {
    configureLibrary([
      makeShelfBook({ id: 'book-c', title: 'Gamma', author: 'Carlos', progressPercentage: 0 }),
      makeShelfBook({ id: 'book-a', title: 'Alpha', author: 'Ana', progressPercentage: 0 }),
      makeShelfBook({ id: 'book-b', title: 'Beta', author: 'Bruno', progressPercentage: 0 }),
    ]);

    render(App);
    const user = userEvent.setup();

    const shelfSection = await screen.findByTestId('shelf-section');
    const sortContainer = screen.getByTestId('shelf-sort');
    const trigger = sortContainer.querySelector('button')!;
    // The sort options are bits-ui `Select` items now, so the popup is
    // portalled and floating-ui hides it while the trigger reports no geometry
    // (jsdom has no layout engine). Both lines below are what make the options
    // real to the a11y tree; the assertion after them is unchanged.
    stubElementRect(trigger, { left: 100, top: 100, width: 130, height: 40 });
    await user.click(trigger);
    const authorOption = screen.getByRole('option', { name: 'Author' });
    await user.click(authorOption);

    await waitFor(() => {
      const titles = Array.from(shelfSection.querySelectorAll('li button p:first-child')).map(
        (node) => node.textContent?.trim(),
      );
      expect(titles).toEqual(['Alpha', 'Beta', 'Gamma']);
    });
  });

  it('uses dedicated single-item visual treatment for shelf results', async () => {
    configureLibrary([
      makeShelfBook({
        id: 'solo-shelf',
        title: 'Solo Shelf',
        author: 'Only Author',
        progressPercentage: 0,
      }),
    ]);

    render(App);

    const shelfSection = await screen.findByTestId('shelf-section');

    await waitFor(() => {
      expect(shelfSection.querySelector('article')).not.toBeNull();
      expect(shelfSection.querySelector('ul.grid')).toBeNull();
      expect(shelfSection.querySelector('ul.space-y-2')).toBeNull();
      expect(shelfSection).toHaveTextContent('Solo Shelf');
    });
  });

  it('supports keyboard-accessible shelf action menu semantics and focus return', async () => {
    configureLibrary([
      makeShelfBook({
        id: 'menu-1',
        title: 'Menu Book',
        author: 'Author A',
        progressPercentage: 0,
      }),
    ]);

    render(App);
    const user = userEvent.setup();

    const trigger = await screen.findByTestId('shelf-actions-trigger-menu-1');
    trigger.focus();

    await user.keyboard('{ArrowDown}');
    const menu = await screen.findByRole('menu', { name: 'Options for Menu Book' });
    expect(trigger).toHaveAttribute('aria-expanded', 'true');

    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitem', { name: 'Edit Metadata' })).toHaveFocus();

    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.queryByRole('menu', { name: 'Options for Menu Book' })).not.toBeInTheDocument();
    });
    expect(trigger).toHaveFocus();

    await user.keyboard('{ArrowDown}');
    expect(await screen.findByRole('menu', { name: 'Options for Menu Book' })).toBeInTheDocument();
    await user.keyboard('{Tab}');
    await waitFor(() => {
      expect(screen.queryByRole('menu', { name: 'Options for Menu Book' })).not.toBeInTheDocument();
    });

    expect(menu).toBeTruthy();
  });

  it('renders required shelf actions and wires favorite toggle + remove', async () => {
    configureLibrary([
      makeShelfBook({
        id: 'actions-1',
        title: 'Actions Book',
        author: 'Author A',
        progressPercentage: 0,
      }),
    ]);

    render(App);
    const user = userEvent.setup();

    const trigger = await screen.findByTestId('shelf-actions-trigger-actions-1');
    trigger.focus();
    await user.keyboard('{ArrowDown}');

    expect(await screen.findByRole('menuitem', { name: 'Add to favorites' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Edit Metadata' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Remove from shelf' })).toBeInTheDocument();

    await user.click(screen.getByRole('menuitem', { name: 'Add to favorites' }));

    await waitFor(() => {
      expect(tauriClientMock.addBookToCollection).toHaveBeenCalledWith({
        bookId: 'actions-1',
        collectionId: 1,
      });
    });

    trigger.focus();
    await user.keyboard('{ArrowDown}');
    expect(
      await screen.findByRole('menuitem', { name: 'Remove from favorites' }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('menuitem', { name: 'Remove from shelf' }));

    // Step 1: confirmation modal opens (REQ-09, SCN-10)
    const removeDialog = await screen.findByRole('dialog', { name: 'Remove from shelf?' });
    expect(removeDialog).toBeInTheDocument();
    await user.click(within(removeDialog).getByRole('button', { name: 'Continue' }));

    // Step 2: "Local only" keeps current behavior — no Drive request (SCN-12)
    expect(within(removeDialog).getByRole('button', { name: /Local only/ })).toBeInTheDocument();
    expect(
      within(removeDialog).getByRole('button', { name: /Local \+ Drive/ }),
    ).toBeInTheDocument();
    await user.click(within(removeDialog).getByRole('button', { name: /Local only/ }));

    await waitFor(() => {
      expect(tauriClientMock.hideBookFromLibrary).toHaveBeenCalledWith('actions-1');
    });
  });
});
