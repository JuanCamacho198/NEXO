/**
 * GoogleDriveStateSync — mirrors Android's GoogleDriveJsonStateSync.kt.
 * Single JSON per book with progress, highlights, bookmarks.
 * Uses GDriveProvider for upload/download and LastWriteWins for conflict resolution.
 */
import { GDriveProvider } from './storage/GDriveProvider';
import { LastWriteWinsConflictResolver } from '../sync/ConflictResolver';
import type { VersionedSyncRecord } from './storage/StorageProvider';

// ---- Types (matching Android's BookStateJson) ----

export interface ProgressStateJson {
  id: string;
  book_id: string;
  cfi_location: string;
  percentage: number;
  current_page?: number;
  updated_at: number; // epoch millis
}

export interface HighlightStateJson extends VersionedSyncRecord {
  id: string;
  book_id: string;
  cfi_range: string;
  text_content: string;
  note: string | null;
  color: string;
  updated_at: number;
  deleted_at: number | null;
}

export interface BookmarkStateJson extends VersionedSyncRecord {
  id: string;
  book_id: string;
  cfi_location: string;
  title_or_snippet: string;
  updated_at: number;
  deleted_at: number | null;
}

export interface BookStateJson {
  progress: ProgressStateJson | null;
  highlights: HighlightStateJson[];
  bookmarks: BookmarkStateJson[];
}

export interface PullResult {
  progress: ProgressStateJson | null;
  highlights: HighlightStateJson[];
  bookmarks: BookmarkStateJson[];
}

// ---- Service ----

export class GoogleDriveStateSync {
  private static gdrive = new GDriveProvider();
  private static STATE_PATH_PREFIX = 'nexo/books';

  private static highlightResolver = new LastWriteWinsConflictResolver<HighlightStateJson>();
  private static bookmarkResolver = new LastWriteWinsConflictResolver<BookmarkStateJson>();

  /**
   * Serialize local state and upload JSON to Drive.
   */
  static async pushState(
    bookId: string,
    progress: ProgressStateJson | null,
    highlights: HighlightStateJson[],
    bookmarks: BookmarkStateJson[],
  ): Promise<void> {
    const state: BookStateJson = { progress, highlights, bookmarks };
    const jsonBytes = new TextEncoder().encode(JSON.stringify(state));
    const fileName = `${bookId}_state.json`;
    await this.gdrive.upload(fileName, jsonBytes, fileName);
  }

  /**
   * Download remote state JSON, deserialize, resolve conflicts.
   * Returns merged state (remote wins on newer updatedAtEpochMillis).
   */
  static async pullState(
    bookId: string,
    localProgress: ProgressStateJson | null,
    localHighlights: HighlightStateJson[],
    localBookmarks: BookmarkStateJson[],
  ): Promise<PullResult> {
    const fileName = `${bookId}_state.json`;

    let jsonBytes: Uint8Array;
    try {
      jsonBytes = await this.gdrive.download(fileName);
    } catch {
      // No remote state yet — return local as-is
      return {
        progress: localProgress,
        highlights: localHighlights,
        bookmarks: localBookmarks,
      };
    }

    const jsonString = new TextDecoder().decode(jsonBytes);
    const remoteState: BookStateJson = JSON.parse(jsonString);

    // Resolve progress: simple last-write-wins
    const resolvedProgress = this.resolveProgress(localProgress, remoteState.progress);

    // Resolve highlights with LWW
    const resolvedHighlights = this.resolveRecords(
      localHighlights,
      remoteState.highlights,
      this.highlightResolver,
    );

    // Resolve bookmarks with LWW
    const resolvedBookmarks = this.resolveRecords(
      localBookmarks,
      remoteState.bookmarks,
      this.bookmarkResolver,
    );

    return {
      progress: resolvedProgress,
      highlights: resolvedHighlights,
      bookmarks: resolvedBookmarks,
    };
  }

  /**
   * List all state JSON files in Drive.
   */
  static async listStateFiles(): Promise<string[]> {
    const allFiles = await this.gdrive.list('');
    return allFiles.filter((f) => f.endsWith('_state.json'));
  }

  private static resolveProgress(
    local: ProgressStateJson | null,
    remote: ProgressStateJson | null,
  ): ProgressStateJson | null {
    if (!remote) return local;
    if (!local) return remote;
    return remote.updated_at > local.updated_at ? remote : local;
  }

  private static resolveRecords<T extends VersionedSyncRecord>(
    local: T[],
    remote: T[],
    resolver: LastWriteWinsConflictResolver<T>,
  ): T[] {
    const allIds = [
      ...new Set([...local.map((r) => r.recordId), ...remote.map((r) => r.recordId)]),
    ];
    const localById = new Map(local.map((r) => [r.recordId, r]));
    const remoteById = new Map(remote.map((r) => [r.recordId, r]));

    return allIds
      .map((id) => {
        const localRecord = localById.get(id);
        const remoteRecord = remoteById.get(id);
        if (!remoteRecord) return localRecord!;
        if (!localRecord) return remoteRecord;
        return resolver.resolve(localRecord, remoteRecord);
      })
      .filter((r): r is T => r !== undefined);
  }
}
