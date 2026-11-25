import type {
  BookDto,
  CollectionDto,
  CreateCollectionInput,
  BookCollectionInput,
  LibraryBookDto,
  UpsertBookCoverInput,
  ScanFolderResult,
} from '$lib/shared/types';

export type PerBookSize = {
  id: string;
  title: string;
  bytes: number;
};

export type UpsertBookInput = {
  id: string;
  title: string;
  author: string;
  filePath?: string;
  format?: string;
  syncStatus?: string;
  currentPage?: number;
  totalPages?: number;
  createdAt: string;
  updatedAt: string;
  genre?: string | null;
  language?: string | null;
  publicationDate?: string | null;
};

export interface LibraryPort {
  listLibraryBooks(responseVersion?: number): Promise<LibraryBookDto[]>;
  listBooks(): Promise<BookDto[]>;
  scanFolder(path: string): Promise<ScanFolderResult>;
  upsertBook(book: UpsertBookInput): Promise<void>;
  hideBook(bookId: string): Promise<void>;
  setReadingStatus(bookId: string, status: string | null): Promise<void>;
  listCollections(): Promise<CollectionDto[]>;
  createCollection(payload: CreateCollectionInput): Promise<CollectionDto>;
  deleteCollection(id: number): Promise<void>;
  addBookToCollection(payload: BookCollectionInput): Promise<void>;
  removeBookFromCollection(payload: BookCollectionInput): Promise<void>;
  getBookCollections(bookId: string): Promise<CollectionDto[]>;
  upsertBookCover(payload: UpsertBookCoverInput): Promise<void>;
  deleteBookCover(bookId: string): Promise<void>;
  extractEpubCover(bookId: string, filePath: string): Promise<boolean>;
  getPerBookSizes(): Promise<PerBookSize[]>;
  getFileBytes(filePath: string): Promise<number[]>;
  saveBookFile(
    id: string,
    data: number[],
    meta?: { title?: string; author?: string; format?: string },
  ): Promise<void>;
  fileExists(path: string): Promise<boolean>;
  getFileSize(filePath: string): Promise<number>;
  updateBookProgress(bookId: string, currentPage: number): Promise<void>;
}
