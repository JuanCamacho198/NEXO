export type BookImportInput = {
  sourcePath: string;
  title?: string;
  author?: string;
  format: string;
  genre?: string | null;
};

export type ScannedFile = {
  fullPath: string;
  fileName: string;
  format: string;
  isDuplicate: boolean;
};

export type ScanFolderResult = {
  files: ScannedFile[];
  skippedUnsupportedCount: number;
  skippedUnreadableCount: number;
};

export const BULK_IMPORT_ITEM_STATUS = {
  QUEUED: 'queued',
  IMPORTING: 'importing',
  SUCCESS: 'success',
  SKIPPED: 'skipped',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
} as const;

export type BulkImportItemStatus =
  (typeof BULK_IMPORT_ITEM_STATUS)[keyof typeof BULK_IMPORT_ITEM_STATUS];

export type BulkImportItemResult = {
  file: ScannedFile;
  status: BulkImportItemStatus;
  bookId: string | null;
  message: string | null;
};

export type BulkImportSummary = {
  total: number;
  queued: number;
  importing: number;
  success: number;
  skipped: number;
  failed: number;
  cancelled: number;
  results: BulkImportItemResult[];
};

export type ReadingStatus = 'to_read' | 'reading' | 'completed';
export type ReadingStateVersion = number;

export type LibraryBookDto = {
  id: string;
  title: string;
  author: string;
  format: string;
  currentPage: number;
  totalPages: number;
  progressPercentage: number;
  coverPath: string | null;
  minutesRead: number;
  updatedAt: string;
  createdAt: string;
  collectionIds?: number[];
  genre?: string | null;
  publicationDate?: string;
  language?: string;
  coverUserDeleted?: boolean;
  readingStatus?: ReadingStatus | null;
  startedAt?: string | null;
  completedAt?: string | null;
  progressUpdatedAt?: string | null;
  stateVersion?: ReadingStateVersion;
  /**
   * Absolute path to the book file. Empty on sync stubs — placeholder rows
   * created for books that were never downloaded here. Absent on older
   * payloads; treat a missing or empty value as "not a real book".
   */
  filePath?: string;
  /** Addon that delivered this book; null for books imported from disk. */
  sourceAddonId?: string | null;
  /** Addon-namespaced item id (e.g. `gutenberg:1342`); frozen after protocol 3.0.0. */
  sourceItemId?: string | null;
  isbn13?: string | null;
};

export type CollectionDto = {
  id: number;
  name: string;
  color: string | null;
  isSystem: boolean;
  createdAt: string;
};

export type CreateCollectionInput = {
  name: string;
  color?: string;
};

export type BookCollectionInput = {
  bookId: string;
  collectionId: number;
};

export type ActivityPoint = {
  bucket: string;
  minutes: number;
};

export type ReadingStatsSummaryDto = {
  totalMinutesRead: number;
  totalSessions: number;
  booksStarted: number;
  booksCompleted: number;
  avgProgressPercentage: number;
};

export type ReaderBook = LibraryBookDto & {
  filePath: string;
};
