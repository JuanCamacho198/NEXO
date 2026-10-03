/**
 * WU1 regression: BulkImportDomainState handler binding + error banner.
 *
 * FR-BI1: every BulkImportDomainState method passed as a callback prop MUST be
 * an arrow closure preserving the store receiver. Bare references throw
 * TypeError when invoked detached.
 * FR-BI2: error notice auto-dismisses after 3500ms, dedups identical
 * (file+message) errors, keeps manual dismiss; success behavior unchanged.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { BulkImportDomainState } from '$lib/shared/stores/BulkImportDomainState.svelte';
import { pickFile, pickFolder } from '$lib/shared/services/FilePicker';
import { importBook } from '$lib/shared/services/BookImportService';

vi.mock('$lib/shared/services/FilePicker', () => ({
  pickFile: vi.fn(),
  pickFolder: vi.fn(),
}));
vi.mock('$lib/shared/services/BookImportService', () => ({
  importBook: vi.fn(),
}));
vi.mock('$lib/shared/services/pdfThumbnail', () => ({
  extractPdfMetadata: vi.fn(async () => ({})),
}));
vi.mock('$lib/shared/services/epubImportMetadata', () => ({
  extractEpubImportMetadata: vi.fn(async () => ({})),
}));
vi.mock('$lib/shared/services/genreHeuristic', () => ({
  inferGenreFromText: vi.fn(() => 'Fiction'),
}));

const pickFileMock = vi.mocked(pickFile);
const pickFolderMock = vi.mocked(pickFolder);
const importBookMock = vi.mocked(importBook);

// Resolved from the desktop package root (vitest cwd), not import.meta.url:
// vite-node serves test modules under a non-file scheme, so URL-based reads
// throw "The URL must be of scheme file".
const ROUTER_PATH = resolve(process.cwd(), 'src/lib/shared/ui/layout/AppRouter.svelte');
const MODALS_PATH = resolve(process.cwd(), 'src/lib/shared/ui/layout/AppModals.svelte');

function freshState(): BulkImportDomainState {
  return new BulkImportDomainState({
    libraryPort: {
      scanFolder: vi.fn(async () => ({ files: [], skipped: [], errors: [] })),
    } as never,
  });
}

function mockPickedFile(): void {
  pickFileMock.mockResolvedValue({ name: 'book.pdf', path: '/tmp/book.pdf' });
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('FR-BI1 — call sites bind the store receiver', () => {
  it('no bare bulkImportState method reference is passed as a callback', () => {
    const sources = [readFileSync(ROUTER_PATH, 'utf8'), readFileSync(MODALS_PATH, 'utf8')];
    const bare =
      /\{(bulkImportState\.(handleImportFile|closeBulkImportModal|handleScanBulkImportFolder|handleStartBulkImport|handleCancelBulkImport))\}/;
    for (const src of sources) {
      expect(src).not.toMatch(bare);
    }
  });

  it('AppRouter wraps the single-file import entry point in a closure', () => {
    expect(readFileSync(ROUTER_PATH, 'utf8')).toContain(
      'onImportBook={() => bulkImportState.handleImportFile()}',
    );
  });

  it('AppModals wraps all six modal handlers in closures', () => {
    const src = readFileSync(MODALS_PATH, 'utf8');
    for (const snippet of [
      'onClose={() => bulkImportState.closeBulkImportModal()}',
      'bulkImportState.handlePickBulkImportFolder(',
      'onScan={() => bulkImportState.handleScanBulkImportFolder()}',
      'onStartImport={() => bulkImportState.handleStartBulkImport()}',
      'onCancelImport={() => bulkImportState.handleCancelBulkImport()}',
    ]) {
      expect(src).toContain(snippet);
    }
  });

  it('bare references throw detached while closures keep the receiver', () => {
    const state = freshState();
    state.isBulkImportOpen = true;
    const bare = state.closeBulkImportModal;
    expect(() => (bare as () => void)()).toThrow(TypeError);
    const bound = () => state.closeBulkImportModal();
    expect(() => bound()).not.toThrow();
    expect(state.isBulkImportOpen).toBe(false);
  });

  it('detached single-file import closure runs without a receiver TypeError', async () => {
    const state = freshState();
    mockPickedFile();
    importBookMock.mockResolvedValue(undefined as never);
    const onImportBook = () => state.handleImportFile();
    await expect(onImportBook()).resolves.toBeUndefined();
    expect(state.importNotice?.status).toBe('success');
  });

  it('detached modal closures keep the receiver', async () => {
    const state = freshState();
    state.isBulkImportOpen = true;
    const onClose = () => state.closeBulkImportModal();
    const onCancelImport = () => state.handleCancelBulkImport();
    pickFolderMock.mockResolvedValue({ path: '/tmp/books', name: 'books' });
    const onPickFolder = () => state.handlePickBulkImportFolder('title');
    expect(() => onClose()).not.toThrow();
    expect(() => onCancelImport()).not.toThrow();
    await expect(onPickFolder()).resolves.toBeUndefined();
    expect(state.isBulkImportOpen).toBe(false);
    expect(state.bulkImportFolderPath).toBe('/tmp/books');
  });
});

describe('FR-BI2 — error banner auto-dismiss and dedup', () => {
  async function failImport(state: BulkImportDomainState, message: string): Promise<void> {
    mockPickedFile();
    importBookMock.mockRejectedValue(new Error(message));
    await expect(state.handleImportFile()).rejects.toThrow(message);
  }

  it('error notice auto-dismisses after 3500ms', async () => {
    const state = freshState();
    await failImport(state, 'boom');
    expect(state.importNotice?.status).toBe('error');
    vi.advanceTimersByTime(3499);
    expect(state.importNotice?.status).toBe('error');
    vi.advanceTimersByTime(1);
    expect(state.importNotice).toBeNull();
  });

  it('identical errors dedup without resetting the timer', async () => {
    const state = freshState();
    const setTimeoutSpy = vi.spyOn(globalThis, 'setTimeout');
    const clearTimeoutSpy = vi.spyOn(globalThis, 'clearTimeout');
    mockPickedFile();
    importBookMock.mockImplementation(async (_req, onProgress) => {
      onProgress?.({ status: 'error', message: 'boom', percentage: 0 });
      throw new Error('boom');
    });
    await expect(state.handleImportFile()).rejects.toThrow('boom');
    expect(state.importNotice?.status).toBe('error');
    // One timer from the first report; the identical re-report in the catch
    // deduped instead of clearing + re-arming it.
    const errorTimers = setTimeoutSpy.mock.calls.filter((args) => args[1] === 3500);
    expect(errorTimers).toHaveLength(1);
    expect(clearTimeoutSpy).not.toHaveBeenCalled();
    vi.advanceTimersByTime(3500);
    expect(state.importNotice).toBeNull();
    setTimeoutSpy.mockRestore();
    clearTimeoutSpy.mockRestore();
  });

  it('distinct errors replace the visible notice', async () => {
    const state = freshState();
    await failImport(state, 'boom');
    await failImport(state, 'different');
    expect(state.importNotice?.message).toBe('different');
  });

  it('manual dismiss still clears the error', async () => {
    const state = freshState();
    await failImport(state, 'boom');
    state.dismissImportNotice();
    expect(state.importNotice).toBeNull();
    vi.advanceTimersByTime(10_000);
    expect(state.importNotice).toBeNull();
  });

  it('success behavior is unchanged (auto-dismiss at 3500ms)', async () => {
    const state = freshState();
    mockPickedFile();
    importBookMock.mockResolvedValue(undefined as never);
    await state.handleImportFile();
    expect(state.importNotice?.status).toBe('success');
    vi.advanceTimersByTime(3499);
    expect(state.importNotice?.status).toBe('success');
    vi.advanceTimersByTime(1);
    expect(state.importNotice).toBeNull();
  });
});
