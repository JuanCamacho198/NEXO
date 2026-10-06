import { get } from 'svelte/store';
import type { ReaderBook, ReadingSessionInput, SaveProgressInput } from '$lib/shared/types';
import { i18n } from '$lib/shared/i18n';
import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';
import { SupabaseProgressSync } from '$lib/shared/sync/SupabaseProgressSync';
import type { SupabaseProgressRow } from '$lib/shared/sync/SupabaseProgressSync';
import { readerSyncState } from './ReaderSyncState.svelte';
import {
  isValidSessionProgressEvent,
  MIN_SESSION_DURATION_SECONDS,
} from './readingSessionValidator';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { createPdfDocument } from '$lib/features/reader/viewer-pdf/pdfStreaming';

const outboxDao = new SyncOutboxDao();

class ReaderDomainState {
  private readonly viewerPort: ViewerPort;
  private readonly libraryPort: LibraryPort;

  // ─── State (lifecycle only) ───
  activeReadingBookId = $state<string | null>(null);
  cfiLocation = $state('');
  percentage = $state(0);
  locatorJson = $state<string | null>(null);
  preloadedBytes = $state<{ filePath: string; data: Uint8Array } | null>(null);
  readerError = $state<string | null>(null);
  isFullscreen = $state(false);

  // Delegated version — sync owns the reactive bump
  get highlightsVersion(): number {
    return readerSyncState.highlightsVersion;
  }
  set highlightsVersion(v: number) {
    readerSyncState.highlightsVersion = v;
  }

  onStatsRefreshNeeded: ((bookId: string) => Promise<void>) | null = null;
  onPageChangeCallback: ((bookId: string, page: number, total: number) => void) | null = null;

  // Expose sync appliedRemote for dedupe checks (read-only proxy)
  get appliedRemote(): Map<string, string> {
    return readerSyncState.appliedRemote;
  }

  // openEpoch stays in domain (lifecycle)
  private openEpoch = 0;

  // ─── Validation — delegated to validator ───
  isValidSessionProgressEvent(event: {
    startedAt: string;
    endedAt?: string;
    durationSeconds: number;
    startPercentage?: number;
    endPercentage?: number;
  }): boolean {
    return isValidSessionProgressEvent(event as never);
  }

  // ─── Sync hooks wiring ───
  constructor(deps: { viewerPort?: ViewerPort; libraryPort?: LibraryPort } = {}) {
    this.viewerPort = deps.viewerPort ?? new TauriViewerAdapter();
    this.libraryPort = deps.libraryPort ?? new TauriLibraryAdapter();
    readerSyncState.injectDomainHooks({
      applyRemoteProgress: (p) => this.applyRemoteProgress(p),
      getActiveReadingBookId: () => this.activeReadingBookId,
      onStatsRefreshNeeded: () => this.onStatsRefreshNeeded,
    });
  }

  // ─── Reading lifecycle ───
  async startReading(book: ReaderBook): Promise<void> {
    const epoch = ++this.openEpoch;
    this.activeReadingBookId = book.id;
    this.preloadedBytes = null;
    try {
      await this.libraryPort.setReadingStatus(book.id, 'reading');
    } catch {
      // Recoverable: the reader still opens. Surface the unsaved status instead of
      // silently showing the book under "Continuar leyendo" with nothing persisted.
      const message = i18n.t(get(i18n.locale), 'reader.readingStatusNotSaved');
      this.readerError = message;
      pushToast('error', message);
    }
    const format = book.format.toLowerCase();
    if (format === 'epub' || format === 'pdf') {
      this.libraryPort
        .getFileBytes(book.filePath)
        .then((bytes) => {
          this.preloadedBytes = { filePath: book.filePath, data: new Uint8Array(bytes) };
        })
        .catch(() => {});
    }
    if (format === 'pdf') {
      void createPdfDocument(book.filePath).catch(() => {});
    }
    if (format === 'epub') {
      try {
        const progress = await this.viewerPort.getProgress(book.id);
        if (epoch !== this.openEpoch) {
          return;
        }
        this.cfiLocation = progress?.cfiLocation ?? '';
        this.percentage = progress?.percentage ?? 0;
      } catch {
        if (epoch !== this.openEpoch) {
          return;
        }
        this.cfiLocation = '';
        this.percentage = 0;
      }
      if (authState.userId) {
        const sync =
          readerSyncState.getSupabaseSync() ?? new SupabaseProgressSync(authState.userId);
        if (!readerSyncState.getSupabaseSync()) readerSyncState.setSupabaseSync(sync);
        const localUpdatedAt = await this.viewerPort
          .getProgress(book.id)
          .then((value) => value?.updatedAt ?? null)
          .catch(() => null);
        void readerSyncState.fetchAndApplyBookState(
          sync,
          book.id,
          epoch,
          localUpdatedAt,
          () => epoch !== this.openEpoch || this.activeReadingBookId !== book.id,
        );
      }
    }
  }

  private applyRemoteProgress(progress: SupabaseProgressRow): void {
    this.cfiLocation = progress.cfiLocation;
    this.percentage = progress.percentage;
    this.locatorJson = progress.locatorJson ?? null;
    readerSyncState.appliedRemote.set(`progress:${progress.bookId}`, progress.updatedAt);
    void this.viewerPort.upsertProgress({
      id: progress.id ?? crypto.randomUUID(),
      bookId: progress.bookId,
      cfiLocation: progress.cfiLocation,
      percentage: progress.percentage,
      updatedAt: progress.updatedAt,
    });
  }

  // ─── Progress ───
  async handleEpubLocationChange(
    bookId: string,
    nextLocation: string,
    nextPercentage: number,
  ): Promise<void> {
    this.cfiLocation = nextLocation;
    this.percentage = Math.max(0, Math.min(100, nextPercentage));
    const payload: SaveProgressInput = {
      bookId,
      cfiLocation: nextLocation,
      percentage: this.percentage,
    };
    try {
      await this.viewerPort.saveProgress(payload);
      await this.libraryPort.setReadingStatus(
        bookId,
        this.percentage >= 100 ? 'completed' : 'reading',
      );
      if (authState.userId) {
        const outboxPayload = {
          userId: authState.userId,
          bookId,
          cfiLocation: nextLocation,
          percentage: this.percentage,
          locatorJson: this.locatorJson,
          updatedAt: new Date().toISOString(),
        };
        void outboxDao.addCoalesced(
          'READING_PROGRESS',
          bookId,
          'UPSERT',
          JSON.stringify(outboxPayload),
        );
      }
    } catch {}
    void this.onStatsRefreshNeeded?.(bookId);
  }

  async handlePdfPageChange(bookId: string, page: number, total: number): Promise<void> {
    this.onPageChangeCallback?.(bookId, page, total);
    try {
      await this.libraryPort.updateBookProgress(bookId, page);
    } catch {}
    void this.onStatsRefreshNeeded?.(bookId);
  }

  async handlePdfSessionProgress(
    bookId: string,
    event: {
      startedAt: string;
      endedAt?: string;
      durationSeconds: number;
      startPercentage?: number;
      endPercentage?: number;
    },
  ): Promise<void> {
    if (event.durationSeconds < MIN_SESSION_DURATION_SECONDS) return;
    if (!this.isValidSessionProgressEvent(event)) return;
    const payload: ReadingSessionInput = {
      bookId,
      startedAt: event.startedAt,
      endedAt: event.endedAt,
      durationSeconds: event.durationSeconds,
      startPercentage: event.startPercentage,
      endPercentage: event.endPercentage,
      userId: authState.userId ?? '',
    };
    try {
      const saved = await this.viewerPort.saveReadingSession(payload);
      void this.onStatsRefreshNeeded?.(bookId);
      const outboxPayload = {
        id: saved.id,
        bookId,
        startedAt: event.startedAt,
        endedAt: event.endedAt,
        durationMinutes: saved.durationMinutes,
        date: saved.date,
        userId: authState.userId ?? '',
        updatedAtEpochMillis: saved.updatedAtEpochMillis,
        startPercentage: event.startPercentage,
        endPercentage: event.endPercentage,
      };
      try {
        await outboxDao.add('READING_SESSION', bookId, 'UPSERT', JSON.stringify(outboxPayload));
      } catch (enqueueError) {
        console.error('Failed to enqueue reading session for sync:', enqueueError);
      }
    } catch {}
  }

  handleReaderLocationContext(ctx?: unknown): void {
    if (!ctx || typeof ctx !== 'object' || !('locator' in ctx)) return;
    const locator = (ctx as { locator?: unknown }).locator;
    this.locatorJson = typeof locator === 'string' ? locator : null;
  }

  // ─── Sync delegation (thin) ───
  subscribeToRemoteProgress(): void {
    readerSyncState.subscribeToRemoteProgress();
  }
  subscribeToRemoteBookmarks(): void {
    readerSyncState.subscribeToRemoteBookmarks();
  }
  subscribeToRemoteHighlights(): void {
    readerSyncState.subscribeToRemoteHighlights();
  }
  subscribeToRemoteSessions(): void {
    readerSyncState.subscribeToRemoteSessions();
  }
  unsubscribeFromRemoteProgress(): void {
    readerSyncState.unsubscribeFromRemoteProgress();
  }
  unsubscribeFromRemoteBookmarks(): void {
    readerSyncState.unsubscribeFromRemoteBookmarks();
  }
  unsubscribeFromRemoteHighlights(): void {
    readerSyncState.unsubscribeFromRemoteHighlights();
  }
  unsubscribeFromRemoteSessions(): void {
    readerSyncState.unsubscribeFromRemoteSessions();
  }
  refreshRemoteProgressSubscription(): void {
    readerSyncState.refreshRemoteProgressSubscription();
  }
  subscribeToAllRemoteChanges(): void {
    readerSyncState.subscribeToAllRemoteChanges();
  }
  unsubscribeFromAllRemoteChanges(): void {
    readerSyncState.unsubscribeFromAllRemoteChanges();
  }

  // For tests / compat: expose sync internals
  get _sync(): typeof readerSyncState {
    return readerSyncState;
  }

  // highlightPullInFlight proxy
  get highlightPullInFlight(): boolean {
    return readerSyncState.highlightPullInFlight;
  }
  set highlightPullInFlight(v: boolean) {
    readerSyncState.highlightPullInFlight = v;
  }

  resetReader(): void {
    this.openEpoch += 1;
    this.activeReadingBookId = null;
    this.cfiLocation = '';
    this.percentage = 0;
    this.preloadedBytes = null;
    this.readerError = null;
  }
}

export const readerState = new ReaderDomainState();
export { ReaderDomainState };
