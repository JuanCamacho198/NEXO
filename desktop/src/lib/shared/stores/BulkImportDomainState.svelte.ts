import { importBook, type ImportProgress } from '$lib/shared/services/BookImportService';
import { BulkImportService, type BulkImportProgress } from '$lib/shared/services/BulkImportService';
import { pickFile, pickFolder } from '$lib/shared/services/FilePicker';
import { extractPdfMetadata } from '$lib/shared/services/pdfThumbnail';
import { extractEpubImportMetadata } from '$lib/shared/services/epubImportMetadata';
import { inferGenreFromText } from '$lib/shared/services/genreHeuristic';
import type { BulkImportSummary, ScanFolderResult } from '$lib/shared/types';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';

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

  // ─── Callback for post-import refresh ───
  onLibraryRefreshNeeded: (() => Promise<void>) | null = null;

  // ─── Notice lifecycle ───

  dismissImportNotice(): void {
    if (this.importNoticeTimeoutId) {
      clearTimeout(this.importNoticeTimeoutId);
      this.importNoticeTimeoutId = null;
    }
    this.importNotice = null;
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
   * dedups identical errors (file+message key): re-reporting the visible
   * error neither stacks nor restarts its timer. Distinct errors replace.
   */
  private reportImportError(fileName: string, message: string): void {
    const current = this.importNotice;
    if (
      current?.status === 'error' &&
      current.fileName === fileName &&
      current.message === message
    ) {
      return;
    }
    this.setImportNotice(
      {
        status: 'error',
        fileName,
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
        console.debug('[import] metadata extraction threw, falling back to filename', err);
      }
      // Observability: log what we're about to commit to the backend so
      // "I imported a book and the title is still the filename" has a
      // paper trail in the dev console.
      console.debug('[import] resolved metadata', {
        format,
        file: file.name,
        titleSource:
          title && title !== fileStem ? 'metadata' : title ? 'filename-fallback' : 'none',
        authorSource: author ? 'metadata' : 'none',
        subjectSource: subject ? 'metadata' : 'none',
        title,
        author,
        subject,
      });
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

      await importBook(
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
            this.reportImportError(displayName, progress.message);
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
      // the import lifecycle is owned.
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
      this.reportImportError(fileStem, errorMessage);
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
