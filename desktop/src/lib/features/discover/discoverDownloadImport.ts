/**
 * Discover download-to-import bridge (WU2).
 *
 * The byte source is the native backend: `downloadTransfer.ts` streams the
 * remote URL to a local file through the Rust command, and this module reads
 * that local file and reuses the verified `importRecoveredBook` pipeline via a
 * synthetic entry: no `contentHash` (hash check skipped) with EPUB metadata
 * fallback, mirroring the shelf download flow. The entry is marked imported only
 * when a live user session exists; otherwise the mark step is a no-op.
 *
 * No webview `fetch` remains anywhere in `features/discover/*`.
 */
import { authState } from '$lib/shared/stores/AuthState.svelte';
import type { CatalogBook } from '$lib/shared/services/catalog';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import { importRecoveredBook, type ImportDeps } from '$lib/shared/recovery/desktopRecoveryImport';
import type { SupabaseUserBookRow } from '$lib/shared/sync/SupabaseBookCatalogSync';
import { SupabaseBookCatalogSync } from '$lib/shared/sync/SupabaseBookCatalogSync';
import { extractEpubMetadataFromBytes } from '$lib/shared/services/epubImportMetadata';
import { logger } from '$lib/shared/logger/Logger';
import type { DownloadTransferPort } from './downloadTransfer';

export type { DiscoverProgressFn } from './downloadTransfer';

export interface DiscoverDownloadPorts {
  /** Backend transfer port; defaults to the Tauri command-backed port. */
  transfer?: DownloadTransferPort;
  importFn?: typeof importRecoveredBook;
  persist?: ImportDeps['persist'];
  markImported?: ImportDeps['markImported'];
  /** Local file reader; defaults to the existing `getFileBytes` command. */
  readFile?: (filePath: string) => Promise<Uint8Array>;
}

let discoverLibraryPort: LibraryPort = new TauriLibraryAdapter();

/** Test seam: swap the persistence port without touching the app wiring. */
export function setDiscoverLibraryPort(port: LibraryPort): void {
  discoverLibraryPort = port;
}

/** Entries that never carried real metadata keep the id as their title. */
const UUID_LIKE_TITLE_RE = /^[0-9a-f-]{36}$/i;

function isFallbackTitle(title: string, id: string): boolean {
  return title === id || UUID_LIKE_TITLE_RE.test(title);
}

/** Default local reader: the existing `getFileBytes` library command. */
async function defaultReadFile(filePath: string): Promise<Uint8Array> {
  return new Uint8Array(await discoverLibraryPort.getFileBytes(filePath));
}

/** Synthetic entry so the verified import pipeline can be reused as-is. */
function buildDiscoverImportRow(book: CatalogBook, nowIso?: string): SupabaseUserBookRow {
  const now = nowIso ?? new Date().toISOString();
  return {
    id: book.id,
    userId: authState.userId ?? '',
    title: book.title,
    author: book.authors[0] ?? '',
    format: 'epub',
    filePath: null,
    coverUrl: null,
    description: null,
    totalPages: null,
    sourceDevice: null,
    importedAt: now,
    updatedAt: now,
    lifecycle: 'available',
    catalogVersion: 1,
  };
}

/**
 * Extract the EPUB cover right after the file landed, reusing the existing
 * native extraction (the same routine `import_book` runs). It stays offline and
 * consistent with locally imported books. A cover failure is non-fatal: it is
 * logged and the book is kept, mirroring `import_book`'s recoverable handling.
 */
async function persistDiscoverCover(bookId: string, sourceFilePath: string): Promise<void> {
  try {
    await discoverLibraryPort.extractEpubCover(bookId, sourceFilePath);
  } catch (err) {
    logger.error(
      '[Discover] cover extraction failed (non-fatal):',
      { error: err instanceof Error ? err.message : String(err) },
      'import',
    );
  }
}

async function defaultPersist(
  bookId: string,
  bytes: Uint8Array,
  meta: { title: string; author: string; format: string },
  sourceFilePath: string,
): Promise<void> {
  let { title, author } = meta;
  if (meta.format === 'epub' && isFallbackTitle(title, bookId)) {
    try {
      const real = await extractEpubMetadataFromBytes(bytes);
      if (real.title) title = real.title;
      if (real.author) author = real.author;
    } catch {
      // keep catalog title
    }
  }
  await discoverLibraryPort.saveBookFile(bookId, Array.from(bytes), {
    title,
    author,
    format: meta.format,
  });
  if (meta.format === 'epub') {
    await persistDiscoverCover(bookId, sourceFilePath);
  }
}

export interface DiscoverImportResult {
  ok: boolean;
  error: string | null;
}

/**
 * Import a backend-downloaded local file into the library. Returns
 * `{ ok: true }` for both fresh and idempotent (`already_imported`)
 * outcomes; anything else maps to `{ ok: false, error }` for retry UI.
 * The file is read lazily inside the import's `download` dependency, so a
 * cancelled transfer never reaches persistence.
 */
export async function importDiscoverFile(
  book: CatalogBook,
  filePath: string,
  ports: DiscoverDownloadPorts = {},
): Promise<DiscoverImportResult> {
  const row = buildDiscoverImportRow(book);
  const readFile = ports.readFile ?? defaultReadFile;
  const importFn = ports.importFn ?? importRecoveredBook;
  const result = await importFn(row, {
    download: async () => readFile(filePath),
    // Bind the downloaded file path so the default persist can extract the
    // cover from it after the book file lands (the port signature stays
    // `(bookId, bytes, meta)`).
    persist: ports.persist ?? ((id, bytes, meta) => defaultPersist(id, bytes, meta, filePath)),
    markImported:
      ports.markImported ??
      (async (id, version) => {
        const userId = authState.userId;
        if (!userId) return;
        await new SupabaseBookCatalogSync(userId).upsertBook({
          ...row,
          id,
          lifecycle: 'imported',
          catalogVersion: version,
          updatedAt: new Date().toISOString(),
        });
      }),
  });
  if (result.outcome === 'imported' || result.outcome === 'already_imported') {
    return { ok: true, error: null };
  }
  return { ok: false, error: result.error?.message ?? result.outcome };
}
