<script lang="ts">
  import type { ViewerPort } from '$lib/shared/ports/ViewerPort';
  import { TauriViewerAdapter } from '$lib/shared/ports/adapters/tauri/TauriViewerAdapter';
  import { handleError } from '$lib/shared/utils/errors';

  type BookmarksPanelProps = {
    bookId: string;
    onNavigate?: (pageNumber: number) => void;
    viewerPort?: ViewerPort;
  };

  type Props = BookmarksPanelProps;

  let { bookId, onNavigate, viewerPort: viewerPortProp }: Props = $props();
  const viewerPort = $derived(viewerPortProp ?? new TauriViewerAdapter());

  let bookmarks: Array<{
    id: string;
    bookId: string;
    pageNumber: number;
    title?: string;
    createdAt: string;
  }> = $state([]);
  let isLoading = $state(true);

  $effect(() => {
    if (bookId) {
      loadBookmarks();
    }
  });

  async function loadBookmarks(): Promise<void> {
    isLoading = true;
    try {
      bookmarks = await viewerPort.listBookmarks(bookId);
    } catch (err) {
      handleError(err, 'reader', {
        bookId,
        action: 'load_bookmarks',
      });
      bookmarks = [];
    } finally {
      isLoading = false;
    }
  }

  async function handleAddBookmark(): Promise<void> {
    if (!bookId) return;

    const pageNumber = 1;
    try {
      await viewerPort.saveBookmark({
        id: crypto.randomUUID(),
        bookId,
        pageNumber,
        title: `Page ${pageNumber}`,
        createdAt: new Date().toISOString(),
      });
      await loadBookmarks();
    } catch (err) {
      handleError(err, 'reader', {
        bookId,
        pageNumber,
        action: 'save_bookmark',
      });
    }
  }

  async function handleDeleteBookmark(id: string): Promise<void> {
    try {
      await viewerPort.deleteBookmark(id);
      await loadBookmarks();
    } catch (err) {
      handleError(err, 'reader', {
        bookId,
        bookmarkId: id,
        action: 'delete_bookmark',
      });
    }
  }

  function handleNavigate(pageNumber: number): void {
    onNavigate?.(pageNumber);
  }
</script>

<section class="flex flex-col h-full">
  <header class="flex items-center justify-between px-3 py-2 border-b border-zinc-200">
    <h3 class="m-0 text-sm font-semibold text-zinc-700">Bookmarks</h3>
    <button
      type="button"
      class="w-6 h-6 border-none rounded bg-zinc-700 text-white cursor-pointer text-base flex items-center justify-center hover:not-disabled:bg-zinc-800 disabled:opacity-50 disabled:cursor-not-allowed"
      onclick={handleAddBookmark}
      disabled={!bookId}
      title="Add bookmark for current page"
    >
      +
    </button>
  </header>

  {#if isLoading}
    <div class="p-6 text-center text-xs text-zinc-500">Loading bookmarks...</div>
  {:else if bookmarks.length === 0}
    <div class="p-6 text-center text-xs text-zinc-500">No bookmarks yet</div>
  {:else}
    <ul class="list-none m-0 p-2">
      {#each bookmarks as bookmark}
        <li class="flex items-center p-1 rounded mb-1 hover:bg-zinc-50">
          <button
            type="button"
            class="flex-1 flex flex-col items-start px-2 py-1 border-none bg-transparent cursor-pointer text-left"
            onclick={() => handleNavigate(bookmark.pageNumber)}
          >
            <span class="text-xs font-medium text-zinc-700">Page {bookmark.pageNumber}</span>
            {#if bookmark.title}
              <span class="text-micro text-zinc-500">{bookmark.title}</span>
            {/if}
          </button>
          <button
            type="button"
            class="w-5 h-5 border-none rounded bg-transparent text-zinc-400 cursor-pointer text-base flex items-center justify-center hover:bg-red-100 hover:text-red-600"
            onclick={() => handleDeleteBookmark(bookmark.id)}
            title="Delete bookmark"
          >
            ×
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</section>
