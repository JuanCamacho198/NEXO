import { importBook as importSingleBook } from './BookImportService';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { inferGenreFromText } from '$lib/shared/services/genreHeuristic';
import { i18n } from '$lib/shared/i18n';
import { ValidationError, validateBookImportInput } from '$lib/shared/validation/importSchemas';
import {
  BULK_IMPORT_ITEM_STATUS,
  type BulkImportItemResult,
  type BulkImportSummary,
  type ScannedFile,
} from '$lib/shared/types';

export type BulkImportProgress = {
  summary: BulkImportSummary;
  currentFile: ScannedFile | null;
};

type ProgressCallback = (progress: BulkImportProgress) => void;

const stripKnownExtension = (fileName: string): string => {
  return fileName.replace(/\.(pdf|epub)$/i, '');
};

const readErrorMessage = (error: unknown): string => {
  if (error instanceof Error && error.message.trim().length > 0) {
    return error.message;
  }

  if (typeof error === 'string' && error.trim().length > 0) {
    return error;
  }

  return 'Import failed';
};

const resolveLocale = (): 'es' | 'en' =>
  i18n.toSupportedLocale((globalThis.localStorage?.getItem('nexo.ui.locale') ?? '').trim()) ?? 'es';

const buildSummary = (results: BulkImportItemResult[]): BulkImportSummary => {
  let queued = 0;
  let importing = 0;
  let success = 0;
  let skipped = 0;
  let failed = 0;
  let cancelled = 0;

  for (const result of results) {
    if (result.status === BULK_IMPORT_ITEM_STATUS.QUEUED) {
      queued += 1;
      continue;
    }

    if (result.status === BULK_IMPORT_ITEM_STATUS.IMPORTING) {
      importing += 1;
      continue;
    }

    if (result.status === BULK_IMPORT_ITEM_STATUS.SUCCESS) {
      success += 1;
      continue;
    }

    if (result.status === BULK_IMPORT_ITEM_STATUS.SKIPPED) {
      skipped += 1;
      continue;
    }

    if (result.status === BULK_IMPORT_ITEM_STATUS.FAILED) {
      failed += 1;
      continue;
    }

    if (result.status === BULK_IMPORT_ITEM_STATUS.CANCELLED) {
      cancelled += 1;
    }
  }

  return {
    total: results.length,
    queued,
    importing,
    success,
    skipped,
    failed,
    cancelled,
    results,
  };
};

const markRemainingAsCancelled = (results: BulkImportItemResult[]): void => {
  for (const result of results) {
    if (result.status === BULK_IMPORT_ITEM_STATUS.QUEUED) {
      result.status = BULK_IMPORT_ITEM_STATUS.CANCELLED;
      result.message = 'Cancelled';
    }
  }
};

export class BulkImportService {
  private cancelled = false;
  private static readonly CONCURRENCY = 3;
  private readonly libraryPort: LibraryPort;

  constructor(deps: { libraryPort?: LibraryPort } = {}) {
    this.libraryPort = deps.libraryPort ?? new TauriLibraryAdapter();
  }

  cancel(): void {
    this.cancelled = true;
  }

  private async processSingleFile(
    result: BulkImportItemResult,
    displayIndex?: number,
  ): Promise<BulkImportItemResult> {
    if (result.status !== BULK_IMPORT_ITEM_STATUS.QUEUED) {
      return result;
    }

    if (this.cancelled) {
      result.status = BULK_IMPORT_ITEM_STATUS.CANCELLED;
      result.message = 'Cancelled';
      return result;
    }

    result.status = BULK_IMPORT_ITEM_STATUS.IMPORTING;
    result.message = null;

    try {
      const stem = stripKnownExtension(result.file.fileName);
      const candidate = {
        sourcePath: result.file.fullPath,
        title: stem,
        format: result.file.format,
        genre: inferGenreFromText({ title: stem }),
      };
      const validation = validateBookImportInput(candidate);
      if (!validation.ok) {
        const locale = resolveLocale();
        const position = displayIndex ?? 0;
        result.status = BULK_IMPORT_ITEM_STATUS.FAILED;
        result.message = i18n.t(locale, 'import.validation.invalidItem', {
          index: position + 1,
        });
        return result;
      }
      const importedBook = await importSingleBook({
        sourcePath: result.file.fullPath,
        title: stem,
        format: result.file.format,
        genre: inferGenreFromText({ title: stem }),
      });

      result.status = BULK_IMPORT_ITEM_STATUS.SUCCESS;
      result.bookId = importedBook.id;
      result.message = null;
    } catch (error) {
      result.status = BULK_IMPORT_ITEM_STATUS.FAILED;
      result.message = readErrorMessage(error);
    }

    return result;
  }

  async importFolder(path: string, onProgress?: ProgressCallback): Promise<BulkImportSummary> {
    this.cancelled = false;
    const scanned = await this.libraryPort.scanFolder(path);

    if (scanned.files.length === 0) {
      const locale = resolveLocale();
      throw new ValidationError(
        'import.validation.emptyBatch',
        i18n.t(locale, 'import.validation.emptyBatch'),
      );
    }

    const results: BulkImportItemResult[] = scanned.files.map((file) => {
      if (file.isDuplicate) {
        return {
          file,
          status: BULK_IMPORT_ITEM_STATUS.SKIPPED,
          bookId: null,
          message: 'Duplicate filename in library',
        };
      }

      return {
        file,
        status: BULK_IMPORT_ITEM_STATUS.QUEUED,
        bookId: null,
        message: null,
      };
    });

    const emit = (currentFile: ScannedFile | null): void => {
      onProgress?.({
        summary: buildSummary(results),
        currentFile,
      });
    };

    emit(null);

    // Process in parallel batches
    const queuedIndices = results
      .map((r, i) => ({ result: r, index: i }))
      .filter(({ result }) => result.status === BULK_IMPORT_ITEM_STATUS.QUEUED)
      .map(({ index }) => index);

    for (
      let batchStart = 0;
      batchStart < queuedIndices.length;
      batchStart += BulkImportService.CONCURRENCY
    ) {
      if (this.cancelled) {
        break;
      }

      const batchIndices = queuedIndices.slice(
        batchStart,
        batchStart + BulkImportService.CONCURRENCY,
      );
      const batch = batchIndices.map((i) => results[i]);

      // Emit current file being processed
      emit(batch[0]?.file ?? null);

      // Process batch in parallel
      await Promise.all(
        batchIndices.map((globalIndex) =>
          this.processSingleFile(results[globalIndex], globalIndex),
        ),
      );

      // Emit after batch completes
      const completedFile =
        batch.find((r) => r.status === BULK_IMPORT_ITEM_STATUS.SUCCESS)?.file ??
        batch[0]?.file ??
        null;
      emit(completedFile);
    }

    if (this.cancelled) {
      markRemainingAsCancelled(results);
      emit(null);
    }

    return buildSummary(results);
  }
}
