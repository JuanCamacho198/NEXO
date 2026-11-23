<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import FilterChip from '$lib/shared/ui/forms/FilterChip.svelte';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import SearchBar from '$lib/shared/ui/navigation/SearchBar.svelte';
  import Toast from '$lib/shared/ui/feedback/Toast.svelte';
  import EmptyState from '$lib/shared/ui/feedback/EmptyState.svelte';
  import LayoutGrid from 'lucide-svelte/icons/layout-grid';
  import List from 'lucide-svelte/icons/list';
  import ShelfGrid from './ShelfGrid.svelte';
  import ShelfList from './ShelfList.svelte';
  import ShelfDownloadsSection from './ShelfDownloadsSection.svelte';
  import { useLibraryShelf } from '$lib/features/library/useLibraryShelf.svelte';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import {
    FILTER_OPTIONS,
    SORT_OPTIONS,
    getSafeProgressPercentage,
    isRealBook,
    type ShelfBook,
  } from '$lib/features/library/utils';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    books: ShelfBook[];
    isImporting?: boolean;
    /**
     * Optional loading override. The screen falls back to
     * `libraryState.isLoadingLibrary` so the routed screen shows the real
     * store flag without an AppRouter change, while tests can pin it.
     */
    isLoading?: boolean;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onImportBook?: () => void;
    onOpenBook?: (book: ShelfBook) => void;
    onContinueReading?: (book: ShelfBook) => void;
    onToggleFavorite?: (book: ShelfBook) => void;
    onStatusChange?: (book: ShelfBook, status: string) => void;
    onViewDetails?: (book: ShelfBook) => void;
    onRemoveBook?: (book: ShelfBook) => void;
    onDownloaded?: () => void;
  };

  let {
    books,
    isImporting = false,
    isLoading: isLoadingProp,
    t,
    onImportBook,
    onOpenBook,
    onContinueReading,
    onToggleFavorite,
    onStatusChange,
    onViewDetails,
    onRemoveBook,
    onDownloaded,
  }: Props = $props();

  const shelf = useLibraryShelf(() => books);

  let downloadSuccessVisible = $state(false);

  function handleDownloaded(): void {
    downloadSuccessVisible = true;
    onDownloaded?.();
  }

  function clearFilters(): void {
    shelf.searchQuery = '';
    shelf.activeFilter = 'all';
  }

  const sortDropdownOptions = $derived(SORT_OPTIONS.map((o) => ({ value: o.key, label: o.label })));
  const activeSortLabel = $derived(
    SORT_OPTIONS.find((o) => o.key === shelf.activeSort)?.label ?? '',
  );

  const shelfWarnings = $derived(shelf.invalidTokens.map((token) => token.raw).join(', '));

  // Empty registry rows can only be trusted once the load settles; the store
  // flag keeps a first paint from flashing the empty state.
  const isLoading = $derived(isLoadingProp ?? libraryState.isLoadingLibrary);

  // Sync stubs are library rows with no local file. They never reach the grid,
  // so every count and the empty-state decision read from the same visible set
  // the shelf renders; otherwise a stub inflates the badge or hides the empty
  // state behind an empty grid.
  const realBooks = $derived(books.filter((book) => isRealBook(book)));

  const isEmptyLibrary = $derived(!isLoading && realBooks.length === 0);
  const hasNoResults = $derived(
    !isLoading && realBooks.length > 0 && shelf.filteredBooks.length === 0,
  );

  const totalBooks = $derived(realBooks.length);
  const readingBooks = $derived(
    realBooks.filter((b) => getSafeProgressPercentage(b) > 0 && getSafeProgressPercentage(b) < 100)
      .length,
  );
  const completedBooks = $derived(
    realBooks.filter((b) => b.readingStatus === 'completed' || getSafeProgressPercentage(b) >= 100)
      .length,
  );
</script>

{#snippet importAction(extraClass: string, label: string)}
  <button
    type="button"
    class={`inline-flex items-center justify-center gap-2 rounded-full border border-(--color-edge) bg-(--color-home-card) font-semibold text-(--color-brand-text) transition-colors duration-(--duration-fast) ease-(--ease-smooth) hover:border-(--color-brand)/50 hover:bg-(--color-brand-soft) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) active:scale-96 disabled:cursor-not-allowed disabled:opacity-50 ${extraClass}`}
    disabled={isImporting}
    onclick={onImportBook}
  >
    {isImporting ? t('shelf.importing') : label}
  </button>
{/snippet}

<section class="space-y-5">
  <header class="flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
    <div class="min-w-0">
      <div class="flex items-center gap-3">
        <div
          class="h-8 w-1 shrink-0 rounded-full bg-linear-to-b from-(--color-brand-start) to-(--color-brand-end)"
          aria-hidden="true"
        ></div>
        <h1 class="text-[1.75rem] font-bold tracking-[-0.02em] text-(--color-primary)">
          {t('library.title')}
        </h1>
      </div>
      <p class="mt-1 text-sm text-(--color-text-muted)">
        {t('library.subtitle')}
        <span class="ml-2 text-(--color-secondary) tabular-nums">
          {t('shelf.booksCount', { count: totalBooks })} ·
          {t('shelf.readingCount', { count: readingBooks })} ·
          {t('shelf.completedCount', { count: completedBooks })}
        </span>
      </p>
    </div>
    {@render importAction('h-11 px-5 text-sm', t('shelf.importBook'))}
  </header>

  <section class="rounded-(--radius-lg) border border-(--color-edge) bg-(--color-home-card) p-3">
    <!-- One quiet band, two rows: the search line rides alone on top; the second
         row splits the filter chips (left) from the sort and view controls
         (right). Nothing here competes with the covers. -->
    <div class="flex flex-col gap-2.5">
      <SearchBar
        bind:value={shelf.searchQuery}
        class="w-full"
        data-testid="shelf-search"
        placeholder={t('library.searchPlaceholder')}
        ariaLabel={t('library.searchAriaLabel')}
        clearLabel={t('home.shelfClearSearch')}
        shortcutKey="/"
        shortcutLabel={t('library.searchShortcut')}
        shortcutAriaLabel={t('library.searchShortcutAria')}
      />

      <div class="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <fieldset class="m-0 min-w-0 border-0 p-0">
          <legend class="sr-only">{t('shelf.filterAria')}</legend>
          <div class="flex flex-wrap items-center gap-1.5">
            {#each FILTER_OPTIONS as option}
              <FilterChip
                data-testid={`shelf-tab-${option.key}`}
                selected={shelf.activeFilter === option.key}
                onclick={() => {
                  shelf.activeFilter = option.key;
                }}
              >
                {option.label}
              </FilterChip>
            {/each}
          </div>
        </fieldset>

        <div class="ml-auto flex flex-wrap items-center gap-x-3 gap-y-2">
          <span class="whitespace-nowrap text-sm text-(--color-text-muted)"
            >{t('shelf.sortBy')}</span
          >
          <div data-testid="shelf-sort" class="shrink-0">
            <Dropdown
              options={sortDropdownOptions}
              bind:value={shelf.activeSort}
              class="min-w-[130px]"
            >
              {#snippet trigger()}
                <span class="text-sm text-(--color-primary)">{activeSortLabel}</span>
                <svg
                  class="ml-1 h-4 w-4 text-(--color-text-muted)"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M19 9l-7 7-7-7"
                  />
                </svg>
              {/snippet}
            </Dropdown>
          </div>
          <fieldset
            data-testid="shelf-view-toggle"
            class="inline-flex shrink-0 items-center rounded-full border border-(--color-edge) bg-(--color-surface-subtle) p-0.5"
          >
            <legend class="sr-only">{t('shelf.viewToggleAria')}</legend>
            <button
              type="button"
              aria-pressed={shelf.activeView === 'grid'}
              class={`flex size-8 items-center justify-center rounded-full transition-colors duration-(--duration-fast) ease-(--ease-smooth) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) ${
                shelf.activeView === 'grid'
                  ? 'bg-(--color-brand-soft) text-(--color-brand-text)'
                  : 'text-(--color-text-muted) hover:text-(--color-primary)'
              }`}
              aria-label={t('shelf.gridView')}
              onclick={() => {
                shelf.activeView = 'grid';
              }}
            >
              <LayoutGrid size={14} strokeWidth={1.8} />
            </button>
            <button
              type="button"
              aria-pressed={shelf.activeView === 'list'}
              class={`flex size-8 items-center justify-center rounded-full transition-colors duration-(--duration-fast) ease-(--ease-smooth) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) ${
                shelf.activeView === 'list'
                  ? 'bg-(--color-brand-soft) text-(--color-brand-text)'
                  : 'text-(--color-text-muted) hover:text-(--color-primary)'
              }`}
              aria-label={t('shelf.listView')}
              onclick={() => {
                shelf.activeView = 'list';
              }}
            >
              <List size={14} strokeWidth={1.8} />
            </button>
          </fieldset>
        </div>
      </div>
    </div>
  </section>

  {#if shelf.invalidTokens.length > 0}
    <div
      class="rounded-(--radius-lg) border border-(--color-edge) bg-(--color-home-card) px-4 py-2 text-xs text-(--color-primary)"
      data-testid="shelf-warnings"
    >
      <p class="font-medium">{t('home.shelfWarningsLabel')}</p>
      <p class="mt-1 text-(--color-text-muted)">
        {t('home.shelfSearchInvalid', { value: shelfWarnings })}
      </p>
    </div>
  {/if}

  {#if isLoading}
    {#if shelf.activeView === 'grid'}
      <ShelfGrid books={[]} isLoading {t} />
    {:else}
      <ShelfList books={[]} isLoading {t} />
    {/if}
  {:else if isEmptyLibrary}
    <EmptyState
      icon="book"
      title={t('library.emptyTitle')}
      description={t('library.emptyDescription')}
    >
      {#snippet action()}
        {@render importAction('min-h-11 px-5 text-sm font-medium', t('library.import'))}
      {/snippet}
    </EmptyState>
  {:else if hasNoResults}
    <div
      class="rounded-(--radius-lg) border border-(--color-edge) bg-(--color-home-card) px-6 py-12 text-center"
    >
      <p class="text-base font-semibold text-(--color-primary)">{t('library.searchNoResults')}</p>
      <p class="mt-1 text-sm text-(--color-text-muted)">{t('library.noResultsDescription')}</p>
      <Button variant="secondary" size="sm" class="mt-4" onclick={clearFilters}>
        {t('library.clearFilters')}
      </Button>
    </div>
  {:else if shelf.activeView === 'grid'}
    <ShelfGrid
      books={shelf.filteredBooks}
      {t}
      {onOpenBook}
      {onContinueReading}
      {onToggleFavorite}
      {onStatusChange}
      {onViewDetails}
      {onRemoveBook}
    />
  {:else}
    <ShelfList
      books={shelf.filteredBooks}
      {t}
      {onOpenBook}
      {onContinueReading}
      {onToggleFavorite}
      {onStatusChange}
      {onViewDetails}
      {onRemoveBook}
    />
  {/if}

  <ShelfDownloadsSection {t} onDownloaded={handleDownloaded} />

  <Toast
    type="success"
    message={t('shelf.downloadSuccess')}
    bind:visible={downloadSuccessVisible}
    onDismiss={() => (downloadSuccessVisible = false)}
  />
</section>
