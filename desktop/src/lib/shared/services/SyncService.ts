/**
 * SyncService — Supabase SoT hot sync + Drive cold backup (PR2).
 * PR2 cutover: Supabase is sole hot SoT via PostgREST onConflict gated by hasLiveSession
 * + single Realtime supervisor (4 channels progress/highlights/bookmarks/sessions).
 * Drive is cold Export/Import only (DriveColdBackupService, PR3) — no hot push/pull.
 * LWW: remote.updatedAt > local → remote, tie → recordId lexicographic, version+1 for progress.
 */
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { getSessionClient, hasLiveSession, recheckLiveSession } from '$lib/services/supabase';
import { reportAuthError } from '$lib/shared/stores/syncAlert.svelte';
import { GDriveProvider } from './storage/GDriveProvider';
import { GoogleDriveStateSync } from './GoogleDriveStateSync';
import { SupabaseProgressSync } from '../sync/SupabaseProgressSync';
import { SupabaseDictionarySync } from '../sync/SupabaseDictionarySync';
import {
  SupabaseBookCatalogSync,
  buildRemoteRefs,
  reportCoverFailure,
} from '../sync/SupabaseBookCatalogSync';
import { canonicalBookName } from '$lib/shared/protocol/DriveCatalogContract';
import { SyncOutboxService } from '../outbox/SyncOutboxService';
import { SyncOutboxDao } from '../outbox/SyncOutboxDao';
import type { SyncHealth, RealtimeStatus } from '$lib/shared/types/book';
import type {
  ProgressStateJson,
  HighlightStateJson,
  BookmarkStateJson,
} from './GoogleDriveStateSync';
import type { TagDto } from '$lib/shared/types';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';

/** Sync mode for reading progress: 'supabase' only (PR2 cutover — Drive hot is cold-only). */
type ReadingProgressSyncMode = 'drive' | 'dual' | 'supabase';
type BookmarkSyncMode = 'drive' | 'dual' | 'supabase';
type HighlightSyncMode = 'drive' | 'dual' | 'supabase';

/**
 * Map a nullable outbox payload field onto its remote column without erasing an
 * explicit value (REQ-DSI-003): an absent key and a `null` value both land as
 * `null`, while a present value — including an empty string — is preserved.
 */
function nullableText(value: unknown): string | null {
  return value != null ? String(value) : null;
}

export class SyncService {
  private static gdrive = new GDriveProvider();
  private static libraryPort: LibraryPort = new TauriLibraryAdapter();
  private static viewerPort: ViewerPort = new TauriViewerAdapter();

  static setLibraryPort(port: LibraryPort): void {
    this.libraryPort = port;
  }
  static setViewerPort(port: ViewerPort): void {
    this.viewerPort = port;
  }

  /** Hot SoT is Supabase only (PR2). Drive hot push/pull removed; cold backup is separate. */
  private static readingProgressSync: ReadingProgressSyncMode = 'supabase';

  /** Bookmark hot sync — Supabase only. */
  private static bookmarkSync: BookmarkSyncMode = 'supabase';

  /** Highlight hot sync — Supabase only. */
  private static highlightSync: HighlightSyncMode = 'supabase';

  /** Flag to track whether Drive → Supabase import was done this session. */
  private static supabaseImportDone = false;

  /** Singleton outbox service for processing queued sync items. */
  private static outboxService: SyncOutboxService | null = null;

  /** Shared in-flight startup sync so repeated auth wiring cannot duplicate work. */
  private static metadataSyncPromise: Promise<void> | null = null;

  /**
   * Override the sync mode at runtime.
   * - 'drive': legacy Drive-only (no Supabase writes)
   * - 'dual': write to both Drive and Supabase (transition)
   * - 'supabase': Supabase-only (cutover complete)
   */
  static setReadingProgressSyncMode(mode: ReadingProgressSyncMode): void {
    this.readingProgressSync = mode;
  }

  static setBookmarkSyncMode(mode: BookmarkSyncMode): void {
    this.bookmarkSync = mode;
  }

  static setHighlightSyncMode(mode: HighlightSyncMode): void {
    this.highlightSync = mode;
  }

  /**
   * Whether any import is still pending (import not done and at least one domain in dual/supabase mode).
   */
  static get hasPendingImport(): boolean {
    if (this.supabaseImportDone) return false;
    return (
      this.readingProgressSync !== 'drive' ||
      this.bookmarkSync !== 'drive' ||
      this.highlightSync !== 'drive'
    );
  }

  /**
   * Set up and start the outbox timer-based processor.
   * Registers a handler that dispatches by entity type:
   * - BOOK → SupabaseBookCatalogSync.upsertBook()
   * - READING_PROGRESS → (future, no-op for now)
   *
   * Idempotent — safe to call multiple times.
   */
  static setupOutboxProcessor(): void {
    if (this.outboxService) return;

    this.outboxService = new SyncOutboxService();
    this.outboxService.setHandler(async (entityType, entityId, operation, payloadJson) => {
      // Gate (SR-1): never process a row without a live session. Silent skip —
      // no throw (a throw would markFailed + backoff, feeding the retry loop)
      // and no request with a dead JWT. The flush-level gate in
      // SyncOutboxService makes this reachable only in a mid-flush race.
      if (!hasLiveSession()) return;
      if (!entityId) throw new Error(`Outbox entity ${entityType} is missing entityId`);

      // The live-session gate guarantees the cached user matches authState (D3),
      // so a null userId here is impossible; the guard keeps the handler
      // type-safe with the same silent-skip contract.
      const userId = authState.userId;
      if (!userId) return;

      const progressSync = new SupabaseProgressSync(userId);
      const payload = JSON.parse(payloadJson) as Record<string, unknown>;

      if (entityType === 'READING_PROGRESS' && operation === 'UPSERT') {
        await progressSync.upsertProgress({
          userId: userId,
          bookId: entityId,
          cfiLocation: String(payload.cfiLocation ?? ''),
          percentage: Number(payload.percentage ?? 0),
          currentPage: payload.currentPage != null ? Number(payload.currentPage) : null,
          locatorJson: payload.locatorJson != null ? String(payload.locatorJson) : null,
          updatedAt: String(payload.updatedAt ?? new Date().toISOString()),
        });
        return;
      }

      // D10 (SCEN-push-2/3/4): READING_SESSION upsert — one outbox row per
      // session (never coalesced). Remote updated_at MUST come from the payload
      // clock, NOT now(): pull-back of an own push is then a tie → no-op
      // (SCEN-pull-3). Success → outbox delete; throw → existing backoff.
      if (entityType === 'READING_SESSION' && operation === 'UPSERT') {
        await progressSync.upsertReadingSession({
          id: String(payload.id ?? ''),
          userId: userId,
          bookId: entityId,
          startedAt: String(payload.startedAt ?? ''),
          durationMinutes: Number(payload.durationMinutes ?? 0),
          date: String(payload.date ?? ''),
          device: 'desktop',
          updatedAt: new Date(Number(payload.updatedAtEpochMillis)).toISOString(),
          startPercentage: payload.startPercentage != null ? Number(payload.startPercentage) : null,
          endPercentage: payload.endPercentage != null ? Number(payload.endPercentage) : null,
        });
        return;
      }

      if (entityType === 'HIGHLIGHT') {
        await progressSync.upsertHighlight({
          id: entityId,
          userId: userId,
          bookId: String(payload.bookId ?? entityId),
          cfiRange: String(payload.cfiRange ?? payload.cfi ?? ''),
          textContent: String(payload.textContent ?? payload.text ?? ''),
          note: payload.note != null ? String(payload.note) : null,
          color: String(payload.color ?? 'yellow'),
          page: payload.page != null ? Number(payload.page) : null,
          rectJson: (payload.rectJson as Record<string, number> | null | undefined) ?? null,
          locatorJson: payload.locatorJson != null ? String(payload.locatorJson) : null,
          deletedAt:
            operation === 'DELETE' ? String(payload.deletedAt ?? new Date().toISOString()) : null,
          updatedAt: String(payload.updatedAt ?? new Date().toISOString()),
        });
        return;
      }

      if (entityType === 'BOOKMARK') {
        await progressSync.upsertBookmark({
          id: entityId,
          userId: userId,
          bookId: String(payload.bookId ?? entityId),
          cfiLocation: String(payload.cfiLocation ?? ''),
          titleSnippet: payload.titleSnippet != null ? String(payload.titleSnippet) : null,
          locatorJson: payload.locatorJson != null ? String(payload.locatorJson) : null,
          deletedAt:
            operation === 'DELETE' ? String(payload.deletedAt ?? new Date().toISOString()) : null,
          updatedAt: String(payload.updatedAt ?? new Date().toISOString()),
        });
        return;
      }

      if (entityType === 'BOOK' && operation === 'UPSERT') {
        const metadata = payload;
        const bookSync = new SupabaseBookCatalogSync(userId);

        const contentHash = metadata.content_hash != null ? String(metadata.content_hash) : null;

        // Content-hash dedup: skip if this SHA-256 hash already exists
        // in the catalog for this user (book imported from other device).
        if (contentHash) {
          const existing = await bookSync.findByHash(contentHash);
          if (existing) {
            // Already in catalog — skip upsert to avoid duplicates
            return;
          }
        }

        // Cover upload: try to read the cover file and upload to Storage
        let coverUrl: string | null = null;
        try {
          const allBooks = await this.libraryPort.listLibraryBooks();
          const localBook = allBooks.find((b) => b.id === entityId);
          if (localBook?.coverPath) {
            const coverBytes = await this.libraryPort.getFileBytes(localBook.coverPath);
            coverUrl = await bookSync.uploadCover(
              userId,
              entityId,
              new Uint8Array(coverBytes).buffer as ArrayBuffer,
            );
          }
        } catch (e) {
          reportCoverFailure(entityId, e);
          // Non-blocking — continue with null coverUrl
        }

        // Binary upload to Drive + remote-ref persistence (DRP-1). The upload
        // must succeed before refs are written; on failure the error propagates
        // so the outbox marks the row failed with backoff and no partial refs
        // are persisted (DRP-4). On retry, DRP-3 find-by-name updates the same
        // Drive file — no duplicates.
        let remoteRefs: ReturnType<typeof buildRemoteRefs> | null = null;
        try {
          const sourceBooks = await this.libraryPort.listBooks();
          const localSource = sourceBooks.find((b) => b.id === entityId);
          const format = String(metadata.format ?? localSource?.format ?? 'epub');
          const expectedName = canonicalBookName(entityId, format);
          if (localSource?.filePath) {
            const fileBytes = await this.libraryPort.getFileBytes(localSource.filePath);
            const fileId = await this.gdrive.upload(
              entityId,
              new Uint8Array(fileBytes),
              expectedName,
            );
            remoteRefs = buildRemoteRefs(entityId, format, fileId);
          }
        } catch (e) {
          throw e;
        }

        await bookSync.upsertBook({
          id: entityId,
          userId: userId,
          title: String(metadata.title ?? ''),
          author: metadata.author != null ? String(metadata.author) : null,
          format: String(metadata.format ?? ''),
          contentHash: contentHash,
          filePath: null,
          coverUrl: coverUrl,
          description: null,
          totalPages: metadata.totalPages != null ? Number(metadata.totalPages) : null,
          sourceDevice: 'desktop',
          importedAt:
            metadata.importedAt != null ? String(metadata.importedAt) : new Date().toISOString(),
          updatedAt:
            metadata.updatedAt != null ? String(metadata.updatedAt) : new Date().toISOString(),
          ...(remoteRefs ?? {}),
        });
      } else if (entityType === 'BOOK' && operation === 'DELETE') {
        const bookSync = new SupabaseBookCatalogSync(userId);
        await bookSync.tombstoneBook(entityId);
      } else if (entityType === 'DICTIONARY_WORD') {
        const dictSync = new SupabaseDictionarySync(userId);
        if (operation === 'DELETE') {
          await dictSync.delete(String(payload.normalizedWord ?? ''));
        } else {
          const normalized = String(payload.normalizedWord ?? payload.normalized_word ?? '')
            .toLowerCase()
            .trim();
          await dictSync.upsert({
            id: entityId,
            userId,
            word: String(payload.word ?? ''),
            normalizedWord: normalized,
            tags: Array.isArray(payload.tags) ? (payload.tags as string[]) : [],
            srsStage: Number(payload.srsStage ?? payload.srs_stage ?? 0),
            updatedAt: String(payload.updatedAt ?? payload.updated_at ?? new Date().toISOString()),
            deletedAt:
              (payload.deletedAt as string | null) ?? (payload.deleted_at as string | null) ?? null,
            createdAt: String(payload.createdAt ?? payload.created_at ?? new Date().toISOString()),
            // REQ-DSI-003: the full snapshot travels field for field. Evidence is
            // never omitted and never coerced to '' — an absent key is an explicit null.
            definition: nullableText(payload.definition),
            partOfSpeech: nullableText(payload.partOfSpeech),
            phonetic: nullableText(payload.phonetic),
            example: nullableText(payload.example),
            quote: nullableText(payload.quote),
            sourceBookId: nullableText(payload.sourceBookId),
            sourceBookTitle: nullableText(payload.sourceBookTitle),
            sourceBookAuthor: nullableText(payload.sourceBookAuthor),
            sourceChapter: nullableText(payload.sourceChapter),
            sourceLocator: nullableText(payload.sourceLocator),
          });
        }
      } else {
        throw new Error(`Unsupported outbox entity: ${entityType}/${operation}`);
      }
    });

    this.outboxService.start();
  }

  /**
   * Reset the outbox auth circuit breaker. Wired to SIGNED_IN / TOKEN_REFRESHED
   * (D4): fresh tokens mean an auth-class pause no longer applies, so the flush
   * may resume immediately instead of waiting out the backoff window.
   */
  static resetOutboxBreaker(): void {
    this.outboxService?.resetAuthBreaker();
  }

  // ─── Auto-sync observability (PR3) ──────────────────────────────────
  private static autoSyncInterval: ReturnType<typeof setInterval> | null = null;
  private static focusHandler: (() => void) | null = null;
  private static onlineHandler: (() => void) | null = null;
  private static lastSyncAt: string | null = null;
  private static lastError: string | null = null;
  private static retryAttempt = 0;
  private static retryTimer: ReturnType<typeof setTimeout> | null = null;
  private static lastFocusAt = 0;
  private static isAutoSyncSetup = false;
  private static healthDao = new SyncOutboxDao();

  private static isScopeEnabled(scope: string): boolean {
    try {
      const raw = localStorage.getItem('sync.scopes');
      if (!raw) return true;
      const map = JSON.parse(raw) as Record<string, boolean>;
      if (scope in map) return map[scope] !== false;
      return true;
    } catch {
      return true;
    }
  }

  static setupAutoSync(intervalMs = 30000): void {
    if (this.isAutoSyncSetup) return;
    this.isAutoSyncSetup = true;

    // Ensure outbox processor exists
    this.setupOutboxProcessor();

    // Interval flush every 30s gated by hasLiveSession
    this.autoSyncInterval = setInterval(() => {
      void this.flushWithGate();
    }, intervalMs);

    // Window focus with 5s debounce
    this.focusHandler = () => {
      const now = Date.now();
      if (now - this.lastFocusAt < 5000) return;
      this.lastFocusAt = now;
      if (!hasLiveSession()) return;
      void this.flushWithGate();
    };
    if (typeof window !== 'undefined') {
      window.addEventListener('focus', this.focusHandler);
    }

    // Online event
    this.onlineHandler = () => {
      if (!hasLiveSession()) return;
      if (typeof navigator !== 'undefined' && !navigator.onLine) return;
      void this.flushWithGate();
    };
    if (typeof window !== 'undefined') {
      window.addEventListener('online', this.onlineHandler);
    }
  }

  static teardownAutoSync(): void {
    if (this.autoSyncInterval) {
      clearInterval(this.autoSyncInterval);
      this.autoSyncInterval = null;
    }
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    if (this.focusHandler && typeof window !== 'undefined') {
      window.removeEventListener('focus', this.focusHandler);
    }
    if (this.onlineHandler && typeof window !== 'undefined') {
      window.removeEventListener('online', this.onlineHandler);
    }
    this.focusHandler = null;
    this.onlineHandler = null;
    this.isAutoSyncSetup = false;
    this.retryAttempt = 0;
  }

  private static async flushWithGate(): Promise<void> {
    if (!hasLiveSession()) return;
    if (typeof navigator !== 'undefined' && !navigator.onLine) return;
    if (this.outboxService?.isBreakerPaused()) return;
    try {
      // Prefer outboxService flush (handles per-row handler + breaker)
      // but gated flush already checks hasLiveSession; on success update health
      await this.outboxService?.flush();
      this.lastSyncAt = new Date().toISOString();
      this.lastError = null;
      this.retryAttempt = 0;
      if (this.retryTimer) {
        clearTimeout(this.retryTimer);
        this.retryTimer = null;
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      this.lastError = msg;
      this.scheduleRetry();
    }
  }

  private static scheduleRetry(): void {
    if (this.retryTimer) return;
    // Exponential backoff 1s -> 2s -> 4s -> 8s -> 16s -> 30s max + jitter
    const base = 1000 * Math.pow(2, this.retryAttempt);
    const capped = Math.min(base, 30_000);
    const jitter = Math.random() * 200;
    const delay = capped + jitter;
    this.retryAttempt = Math.min(this.retryAttempt + 1, 5);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      void this.flushWithGate();
    }, delay);
  }

  static async getSyncHealth(): Promise<SyncHealth> {
    let pendingCount = 0;
    try {
      const rows = await this.healthDao.listReady();
      pendingCount = rows.length;
    } catch {
      pendingCount = 0;
    }

    // Realtime status derived from supabase getChannels
    let realtimeStatus: RealtimeStatus = 'closed';
    let realtimeMap: Record<string, RealtimeStatus> = {};
    try {
      const client = getSessionClient() as unknown as {
        getChannels?: () => Array<{ topic?: string; state?: string }>;
        getChannel?: (name: string) => { state?: string } | undefined;
      };
      const uid = authState.userId;
      if (uid && typeof client.getChannels === 'function') {
        const channels = client.getChannels();
        const relevant = channels.filter((c) => (c.topic ?? '').includes(uid));
        const mapState = (s: string): RealtimeStatus => {
          if (s === 'subscribed' || s === 'joined') return 'connected';
          if (s === 'joining' || s === 'connecting') return 'connecting';
          if (s === 'closed' || s === 'leaving' || s === 'unsubscribed') return 'closed';
          if (s === 'errored' || s === 'error') return 'error';
          return 'closed';
        };
        if (relevant.length > 0) {
          // Overall = worst status: error > closed > connecting > connected
          const rank: Record<RealtimeStatus, number> = {
            error: 3,
            closed: 2,
            connecting: 1,
            connected: 0,
          };
          let worst: RealtimeStatus = 'connected';
          for (const ch of relevant) {
            const st = mapState(ch.state ?? '');
            realtimeMap[ch.topic ?? 'unknown'] = st;
            if (rank[st] > rank[worst]) worst = st;
          }
          realtimeStatus = worst;
        } else {
          realtimeStatus = hasLiveSession() ? 'connecting' : 'closed';
        }
      } else if (uid) {
        realtimeStatus = hasLiveSession() ? 'connecting' : 'closed';
      }
    } catch {
      realtimeStatus = 'closed';
    }

    const nextRetryAt = this.retryTimer
      ? new Date(Date.now() + 1000 * Math.pow(2, this.retryAttempt)).toISOString()
      : null;

    return {
      lastSyncAt: this.lastSyncAt,
      pendingCount,
      lastError: this.lastError,
      realtimeStatus,
      outboxDepth: pendingCount,
      nextRetryAt,
      realtime: realtimeMap,
    };
  }

  /**
   * Sync book metadata catalog with Supabase.
   *
   * Flow:
   * 1. Fetch remote catalog from user_books table
   * 2. Get local books from SQLite
   * 3. Reconcile: push local books that are missing from the remote catalog
   *
   * The shelf "Available from other devices" section is no longer fed from
   * user_books — it lists Drive directly via
   * `downloadableCatalog.loadAvailableFromDrive()` (T-03).
   */
  static async syncBookCatalog(): Promise<void> {
    if (!authState.userId) return;
    // D1: re-verify the live session once for silent drops (DA-1 "stale without event").
    if (!(await recheckLiveSession())) return;

    try {
      const catalogSync = new SupabaseBookCatalogSync(authState.userId);

      // 1. Fetch remote catalog
      const remoteBooks = await catalogSync.fetchCatalog();

      // 2. Get local books — library listing for coverPath, book listing for filePath
      const [booksWithCover, sourceBooks] = await Promise.all([
        this.libraryPort.listLibraryBooks(),
        this.libraryPort.listBooks(),
      ]);
      const filePathByBookId = new Map(sourceBooks.map((b) => [b.id, b.filePath]));
      const localBooks = booksWithCover.map((lb) => ({
        ...lb,
        filePath: filePathByBookId.get(lb.id) ?? '',
      }));

      const remoteIds = new Set(remoteBooks.map((b) => b.id));

      // 3. Reconcile: push any local book missing from remote catalog, or
      // present in the catalog but without a Drive remote ref (the outbox
      // BOOK/UPSERT may have been lost silently, leaving metadata-only rows).
      for (const book of localBooks) {
        const remoteBook = remoteBooks.find((rb) => rb.id === book.id);
        const missingFromCatalog = !remoteIds.has(book.id);
        const missingRemoteRef = Boolean(remoteBook && !remoteBook.remoteProvider);
        if (!missingFromCatalog && !missingRemoteRef) continue;
        try {
          // Cover upload: try to read the cover file and upload to Storage
          let coverUrl: string | null = null;
          try {
            if (book.coverPath) {
              const coverBytes = await this.libraryPort.getFileBytes(book.coverPath);
              coverUrl = await catalogSync.uploadCover(
                authState.userId,
                book.id,
                new Uint8Array(coverBytes).buffer as ArrayBuffer,
              );
            }
          } catch (e) {
            reportCoverFailure(book.id, e);
          }

          // Binary upload to Drive + remote-ref persistence (DRP-1) when the
          // local file exists and the catalog row has no remote ref yet. The
          // upload must succeed before refs are written; on failure the error
          // is non-blocking for metadata but no partial refs are persisted.
          let remoteRefs: ReturnType<typeof buildRemoteRefs> | null = null;
          if (book.filePath && missingRemoteRef) {
            try {
              const fileBytes = await this.libraryPort.getFileBytes(book.filePath);
              const expectedName = canonicalBookName(book.id, book.format);
              const fileId = await this.gdrive.upload(
                book.id,
                new Uint8Array(fileBytes),
                expectedName,
              );
              remoteRefs = buildRemoteRefs(book.id, book.format, fileId);
            } catch (e) {
              console.error(`Failed to upload book file for ${book.id}:`, e);
            }
          }

          await catalogSync.upsertBook({
            id: book.id,
            userId: authState.userId,
            title: book.title,
            author: book.author || null,
            format: book.format,
            filePath: book.filePath || null,
            coverUrl: coverUrl,
            description: null,
            totalPages: book.totalPages > 0 ? book.totalPages : null,
            sourceDevice: 'desktop',
            importedAt: book.createdAt,
            updatedAt: book.updatedAt,
            ...(remoteRefs ?? {}),
          });
        } catch (e) {
          console.error(`Failed to push local book ${book.id} to catalog:`, e);
        }
      }
    } catch (e) {
      // SR-3: typed AUTH_REQUIRED/AUTH_EXPIRED must surface, never console.error-only.
      reportAuthError(e);
      console.error('Failed to sync book catalog:', e);
    }
  }

  static async syncMetadata(): Promise<void> {
    if (!authState.isSignedIn) return;
    // D1: async sync path re-verifies the live session once (DA-1.2).
    if (!(await recheckLiveSession())) return;

    if (this.metadataSyncPromise) return this.metadataSyncPromise;

    const syncPromise = Promise.all([this.syncBooks(), this.syncState(), this.syncBookCatalog()])
      .then(() => undefined)
      .catch((error: unknown) => {
        reportAuthError(error);
        console.error('Failed to sync startup metadata:', error);
      })
      .finally(() => {
        if (this.metadataSyncPromise === syncPromise) {
          this.metadataSyncPromise = null;
        }
      });

    this.metadataSyncPromise = syncPromise;
    return syncPromise;
  }

  /**
   * On first sync after login, run the one-time import of Drive progress,
   * bookmarks, highlights, and tags to Supabase.
   */
  private static async ensureSupabaseImport(): Promise<void> {
    if (this.supabaseImportDone) return;
    if (
      this.readingProgressSync === 'drive' &&
      this.bookmarkSync === 'drive' &&
      this.highlightSync === 'drive'
    )
      return;
    if (!authState.userId) return;
    // D1: re-verify the live session once for silent drops (DA-1.2).
    if (!(await recheckLiveSession())) return;

    try {
      const sync = new SupabaseProgressSync(authState.userId);
      const localBooks = await this.libraryPort.listBooks();

      // ── Import progress ──
      if (this.readingProgressSync !== 'drive') {
        const progressRows: Array<{
          userId: string;
          bookId: string;
          cfiLocation: string;
          percentage: number;
          updatedAt: string;
        }> = [];

        for (const book of localBooks) {
          const progress = await this.viewerPort.getProgress(book.id);
          if (progress) {
            progressRows.push({
              userId: authState.userId,
              bookId: progress.bookId,
              cfiLocation: progress.cfiLocation,
              percentage: progress.percentage,
              updatedAt: progress.updatedAt,
            });
          }
        }

        if (progressRows.length > 0) {
          await sync.importFromDrive(progressRows);
        }
      }

      // ── Import bookmarks ──
      if (this.bookmarkSync !== 'drive') {
        const bookmarkRows: Array<{
          userId: string;
          bookId: string;
          cfiLocation: string;
          titleSnippet: string | null;
          updatedAt: string;
        }> = [];

        for (const book of localBooks) {
          const bookmarks = await this.viewerPort.listBookmarks(book.id);
          for (const b of bookmarks) {
            bookmarkRows.push({
              userId: authState.userId,
              bookId: b.bookId,
              cfiLocation: '',
              titleSnippet: b.title ?? null,
              updatedAt: b.createdAt,
            });
          }
        }

        if (bookmarkRows.length > 0) {
          await sync.importBookmarksFromDrive(
            bookmarkRows.map((b) => ({
              ...b,
              id: crypto.randomUUID(),
              locatorJson: null,
              deletedAt: null,
            })),
          );
        }
      }

      // ── Import highlights + tags ──
      if (this.highlightSync !== 'drive') {
        const highlightRows: Array<{
          id: string;
          userId: string;
          bookId: string;
          cfiRange: string;
          textContent: string;
          note: string | null;
          color: string;
          rectJson: Record<string, number> | null;
          updatedAt: string;
        }> = [];

        for (const book of localBooks) {
          const highlights = await this.viewerPort.listHighlights(book.id);
          for (const h of highlights) {
            highlightRows.push({
              id: h.id,
              userId: authState.userId,
              bookId: h.bookId,
              cfiRange: h.cfi ?? '',
              textContent: h.text,
              note: h.note ?? null,
              color: h.color,
              rectJson: h.pageNumber ? { left: 0, right: 0, top: 0, bottom: 0 } : null,
              updatedAt: h.createdAt,
            });
          }

          // Import tags for each highlight
          for (const h of highlights) {
            const tags: TagDto[] = await this.viewerPort.listTagsForHighlight(h.id).catch(() => []);
            for (const tag of tags) {
              const tagRow = await sync.upsertTag({
                userId: authState.userId,
                name: tag.name,
                color: tag.color ?? null,
              });
              await sync.linkTagToHighlight(h.id, tagRow.id!);
            }
          }
        }

        if (highlightRows.length > 0) {
          await sync.importHighlightsFromDrive(
            highlightRows.map((h) => ({
              ...h,
              type: null,
              locatorJson: null,
              deletedAt: null,
            })),
          );
        }
      }

      this.supabaseImportDone = true;
    } catch (e) {
      console.error('Failed to import data to Supabase:', e);
      // Non-blocking — retry on next sync
    }
  }

  /**
   * Sync book files with Drive — download missing files, upload local-only files.
   * Book metadata (title, author) stays local-only (no table sync).
   */
  private static async syncBooks(): Promise<void> {
    const userId = authState.userId;
    if (!userId) return;
    // D1: re-verify the live session once for silent drops (DA-1.2).
    if (!(await recheckLiveSession())) return;

    // 1. List remote book files from Drive
    const remoteFiles = await this.gdrive.list('');
    const remoteBookFiles = remoteFiles.filter((f) => !f.endsWith('_state.json'));

    // 2. Get local books from SQLite
    const localBooks = await this.libraryPort.listBooks();

    // 3. Download book files that are on Drive but missing locally
    for (const remoteFile of remoteBookFiles) {
      const localBook = localBooks.find((b) => {
        const ext = b.format || 'epub';
        return remoteFile === `${b.id}.${ext}` || remoteFile.startsWith(b.id);
      });

      if (localBook) {
        const existsLocally = await this.libraryPort.fileExists(localBook.filePath);
        if (!existsLocally) {
          try {
            console.log(`Syncing missing file for book: ${localBook.id}`);
            const fileData = await this.gdrive.download(remoteFile);
            await this.libraryPort.saveBookFile(localBook.id, Array.from(fileData));
          } catch (e) {
            reportAuthError(e);
            console.error(`Failed to sync book file for ${localBook.id}:`, e);
          }
        }
      }
    }

    // 4. Upload local-only books to Drive (file only, not metadata)
    const remoteFileIds = new Set(remoteBookFiles);
    for (const localBook of localBooks) {
      const ext = localBook.format || 'epub';
      const expectedName = `${localBook.id}.${ext}`;
      if (!remoteFileIds.has(expectedName)) {
        try {
          const existsLocally = await this.libraryPort.fileExists(localBook.filePath);
          if (existsLocally) {
            const fileBytes = await this.libraryPort.getFileBytes(localBook.filePath);
            const fileId = await this.gdrive.upload(
              expectedName,
              new Uint8Array(fileBytes),
              expectedName,
            );
            // Persist the remote ref (DRP-1): the upload fileId was previously
            // discarded. The merge upsert preserves existing fields and never
            // lowers catalog_version (DRP-2).
            try {
              const bookSync = new SupabaseBookCatalogSync(userId);
              await bookSync.upsertBook({
                id: localBook.id,
                userId,
                title: localBook.title,
                author: localBook.author || null,
                format: localBook.format,
                filePath: null,
                coverUrl: null,
                description: null,
                totalPages: null,
                sourceDevice: 'desktop',
                ...buildRemoteRefs(localBook.id, localBook.format, fileId),
                importedAt: localBook.createdAt,
                updatedAt: new Date().toISOString(),
              });
            } catch (persistError) {
              console.error(`Failed to persist Drive refs for book ${localBook.id}:`, persistError);
            }
          }
        } catch (e) {
          reportAuthError(e);
          console.error(`Failed to upload book file for ${localBook.id}:`, e);
        }
      }
    }
  }

  /**
   * Sync reading state — PR2: Supabase SoT hot only.
   * Drive hot push/pull removed; cold backup is DriveColdBackupService (PR3).
   * Hot state is handled via outbox → Supabase (setupOutboxProcessor) + Realtime supervisor.
   * This method now only runs the one-time Drive→Supabase import for migrated users.
   */
  private static async syncState(): Promise<void> {
    if (!(await recheckLiveSession())) return;
    await this.ensureSupabaseImport();
    // PR2: no Drive hot sync — Supabase Realtime + outbox is sole hot path.
    // Import above covers legacy Drive state.json migration; do not pull/push Drive.
  }
}
