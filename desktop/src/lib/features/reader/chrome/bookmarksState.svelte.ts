import { authState } from '$lib/shared/stores/AuthState.svelte';
import { SyncOutboxDao } from '$lib/shared/outbox/SyncOutboxDao';
import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
import { handleError } from '$lib/shared/utils/errors';
import { captureBreadcrumb } from '$lib/shared/logger/BreadcrumbsStore';
import { BREADCRUMB_LABELS } from '$lib/shared/logger/breadcrumbTypes';

const defaultOutboxDao = new SyncOutboxDao();
const defaultViewerPort: ViewerPort = new TauriViewerAdapter();

export type BookmarkItem = {
  id: string;
  bookId: string;
  pageNumber: number;
  title?: string;
  createdAt: string;
};

export function createBookmarksState(
  deps: { outboxDao?: SyncOutboxDao; viewerPort?: ViewerPort } = {},
): {
  readonly bookmarksList: BookmarkItem[];
  readonly bookmarksLoading: boolean;
  loadBookmarks(bookId: string): Promise<void>;
  addBookmark(
    bookId: string,
    pageNumber: number,
    location?: { cfiLocation?: string | null; locatorJson?: string | null },
  ): Promise<void>;
  removeBookmark(id: string, bookId: string): Promise<void>;
} {
  const outboxDao = deps.outboxDao ?? defaultOutboxDao;
  const viewerPort = deps.viewerPort ?? defaultViewerPort;
  let bookmarksList = $state<BookmarkItem[]>([]);
  let bookmarksLoading = $state(false);
  // Supersede marker for concurrent loads. The guard lives here, at the write,
  // not in the caller: guarding on the read side would leave a stale
  // `bookmarksList = rows` intact, which is exactly the count-follows-book bug.
  let requestedBookId: string | null = null;

  async function loadBookmarks(bookId: string): Promise<void> {
    requestedBookId = bookId;
    bookmarksLoading = true;
    try {
      const rows = await viewerPort.listBookmarks(bookId);
      if (requestedBookId !== bookId) return; // a newer load superseded this one
      bookmarksList = rows;
    } catch (err) {
      if (requestedBookId !== bookId) return;
      handleError(err, 'reader', {
        bookId,
        action: 'load_bookmarks',
      });
      bookmarksList = [];
    } finally {
      if (requestedBookId === bookId) bookmarksLoading = false;
    }
  }

  async function addBookmark(
    bookId: string,
    pageNumber: number,
    location?: { cfiLocation?: string | null; locatorJson?: string | null },
  ): Promise<void> {
    const id = crypto.randomUUID();
    const createdAt = new Date().toISOString();
    captureBreadcrumb('action', BREADCRUMB_LABELS.BOOKMARK_ADD, { bookId, bookmarkId: id });
    try {
      await viewerPort.saveBookmark({
        id,
        bookId,
        pageNumber,
        title: `Page ${pageNumber}`,
        createdAt,
      });
      if (authState.userId) {
        void outboxDao.add(
          'BOOKMARK',
          id,
          'UPSERT',
          JSON.stringify({
            userId: authState.userId,
            bookId,
            cfiLocation: location?.cfiLocation ?? `page:${pageNumber}`,
            locatorJson: location?.locatorJson ?? null,
            titleSnippet: `Page ${pageNumber}`,
            updatedAt: createdAt,
          }),
        );
      }
      await loadBookmarks(bookId);
    } catch (err) {
      handleError(err, 'reader', {
        bookId,
        pageNumber,
        action: 'save_bookmark',
      });
    }
  }

  async function removeBookmark(id: string, bookId: string): Promise<void> {
    const bookmark = bookmarksList.find((item) => item.id === id);
    captureBreadcrumb('action', BREADCRUMB_LABELS.BOOKMARK_REMOVE, { bookId, bookmarkId: id });
    try {
      await viewerPort.deleteBookmark(id);
      if (authState.userId && bookmark) {
        const updatedAt = new Date().toISOString();
        void outboxDao.add(
          'BOOKMARK',
          id,
          'DELETE',
          JSON.stringify({
            userId: authState.userId,
            bookId,
            cfiLocation: `page:${bookmark.pageNumber}`,
            titleSnippet: bookmark.title ?? null,
            deletedAt: updatedAt,
            updatedAt,
          }),
        );
      }
      await loadBookmarks(bookId);
    } catch (err) {
      handleError(err, 'reader', {
        bookId,
        bookmarkId: id,
        action: 'delete_bookmark',
      });
    }
  }

  return {
    get bookmarksList() {
      return bookmarksList;
    },
    get bookmarksLoading() {
      return bookmarksLoading;
    },
    loadBookmarks,
    addBookmark,
    removeBookmark,
  };
}
