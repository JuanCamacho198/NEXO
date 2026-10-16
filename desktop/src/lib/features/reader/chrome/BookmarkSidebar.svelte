<script lang="ts">
  import { createFocusTrap } from '$lib/shared/utils/focusTrap';
  import type { BookmarksPanel } from './useBookmarksPanel.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { LibraryBookDto } from '$lib/shared/types/library';
  import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';

  type ActiveBook = LibraryBookDto & { filePath: string };

  type Props = {
    bookmarksPanel: BookmarksPanel;
    activeReadingBook: ActiveBook | null;
    currentPage: number;
    currentChapter: number;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { bookmarksPanel, activeReadingBook, currentPage, currentChapter, t }: Props = $props();

  const epubActive = $derived(
    activeReadingBook?.format ? String(activeReadingBook.format).toLowerCase() === 'epub' : false,
  );

  // Focus trap when open
  $effect(() => {
    if (bookmarksPanel.showBookmarks && bookmarksPanel.bookmarksPanelEl) {
      const trap = createFocusTrap(bookmarksPanel.bookmarksPanelEl as HTMLElement);
      trap.activate();
      return () => trap.deactivate();
    }
  });
</script>

{#if bookmarksPanel.showBookmarks && activeReadingBook}
  <div
    class="fixed inset-0 z-40"
    onclick={(e) => {
      if (e.target === e.currentTarget) bookmarksPanel.showBookmarks = false;
    }}
    onkeydown={(e) => e.key === 'Escape' && (bookmarksPanel.showBookmarks = false)}
    role="presentation"
  >
    <div class="absolute inset-0 bg-(--color-surface)/70"></div>
    <div
      bind:this={bookmarksPanel.bookmarksPanelEl}
      class="absolute right-0 top-0 flex h-full w-65 flex-col border-l border-(--color-border-deep) bg-(--color-surface)/70 pt-15 text-(--color-text-muted) backdrop-blur-sm"
      onkeydown={(e) => e.key === 'Escape' && (bookmarksPanel.showBookmarks = false)}
      role="dialog"
      aria-label={t('reader.bookmark')}
      tabindex="0"
    >
      <!-- Header -->
      <div class="flex items-center justify-between border-b border-(--color-border)/5 px-5 py-4">
        <h2 class="text-base font-bold text-(--color-primary)">{t('reader.bookmark')}</h2>
        <div class="flex items-center gap-2">
          <button
            type="button"
            onclick={() => {
              void bookmarksPanel.bookmarksState.addBookmark(
                activeReadingBook.id,
                epubActive ? currentChapter + 1 : currentPage || 1,
                { cfiLocation: readerState.cfiLocation, locatorJson: readerState.locatorJson },
              );
              bookmarksPanel.triggerBookmarkRibbon();
            }}
            class="flex h-6 w-6 cursor-pointer items-center justify-center rounded-md bg-(--color-accent-blue) text-xs font-bold text-(--color-bg-deep) transition-colors hover:bg-(--color-accent-sky)"
            title={t('reader.bookmark')}
          >
            +
          </button>
          <button
            type="button"
            onclick={() => (bookmarksPanel.showBookmarks = false)}
            class="cursor-pointer text-(--color-text-muted) hover:text-(--color-text-inverse)"
            aria-label={t('settings.close')}
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
      </div>

      <!-- Content -->
      <div class="flex-1 overflow-y-auto p-4">
        {#if bookmarksPanel.bookmarksState.bookmarksLoading}
          <p class="text-center text-sm italic text-(--color-text-muted)/60">
            {t('settings.loadingBookmarks')}
          </p>
        {:else if bookmarksPanel.bookmarksState.bookmarksList.length === 0}
          <p class="text-center text-sm italic text-(--color-text-muted)/60">
            {t('settings.noBookmarks')}
          </p>
        {:else}
          <ul class="flex flex-col gap-2">
            {#each bookmarksPanel.bookmarksState.bookmarksList as bookmark (bookmark.id)}
              <li
                class="flex items-center gap-2 rounded-lg border border-(--color-border-deep) bg-(--color-text-inverse)/2 px-3 py-2 transition-colors hover:bg-(--color-text-inverse)/5"
              >
                <button
                  type="button"
                  class="flex flex-1 flex-col items-start gap-0.5 text-left"
                  onclick={() => {
                    bookmarksPanel.showBookmarks = false;
                  }}
                >
                  <span class="text-sm font-medium text-(--color-primary)"
                    >Page {bookmark.pageNumber}</span
                  >
                  {#if bookmark.title}
                    <span class="text-xs text-(--color-text-muted)/60">{bookmark.title}</span>
                  {/if}
                </button>
                <button
                  type="button"
                  onclick={() =>
                    bookmarksPanel.bookmarksState.removeBookmark(bookmark.id, activeReadingBook.id)}
                  class="flex h-6 w-6 cursor-pointer items-center justify-center rounded text-sm text-(--color-text-muted) transition-colors hover:bg-red-500/20 hover:text-red-400"
                  title={t('settings.deleteBookmark')}
                >
                  <svg
                    xmlns="http://www.w3.org/2000/svg"
                    width="12"
                    height="12"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  >
                    <polyline points="3 6 5 6 21 6"></polyline>
                    <path
                      d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"
                    ></path>
                  </svg>
                </button>
              </li>
            {/each}
          </ul>
        {/if}
      </div>
    </div>
  </div>
{/if}

{#if bookmarksPanel.showBookmarkRibbon}
  <div
    class="pointer-events-none fixed top-20 right-8 z-50 animate-[bookmarkRibbon_2200ms_ease-out] flex items-center gap-2 rounded-xl border border-amber-300 bg-amber-50 px-4 py-2 text-sm font-medium text-amber-900 shadow-lg"
  >
    <span class="text-amber-600">🔖</span>
    {t('reader.bookmarkAdded')}
  </div>
{/if}

<style>
  @keyframes bookmarkRibbon {
    0% {
      opacity: 0;
      transform: translateY(-12px) scale(0.9);
    }
    15% {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
    85% {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
    100% {
      opacity: 0;
      transform: translateY(-12px) scale(0.9);
    }
  }
</style>
