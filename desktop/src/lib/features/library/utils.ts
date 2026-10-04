import type { LibraryBookDto } from '$lib/shared/types';
import { getSafeProgressPercentage as getCanonicalProgress } from '$lib/shared/stores/HomeState';

// ─── View mode ───

export const LIBRARY_VIEW_MODE = {
  LIST: 'list',
  GRID: 'grid',
} as const;
export type LibraryViewMode = (typeof LIBRARY_VIEW_MODE)[keyof typeof LIBRARY_VIEW_MODE];

// ─── Bulk import status ───

export const BULK_IMPORT_STATUS = {
  QUEUED: 'queued',
  IMPORTING: 'importing',
  SUCCESS: 'success',
  SKIPPED: 'skipped',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
} as const;

// ─── Collection colors ───

export const COLLECTION_COLOR_OPTIONS = [
  '#6366f1',
  '#8b5cf6',
  '#ec4899',
  '#ef4444',
  '#f97316',
  '#eab308',
  '#22c55e',
  '#14b8a6',
  '#0ea5e9',
] as const;

// ─── Shelf menu ID generator ───

export function getShelfMenuId(bookId: string): string {
  return `shelf-actions-menu-${bookId}`;
}

// ─── Formatting utilities ───

export function formatUpdatedAt(iso: string, unknownLabel: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return unknownLabel;
  return parsed.toLocaleDateString();
}

export function formatProgress(progress: number): string {
  return `${Math.round(progress)}%`;
}

export function getBulkImportStatusKey(status: string): string {
  const map: Record<string, string> = {
    importing: 'library.bulkImport.status.ingesting',
    success: 'library.bulkImport.status.success',
    skipped: 'library.bulkImport.status.skipped',
    failed: 'library.bulkImport.status.failed',
    cancelled: 'library.bulkImport.status.cancelled',
  };
  return map[status] || 'library.bulkImport.status.queued';
}

export function getBulkImportStatusClass(status: string): string {
  const map: Record<string, string> = {
    success: 'text-emerald-700',
    failed: 'text-red-700',
    importing: 'text-blue-700',
    cancelled: 'text-amber-700',
  };
  return map[status] || 'text-(--color-text-muted)';
}

// ─── Shelf helpers ───

export type ShelfBook = LibraryBookDto & {
  filePath: string;
};

export type ShelfFilter = 'all' | 'reading' | 'pending' | 'completed' | 'favorites';
// `author` and `file_size` are not offered by the visible sort control; they
// exist so a `sort:` token can reach the same engine (see useLibraryShelf).
export type ShelfSort = 'date_added' | 'last_read' | 'progress' | 'title' | 'author' | 'file_size';
export type ShelfView = 'grid' | 'list';

export const FILTER_OPTIONS: Array<{ key: ShelfFilter; label: string }> = [
  { key: 'all', label: 'Todos' },
  { key: 'reading', label: 'Leyendo' },
  { key: 'pending', label: 'Pendientes' },
  { key: 'completed', label: 'Completados' },
  { key: 'favorites', label: 'Favoritos' },
];

export const SORT_OPTIONS: Array<{ key: ShelfSort; label: string }> = [
  { key: 'date_added', label: 'Fecha agregada' },
  { key: 'last_read', label: 'Ultima lectura' },
  { key: 'progress', label: 'Progreso' },
  { key: 'title', label: 'Titulo' },
];

export function getSafeProgressPercentage(book: LibraryBookDto): number {
  return getCanonicalProgress(book);
}

export const FAVORITES_COLLECTION_ID = 1;

export function getBookState(book: ShelfBook): ShelfFilter {
  const progress = getSafeProgressPercentage(book);
  if (book.readingStatus === 'completed' || progress >= 100) return 'completed';
  if (progress > 0) return 'reading';
  if (book.collectionIds?.includes(FAVORITES_COLLECTION_ID)) return 'favorites';
  return 'pending';
}

export function getStateLabel(book: ShelfBook): string {
  const progress = getSafeProgressPercentage(book);
  if (book.readingStatus === 'completed' || progress >= 100) return 'Completado';
  if (progress > 0) return 'En lectura';
  if (book.collectionIds?.includes(FAVORITES_COLLECTION_ID)) return 'Favorito';
  return 'Pendiente';
}

export function getTimestamp(book: ShelfBook): number {
  const parsed = Date.parse(book.updatedAt);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function formatPercent(book: ShelfBook): string {
  return `${Math.round(getSafeProgressPercentage(book))}%`;
}
