import { describe, expect, it, vi } from 'vitest';
import {
  createBookmarksState,
  type BookmarkItem,
} from '$lib/features/reader/chrome/bookmarksState.svelte';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';

vi.mock('$lib/shared/stores/AuthState.svelte', () => ({
  authState: { userId: 'user-1' },
}));

vi.mock('$lib/shared/outbox/SyncOutboxDao', () => ({
  SyncOutboxDao: class {
    add = vi.fn().mockResolvedValue('id');
  },
}));

const bookmark = (bookId: string, id: string): BookmarkItem => ({
  id,
  bookId,
  pageNumber: 1,
  createdAt: new Date().toISOString(),
});

/** A deferred whose settle function is captured when the load starts. */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (err: unknown) => void;
} {
  const box: { resolve?: (v: T) => void; reject?: (e: unknown) => void } = {};
  const promise = new Promise<T>((resolve, reject) => {
    box.resolve = resolve;
    box.reject = reject;
  });
  return {
    promise,
    resolve: (value: T) => box.resolve?.(value),
    reject: (err: unknown) => box.reject?.(err),
  };
}

function makePort(listBookmarks: ViewerPort['listBookmarks']): ViewerPort {
  return {
    listBookmarks,
    saveBookmark: vi.fn().mockResolvedValue(undefined),
    deleteBookmark: vi.fn().mockResolvedValue(undefined),
  } as unknown as ViewerPort;
}

describe('bookmarksState supersede guard (ADR-005, U3.13)', () => {
  it('keeps the newer rows when an older load resolves last', async () => {
    const oldRequest = deferred<BookmarkItem[]>();
    const newRequest = deferred<BookmarkItem[]>();

    const listBookmarks = vi.fn((bookId: string) =>
      bookId === 'book-old' ? oldRequest.promise : newRequest.promise,
    );

    const state = createBookmarksState({ viewerPort: makePort(listBookmarks) });

    // Start the load for the old book, then switch books before it resolves.
    const older = state.loadBookmarks('book-old');
    const newer = state.loadBookmarks('book-new');

    // The newer request wins even though the older one settles afterwards.
    newRequest.resolve([bookmark('book-new', 'new-1')]);
    await newer;
    oldRequest.resolve([bookmark('book-old', 'old-1')]);
    await older;

    expect(state.bookmarksList.map((b) => b.id)).toEqual(['new-1']);
    expect(state.bookmarksLoading).toBe(false);
  });

  it('does not clear the list when a superseded load rejects', async () => {
    const oldRequest = deferred<BookmarkItem[]>();

    const listBookmarks = vi.fn((bookId: string) =>
      bookId === 'book-old' ? oldRequest.promise : Promise.resolve([bookmark(bookId, 'new-1')]),
    );

    const state = createBookmarksState({ viewerPort: makePort(listBookmarks) });

    const older = state.loadBookmarks('book-old');
    await state.loadBookmarks('book-new');
    oldRequest.reject(new Error('stale request failed'));
    await older;

    expect(state.bookmarksList.map((b) => b.id)).toEqual(['new-1']);
    expect(state.bookmarksLoading).toBe(false);
  });
});
