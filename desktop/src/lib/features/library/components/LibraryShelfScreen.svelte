<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
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

  let searchInput = $state<HTMLInputElement | null>(null);

  function focusSearch(): void {
    searchInput?.focus();
    searchInput?.select();
  }

  // Ctrl+K / Cmd+K focuses the shelf search. The chip advertises this shortcut,
  // so it has to actually work. Scoped to the window while this screen is
  // mounted; the reader never renders this component.
  function handleSearchShortcut(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && (event.key === 'k' || event.key === 'K')) {
      event.preventDefault();
      focusSearch();
    }
  }

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
  const isEmptyLibrary = $derived(!isLoading && books.length === 0);
  const hasNoResults = $derived(!isLoading && books.length > 0 && shelf.filteredBooks.length === 0);

  const totalBooks = $derived(books.length);
  const readingBooks = $derived(
    books.filter((b) => getSafeProgressPercentage(b) > 0 && getSafeProgressPercentage(b) < 100)
      .length,
  );
  const completedBooks = $derived(
    books.filter((b) => b.readingStatus === 'completed' || getSafeProgressPercentage(b) >= 100)
      .length,
  );
</script>

{#snippet importAction(extraClass: string, label: string)}
  <button
    type="button"
    class={`inline-flex items-center justify-center rounded-2xl border border-(--color-import) bg-transparent text-(--color-import) transition-colors hover:bg-(--color-import-bg) focus-visible:ring-2 ring-(--color-import) disabled:cursor-not-allowed disabled:opacity-50 ${extraClass}`}
    disabled={isImporting}
    onclick={onImportBook}
  >
    {isImporting ? t('shelf.importing') : label}
  </button>
{/snippet}

<svelte:window onkeydown={handleSearchShortcut} />

<section class="space-y-4">
  <header class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
    <div class="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-0.5">
      <h1 class="text-2xl font-semibold tracking-tight text-(--color-primary)">
        {t('library.title')}
      </h1>
      <p class="text-sm text-(--color-text-muted)">{t('library.subtitle')}</p>
      <span class="text-xs text-(--color-text-muted)">
        {t('shelf.booksCount', { count: totalBooks })} ·
        {t('shelf.readingCount', { count: readingBooks })} ·
        {t('shelf.completedCount', { count: completedBooks })}
      </span>
    </div>
    {@render importAction('h-10 min-w-[170px] px-4 text-sm font-medium', t('shelf.importBook'))}
  </header>

  <section class="rounded-(--radius-2xl) border border-(--color-border) bg-(--color-bg-panel) p-3">
    <div class="flex flex-col gap-3 xl:flex-row xl:flex-wrap xl:items-center xl:gap-x-5 xl:gap-y-3">
      <!--
        The magnifier is a flow sibling, not an absolutely positioned overlay:
        the text is laid out after it and can never sit under it, even if the
        unlayered global `input[type='text']` rule in styles.css wins on
        padding. `type="search"` additionally keeps this input out of that rule
        without touching the padding of every other text input.
      -->
      <div
        class="group flex h-11 w-full min-w-0 flex-1 items-center gap-2 rounded-2xl border border-(--color-border) bg-(--color-background) px-3.5 transition-colors focus-within:border-(--color-accent) focus-within:ring-2 focus-within:ring-(--color-accent-soft) xl:min-w-80 xl:max-w-md"
      >
        <svg
          class="h-4 w-4 shrink-0 text-(--color-text-muted)"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          aria-hidden="true"
        >
          <circle cx="11" cy="11" r="7"></circle>
          <path d="M20 20L17 17"></path>
        </svg>
        <input
          type="search"
          data-testid="shelf-search"
          bind:this={searchInput}
          aria-label={t('library.searchAriaLabel')}
          class="h-full min-w-0 flex-1 appearance-none border-0 bg-transparent p-0 text-sm text-(--color-primary) outline-none placeholder:text-(--color-text-muted)"
          placeholder={t('library.searchPlaceholder')}
          bind:value={shelf.searchQuery}
        />
        <button
          type="button"
          class="shrink-0 rounded-md border border-(--color-border) px-1.5 py-0.5 text-micro text-(--color-text-muted) transition-colors hover:border-(--color-accent) hover:text-(--color-primary) focus-visible:ring-2 ring-(--color-accent)"
          aria-label={t('library.searchShortcutAria')}
          onclick={focusSearch}
        >
          {t('library.searchShortcut')}
        </button>
      </div>

      <div
        class="flex flex-col gap-3 lg:flex-row lg:flex-wrap lg:items-center lg:justify-end lg:gap-x-5 lg:gap-y-3 xl:gap-x-6"
      >
        <fieldset class="border-0 p-0 m-0">
          <legend class="sr-only">{t('shelf.filterAria')}</legend>
          <div class="flex flex-wrap gap-2">
            {#each FILTER_OPTIONS as option}
              <button
                type="button"
                data-testid={`shelf-tab-${option.key}`}
                class={`rounded-2xl border px-3 py-2 text-xs font-medium transition focus-visible:ring-2 ring-(--color-accent) ${shelf.activeFilter === option.key ? 'border-(--color-accent) bg-(--color-accent-soft) text-(--color-primary)' : 'border-(--color-border) bg-(--color-surface-subtle) text-(--color-text-muted) hover:text-(--color-primary)'}`}
                onclick={() => {
                  shelf.activeFilter = option.key;
                }}
              >
                {option.label}
              </button>
            {/each}
          </div>
        </fieldset>
        <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
          <span class="whitespace-nowrap text-xs text-(--color-text-muted)"
            >{t('shelf.sortBy')}</span
          >
          <div data-testid="shelf-sort">
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
            class="inline-flex rounded-2xl border-(--color-border) bg-(--color-surface-subtle) p-1 border-0"
          >
            <legend class="sr-only">{t('shelf.viewToggleAria')}</legend>
            <button
              type="button"
              class={`flex h-9 w-10 items-center justify-center rounded-xl focus-visible:ring-2 ring-(--color-accent) ${shelf.activeView === 'grid' ? 'bg-(--color-accent-soft) text-(--color-accent)' : 'text-(--color-text-muted)'}`}
              aria-label={t('shelf.gridView')}
              onclick={() => {
                shelf.activeView = 'grid';
              }}
            >
              <LayoutGrid size={14} strokeWidth={1.8} />
            </button>
            <button
              type="button"
              class={`flex h-9 w-10 items-center justify-center rounded-xl focus-visible:ring-2 ring-(--color-accent) ${shelf.activeView === 'list' ? 'bg-(--color-accent-soft) text-(--color-accent)' : 'text-(--color-text-muted)'}`}
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
      class="rounded-2xl border border-(--color-border) bg-(--color-surface) px-4 py-2 text-xs text-(--color-primary) shadow-(--shadow-soft)"
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
      class="rounded-(--radius-2xl) border border-(--color-border) bg-(--color-surface) px-6 py-12 text-center"
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
