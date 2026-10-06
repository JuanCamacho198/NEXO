import {
  liveCatalogProvider,
  type CatalogBook,
  type CatalogErrorCode,
  type CatalogProvider,
  type CatalogSourceInfo,
} from '$lib/shared/services/catalog';
import { importDiscoverFile, type DiscoverDownloadPorts } from './discoverDownloadImport';
import {
  createTauriDownloadTransfer,
  isDownloadCancelled,
  type DownloadTransferPort,
} from './downloadTransfer';
import { discardRemoteDownload } from '$lib/shared/api/downloadApi';
import { TRENDING_CHIPS } from './discoverChips';
import {
  catalogCodeOf,
  DiscoverRailsDomainState,
  isOfflineCatalogCode,
  type DiscoverBrowseScope,
  type DiscoverRailState,
  type DiscoverRailsDeps,
} from './DiscoverRailsDomainState.svelte';

export type DiscoverStatus =
  'idle' | 'loading' | 'loadingMore' | 'loaded' | 'empty' | 'error' | 'offline';

export type DiscoverDetailStatus =
  'closed' | 'loading' | 'loaded' | 'notFound' | 'error' | 'offline';

/** In-app download-to-import lifecycle for the open detail book. */
export type DiscoverDownloadState =
  'idle' | 'downloading' | 'importing' | 'imported' | 'cancelled' | 'error';

/** Chip taxonomy + chip filtering live in `discoverChips.ts`; re-exported for consumers. */
export { CHIP_KEYWORDS, TRENDING_CHIPS, filterBooksByChip, matchesChip } from './discoverChips';
/** The 3-rail plan lives in `railPlan.ts`; re-exported so existing imports keep working. */
export { DISCOVER_RAIL_COUNT, DISCOVER_RAIL_LIMIT, type DiscoverRailSpec } from './railPlan';
export type { DiscoverBrowseScope, DiscoverRailState } from './DiscoverRailsDomainState.svelte';

/** Single production transfer port; the Rust command owns the actual transfer. */
const defaultDownloadTransfer: DownloadTransferPort = createTauriDownloadTransfer();

function messageOf(err: unknown): string {
  if (typeof err === 'string') return err;
  if (err instanceof Error) return err.message;
  return String(err);
}

class DiscoverDomainState {
  query = $state('');
  status = $state<DiscoverStatus>('idle');
  books = $state<CatalogBook[]>([]);
  totalCount = $state(0);
  nextPage = $state<number | null>(null);
  activePage = $state(0);
  errorCode = $state<CatalogErrorCode | null>(null);
  detail = $state<CatalogBook | null>(null);
  detailStatus = $state<DiscoverDetailStatus>('closed');
  /** Download-to-import lifecycle for the open detail book. */
  downloadState = $state<DiscoverDownloadState>('idle');
  /** Code-only failure message for the retry UI (redacted at the boundary). */
  downloadError = $state<string | null>(null);
  /** Bytes received so far in the current download. */
  progressBytes = $state(0);
  /** Total bytes from `content-length`, or null when the host omits it. */
  progressTotal = $state<number | null>(null);
  /** Static chip labels for client-side filtering over loaded rails. */
  trending = $state<string[]>([...TRENDING_CHIPS]);

  /**
   * Post-import refresh seam, mirroring `BulkImportDomainState`: the Discover
   * download imports through the same pipeline as a file import, so the library
   * mirror must reload or the shelf keeps serving its boot-time snapshot. Null
   * until the composition root wires it; best-effort by contract.
   */
  onLibraryRefreshNeeded: (() => Promise<void>) | null = null;

  /** Rail orchestration (3-rail plan, per-rail machine, scoped browse). */
  readonly railsState: DiscoverRailsDomainState;

  private lastAttemptedPage = 0;
  /** Id of the last requested detail, so a failed load can be retried. */
  private lastDetailId: string | null = null;
  /** Card payload of the last open, re-seeded by `retryDetail` when present. */
  private lastDetailSeed: CatalogBook | null = null;
  /** Bumped per open/close so a superseded enrichment cannot touch the sheet. */
  private detailGeneration = 0;
  /** Transfer id of the in-flight backend download, or null. */
  private activeTransferId: string | null = null;
  /** Set by `cancelDownload` so a late settle becomes `cancelled`, never `imported`. */
  private cancelRequested = false;
  /** Bumped by `resetDownload` so a superseded transfer cannot touch the machine. */
  private transferGeneration = 0;

  constructor(
    private readonly provider: CatalogProvider = liveCatalogProvider,
    private readonly downloadPorts: DiscoverDownloadPorts = {},
    rails: Pick<DiscoverRailsDeps, 'now'> = {},
  ) {
    this.railsState = new DiscoverRailsDomainState({ provider: this.provider, now: rails.now });
  }

  /** Browse rails in stable index order; `Hidden` rails render nothing. */
  get rails(): DiscoverRailState[] {
    return this.railsState.rails;
  }

  /** False once any rail observed a connectivity failure. */
  get isOnline(): boolean {
    return this.railsState.isOnline;
  }

  setQuery(query: string): void {
    this.query = query;
    if (query.trim() === '') {
      this.resetToIdle();
    }
  }

  async searchFirstPage(): Promise<void> {
    if (this.query.trim() === '') {
      this.resetToIdle();
      return;
    }
    this.status = 'loading';
    this.errorCode = null;
    this.lastAttemptedPage = 1;
    try {
      const page = await this.provider.search(this.query, 1);
      this.books = page.results;
      this.totalCount = page.totalCount;
      this.nextPage = page.nexoPage;
      this.activePage = 1;
      this.status = page.results.length === 0 ? 'empty' : 'loaded';
    } catch (err) {
      const code = catalogCodeOf(err);
      this.errorCode = code;
      this.status = statusForCode(code);
    }
  }

  async loadNextPage(): Promise<void> {
    const page = this.nextPage;
    if (page == null || this.status === 'loading' || this.status === 'loadingMore') {
      return;
    }
    this.status = 'loadingMore';
    this.lastAttemptedPage = page;
    try {
      const result = await this.provider.search(this.query, page);
      const seen = new Set(this.books.map((b) => b.id));
      for (const book of result.results) {
        if (!seen.has(book.id)) {
          seen.add(book.id);
          this.books.push(book);
        }
      }
      this.totalCount = result.totalCount;
      this.nextPage = result.nexoPage;
      this.activePage = page;
      this.errorCode = null;
      this.status = 'loaded';
    } catch (err) {
      const code = catalogCodeOf(err);
      this.errorCode = code;
      this.status = statusForCode(code);
    }
  }

  /**
   * Open a detail sheet. A `CatalogBook` seeds the sheet synchronously from the
   * card's full payload and paints `loaded` with no round-trip; the provider
   * call then runs as background enrichment whose success replaces the seed and
   * whose failure leaves it in place. A bare id (direct callers, retries) keeps
   * the original loading → fetch path.
   */
  async openDetail(bookOrId: CatalogBook | string): Promise<void> {
    // Opening a book supersedes any transfer in flight for the previous one.
    this.cancelDownload();
    this.resetDownload();
    const seed = typeof bookOrId === 'string' ? null : bookOrId;
    const id = typeof bookOrId === 'string' ? bookOrId : bookOrId.id;
    this.lastDetailId = id;
    this.lastDetailSeed = seed;
    // Supersede any enrichment still in flight for a previously opened book.
    const generation = (this.detailGeneration += 1);
    if (seed) {
      this.detail = seed;
      this.detailStatus = 'loaded';
    } else {
      this.detail = null;
      this.detailStatus = 'loading';
    }
    let enriched: CatalogBook;
    try {
      enriched = await this.provider.getDetails(id);
    } catch (err) {
      // A superseded open must never touch the current sheet.
      if (this.detailGeneration !== generation) return;
      // With a seed, enrichment is best-effort: the card's payload stays on
      // screen instead of blanking the sheet the user is already reading.
      if (seed) return;
      const code = catalogCodeOf(err);
      // Offline is its own state: the detail sheet shows connectivity copy plus
      // a retry, not the generic "catalog unavailable" message.
      this.detailStatus =
        code === 'NOT_FOUND' ? 'notFound' : isOfflineCatalogCode(code) ? 'offline' : 'error';
      return;
    }
    if (this.detailGeneration !== generation) return;
    this.detail = enriched;
    this.detailStatus = 'loaded';
  }

  /** Re-open the last requested detail (the offline/error retry affordance). */
  async retryDetail(): Promise<void> {
    if (this.lastDetailId === null) return;
    await this.openDetail(this.lastDetailSeed ?? this.lastDetailId);
  }

  dismissDetail(): void {
    this.cancelDownload();
    // Supersede an in-flight enrichment so a closed sheet cannot reopen itself.
    this.detailGeneration += 1;
    this.detail = null;
    this.detailStatus = 'closed';
    this.lastDetailSeed = null;
    this.resetDownload();
  }

  /**
   * Transfer the open detail book through the backend port, then import the
   * resulting local file into the library. Progress reports received bytes;
   * cancel aborts the backend transfer so a halted transfer never reaches
   * persistence.
   */
  async startDownload(): Promise<void> {
    if (this.downloadState === 'downloading' || this.downloadState === 'importing') return;
    const book = this.detail;
    const url = book?.downloadUrl ?? null;
    if (!book || !url || url.trim() === '') {
      this.downloadState = 'error';
      this.downloadError = 'UNAVAILABLE_DOWNLOAD';
      return;
    }
    await this.startDownloadUrl(book, url);
  }

  /**
   * Start a transfer of `url` for `book` on the single download machine.
   * `downloading` → `importing` → `imported`, plus `error` and `cancelled`;
   * the backend byte source replaces the previous webview fetch. The backend's
   * temp file is discarded best-effort on success and on a failed import, so
   * retries never accumulate leftovers.
   */
  async startDownloadUrl(book: CatalogBook, url: string): Promise<void> {
    if (this.downloadState === 'downloading' || this.downloadState === 'importing') return;
    const trimmed = typeof url === 'string' ? url.trim() : '';
    if (!book || trimmed === '') {
      this.downloadState = 'error';
      this.downloadError = 'UNAVAILABLE_DOWNLOAD';
      return;
    }
    const transferId = crypto.randomUUID();
    const generation = this.transferGeneration;
    const transfer = this.downloadPorts.transfer ?? defaultDownloadTransfer;
    this.activeTransferId = transferId;
    this.cancelRequested = false;
    this.downloadState = 'downloading';
    this.downloadError = null;
    this.progressBytes = 0;
    this.progressTotal = null;
    try {
      const { filePath } = await transfer.download(
        { transferId, url: trimmed, format: 'epub' },
        (done, total) => {
          this.progressBytes = done;
          this.progressTotal = total;
        },
      );
      if (this.transferGeneration !== generation) return;
      if (this.cancelRequested) {
        this.settleCancelled(filePath);
        return;
      }
      this.downloadState = 'importing';
      const result = await importDiscoverFile(book, filePath, this.downloadPorts);
      if (this.transferGeneration !== generation) return;
      if (result.ok) {
        this.downloadState = 'imported';
        this.downloadError = null;
        this.discardDownloadedFile(filePath);
        // The import landed a library row, but the module singleton was seeded
        // at boot. Refresh best-effort so the shelf reflects it immediately;
        // a refresh failure must never turn a successful download into one.
        await this.refreshLibraryAfterImport();
      } else {
        this.downloadState = 'error';
        this.downloadError = result.error;
        // A failed import must not strand the transfer file: each retry mints a
        // fresh id, so keeping it would accumulate one download per attempt.
        this.discardDownloadedFile(filePath);
      }
    } catch (err) {
      if (this.transferGeneration !== generation) return;
      if (this.cancelRequested || isDownloadCancelled(err)) {
        this.downloadState = 'cancelled';
        this.downloadError = null;
      } else {
        this.downloadState = 'error';
        this.downloadError = messageOf(err);
      }
    } finally {
      if (this.transferGeneration === generation) this.activeTransferId = null;
    }
  }

  /**
   * Cancel the in-flight backend transfer (the single cancel path). The halted
   * transfer settles as `cancelled` and is never imported.
   */
  cancelDownload(): void {
    if (this.downloadState !== 'downloading') return;
    const transferId = this.activeTransferId;
    if (transferId === null) return;
    this.cancelRequested = true;
    const transfer = this.downloadPorts.transfer ?? defaultDownloadTransfer;
    void transfer.cancel(transferId).catch(() => undefined);
  }

  /** Re-run the last failed or halted transfer for the same open book. */
  async retryDownload(): Promise<void> {
    if (this.downloadState !== 'error' && this.downloadState !== 'cancelled') return;
    await this.startDownload();
  }

  async retry(): Promise<void> {
    if (this.lastAttemptedPage <= 1) {
      await this.searchFirstPage();
      return;
    }
    this.nextPage = this.lastAttemptedPage;
    await this.loadNextPage();
  }

  /**
   * Live source list for the hero pill (`En línea · N fuentes` where
   * N = the returned length). Pure read-through, no I/O.
   */
  refreshSources(): CatalogSourceInfo[] {
    return this.provider.listSources();
  }

  /** Load the rail set once per session; a settled set is reused on remount. */
  async ensureRailsLoaded(): Promise<void> {
    await this.railsState.ensureLoaded();
  }

  /** Re-resolve every rail of the index-stable 3-rail plan. */
  async refreshRails(): Promise<void> {
    await this.railsState.refreshRails();
  }

  /** Re-resolve only the rail at `index`; other rails keep their content. */
  async retryRail(index: number): Promise<void> {
    await this.railsState.retryRail(index);
  }

  /** Open the rail-scoped browse view for a rail header ("Ver todo"). */
  async openRailScope(scope: DiscoverBrowseScope): Promise<void> {
    await this.railsState.openScope(scope);
  }

  /** Append the next page of the open rail scope. */
  async loadRailScopeNextPage(): Promise<void> {
    await this.railsState.loadScopeNextPage();
  }

  /** Leave the rail-scoped browse view. */
  closeRailScope(): void {
    this.railsState.closeScope();
  }

  /**
   * Release rail retry timers and the connectivity listener. The screen shares
   * one app-lifetime state, so production never disposes; teardown paths and
   * tests do, so a discarded instance leaves no orphan timer or listener.
   */
  dispose(): void {
    this.railsState.dispose();
  }

  private resetToIdle(): void {
    this.books = [];
    this.totalCount = 0;
    this.nextPage = null;
    this.activePage = 0;
    this.errorCode = null;
    this.status = 'idle';
    this.lastAttemptedPage = 0;
  }

  /** Best-effort removal of the backend's temp file; never affects the UI state. */
  private discardDownloadedFile(filePath: string): void {
    void discardRemoteDownload(filePath).catch(() => undefined);
  }

  /**
   * Best-effort library refresh after a successful import. A rejecting hook is
   * logged only: the transfer already reached persistence, so the machine stays
   * `imported` and the UI keeps reporting success.
   */
  private async refreshLibraryAfterImport(): Promise<void> {
    if (!this.onLibraryRefreshNeeded) return;
    try {
      await this.onLibraryRefreshNeeded();
    } catch (err) {
      console.error('[Discover] library refresh after import failed (non-fatal):', err);
    }
  }

  /** Settle a transfer the user cancelled after the backend had finished it. */
  private settleCancelled(filePath: string): void {
    this.downloadState = 'cancelled';
    this.downloadError = null;
    this.discardDownloadedFile(filePath);
  }

  private resetDownload(): void {
    this.transferGeneration += 1;
    this.activeTransferId = null;
    this.cancelRequested = false;
    this.downloadState = 'idle';
    this.downloadError = null;
    this.progressBytes = 0;
    this.progressTotal = null;
  }
}

function statusForCode(code: CatalogErrorCode): DiscoverStatus {
  return isOfflineCatalogCode(code) ? 'offline' : 'error';
}

export const discoverState = new DiscoverDomainState();
export { DiscoverDomainState };
