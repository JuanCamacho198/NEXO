import { importBook, type ImportProgress } from '$lib/shared/services/BookImportService';
import { BulkImportService, type BulkImportProgress } from '$lib/shared/services/BulkImportService';
import { pickFile, pickFolder } from '$lib/shared/services/FilePicker';
import { extractPdfMetadata } from '$lib/shared/services/pdfThumbnail';
import { extractEpubImportMetadata } from '$lib/shared/services/epubImportMetadata';
import { inferGenreFromText } from '$lib/shared/services/genreHeuristic';
import type { BulkImportSummary, ScanFolderResult } from '$lib/shared/types';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { notify } from '$lib/shared/stores/notificationCenter.svelte';
import { logger } from '$lib/shared/logger/Logger';

export type ImportNoticeStatus = 'importing' | 'success' | 'error';

export type ImportNotice = {
  status: ImportNoticeStatus;
  fileName: string;
  message: string;
  percentage: number;
};

const SUCCESS_DISMISS_MS = 3500;
const ERROR_DISMISS_MS = SUCCESS_DISMISS_MS;

class BulkImportDomainState {
  private readonly libraryPort: LibraryPort;

  constructor(deps: { libraryPort?: LibraryPort } = {}) {
    this.libraryPort = deps.libraryPort ?? new TauriLibraryAdapter();
    this.bulkImportService = new BulkImportService({ libraryPort: this.libraryPort });
  }

  // ─── State ───
  isBulkImportOpen = $state(false);
  isBulkScanning = $state(false);
  isBulkImporting = $state(false);
  bulkImportFolderPath = $state<string | null>(null);
  bulkImportFolderName = $state<string | null>(null);
  bulkScanResult = $state<ScanFolderResult | null>(null);
  bulkScanError = $state<string | null>(null);
  bulkImportProgress = $state<BulkImportProgress | null>(null);
  bulkImportSummary = $state<BulkImportSummary | null>(null);

  isImporting = $state(false);
  importProgress = $state<ImportProgress | null>(null);

  /**
   * Persistent import notice for the top progress banner. Lives across the
   * full lifecycle of a single-file import (start → success/error) and is
   * cleared manually via `dismissImportNotice()` or automatically after
   * SUCCESS_DISMISS_MS (success) / ERROR_DISMISS_MS (error).
   */
  importNotice = $state<ImportNotice | null>(null);

  // Internal
  bulkImportService: BulkImportService;
  private importNoticeTimeoutId: ReturnType<typeof setTimeout> | null = null;
  // Stable identity of the currently visible error notice, used for dedup.
  // Kept separate from the visible label so the banner can show the metadata
  // display name while the progress and catch paths dedup on the same file.
  private importNoticeDedupKey: string | null = null;

  // ─── Callback for post-import refresh ───
  onLibraryRefreshNeeded: (() => Promise<void>) | null = null;

  // ─── Notice lifecycle ───

  dismissImportNotice(): void {
    if (this.importNoticeTimeoutId) {
      clearTimeout(this.importNoticeTimeoutId);
      this.importNoticeTimeoutId = null;
    }
    this.importNotice = null;
    this.importNoticeDedupKey = null;
  }

  private setImportNotice(notice: ImportNotice, autoDismissMs?: number): void {
    if (this.importNoticeTimeoutId) {
      clearTimeout(this.importNoticeTimeoutId);
      this.importNoticeTimeoutId = null;
    }
    this.importNotice = notice;
    if (autoDismissMs !== undefined) {
      this.importNoticeTimeoutId = setTimeout(() => {
        this.importNotice = null;
        this.importNoticeTimeoutId = null;
      }, autoDismissMs);
    }
  }

  /**
   * FR-BI2: single error reporter. Auto-dismisses after ERROR_DISMISS_MS and
   * dedups identical errors. The dedup identity is the import's source path,
   * which is stable across the progress and catch paths of the same failure
   * even when the metadata display name differs from the filename stem. The
   * visible label is passed separately so the banner keeps the most useful
   * name.
   *
   * FR-DN1 feed: every reported error also records one tray entry per event
   * (before banner dedup — the tray counts outcome events, not visible
   * banners). Feed-only: banner behavior below is unchanged.
   */
  private reportImportError(identity: string, message: string, label: string = identity): void {
    notify({
      source: 'import',
      severity: 'error',
      i18nKey: 'notifications.kind.importFailure',
      i18nParams: { name: label, detail: message },
      target: { kind: 'route', route: 'library' },
    });
    const current = this.importNotice;
    if (
      current?.status === 'error' &&
      this.importNoticeDedupKey === identity &&
      current.message === message
    ) {
      return;
    }
    this.importNoticeDedupKey = identity;
    this.setImportNotice(
      {
        status: 'error',
        fileName: label,
        message,
        percentage: 0,
      },
      ERROR_DISMISS_MS,
    );
  }

  // ─── Single file import ───

  async handleImportFile(): Promise<void> {
    const file = await pickFile();
    if (!file) {
      return;
    }

    const format = file.name.toLowerCase().endsWith('.epub') ? 'epub' : 'pdf';
    // Fallback title (filename without extension) used when no metadata is
    // available. Kept here so the import notice can render something useful
    // before metadata extraction completes.
    const fileStem = file.name.replace(/\.(pdf|epub)$/i, '');

    this.isImporting = true;
    this.setImportNotice({
      status: 'importing',
      fileName: fileStem,
      message: '', // populated on first progress callback
      percentage: 0,
    });

    try {
      // Pull the title/author/subject from the file's embedded metadata
      // when possible. The PDF branch already extracted both via pdfjs;
      // the EPUB branch uses a lightweight epubjs one-shot. Either way,
      // a missing or empty metadata field falls back to the filename.
      let title: string | undefined;
      let author: string | undefined;
      let subject: string | null = null;
      try {
        if (format === 'pdf') {
          const meta = await extractPdfMetadata(file.path);
          if (meta.title?.trim()) title = meta.title.trim();
          if (meta.author?.trim()) author = meta.author.trim();
          if (meta.subject?.trim()) subject = meta.subject.trim();
        } else if (format === 'epub') {
          const meta = await extractEpubImportMetadata(file.path);
          if (meta.title?.trim()) title = meta.title.trim();
          if (meta.author?.trim()) author = meta.author.trim();
          if (meta.subject?.trim()) subject = meta.subject.trim();
        }
      } catch (err) {
        // best-effort: fall through to filename-based title
        logger.debug(
          '[import] metadata extraction threw, falling back to filename',
          { error: err instanceof Error ? err.message : String(err) },
          'import',
        );
      }
      // Observability: log what we're about to commit to the backend so
      // "I imported a book and the title is still the filename" has a
      // paper trail in the dev console.
      logger.debug(
        '[import] resolved metadata',
        {
          format,
          file: file.name,
          titleSource:
            title && title !== fileStem ? 'metadata' : title ? 'filename-fallback' : 'none',
          authorSource: author ? 'metadata' : 'none',
          subjectSource: subject ? 'metadata' : 'none',
          title,
          author,
          subject,
        },
        'import',
      );
      if (!title) title = fileStem;

      // Genre resolution: prefer the embedded subject (EPUB <dc:subject>
      // or PDF info-dict Subject/Keywords) verbatim. Fall back to the
      // keyword heuristic over title + author so the book lands in a
      // sensible bucket when no metadata is present.
      const genre = subject ?? inferGenreFromText({ title, author: author ?? null });

      // What the user sees in the import banner / success / error notice.
      // Prefer the metadata title (shorter, cleaner); otherwise the file
      // stem (filename without extension) which can be long but is still
      // meaningful.
      const displayName = title;

      const imported = await importBook(
        {
          sourcePath: file.path,
          title,
          author,
          format,
          genre,
        },
        (progress) => {
          this.importProgress = progress;
          // Map service-level status to notice status. The progress
          // `message` is locale-correct (it comes from the service's own
          // i18n lookup) so we reuse it verbatim.
          const noticeStatus: ImportNoticeStatus =
            progress.status === 'complete'
              ? 'success'
              : progress.status === 'error'
                ? 'error'
                : 'importing';
          if (noticeStatus === 'error') {
            this.reportImportError(file.path, progress.message, displayName);
          } else {
            this.importNotice = {
              status: noticeStatus,
              fileName: displayName,
              message: progress.message,
              percentage: progress.percentage ?? 0,
            };
          }
        },
      );

      await this.onLibraryRefreshNeeded?.();

      // After successful import, ensure the banner shows a success state
      // for SUCCESS_DISMISS_MS. The progress callback already set it, but
      // (a) we re-confirm and (b) schedule the auto-dismiss here, where
      // the import lifecycle is owned. Record the tray entry.
      notify({
        source: 'import',
        severity: 'success',
        i18nKey: 'notifications.kind.importSuccess',
        i18nParams: { name: displayName },
        target: { kind: 'book', bookId: imported.id },
      });
      this.setImportNotice(
        {
          status: 'success',
          fileName: displayName,
          message: '', // resolved by banner i18n
          percentage: 100,
        },
        SUCCESS_DISMISS_MS,
      );
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.reportImportError(file.path, errorMessage, fileStem);
      // Re-throw so the coordinator can surface the error elsewhere if needed.
      throw error;
    } finally {
      this.isImporting = false;
      this.importProgress = null;
    }
  }

  // ─── Bulk import ───

  openBulkImportModal(): void {
    this.isBulkImportOpen = true;
  }

  closeBulkImportModal(): void {
    if (this.isBulkImporting) {
      this.bulkImportService.cancel();
    }

    this.isBulkImportOpen = false;
    this.isBulkScanning = false;
    this.bulkScanError = null;
    this.bulkImportProgress = null;
    this.bulkImportSummary = null;
  }

  async handlePickBulkImportFolder(folderTitle: string): Promise<void> {
    const selected = await pickFolder(folderTitle);
    if (!selected) {
      return;
    }

    this.bulkImportFolderPath = selected.path;
    this.bulkImportFolderName = selected.name;
    this.bulkScanResult = null;
    this.bulkScanError = null;
    this.bulkImportProgress = null;
    this.bulkImportSummary = null;
  }

  async handleScanBulkImportFolder(): Promise<void> {
    if (!this.bulkImportFolderPath) {
      return;
    }

    this.isBulkScanning = true;
    this.bulkScanError = null;

    try {
      this.bulkScanResult = await this.libraryPort.scanFolder(this.bulkImportFolderPath);
    } catch (error) {
      this.bulkScanError = error instanceof Error ? error.message : 'Import failed';
    } finally {
      this.isBulkScanning = false;
    }
  }

  handleCancelBulkImport(): void {
    this.bulkImportService.cancel();
  }

  async handleStartBulkImport(): Promise<void> {
    if (
      !this.bulkImportFolderPath ||
      !this.bulkScanResult ||
      this.bulkScanResult.files.length === 0
    ) {
      return;
    }

    this.isBulkImporting = true;
    this.bulkScanError = null;
    this.bulkImportProgress = null;
    this.bulkImportSummary = null;

    try {
      const summary = await this.bulkImportService.importFolder(
        this.bulkImportFolderPath,
        (progress) => {
          this.bulkImportProgress = progress;
        },
      );

      this.bulkImportSummary = summary;

      // One tray entry per bulk-import outcome (feed-only).
      notify({
        source: 'import',
        severity: summary.failed === 0 ? 'success' : 'error',
        i18nKey:
          summary.failed === 0
            ? 'notifications.kind.importSuccess'
            : 'notifications.kind.importFailure',
        i18nParams: {
          name: this.bulkImportFolderName ?? this.bulkImportFolderPath ?? '',
          detail: `${summary.success} ok · ${summary.failed} failed · ${summary.skipped} skipped`,
        },
        target: { kind: 'route', route: 'library' },
      });

      if (
        summary.success > 0 ||
        summary.skipped > 0 ||
        summary.failed > 0 ||
        summary.cancelled > 0
      ) {
        await this.onLibraryRefreshNeeded?.();
      }
    } catch (error) {
      this.bulkScanError = error instanceof Error ? error.message : 'Import failed';
    } finally {
      this.isBulkImporting = false;
    }
  }
}

export const bulkImportState = new BulkImportDomainState();
export { BulkImportDomainState };
