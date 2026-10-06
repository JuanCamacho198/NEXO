import { describe, expect, it, vi } from 'vitest';

/**
 * Covers the DF-01 fix in the dev-only browser shim: a book promoted to
 * "reading" through `setReadingStatus` must be reported back by the library
 * row builder (and by the status-derived stats) on the next read, instead of
 * diverging silently from the real Tauri backend.
 */

type TauriShimInternals = {
  invoke: (cmd: string, args?: Record<string, unknown>) => Promise<unknown>;
};

type LibraryRow = { id: string; readingStatus: string };
type ReadingStats = { booksStarted: number; booksCompleted: number };

/**
 * The shim installs itself once on import and only when no bridge is present.
 * Reset both so each test exercises a fresh module instance and a fresh
 * override map.
 */
async function loadShim(): Promise<TauriShimInternals> {
  delete (window as unknown as Record<string, unknown>).__TAURI_INTERNALS__;
  vi.resetModules();
  await import('$lib/dev/browserTauriShim');
  const internals = (window as unknown as Record<string, unknown>).__TAURI_INTERNALS__ as
    TauriShimInternals | undefined;
  if (!internals) throw new Error('browser Tauri shim did not install');
  return internals;
}

function findRow(rows: unknown, id: string): LibraryRow | undefined {
  return (rows as LibraryRow[]).find((row) => row.id === id);
}

describe('browserTauriShim setReadingStatus', () => {
  it('reports a promoted status back from the row builder', async () => {
    const internals = await loadShim();

    expect(
      findRow(await internals.invoke('listLibraryBooks', { page: 1 }), 'shim-book-3'),
    ).toMatchObject({ readingStatus: 'to_read' });
    expect((await internals.invoke('getReadingStats')) as ReadingStats).toMatchObject({
      booksStarted: 2,
      booksCompleted: 1,
    });

    await internals.invoke('setReadingStatus', { bookId: 'shim-book-3', status: 'reading' });

    expect(
      findRow(await internals.invoke('listLibraryBooks', { page: 1 }), 'shim-book-3'),
    ).toMatchObject({ readingStatus: 'reading' });
    expect((await internals.invoke('getReadingStats')) as ReadingStats).toMatchObject({
      booksStarted: 3,
    });
  });

  it('clears the override when the status returns to null', async () => {
    const internals = await loadShim();

    await internals.invoke('setReadingStatus', { bookId: 'shim-book-1', status: 'to_read' });
    expect(findRow(await internals.invoke('listLibraryBooks'), 'shim-book-1')).toMatchObject({
      readingStatus: 'to_read',
    });

    await internals.invoke('setReadingStatus', { bookId: 'shim-book-1', status: null });
    expect(findRow(await internals.invoke('listLibraryBooks'), 'shim-book-1')).toMatchObject({
      readingStatus: 'reading',
    });
  });
});
