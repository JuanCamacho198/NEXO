import { invoke } from '@tauri-apps/api/core';
import { i18n } from '$lib/shared/i18n';
import { SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';
import { ValidationError, validateBookImportInput } from '$lib/shared/validation/importSchemas';

export type BookImportInput = {
  sourcePath: string;
  title?: string;
  author?: string;
  format: string;
  genre?: string | null;
};

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
};

export type ImportProgress = {
  status: 'reading' | 'importing' | 'complete' | 'error';
  message: string;
  percentage?: number;
};

const normalizeSourcePath = (value: string): string => {
  const trimmed = value.trim();
  if (trimmed.startsWith('file://')) {
    try {
      return decodeURIComponent(trimmed.replace(/^file:\/\//, ''));
    } catch {
      return trimmed.replace(/^file:\/\//, '');
    }
  }

  return trimmed;
};

const readImportErrorMessage = (error: unknown): string => {
  if (error instanceof Error && error.message) {
    try {
      const parsed = JSON.parse(error.message) as {
        message?: unknown;
      };
      if (typeof parsed.message === 'string' && parsed.message.length > 0) {
        return parsed.message;
      }
    } catch {
      return error.message;
    }

    return error.message;
  }

  if (typeof error === 'string' && error.length > 0) {
    return error;
  }

  if (typeof error === 'object' && error !== null) {
    const candidate = (error as { message?: unknown }).message;
    if (typeof candidate === 'string' && candidate.length > 0) {
      return candidate;
    }
  }

  return i18n.t('en', 'errors.importCommandFailed');
};

export async function importBook(
  input: BookImportInput,
  onProgress?: (progress: ImportProgress) => void,
): Promise<BookDto> {
  const locale =
    i18n.toSupportedLocale((globalThis.localStorage?.getItem('nexo.ui.locale') ?? '').trim()) ??
    'es';
  onProgress?.({
    status: 'reading',
    message: i18n.t(locale, 'import.reading'),
  });

  try {
    const sourcePath = normalizeSourcePath(input.sourcePath);
    if (!sourcePath) {
      throw new Error(i18n.t(locale, 'import.emptyPath'));
    }

    const validation = validateBookImportInput({ ...input, sourcePath });
    if (!validation.ok) {
      const localized = i18n.t(locale, validation.key);
      const message = localized === validation.key ? validation.fallback : localized;
      throw new ValidationError(validation.key, message);
    }

    const book = await invoke<BookDto>('importBook', {
      input: {
        sourcePath,
        title: input.title,
        author: input.author,
        format: input.format,
        genre: input.genre ?? null,
      },
    });

    // Compute SHA-256 hash of the imported file for content-hash dedup
    // (PR 5 — cross-device book sync). Non-blocking on failure.
    let contentHash: string | undefined;
    try {
      const fileBytes = await getFileBytes(sourcePath);
      const hashBuffer = await crypto.subtle.digest('SHA-256', new Uint8Array(fileBytes));
      const hashArray = Array.from(new Uint8Array(hashBuffer));
      const hashHex = hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
      contentHash = `sha256:${hashHex}`;
    } catch {
      // Non-blocking — hash failure does not break the import UX.
      // The reconciliation pass still works (just without dedup).
    }

    // Queue a BOOK outbox entry so metadata is pushed to Supabase
    // for cross-device catalog visibility.
    try {
      const outboxDao = new SyncOutboxDao();
      await outboxDao.add(
        'BOOK',
        book.id,
        'UPSERT',
        JSON.stringify({
          title: book.title,
          author: book.author,
          format: book.format,
          totalPages: book.totalPages,
          content_hash: contentHash,
          importedAt: book.createdAt,
          updatedAt: book.updatedAt,
        }),
      );
    } catch (e) {
      // Non-blocking for the import UX, but must not be silent: if the outbox
      // write fails, the metadata would only reach Supabase via the
      // syncBookCatalog() reconciliation pass — and that pass needs to know the
      // gap exists. Logging here keeps the failure observable.
      console.error('Failed to enqueue BOOK outbox entry; syncBookCatalog will reconcile', e);
    }

    onProgress?.({
      status: 'importing',
      message: i18n.t(locale, 'import.importing'),
      percentage: 50,
    });

    onProgress?.({
      status: 'complete',
      message: i18n.t(locale, 'import.complete'),
      percentage: 100,
    });

    return book;
  } catch (error) {
    if (error instanceof ValidationError) {
      onProgress?.({
        status: 'error',
        message: error.fallback,
      });
      throw error;
    }
    onProgress?.({
      status: 'error',
      message: readImportErrorMessage(error),
    });

    throw new Error(readImportErrorMessage(error));
  }
}

export async function getFileBytes(filePath: string): Promise<Uint8Array> {
  const bytes = await invoke<number[]>('getFileBytes', { filePath });
  return new Uint8Array(bytes);
}
