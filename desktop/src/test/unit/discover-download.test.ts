/**
 * Discover download wiring (slice 5).
 *
 * The byte source is the Rust command: `createTauriDownloadTransfer()` invokes
 * `downloadRemoteBook` and relays `discover-download-progress` events, and
 * `importDiscoverFile` reads the resulting local file through the existing
 * `importRecoveredBook` pipeline. A source-read assertion also proves that no
 * webview `fetch` path survives anywhere in `features/discover/*`.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { tick } from 'svelte';
import type { DownloadProgressPayload } from '$lib/shared/api/downloadApi';
import type { CatalogBook, CatalogProvider } from '$lib/shared/services/catalog/CatalogProvider';
import { catalogError } from '$lib/shared/services/catalog/errors';
import type { importRecoveredBook } from '$lib/shared/recovery/desktopRecoveryImport';
import type { SupabaseUserBookRow } from '$lib/shared/sync/SupabaseBookCatalogSync';
import {
  DiscoverDomainState,
  type DiscoverDownloadState,
} from '$lib/features/discover/DiscoverDomainState.svelte';
import { importDiscoverFile } from '$lib/features/discover/discoverDownloadImport';
import {
  createTauriDownloadTransfer,
  downloadErrorCode,
  DownloadCancelledError,
  isDownloadCancelled,
  type DownloadTransferPort,
  type DownloadTransferRequest,
  type DownloadTransferResult,
} from '$lib/features/discover/downloadTransfer';

const mocks = vi.hoisted(() => ({
  listen: vi.fn(),
  downloadRemoteBook: vi.fn(),
  cancelRemoteDownload: vi.fn(),
  discardRemoteDownload: vi.fn(),
}));

vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));
vi.mock('$lib/shared/api/downloadApi', () => ({
  downloadRemoteBook: mocks.downloadRemoteBook,
  cancelRemoteDownload: mocks.cancelRemoteDownload,
  discardRemoteDownload: mocks.discardRemoteDownload,
}));

type ProgressHandler = (event: { payload: DownloadProgressPayload }) => void;

function captureListener(): {
  handler: () => ProgressHandler | null;
  unlisten: ReturnType<typeof vi.fn>;
} {
  let captured: ProgressHandler | null = null;
  const unlisten = vi.fn();
  mocks.listen.mockImplementation(async (_name: string, cb: ProgressHandler) => {
    captured = cb;
    return unlisten;
  });
  return { handler: () => captured, unlisten };
}

function event(
  phase: DownloadProgressPayload['phase'],
  downloaded = 0,
  total: number | null = null,
  transferId = 't1',
): { payload: DownloadProgressPayload } {
  return { payload: { transferId, downloaded, total, phase } };
}

function fakeBook(overrides: Partial<CatalogBook> = {}): CatalogBook {
  return {
    id: 'gutendex:1342',
    provider: 'builtin:gutendex',
    title: 'Pride and Prejudice',
    authors: ['Jane Austen'],
    coverUrl: null,
    languages: ['en'],
    subjects: ['Fiction'],
    downloadUrl: 'https://www.gutenberg.org/ebooks/1342.epub3.images',
    description: 'A classic novel.',
    formats: { 'application/epub+zip': 'https://www.gutenberg.org/ebooks/1342.epub3.images' },
    isPublicDomain: true,
    ...overrides,
  };
}

function fakeProvider(book: CatalogBook): CatalogProvider {
  return {
    async search() {
      return { results: [], nexoPage: null, totalCount: 0 };
    },
    async getDetails(id: string) {
      if (id !== book.id) throw catalogError('NOT_FOUND', `unknown catalog id ${id}`);
      return book;
    },
    resolveDownloadUrl() {
      throw catalogError('UNAVAILABLE_DOWNLOAD', 'no usable url');
    },
    listSources() {
      return [];
    },
    async featured() {
      return { results: [], nexoPage: null, totalCount: 0 };
    },
    supportsFeatured() {
      return false;
    },
    async searchSource() {
      return { results: [], nexoPage: null, totalCount: 0 };
    },
  };
}

interface RecordingTransfer extends DownloadTransferPort {
  requests: DownloadTransferRequest[];
  cancelCalls: string[];
}

function recordingTransfer(
  impl: (
    req: DownloadTransferRequest,
    onProgress: (done: number, total: number | null) => void,
  ) => Promise<DownloadTransferResult>,
): RecordingTransfer {
  const requests: DownloadTransferRequest[] = [];
  const cancelCalls: string[] = [];
  return {
    requests,
    cancelCalls,
    async download(req, onProgress) {
      requests.push(req);
      return impl(req, onProgress);
    },
    async cancel(transferId) {
      cancelCalls.push(transferId);
    },
  };
}

function importedStub(): typeof importRecoveredBook {
  return (async (row: SupabaseUserBookRow) => ({
    bookId: row.id,
    outcome: 'imported' as const,
  })) as typeof importRecoveredBook;
}

beforeEach(() => {
  vi.resetAllMocks();
  mocks.listen.mockResolvedValue(() => undefined);
  mocks.cancelRemoteDownload.mockResolvedValue(true);
  mocks.discardRemoteDownload.mockResolvedValue(true);
});

describe('createTauriDownloadTransfer', () => {
  it('maps this transfer’s progress events and reports an unknown total', async () => {
    const { handler, unlisten } = captureListener();
    mocks.downloadRemoteBook.mockImplementation(async () => {
      handler()?.(event('progress', 10, 100));
      handler()?.(event('progress', 64, null));
      // Another transfer's progress is filtered out by transfer id.
      handler()?.(event('progress', 9, 9, 'other'));
      return { filePath: '/tmp/downloads/t1.epub', bytes: 64, sha256: 'sha' };
    });

    const seen: Array<[number, number | null]> = [];
    const transfer = createTauriDownloadTransfer();
    const result = await transfer.download(
      { transferId: 't1', url: 'https://example.org/b.epub', format: 'epub' },
      (done, total) => seen.push([done, total]),
    );

    expect(seen).toEqual([
      [10, 100],
      [64, null],
    ]);
    expect(result).toEqual({ filePath: '/tmp/downloads/t1.epub', bytes: 64 });
    expect(mocks.listen).toHaveBeenCalledWith('discover-download-progress', expect.any(Function));
    expect(mocks.downloadRemoteBook).toHaveBeenCalledWith({
      transferId: 't1',
      url: 'https://example.org/b.epub',
      format: 'epub',
    });
    expect(unlisten).toHaveBeenCalledTimes(1);
  });

  it('delivers exactly one terminal signal when terminal events repeat', async () => {
    const { handler, unlisten } = captureListener();
    let settles = 0;
    mocks.downloadRemoteBook.mockImplementation(async () => {
      handler()?.(event('completed', 32, 32));
      handler()?.(event('completed', 32, 32));
      handler()?.(event('failed', 32, 32));
      handler()?.(event('cancelled', 32, 32));
      return { filePath: '/tmp/downloads/t1.epub', bytes: 32, sha256: 'sha' };
    });

    const seen: Array<[number, number | null]> = [];
    const transfer = createTauriDownloadTransfer();
    const promise = transfer.download(
      { transferId: 't1', url: 'https://example.org/b.epub', format: 'epub' },
      (done, total) => seen.push([done, total]),
    );
    void promise.then(() => {
      settles += 1;
    });
    await promise;

    // A late duplicate event after the settle must not resurrect the transfer.
    handler()?.(event('progress', 99, 99));
    handler()?.(event('completed', 99, 99));

    expect(seen).toEqual([]);
    expect(settles).toBe(1);
    expect(unlisten).toHaveBeenCalledTimes(1);
  });

  it('maps a cancelled backend transfer to DownloadCancelledError', async () => {
    mocks.downloadRemoteBook.mockRejectedValue('BOOK_DOWNLOAD_CANCELLED');
    const transfer = createTauriDownloadTransfer();

    await expect(
      transfer.download(
        { transferId: 't1', url: 'https://example.org/b.epub', format: 'epub' },
        () => undefined,
      ),
    ).rejects.toBeInstanceOf(DownloadCancelledError);

    await transfer.cancel('t1');
    expect(mocks.cancelRemoteDownload).toHaveBeenCalledWith('t1');
    expect(isDownloadCancelled(new DownloadCancelledError('t1'))).toBe(true);
    expect(isDownloadCancelled('boom BOOK_DOWNLOAD_CANCELLED boom')).toBe(true);
    expect(isDownloadCancelled(new Error('nope'))).toBe(false);
  });

  it('surfaces the stable code and never the upstream body on failure', async () => {
    mocks.downloadRemoteBook.mockRejectedValue(
      'BOOK_DOWNLOAD_HTTP_503: <html>supposedly secret upstream body</html>',
    );
    const transfer = createTauriDownloadTransfer();

    await expect(
      transfer.download(
        { transferId: 't1', url: 'https://example.org/b.epub', format: 'epub' },
        () => undefined,
      ),
    ).rejects.toThrow('BOOK_DOWNLOAD_HTTP_503');
    await expect(
      transfer.download(
        { transferId: 't1', url: 'https://example.org/b.epub', format: 'epub' },
        () => undefined,
      ),
    ).rejects.not.toThrow(/supposedly secret/);

    expect(downloadErrorCode('BOOK_DOWNLOAD_NETWORK: url https://example.org')).toBe(
      'BOOK_DOWNLOAD_NETWORK',
    );
    expect(downloadErrorCode(new Error('plain failure'))).toBe('plain failure');
  });
});

describe('DiscoverDomainState download-to-import through the backend byte source', () => {
  it('passes downloading → importing → imported and reports progress', async () => {
    const book = fakeBook();
    const observed: DiscoverDownloadState[] = [];
    const importFn = vi.fn(async (row: SupabaseUserBookRow) => {
      observed.push(state.downloadState);
      return { bookId: row.id, outcome: 'imported' as const };
    });
    const transfer = recordingTransfer(async (req, onProgress) => {
      observed.push(state.downloadState);
      onProgress(5, null);
      // An unknown total is represented as null and is not an error.
      expect(state.progressTotal).toBeNull();
      expect(state.progressBytes).toBe(5);
      expect(state.downloadError).toBeNull();
      onProgress(10, 10);
      return { filePath: `/tmp/downloads/${req.transferId}.epub`, bytes: 10 };
    });
    const state = new DiscoverDomainState(fakeProvider(book), { transfer, importFn });
    await state.openDetail(book.id);

    await state.startDownload();

    expect(observed).toEqual(['downloading', 'importing']);
    expect(state.downloadState).toBe('imported');
    expect(state.downloadError).toBeNull();
    expect(state.progressBytes).toBe(10);
    expect(state.progressTotal).toBe(10);
    expect(importFn).toHaveBeenCalledOnce();
    expect(transfer.requests).toHaveLength(1);
    expect(transfer.requests[0]).toMatchObject({ url: book.downloadUrl, format: 'epub' });
    expect(transfer.requests[0].transferId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(mocks.discardRemoteDownload).toHaveBeenCalledWith(
      `/tmp/downloads/${transfer.requests[0].transferId}.epub`,
    );
  });

  it('cancel routes through the single backend cancel path and never imports', async () => {
    const book = fakeBook();
    const importFn = vi.fn(importedStub());
    let rejectDownload: (err: unknown) => void = () => undefined;
    const transfer = recordingTransfer(
      () =>
        new Promise<DownloadTransferResult>((_, reject) => {
          rejectDownload = reject;
        }),
    );
    const state = new DiscoverDomainState(fakeProvider(book), { transfer, importFn });
    await state.openDetail(book.id);

    const pending = state.startDownload();
    await tick();
    expect(state.downloadState).toBe('downloading');

    const transferId = transfer.requests[0].transferId;
    state.cancelDownload();
    expect(transfer.cancelCalls).toEqual([transferId]);
    expect(state.downloadState).toBe('downloading');

    // The backend command settles with its stable cancelled code.
    rejectDownload('BOOK_DOWNLOAD_CANCELLED');
    await pending;

    expect(state.downloadState).toBe('cancelled');
    expect(state.downloadError).toBeNull();
    expect(importFn).not.toHaveBeenCalled();
    expect(mocks.discardRemoteDownload).not.toHaveBeenCalled();
  });

  it('surfaces a transfer failure as the error state without importing', async () => {
    const book = fakeBook();
    const importFn = vi.fn(importedStub());
    const transfer = recordingTransfer(async () => {
      throw new Error('BOOK_DOWNLOAD_NETWORK');
    });
    const state = new DiscoverDomainState(fakeProvider(book), { transfer, importFn });
    await state.openDetail(book.id);

    await state.startDownload();

    expect(state.downloadState).toBe('error');
    expect(state.downloadError).toBe('BOOK_DOWNLOAD_NETWORK');
    expect(importFn).not.toHaveBeenCalled();
  });

  it('a failed import reports the error and discards the leftover temp file', async () => {
    const book = fakeBook();
    const importFn = vi.fn(async () => ({
      bookId: book.id,
      outcome: 'failed' as const,
      error: {
        code: 'UNAVAILABLE' as const,
        message: 'bad bytes',
        retryable: true,
        correlationId: 'c1',
        bookId: book.id,
      },
    }));
    const transfer = recordingTransfer(async () => ({
      filePath: '/tmp/downloads/t1.epub',
      bytes: 3,
    }));
    const state = new DiscoverDomainState(fakeProvider(book), { transfer, importFn });
    await state.openDetail(book.id);

    await state.startDownload();

    expect(state.downloadState).toBe('error');
    expect(state.downloadError).toBe('bad bytes');
    // F4: the failed import still cleans up, so retries cannot accumulate
    // one 812 KB temp file per attempt.
    expect(mocks.discardRemoteDownload).toHaveBeenCalledWith('/tmp/downloads/t1.epub');
  });

  it('a failing best-effort discard never changes the imported state', async () => {
    const book = fakeBook();
    mocks.discardRemoteDownload.mockRejectedValue(new Error('BOOK_DOWNLOAD_BAD_PATH'));
    const transfer = recordingTransfer(async () => ({
      filePath: '/tmp/downloads/t1.epub',
      bytes: 3,
    }));
    const state = new DiscoverDomainState(fakeProvider(book), {
      transfer,
      importFn: importedStub(),
    });
    await state.openDetail(book.id);

    await state.startDownload();

    expect(state.downloadState).toBe('imported');
    expect(state.downloadError).toBeNull();
    expect(mocks.discardRemoteDownload).toHaveBeenCalledWith('/tmp/downloads/t1.epub');
  });

  it('fails closed without any transfer when the book has no download URL', async () => {
    const book = fakeBook({ downloadUrl: null });
    const transfer = recordingTransfer(async () => ({
      filePath: '/tmp/downloads/t1.epub',
      bytes: 3,
    }));
    const state = new DiscoverDomainState(fakeProvider(book), {
      transfer,
      importFn: importedStub(),
    });
    await state.openDetail(book.id);

    await state.startDownload();

    expect(state.downloadState).toBe('error');
    expect(state.downloadError).toBe('UNAVAILABLE_DOWNLOAD');
    expect(transfer.requests).toHaveLength(0);
  });

  it('refreshes the library exactly once after a successful import', async () => {
    const book = fakeBook();
    const transfer = recordingTransfer(async (req) => ({
      filePath: `/tmp/downloads/${req.transferId}.epub`,
      bytes: 3,
    }));
    const refresh = vi.fn(async () => undefined);
    const state = new DiscoverDomainState(fakeProvider(book), {
      transfer,
      importFn: importedStub(),
    });
    state.onLibraryRefreshNeeded = refresh;
    await state.openDetail(book.id);

    await state.startDownload();

    expect(state.downloadState).toBe('imported');
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('does not refresh the library after a failed import', async () => {
    const book = fakeBook();
    const importFn = vi.fn(async () => ({
      bookId: book.id,
      outcome: 'failed' as const,
      error: {
        code: 'UNAVAILABLE' as const,
        message: 'bad bytes',
        retryable: true,
        correlationId: 'c1',
        bookId: book.id,
      },
    }));
    const transfer = recordingTransfer(async () => ({
      filePath: '/tmp/downloads/t1.epub',
      bytes: 3,
    }));
    const refresh = vi.fn(async () => undefined);
    const state = new DiscoverDomainState(fakeProvider(book), { transfer, importFn });
    state.onLibraryRefreshNeeded = refresh;
    await state.openDetail(book.id);

    await state.startDownload();

    expect(state.downloadState).toBe('error');
    expect(refresh).not.toHaveBeenCalled();
  });

  it('keeps the imported state when the library refresh rejects', async () => {
    const book = fakeBook();
    const transfer = recordingTransfer(async () => ({
      filePath: '/tmp/downloads/t1.epub',
      bytes: 3,
    }));
    const refresh = vi.fn(async () => {
      throw new Error('library reload failed');
    });
    const state = new DiscoverDomainState(fakeProvider(book), {
      transfer,
      importFn: importedStub(),
    });
    state.onLibraryRefreshNeeded = refresh;
    await state.openDetail(book.id);

    await state.startDownload();

    // A refresh that rejects is non-fatal: the download is still a success.
    expect(refresh).toHaveBeenCalledOnce();
    expect(state.downloadState).toBe('imported');
    expect(state.downloadError).toBeNull();
  });
});

describe('importDiscoverFile', () => {
  it('reads the local file and reuses importRecoveredBook end to end', async () => {
    const book = fakeBook();
    const bytes = new Uint8Array([1, 2, 3]);
    const readFile = vi.fn(async (filePath: string) => {
      expect(filePath).toBe('/tmp/downloads/t1.epub');
      return bytes;
    });
    const persist = vi.fn(
      async (
        bookId: string,
        fileBytes: Uint8Array,
        meta: { title: string; author: string; format: string },
      ) => {
        expect(bookId).toBe(book.id);
        expect(fileBytes).toBe(bytes);
        expect(meta).toEqual({ title: book.title, author: 'Jane Austen', format: 'epub' });
        return undefined;
      },
    );
    const markImported = vi.fn(async (bookId: string, version: number) => {
      expect(bookId).toBe(book.id);
      expect(version).toBe(2);
      return undefined;
    });

    const result = await importDiscoverFile(book, '/tmp/downloads/t1.epub', {
      readFile,
      persist,
      markImported,
    });

    expect(result).toEqual({ ok: true, error: null });
    expect(readFile).toHaveBeenCalledWith('/tmp/downloads/t1.epub');
    expect(persist).toHaveBeenCalledTimes(1);
    expect(markImported).toHaveBeenCalledTimes(1);
  });

  it('builds the unchanged synthetic import row', async () => {
    const book = fakeBook();
    const importFn = vi.fn(async (row: SupabaseUserBookRow) => ({
      bookId: row.id,
      outcome: 'imported' as const,
    }));

    await importDiscoverFile(book, '/tmp/x.epub', {
      importFn,
      readFile: async () => new Uint8Array([1]),
    });

    expect(importFn.mock.calls[0][0]).toMatchObject({
      id: book.id,
      title: book.title,
      author: 'Jane Austen',
      format: 'epub',
      filePath: null,
      lifecycle: 'available',
      catalogVersion: 1,
    });
  });

  it('maps a non-imported outcome to a retryable error result', async () => {
    const importFn = vi.fn(async () => ({
      bookId: 'gutendex:1342',
      outcome: 'failed' as const,
      error: {
        code: 'UNAVAILABLE' as const,
        message: 'bad bytes',
        retryable: true,
        correlationId: 'c1',
        bookId: 'gutendex:1342',
      },
    }));

    const result = await importDiscoverFile(fakeBook(), '/tmp/x.epub', {
      importFn,
      readFile: async () => new Uint8Array([1]),
    });

    expect(result).toEqual({ ok: false, error: 'bad bytes' });
  });
});

describe('Discover source guard', () => {
  it('keeps no webview fetch path in features/discover/*', () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const dir = resolve(here, '../../lib/features/discover');
    const sources = readdirSync(dir).filter((f) => f.endsWith('.ts') || f.endsWith('.svelte'));
    expect(sources.length).toBeGreaterThan(0);
    for (const file of sources) {
      const source = readFileSync(join(dir, file), 'utf8');
      expect(source, file).not.toContain('fetchBytesWithProgress');
      expect(source, file).not.toContain('fetchFn');
      expect(source, file).not.toMatch(/(^|[^.\w])fetch\s*\(/);
    }
  });
});
