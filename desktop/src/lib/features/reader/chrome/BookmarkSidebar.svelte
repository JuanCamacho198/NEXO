<script lang="ts">
  import Bookmark from 'lucide-svelte/icons/bookmark';
  import Plus from 'lucide-svelte/icons/plus';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import { createFocusTrap } from '$lib/shared/utils/focusTrap';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import ReaderDrawerHeader from './ReaderDrawerHeader.svelte';
  import { READER_DRAWER, READER_DRAWER_SCRIM } from './readerDrawer';
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
    <div class={READER_DRAWER_SCRIM}></div>
    <div
      bind:this={bookmarksPanel.bookmarksPanelEl}
      class={READER_DRAWER}
      onkeydown={(e) => e.key === 'Escape' && (bookmarksPanel.showBookmarks = false)}
      role="dialog"
      aria-label={t('reader.bookmark')}
      tabindex="0"
    >
      <ReaderDrawerHeader
        title={t('reader.bookmark')}
        closeLabel={t('settings.close')}
        onClose={() => (bookmarksPanel.showBookmarks = false)}
      >
        {#snippet actions()}
          <Button
            variant="accent"
            size="sm"
            class="size-9 shrink-0 p-0"
            onclick={() => {
              void bookmarksPanel.bookmarksState.addBookmark(
                activeReadingBook.id,
                epubActive ? currentChapter + 1 : currentPage || 1,
                { cfiLocation: readerState.cfiLocation, locatorJson: readerState.locatorJson },
              );
              bookmarksPanel.triggerBookmarkRibbon();
            }}
            title={t('reader.bookmark')}
            aria-label={t('reader.bookmark')}
          >
            <Plus size={16} strokeWidth={2.5} aria-hidden="true" />
          </Button>
        {/snippet}
      </ReaderDrawerHeader>

      <!-- Content -->
      <div class="flex-1 p-3">
        {#if bookmarksPanel.bookmarksState.bookmarksLoading}
          <p class="py-10 text-center text-sm text-(--color-text-muted)">
            {t('settings.loadingBookmarks')}
          </p>
        {:else if bookmarksPanel.bookmarksState.bookmarksList.length === 0}
          <div class="flex flex-col items-center gap-3 px-4 py-12 text-center">
            <span
              class="flex size-12 items-center justify-center rounded-full bg-(--color-brand-soft) text-(--color-brand-text)"
              aria-hidden="true"
            >
              <Bookmark size={20} strokeWidth={2} />
            </span>
            <p class="m-0 text-sm text-(--color-text-muted)">{t('settings.noBookmarks')}</p>
          </div>
        {:else}
          <ul class="flex flex-col gap-0.5">
            {#each bookmarksPanel.bookmarksState.bookmarksList as bookmark (bookmark.id)}
              <li
                class="group flex items-center gap-1 rounded-(--radius-md) transition-colors hover:bg-(--color-surface-hover)"
              >
                <button
                  type="button"
                  class="flex min-w-0 flex-1 cursor-pointer items-center gap-3 py-2.5 pl-3 text-left"
                  onclick={() => {
                    bookmarksPanel.showBookmarks = false;
                  }}
                >
                  <Bookmark
                    size={16}
                    strokeWidth={2}
                    class="shrink-0 fill-(--color-brand-soft) text-(--color-brand-text)"
                    aria-hidden="true"
                  />
                  <span class="flex min-w-0 flex-col gap-0.5">
                    <span class="text-sm font-semibold text-(--color-primary)"
                      >Page {bookmark.pageNumber}</span
                    >
                    {#if bookmark.title}
                      <span class="truncate text-xs text-(--color-text-muted)"
                        >{bookmark.title}</span
                      >
                    {/if}
                  </span>
                </button>
                <button
                  type="button"
                  onclick={() =>
                    bookmarksPanel.bookmarksState.removeBookmark(bookmark.id, activeReadingBook.id)}
                  class="mr-1 flex size-9 shrink-0 cursor-pointer items-center justify-center rounded-full text-(--color-text-muted) opacity-60 transition group-hover:opacity-100 hover:bg-(--color-danger)/12 hover:text-(--color-danger) focus-visible:opacity-100"
                  title={t('settings.deleteBookmark')}
                  aria-label={t('settings.deleteBookmark')}
                >
                  <Trash2 size={14} strokeWidth={2} aria-hidden="true" />
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
    class="pointer-events-none fixed inset-x-0 bottom-20 z-50 mx-auto flex w-fit animate-[bookmarkRibbon_2200ms_ease-out] items-center gap-2.5 rounded-full border border-(--color-edge) bg-(--color-reader-chrome) py-2 pr-4 pl-2 text-sm font-medium text-(--color-primary) shadow-[0_18px_40px_-16px_rgba(0,0,0,0.55)]"
  >
    <span
      class="flex size-7 items-center justify-center rounded-full bg-linear-to-br from-(--color-brand-start) to-(--color-brand-end) text-(--color-brand-on)"
      aria-hidden="true"
    >
      <Bookmark size={14} strokeWidth={2.5} />
    </span>
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
