/**
 * DriveColdBackupService — cold backup on demand only (PR3).
 * Reuses GDriveProvider primitives (parents only on create, PATCH without parents
 * keeps 403 parents fix, drive.file scope). Export/import are Settings-only;
 * hot save/open never touches Drive.
 *
 * Cold backup file: `books/{userId}/nexo_cold_backup.json`
 * (physical `nexo_cold_backup.json` inside `Nexo/Books`). JSON carries
 * metadata for FK-order restore; bins are Drive book files already in Books folder.
 *
 * Import is FK-ordered `books→progress→highlights→bookmarks→sessions`
 * chunk 100 idempotent (onConflict). Backfill reuses same path.
 */
import { GDriveProvider } from './storage/GDriveProvider';
import { isDriveAuthorized } from '$lib/shared/services/DriveConnectService';
import {
  syncError,
  redactLogLine,
  type SyncError,
} from '$lib/shared/protocol/DriveCatalogContract';
import { toSyncError } from '$lib/shared/recovery/desktopRecoveryImport';
import { hasLiveSession } from '$lib/services/supabase';
import { SupabaseProgressSync } from '../sync/SupabaseProgressSync';
import { SupabaseBookCatalogSync } from '../sync/SupabaseBookCatalogSync';
import type { RemoteReadingSessionRow } from '$lib/shared/types';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';

export interface ColdBackupJson {
  version: number;
  exportedAt: number;
  books: Array<{
    id: string;
    userId: string;
    title: string;
    author: string | null;
    format: string;
    contentHash?: string | null;
    importedAt: string;
    updatedAt: string;
  }>;
  progress: Array<{
    userId: string;
    bookId: string;
    cfiLocation: string;
    percentage: number;
    locatorJson?: string | null;
    updatedAt: string;
  }>;
  highlights: Array<{
    id: string;
    userId: string;
    bookId: string;
    cfiRange: string;
    textContent: string;
    note?: string | null;
    color: string;
    updatedAt: string;
    deletedAt?: string | null;
    locatorJson?: string | null;
  }>;
  bookmarks: Array<{
    id: string;
    userId: string;
    bookId: string;
    cfiLocation: string;
    titleSnippet?: string | null;
    locatorJson?: string | null;
    updatedAt: string;
    deletedAt?: string | null;
  }>;
  sessions: RemoteReadingSessionRow[];
}

/** Domain of a single row inside a cold-backup import (FK order). */
export type ImportEntity = 'book' | 'progress' | 'highlight' | 'bookmark' | 'session';

/** One per-row import failure: which record, which stable error code (REQ-07). */
export interface ImportFailure {
  entity: ImportEntity;
  /** Stable identifier of the failing record (book id for progress, own id otherwise). */
  id: string;
  error: SyncError;
}

export interface ImportResult {
  books: number;
  progress: number;
  highlights: number;
  bookmarks: number;
  sessions: number;
  totalImported: number;
  /** Per-row failures collected instead of swallowed; empty on a fully successful run. */
  failures: ImportFailure[];
}

const CHUNK_SIZE = 100;
const COLD_BACKUP_FILE = 'nexo_cold_backup.json';

function emptyImportResult(): ImportResult {
  return {
    books: 0,
    progress: 0,
    highlights: 0,
    bookmarks: 0,
    sessions: 0,
    totalImported: 0,
    failures: [],
  };
}

/**
 * Collect a per-row import failure using the existing SyncError taxonomy and
 * the sync error mapper; the message always travels through `redactLogLine`
 * (DTL-3) so tokens, JWTs, and hashes never reach the aggregated UI message.
 */
function collectImportFailure(entity: ImportEntity, id: string, err: unknown): ImportFailure {
  const mapped = toSyncError(err, id);
  return { entity, id, error: { ...mapped, message: redactLogLine(mapped.message) } };
}

function chunk<T>(arr: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

export class DriveColdBackupService {
  private static libraryPort: LibraryPort = new TauriLibraryAdapter();
  private static viewerPort: ViewerPort = new TauriViewerAdapter();
  static setLibraryPort(port: LibraryPort): void {
    this.libraryPort = port;
  }
  static setViewerPort(port: ViewerPort): void {
    this.viewerPort = port;
  }
  private static gdrive = new GDriveProvider();

  /**
   * Drive-authorization gate (login-drive-separation): cold-backup Drive I/O
   * requires the independent Drive grant, not merely a live identity session.
   * Unauthorized callers get a typed `DRIVE_NOT_CONNECTED` (retryable=false)
   * carrying the Settings connect route so UI layers can offer the connect
   * CTA / pre-prompt instead of failing opaquely. Supabase I/O keeps its own
   * `hasLiveSession()` checks at each call site below.
   */
  private static async requireDriveAuthorized(
    action: 'export' | 'import' | 'backfill',
  ): Promise<void> {
    if (await isDriveAuthorized()) return;
    throw syncError(
      'DRIVE_NOT_CONNECTED',
      `Google Drive is not connected. Connect Google Drive in Settings to enable cold-backup ${action}.`,
      false,
    );
  }

  /**
   * Export local state to Drive as single JSON.
   * Gathers via tauri (books, progress, highlights, bookmarks) and Supabase sessions (if live).
   * Reuses GDriveProvider.upload which sends parents only on create (403 fix).
   */
  static async exportColdBackup(userId: string): Promise<void> {
    await this.requireDriveAuthorized('export');
    const [libraryBooks, sourceBooks] = await Promise.all([
      this.libraryPort.listLibraryBooks().catch(() => []),
      this.libraryPort.listBooks().catch(() => []),
    ]);

    // Books: map library DTOs to catalog rows
    const books: ColdBackupJson['books'] = libraryBooks.map(
      (b: import('$lib/shared/types').LibraryBookDto) => ({
        id: b.id,
        userId,
        title: b.title,
        author: b.author ?? null,
        format: b.format,
        contentHash: null,
        importedAt: b.createdAt ?? new Date().toISOString(),
        updatedAt: b.updatedAt ?? new Date().toISOString(),
      }),
    );

    // Progress: one per book
    const progress: ColdBackupJson['progress'] = [];
    for (const b of sourceBooks) {
      try {
        const p = await this.viewerPort.getProgress(b.id);
        if (p) {
          progress.push({
            userId,
            bookId: p.bookId,
            cfiLocation: p.cfiLocation,
            percentage: p.percentage,
            updatedAt: p.updatedAt,
          });
        }
      } catch {
        // skip
      }
    }

    // Highlights + bookmarks per book
    const highlights: ColdBackupJson['highlights'] = [];
    const bookmarks: ColdBackupJson['bookmarks'] = [];
    for (const b of sourceBooks) {
      try {
        const hs = await this.viewerPort.listHighlights(b.id);
        for (const h of hs) {
          highlights.push({
            id: h.id,
            userId,
            bookId: h.bookId,
            cfiRange: h.cfi ?? '',
            textContent: h.text,
            note: h.note ?? null,
            color: h.color,
            updatedAt: h.createdAt ?? new Date().toISOString(),
            deletedAt: null,
          });
        }
      } catch {}
      try {
        const bms = await this.viewerPort.listBookmarks(b.id);
        for (const bm of bms) {
          bookmarks.push({
            id: bm.id,
            userId,
            bookId: bm.bookId,
            cfiLocation: '',
            titleSnippet: bm.title ?? null,
            updatedAt: bm.createdAt ?? new Date().toISOString(),
            deletedAt: null,
          });
        }
      } catch {}
    }

    // Sessions: fetch from Supabase when live, else empty (local sessions already on disk)
    let sessions: RemoteReadingSessionRow[] = [];
    if (hasLiveSession()) {
      try {
        const sync = new SupabaseProgressSync(userId);
        sessions = await sync.fetchReadingSessions();
      } catch {}
    }

    const backup: ColdBackupJson = {
      version: 1,
      exportedAt: Date.now(),
      books,
      progress,
      highlights,
      bookmarks,
      sessions,
    };

    const jsonBytes = new TextEncoder().encode(JSON.stringify(backup));
    // Reuses GDriveProvider.upload: POST with parents on create, PATCH without parents on update (403 fix)
    await this.gdrive.upload(
      COLD_BACKUP_FILE,
      jsonBytes as unknown as Uint8Array,
      COLD_BACKUP_FILE,
    );
  }

  /**
   * Import cold backup JSON from Drive, FK-order chunk 100 idempotent.
   * Gated by the Drive grant (typed DRIVE_NOT_CONNECTED) and the live
   * session — no request fires without both.
   */
  static async importColdBackup(userId: string): Promise<ImportResult> {
    await this.requireDriveAuthorized('import');
    if (!hasLiveSession()) return emptyImportResult();
    const bytes: Uint8Array = await this.gdrive.download(COLD_BACKUP_FILE);
    const json = new TextDecoder().decode(bytes);
    const backup = JSON.parse(json) as ColdBackupJson;
    return this.importInFkOrder(backup, userId);
  }

  /**
   * One-shot Drive→Supabase backfill, FK-order chunk 100 idempotent, gated on first login.
   * Reuses Drive catalog read: delegates to importColdBackup when cold file exists,
   * otherwise no-op (legacy per-book state.json path already covered by import).
   */
  static async backfillFromDrive(userId: string): Promise<ImportResult> {
    await this.requireDriveAuthorized('backfill');
    if (!hasLiveSession()) return emptyImportResult();
    try {
      const res = await this.importColdBackup(userId);
      if (res.totalImported > 0) return res;
    } catch {}
    // Legacy fallback: list Drive state files via GDriveProvider.list and aggregate
    try {
      const files = await this.gdrive.list('');
      const stateFiles = files.filter((f) => f.endsWith('_state.json'));
      if (stateFiles.length === 0) return emptyImportResult();
      // Best-effort: each state.json is BookStateJson with progress/highlights/bookmarks
      const backup: ColdBackupJson = {
        version: 1,
        exportedAt: Date.now(),
        books: [],
        progress: [],
        highlights: [],
        bookmarks: [],
        sessions: [],
      };
      for (const f of stateFiles) {
        try {
          const b = await this.gdrive.download(f);
          const j = JSON.parse(new TextDecoder().decode(b)) as {
            progress: {
              book_id: string;
              cfi_location: string;
              percentage: number;
              updated_at: number;
            } | null;
            highlights: Array<{
              id: string;
              book_id: string;
              cfi_range: string;
              text_content: string;
              note: string | null;
              color: string;
              updated_at: number;
            }>;
            bookmarks: Array<{
              id: string;
              book_id: string;
              cfi_location: string;
              title_or_snippet: string;
              updated_at: number;
            }>;
          };
          if (j.progress) {
            backup.progress.push({
              userId,
              bookId: j.progress.book_id,
              cfiLocation: j.progress.cfi_location,
              percentage: j.progress.percentage,
              updatedAt: new Date(j.progress.updated_at).toISOString(),
            });
          }
          for (const h of j.highlights ?? []) {
            backup.highlights.push({
              id: h.id,
              userId,
              bookId: h.book_id,
              cfiRange: h.cfi_range,
              textContent: h.text_content,
              note: h.note,
              color: h.color,
              updatedAt: new Date(h.updated_at).toISOString(),
            });
          }
          for (const bm of j.bookmarks ?? []) {
            backup.bookmarks.push({
              id: bm.id,
              userId,
              bookId: bm.book_id,
              cfiLocation: bm.cfi_location,
              titleSnippet: bm.title_or_snippet,
              updatedAt: new Date(bm.updated_at).toISOString(),
            });
          }
        } catch {}
      }
      if (
        backup.progress.length === 0 &&
        backup.highlights.length === 0 &&
        backup.bookmarks.length === 0
      ) {
        return emptyImportResult();
      }
      return this.importInFkOrder(backup, userId);
    } catch {
      return emptyImportResult();
    }
  }

  private static async importInFkOrder(
    backup: ColdBackupJson,
    userId: string,
  ): Promise<ImportResult> {
    let books = 0;
    let progress = 0;
    let highlights = 0;
    let bookmarks = 0;
    let sessions = 0;
    const failures: ImportFailure[] = [];

    const bookSync = new SupabaseBookCatalogSync(userId);
    const progressSync = new SupabaseProgressSync(userId);

    // 1. books first (FK parent)
    for (const c of chunk(backup.books ?? [], CHUNK_SIZE)) {
      for (const row of c) {
        try {
          await bookSync.upsertBook({
            id: row.id,
            userId,
            title: row.title,
            author: row.author,
            format: row.format,
            contentHash: row.contentHash ?? null,
            filePath: null,
            coverUrl: null,
            description: null,
            totalPages: null,
            sourceDevice: 'desktop',
            importedAt: row.importedAt,
            updatedAt: row.updatedAt,
          });
          books++;
        } catch (e) {
          failures.push(collectImportFailure('book', row.id, e));
        }
      }
    }
    // 2. progress
    for (const c of chunk(backup.progress ?? [], CHUNK_SIZE)) {
      for (const row of c) {
        try {
          await progressSync.upsertProgress({
            userId,
            bookId: row.bookId,
            cfiLocation: row.cfiLocation,
            percentage: row.percentage,
            updatedAt: row.updatedAt,
          });
          progress++;
        } catch (e) {
          failures.push(collectImportFailure('progress', row.bookId, e));
        }
      }
    }
    // 3. highlights
    for (const c of chunk(backup.highlights ?? [], CHUNK_SIZE)) {
      for (const row of c) {
        try {
          await progressSync.upsertHighlight({
            userId,
            bookId: row.bookId,
            cfiRange: row.cfiRange,
            textContent: row.textContent,
            note: row.note ?? null,
            color: row.color,
            updatedAt: row.updatedAt,
            deletedAt: row.deletedAt ?? null,
          } as never);
          highlights++;
        } catch (e) {
          failures.push(collectImportFailure('highlight', row.id, e));
        }
      }
    }
    // 4. bookmarks
    for (const c of chunk(backup.bookmarks ?? [], CHUNK_SIZE)) {
      for (const row of c) {
        try {
          await progressSync.upsertBookmark({
            userId,
            bookId: row.bookId,
            cfiLocation: row.cfiLocation,
            titleSnippet: row.titleSnippet ?? null,
            updatedAt: row.updatedAt,
            deletedAt: row.deletedAt ?? null,
          } as never);
          bookmarks++;
        } catch (e) {
          failures.push(collectImportFailure('bookmark', row.id, e));
        }
      }
    }
    // 5. sessions last (chunk 100, idempotent onConflict id)
    for (const c of chunk(backup.sessions ?? [], CHUNK_SIZE)) {
      for (const row of c) {
        try {
          await progressSync.upsertReadingSession({
            id: row.id,
            userId,
            bookId: row.bookId,
            startedAt: row.startedAt,
            durationMinutes: row.durationMinutes,
            date: row.date,
            device: 'desktop',
            updatedAt: new Date(row.updatedAtEpochMillis).toISOString(),
            startPercentage: row.startPercentage ?? null,
            endPercentage: row.endPercentage ?? null,
          });
          sessions++;
        } catch (e) {
          failures.push(collectImportFailure('session', row.id, e));
        }
      }
    }

    const totalImported = books + progress + highlights + bookmarks + sessions;
    return { books, progress, highlights, bookmarks, sessions, totalImported, failures };
  }
}
