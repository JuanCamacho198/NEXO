<script lang="ts">
  import { appState } from '$lib/shared/stores/AppState.svelte';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { BookCard, ShelfActionMenu } from '$lib/features/library';
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import ChevronRight from 'lucide-svelte/icons/chevron-right';

  let scrollEl = $state<HTMLUListElement | null>(null);
  let activeIndex = $state(0);
  let reducedMotion = $state(false);

  const books = $derived(libraryState.continueReadingBooks);
  const activeBook = $derived(books[activeIndex] ?? null);
  const showArrows = $derived(books.length > 1);

  // React to the user's reduced-motion preference; it only decides whether the
  // carousel scrolls smoothly or jumps.
  $effect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const apply = (): void => {
      reducedMotion = mq.matches;
    };
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  });

  // Scroll to the active card and clamp an out-of-range index when the
  // list shrinks (e.g. a book leaves the continue-reading partition).
  $effect(() => {
    const n = books.length;
    if (n === 0) return;
    if (activeIndex >= n) {
      activeIndex = n - 1;
      return;
    }
    const li = scrollEl?.children[activeIndex] as HTMLElement | undefined;
    if (!li) return;
    scrollEl?.scrollTo({ left: li.offsetLeft, behavior: reducedMotion ? 'auto' : 'smooth' });
  });

  // Re-sync the active index after a manual scroll snaps to a card.
  function onScrollEnd(): void {
    if (!scrollEl) return;
    const page = Math.round(scrollEl.scrollLeft / scrollEl.clientWidth);
    if (page >= 0 && page < books.length) activeIndex = page;
  }

  function handlePrev(): void {
    const n = books.length;
    if (!n) return;
    activeIndex = (activeIndex - 1 + n) % n;
  }

  function handleNext(): void {
    const n = books.length;
    if (!n) return;
    activeIndex = (activeIndex + 1) % n;
  }
</script>

{#if books.length === 0}
  <p class="text-sm text-(--color-text-muted)">{appState.t('home.continueReadingPlaceholder')}</p>
{:else}
  <div data-testid="continue-carousel" role="group" class="space-y-3">
    <div class="flex items-center justify-between gap-3">
      <div class="flex min-w-0 items-center gap-2">
        <h3 class="text-base font-semibold tracking-tight text-(--color-primary)">
          {appState.t('home.continueReading')}
        </h3>
        <span
          class="rounded-full bg-(--color-accent-soft) px-2 py-0.5 text-xs font-medium text-(--color-accent)"
          aria-label={appState.t('home.continue.countAria', { count: books.length })}
        >
          {books.length}
        </span>
      </div>
      {#if showArrows}
        <div class="flex shrink-0 items-center gap-2">
          <button
            type="button"
            class="rounded-full border border-(--color-border) bg-(--color-surface) p-1.5 shadow-md hover:bg-(--color-surface-hover)"
            aria-label={appState.t('home.continue.prevBook')}
            onclick={handlePrev}
          >
            <ChevronLeft size={14} strokeWidth={1.8} />
          </button>
          <button
            type="button"
            class="rounded-full border border-(--color-border) bg-(--color-surface) p-1.5 shadow-md hover:bg-(--color-surface-hover)"
            aria-label={appState.t('home.continue.nextBook')}
            onclick={handleNext}
          >
            <ChevronRight size={14} strokeWidth={1.8} />
          </button>
        </div>
      {/if}
    </div>

    <div class="relative">
      <ul
        bind:this={scrollEl}
        onscrollend={onScrollEnd}
        data-active-index={activeIndex}
        class="flex gap-0 overflow-x-auto overflow-y-hidden snap-x snap-mandatory scroll-smooth [scrollbar-width:none] [-ms-overflow-style:none] [&::-webkit-scrollbar]:hidden"
        style="scrollbar-width: none; -ms-overflow-style: none;"
      >
        {#each books as book (book.id)}
          <li class="relative snap-start shrink-0 w-full">
            <BookCard
              {book}
              variant="continue-reading"
              compact={false}
              selected={navigationState.previewBookId === book.id}
              onSelect={() => {
                navigationState.openShelfDetails(book.id);
              }}
              onRead={() => {
                void appState.startReading(book);
              }}
              t={appState.t}
            >
              {#snippet actions()}
                {#if book.readingStatus === 'reading'}
                  <span
                    class="rounded-full bg-(--color-accent-soft) px-2 py-0.5 text-xs font-bold uppercase tracking-wider text-(--color-primary)"
                  >
                    {appState.t('home.continue.liveBadge')}
                  </span>
                {/if}
                <ShelfActionMenu
                  bookId={book.id}
                  isFavorite={Boolean(book.collectionIds?.includes(1))}
                  readLabel={appState.t('app.read')}
                  editLabel={appState.t('library.editMetadata.title')}
                  removeLabel={appState.t('library.removeFromShelf')}
                  favoriteAddLabel={appState.t('library.favoriteAdd')}
                  favoriteRemoveLabel={appState.t('library.favoriteRemove')}
                  triggerLabel={appState.t('library.optionsFor', { title: book.title })}
                  onViewDetails={() => navigationState.openShelfDetails(book.id)}
                  viewDetailsLabel={appState.t('shelf.viewDetails')}
                  onEdit={() => {
                    libraryState.handleEditBook(book);
                  }}
                  onRemove={() => {
                    libraryState.pendingRemoveBook = book;
                  }}
                  onToggleFavorite={() => {
                    void libraryState.handleToggleFavorite(book);
                  }}
                />
              {/snippet}
            </BookCard>
          </li>
        {/each}
      </ul>
    </div>

    <!-- Screen-reader announcement of the active book -->
    <p class="sr-only" aria-live="polite" aria-atomic="true">
      {activeBook?.title ?? ''}
    </p>
  </div>
{/if}
