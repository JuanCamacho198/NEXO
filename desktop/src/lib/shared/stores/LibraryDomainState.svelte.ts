import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { recordMetric } from '$lib/shared/logger/MetricsStore';
import { METRIC_NAMES } from '$lib/shared/logger/metricTypes';
import {
  createShelfQueryState,
  updateShelfQueryState,
  partitionHomeBooks,
  selectShelfBooks,
  getShelfQueryWarnings,
  promoteBookForReading,
} from '$lib/shared/stores/HomeState';
import {
  ensureEpubCover as coverEpub,
  ensurePdfCover as coverPdf,
  runCoverBatches,
} from './libraryCoverStrategy';
import { handleRemoveBookFromDrive as removeFlow } from './libraryRemoveFlow';
import type { BookDto, CollectionDto, LibraryBookDto, ReaderBook } from '$lib/shared/types';
type MaybeCommandError = Error & {
  commandError?: { code: string; message: string; recoverable: boolean };
};
class LibraryDomainState {
  private readonly libraryPort: LibraryPort;
  constructor(deps: { libraryPort?: LibraryPort } = {}) {
    this.libraryPort = deps.libraryPort ?? new TauriLibraryAdapter();
  }
  // ─── State 8 ───
  books = $state<ReaderBook[]>([]);
  shelfQueryState = $state(createShelfQueryState(''));
  collections = $state<CollectionDto[]>([]);
  isLoadingLibrary = $state(false);
  readerError = $state<string | null>(null);
  editingBook = $state<ReaderBook | null>(null);
  pendingRemoveBook = $state<ReaderBook | null>(null);
  thumbnailGenerationInFlight = new Set<string>();
  thumbnailGenerationAttempted = new Set<string>();
  // ─── derived 5 ───
  continueReadingBooks = $derived.by(() => partitionHomeBooks(this.books).continueReadingBooks);
  myShelfBooks = $derived.by(() => partitionHomeBooks(this.books).myShelfBooks);
  shelfBooks = $derived.by(() => selectShelfBooks(this.myShelfBooks, this.shelfQueryState));
  shelfWarnings = $derived.by(() => getShelfQueryWarnings(this.shelfQueryState));
  shelfSortToken = $derived.by(() => {
    for (let i = this.shelfQueryState.smartTokens.length - 1; i >= 0; i--) {
      const tok = this.shelfQueryState.smartTokens[i];
      if (tok.field === 'sort') return tok.value;
    }
    return null;
  });
  // ─── Constants ───
  readonly SHELF_TAB_OPTIONS = [
    { key: 'all', label: 'home.shelfTab.all' },
    { key: 'favorites', label: 'home.shelfTab.favorites' },
    { key: 'to_read', label: 'home.shelfTab.toRead' },
    { key: 'completed', label: 'home.shelfTab.completed' },
  ] as const;
  readonly SHELF_SORT_OPTIONS = [
    { key: 'progress', label: 'home.shelfSort.progress' },
    { key: 'date', label: 'home.shelfSort.date' },
    { key: 'last_read', label: 'home.shelfSort.lastRead' },
    { key: 'author', label: 'home.shelfSort.author' },
    { key: 'title', label: 'home.shelfSort.title' },
    { key: 'file_size', label: 'home.shelfSort.fileSize' },
  ] as const;
  getBookById(bookId: string | null): ReaderBook | null {
    if (!bookId) return null;
    return this.books.find((b) => b.id === bookId) ?? null;
  }
  hasResolvedCoverPath(book: Pick<LibraryBookDto, 'coverPath'>): boolean {
    return typeof book.coverPath === 'string' && book.coverPath.trim().length > 0;
  }
  shouldGeneratePdfCover(book: ReaderBook): boolean {
    if (book.format.toLowerCase() !== 'pdf') return false;
    if (this.hasResolvedCoverPath(book)) return false;
    return book.filePath.trim().length > 0;
  }
  shouldGenerateEpubCover(book: ReaderBook): boolean {
    if (book.format.toLowerCase() !== 'epub') return false;
    if (this.hasResolvedCoverPath(book)) return false;
    if (book.coverUserDeleted) return false;
    return book.filePath.trim().length > 0;
  }
  async ensureEpubCover(book: ReaderBook): Promise<void> {
    await coverEpub(book, {
      libraryPort: this.libraryPort,
      loadLibrary: () => this.loadLibrary(),
      inFlight: this.thumbnailGenerationInFlight,
    });
  }
  async ensurePdfCover(book: ReaderBook): Promise<void> {
    await coverPdf(book, {
      libraryPort: this.libraryPort,
      loadLibrary: () => this.loadLibrary(),
      inFlight: this.thumbnailGenerationInFlight,
    });
  }
  async loadLibrary(): Promise<void> {
    this.isLoadingLibrary = true;
    this.readerError = null;
    try {
      const [libraryRows, sourceRows, loadedCollections] = await Promise.all([
        this.libraryPort.listLibraryBooks(1),
        this.libraryPort.listBooks(),
        this.libraryPort.listCollections(),
      ]);
      this.collections = loadedCollections;
      const filePathById = new Map<string, string>(
        sourceRows.map((b: BookDto) => [b.id, b.filePath]),
      );
      this.books = libraryRows.map((entry: LibraryBookDto) => ({
        ...entry,
        filePath: filePathById.get(entry.id) ?? '',
        collectionIds: entry.collectionIds ?? [],
      }));
      this._booksJustChanged = true;
      await runCoverBatches(this.books, {
        shouldGeneratePdfCover: (b) => this.shouldGeneratePdfCover(b),
        shouldGenerateEpubCover: (b) => this.shouldGenerateEpubCover(b),
        attempted: this.thumbnailGenerationAttempted,
        ensureEpubCover: (b) => this.ensureEpubCover(b),
        ensurePdfCover: (b) => this.ensurePdfCover(b),
      });
    } catch (error) {
      const typed = error as MaybeCommandError;
      if (typed.commandError?.recoverable)
        this._lastRecoverableError = {
          code: typed.commandError.code,
          message: typed.commandError.message,
        };
      else
        this.readerError =
          typed.commandError?.message ?? (error instanceof Error ? error.message : 'Unknown error');
    } finally {
      this.isLoadingLibrary = false;
    }
  }
  _booksJustChanged = false;
  _lastRecoverableError: { code: string; message: string } | null = null;
  consumeBooksJustChanged(): boolean {
    const v = this._booksJustChanged;
    this._booksJustChanged = false;
    return v;
  }
  consumeLastRecoverableError(): { code: string; message: string } | null {
    const v = this._lastRecoverableError;
    this._lastRecoverableError = null;
    return v;
  }
  private messageFrom(error: unknown): string {
    const t = error as MaybeCommandError;
    return t.commandError?.message ?? (error instanceof Error ? error.message : 'Unknown error');
  }
  async handleHideBook(book: ReaderBook): Promise<void> {
    try {
      await this.libraryPort.hideBook(book.id);
      await this.loadLibrary();
    } catch (e) {
      this.readerError = this.messageFrom(e);
    }
  }
  async handleRemoveBookFromDrive(book: ReaderBook): Promise<void> {
    await removeFlow(book, {
      libraryPort: this.libraryPort,
      loadLibrary: () => this.loadLibrary(),
      setReaderError: (m) => (this.readerError = m),
    });
  }
  async handleToggleFavorite(book: ReaderBook): Promise<void> {
    const isFav = book.collectionIds?.includes(1) ?? false;
    try {
      if (isFav)
        await this.libraryPort.removeBookFromCollection({ bookId: book.id, collectionId: 1 });
      else await this.libraryPort.addBookToCollection({ bookId: book.id, collectionId: 1 });
      await this.loadLibrary();
    } catch (e) {
      this.readerError = this.messageFrom(e);
    }
  }
  async handleStatusChange(
    book: ReaderBook,
    status: 'to_read' | 'reading' | 'completed',
  ): Promise<void> {
    try {
      await this.libraryPort.setReadingStatus(book.id, status);
      await this.loadLibrary();
    } catch (e) {
      this.readerError = this.messageFrom(e);
    }
  }
  handleEditBook(book: ReaderBook): void {
    this.editingBook = book;
  }
  async handleDeleteCover(book: ReaderBook): Promise<void> {
    await this.libraryPort.deleteBookCover(book.id);
    const f = this.books.find((b) => b.id === book.id);
    if (f) f.coverPath = null;
  }
  async handleSaveEditedBook(updatedBook: LibraryBookDto): Promise<void> {
    try {
      const rb = this.books.find((b) => b.id === updatedBook.id);
      if (!rb) return;
      const normalizedGenre =
        typeof updatedBook.genre === 'string' && updatedBook.genre.trim().length > 0
          ? updatedBook.genre.trim()
          : null;
      await this.libraryPort.upsertBook({
        id: updatedBook.id,
        title: updatedBook.title,
        author: updatedBook.author || '',
        filePath: rb.filePath,
        format: rb.format,
        syncStatus: 'local' as const,
        currentPage: rb.currentPage,
        totalPages: rb.totalPages,
        createdAt: rb.createdAt,
        updatedAt: rb.updatedAt,
        genre: normalizedGenre,
      });
      this.books = this.books.map((b) =>
        b.id === updatedBook.id
          ? { ...b, title: updatedBook.title, author: updatedBook.author, genre: normalizedGenre }
          : b,
      );
      this.editingBook = null;
    } catch (e) {
      this.readerError = this.messageFrom(e);
    }
  }
  setShelfTab(tab: (typeof this.SHELF_TAB_OPTIONS)[number]['key']): void {
    this.shelfQueryState = updateShelfQueryState(this.shelfQueryState, { tab });
  }
  setShelfSort(sortKey: (typeof this.SHELF_SORT_OPTIONS)[number]['key']): void {
    this.shelfQueryState = updateShelfQueryState(this.shelfQueryState, { sortKey });
  }
  setShelfViewMode(viewMode: 'grid' | 'list'): void {
    this.shelfQueryState = updateShelfQueryState(this.shelfQueryState, { viewMode });
  }
  handleShelfQueryInput(event: Event): void {
    const t = event.target as HTMLInputElement;
    this.shelfQueryState = updateShelfQueryState(this.shelfQueryState, { rawQuery: t.value });
  }
  clearShelfQuery(): void {
    this.shelfQueryState = updateShelfQueryState(this.shelfQueryState, { rawQuery: '' });
  }
  promoteBookForReading(bookId: string): void {
    this.books = promoteBookForReading(this.books, bookId);
  }
  updateBookPage(bookId: string, page: number, total: number): void {
    this.books = this.books.map((b) =>
      b.id === bookId ? { ...b, currentPage: page, totalPages: total } : b,
    );
  }
  recordReaderOpenMetric(format: string): void {
    recordMetric(METRIC_NAMES.READER_OPEN, { feature: format.toLowerCase() });
  }
  // ─── Facade notes ───
  // 7 state: books, shelfQueryState, collections, isLoadingLibrary, readerError, editingBook, pendingRemoveBook
  // 5 derived: continueReadingBooks, myShelfBooks, shelfBooks, shelfWarnings, shelfSortToken
}
export const libraryState = new LibraryDomainState();
export { LibraryDomainState };
