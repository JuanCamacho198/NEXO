/**
 * Discover import cover wiring (F3).
 *
 * The default persist must run the EXISTING native EPUB cover extraction after
 * the book file lands, and a cover failure must be non-fatal so the import still
 * succeeds — the same recoverable handling `import_book` uses. Fakes only: no
 * live network and no Tauri invocation.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { logger } from '$lib/shared/logger/Logger';
import type { CatalogBook } from '$lib/shared/services/catalog/CatalogProvider';
import type { LibraryPort } from '$lib/shared/ports/LibraryPort';
import { TauriLibraryAdapter } from '$lib/shared/ports/adapters/tauri/TauriLibraryAdapter';
import {
  importDiscoverFile,
  setDiscoverLibraryPort,
} from '$lib/features/discover/discoverDownloadImport';

function fakeBook(): CatalogBook {
  return {
    id: 'gutendex:2701',
    provider: 'builtin:gutendex',
    title: 'Moby Dick; Or, The Whale',
    authors: ['Herman Melville'],
    coverUrl: null,
    languages: ['en'],
    subjects: ['Fiction'],
    downloadUrl: 'https://www.gutenberg.org/ebooks/2701.epub3.images',
    description: 'A whale.',
    formats: {
      'application/epub+zip': 'https://www.gutenberg.org/ebooks/2701.epub3.images',
    },
    isPublicDomain: true,
  };
}

describe('Discover import cover wiring', () => {
  const saveBookFile = vi.fn(async () => undefined);
  const extractEpubCover = vi.fn(async () => true);
  const libraryPort = { saveBookFile, extractEpubCover } as unknown as LibraryPort;

  beforeEach(() => {
    vi.clearAllMocks();
    setDiscoverLibraryPort(libraryPort);
  });

  afterEach(() => {
    setDiscoverLibraryPort(new TauriLibraryAdapter());
  });

  /** The downloaded file path handed to the import in every case. */
  const DOWNLOADED_PATH = '/tmp/downloads/t1.epub';

  async function runImport(): Promise<{ ok: boolean; error: string | null }> {
    return importDiscoverFile(fakeBook(), DOWNLOADED_PATH, {
      readFile: async () => new Uint8Array([1, 2, 3]),
      markImported: async () => undefined,
    });
  }

  it('extracts the cover from the downloaded file after the book lands', async () => {
    const result = await runImport();

    expect(result).toEqual({ ok: true, error: null });
    expect(saveBookFile).toHaveBeenCalledTimes(1);
    expect(extractEpubCover).toHaveBeenCalledWith('gutendex:2701', DOWNLOADED_PATH);
    // The book must be persisted before its cover is extracted from the file.
    expect(saveBookFile.mock.invocationCallOrder[0]).toBeLessThan(
      extractEpubCover.mock.invocationCallOrder[0],
    );
  });

  it('keeps the book when cover extraction fails (non-fatal)', async () => {
    extractEpubCover.mockRejectedValueOnce(new Error('no cover in epub'));
    const errorSpy = vi.spyOn(logger, 'error').mockImplementation(() => undefined);

    const result = await runImport();

    expect(result).toEqual({ ok: true, error: null });
    expect(saveBookFile).toHaveBeenCalledTimes(1);
    expect(extractEpubCover).toHaveBeenCalledTimes(1);
    // The failure is logged, not surfaced as an import error.
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });
});
