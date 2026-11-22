<script lang="ts">
  import { appState } from '$lib/shared/stores/AppState.svelte';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { BookCard, ShelfActionMenu } from '$lib/features/library';
  import ChevronLeft from 'lucide-svelte/icons/chevron-left';
  import ChevronRight from 'lucide-svelte/icons/chevron-right';
  import HomeHorizon from '$lib/features/home/components/HomeHorizon.svelte';

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

{#snippet sectionHeading()}
  <div class="flex min-w-0 items-center gap-3">
    <span
      class="h-7 w-1 shrink-0 rounded-full bg-linear-to-b from-(--color-brand-start) to-(--color-brand-end)"
      aria-hidden="true"
    ></span>
    <h3
      id="home-continue-heading"
      class="text-2xl font-bold tracking-[-0.02em] text-(--color-primary)"
    >
      {appState.t('home.continueReading')}
    </h3>
    {#if books.length > 0}
      <span
        class="rounded-(--radius-sm) border border-(--color-edge) bg-(--color-surface-subtle) px-2.5 py-0.5 text-sm font-semibold tabular-nums text-(--color-secondary)"
        aria-label={appState.t('home.continue.countAria', { count: books.length })}
      >
        {books.length}
      </span>
    {/if}
  </div>
{/snippet}

{#if books.length === 0}
  <div class="space-y-4">
    {@render sectionHeading()}
    <div
      class="relative isolate overflow-hidden rounded-(--radius-xl) border border-(--color-edge) bg-(--color-horizon-fade) px-7 py-10"
    >
      <div class="absolute inset-0 -z-10"><HomeHorizon /></div>
      <p class="max-w-md text-base text-(--color-secondary)">
        {appState.t('home.continueReadingPlaceholder')}
      </p>
    </div>
  </div>
{:else}
  <div class="space-y-4">
    {@render sectionHeading()}
    <div
      data-testid="continue-carousel"
      role="group"
      aria-labelledby="home-continue-heading"
      class="relative isolate overflow-hidden rounded-(--radius-xl) border border-(--color-edge) bg-(--color-horizon-fade)"
    >
      <div class="absolute inset-0 -z-10"><HomeHorizon /></div>
      {#if showArrows}
        <div class="absolute top-5 right-5 z-10 flex items-center gap-2">
          <button
            type="button"
            class="flex size-10 items-center justify-center rounded-full border border-(--color-edge-strong) bg-(--color-horizon-fade)/70 text-(--color-primary) backdrop-blur-sm transition-colors duration-(--duration-fast) ease-(--ease-smooth) hover:border-(--color-brand)/60 hover:text-(--color-brand-text) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
            aria-label={appState.t('home.continue.prevBook')}
            onclick={handlePrev}
          >
            <ChevronLeft size={18} strokeWidth={2} aria-hidden="true" />
          </button>
          <button
            type="button"
            class="flex size-10 items-center justify-center rounded-full border border-(--color-edge-strong) bg-(--color-horizon-fade)/70 text-(--color-primary) backdrop-blur-sm transition-colors duration-(--duration-fast) ease-(--ease-smooth) hover:border-(--color-brand)/60 hover:text-(--color-brand-text) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
            aria-label={appState.t('home.continue.nextBook')}
            onclick={handleNext}
          >
            <ChevronRight size={18} strokeWidth={2} aria-hidden="true" />
          </button>
        </div>
      {/if}

      <div class="relative">
        <ul
          bind:this={scrollEl}
          onscrollend={onScrollEnd}
          data-active-index={activeIndex}
          class="flex gap-0 overflow-x-auto overflow-y-hidden snap-x snap-mandatory scroll-smooth [scrollbar-width:none] [-ms-overflow-style:none] [&::-webkit-scrollbar]:hidden"
          style="scrollbar-width: none; -ms-overflow-style: none;"
        >
          {#each books as book (book.id)}
            <li class="relative w-full shrink-0 snap-start p-6">
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
                      class="inline-flex h-11 items-center rounded-full border border-(--color-edge-strong) bg-(--color-surface-subtle) px-5 text-xs font-bold uppercase tracking-[0.12em] text-(--color-primary)"
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
  </div>
{/if}
