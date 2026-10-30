export type BookDto = {
  id: string;
  title: string;
  author: string;
  filePath: string;
  format: string;
  syncStatus: string;
  currentPage: number;
  totalPages: number;
  createdAt: string;
  updatedAt: string;
  genre?: string | null;
};

export type ReadingProgressDto = {
  id: string;
  bookId: string;
  cfiLocation: string;
  percentage: number;
  updatedAt: string;
};

export type SaveProgressInput = {
  bookId: string;
  cfiLocation: string;
  percentage: number;
};

export type HighlightDto = {
  id: string;
  bookId: string;
  text: string;
  color: string;
  pageNumber: number;
  note?: string | null;
  createdAt: string;
  updatedAt: string;
  /**
   * EPUB only. The Canonical Fragment Identifier that pins the
   * highlight to a specific range inside a chapter. Null for PDF
   * highlights and for legacy EPUB highlights saved before the
   * CFI-persistence change.
   */
  cfi?: string | null;
};

export type BookmarkDto = {
  id: string;
  bookId: string;
  pageNumber: number;
  title?: string;
  createdAt: string;
  cfiLocation?: string | null;
  locatorJson?: string | null;
  updatedAt?: string;
};

export type SaveHighlightInput = {
  id: string;
  bookId: string;
  text: string;
  color: string;
  pageNumber: number;
  page?: number;
  rectLeft: number;
  rectRight: number;
  rectTop: number;
  rectBottom: number;
  cfi: string | null;
  note?: string | null;
};

export type SaveBookmarkInput = {
  id: string;
  bookId: string;
  pageNumber: number;
  title?: string;
  createdAt: string;
  cfiLocation?: string | null;
  locatorJson?: string | null;
};

export type TagDto = {
  id: string;
  name: string;
  color?: string | null;
  createdAt: string;
};

export type DictionaryWordDto = {
  id: string;
  word: string;
  createdAt: string;
  normalizedWord?: string | null;
  userId?: string | null;
  tags?: string[] | null;
  srsStage?: number | null;
  updatedAt?: string | null;
  deletedAt?: string | null;
  syncedAt?: string | null;
  definition?: string | null;
  partOfSpeech?: string | null;
  phonetic?: string | null;
  example?: string | null;
  quote?: string | null;
  sourceBookId?: string | null;
  sourceBookTitle?: string | null;
  sourceBookAuthor?: string | null;
  sourceChapter?: string | null;
  sourceLocator?: string | null;
};

/**
 * The six reader-captured evidence fields (REQ-DRE-003, REQ-DSI-003). The
 * declaration lives beside the capture orchestration that builds it; it is
 * re-exported here so the type has exactly one definition — a second
 * declaration in this file would be free to drift from that one.
 */
export type { DictionaryEvidence } from '$lib/shared/dictionary/captureFromSelection';

export type BookCoverDto = {
  bookId: string;
  storagePath: string;
  mimeType: string;
  width?: number;
  height?: number;
  byteSize: number;
};

export type UpsertBookCoverInput = {
  bookId: string;
  data: number[];
  mimeType?: string;
};

/**
 * Discriminated action kinds for highlight events originating from a
 * viewer. `open` is fired by the EPUB/PDF iframe when the user clicks
 * a persisted highlight (the parent renders the Menu 2 toolbar).
 * `updateColor` and `delete` are fired by the Menu 2 toolbar itself.
 * `close` dismisses the toolbar without mutating the highlight.
 */
export type HighlightActionKind = 'open' | 'updateColor' | 'delete' | 'close';

/**
 * Options payload for `onHighlightAction`. `color` is the current
 * color (hex) for `open` and the new color for `updateColor`. `x`
 * and `y` are parent-viewport coordinates (the click point) for
 * `open` so the parent can anchor the Menu 2 toolbar.
 */
export interface HighlightActionOpts {
  color?: string;
  text?: string;
  x?: number;
  y?: number;
}

// ─── Sync Observability (PR3) ─────────────────────────────────────
export type SyncScope =
  'progress' | 'bookmarks' | 'highlights' | 'sessions' | 'catalog' | 'dictionary' | 'library';

export type RealtimeStatus = 'connected' | 'connecting' | 'closed' | 'error';

export interface SyncHealth {
  lastSyncAt: string | null;
  pendingCount: number;
  lastError: string | null;
  realtimeStatus: RealtimeStatus;
  outboxDepth?: number;
  nextRetryAt?: string | null;
  realtime?: Record<string, RealtimeStatus>;
}

export interface SyncConflict {
  id: string;
  scope: SyncScope;
  localUpdatedAt: string;
  remoteUpdatedAt: string;
  word?: string;
  localWord?: string;
  remoteWord?: string;
  resolution?: 'keep_local' | 'keep_remote';
}
