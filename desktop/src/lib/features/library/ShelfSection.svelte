<script lang="ts">
  import { BookCard, ShelfActionMenu } from '$lib/features/library';
  import { FAVORITES_COLLECTION_ID } from '$lib/features/library/utils';
  import ShelfDetailModal from './ShelfDetailModal.svelte';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import LayoutGrid from 'lucide-svelte/icons/layout-grid';
  import List from 'lucide-svelte/icons/list';
  import type { LibraryBookDto, CollectionDto } from '$lib/shared/types';
  import type { ReaderBook } from '$lib/shared/types';
  import type { ShelfQueryState } from '$lib/shared/stores/HomeState';
  import type { MessageKey } from '$lib/shared/i18n';

  export type ShelfSectionProps = {
    shelfQueryState: ShelfQueryState;
    shelfBooks: ReaderBook[];
    myShelfBooks: ReaderBook[];
    collections: CollectionDto[];
    previewBookId: string | null;
    selectedShelfBook: LibraryBookDto | null;
    shelfTabOptions: readonly { key: string; label: string }[];
    shelfSortOptions: readonly { key: string; label: string }[];
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onSetTab: (key: string) => void;
    onSetSort: (key: string) => void;
    onSetViewMode: (mode: 'grid' | 'list') => void;
    onShelfQueryInput: (event: Event) => void;
    onClearShelfQuery: () => void;
    onOpenDetails: (book: ReaderBook) => void;
    onStartReading: (book: ReaderBook) => void;
    onEditBook: (book: ReaderBook) => void;
    onRemoveBook: (book: ReaderBook) => void;
    onToggleFavorite: (book: ReaderBook) => Promise<void>;
    onStatusChange: (book: ReaderBook, status: string) => Promise<void>;
    onDeleteCover: (book: ReaderBook) => Promise<void>;
    onSaveEdit: (dto: Partial<LibraryBookDto>) => Promise<void>;
    onCloseDetails: () => void;
    onCoverUpdated?: (bookId: string, path: string) => void;
  };

  let {
    shelfQueryState,
    shelfBooks,
    myShelfBooks,
    collections,
    previewBookId,
    selectedShelfBook,
    shelfTabOptions,
    shelfSortOptions,
    t,
    onSetTab,
    onSetSort,
    onSetViewMode,
    onShelfQueryInput,
    onClearShelfQuery,
    onOpenDetails,
    onStartReading,
    onEditBook,
    onRemoveBook,
    onToggleFavorite,
    onStatusChange,
    onDeleteCover,
    onSaveEdit,
    onCloseDetails,
    onCoverUpdated,
  }: ShelfSectionProps = $props();

  // previewBookId is part of the public contract (PR3) but unused in presentation-only restyle
  // svelte-ignore state_referenced_locally
  void previewBookId;

  let showShelfModal = $state(false);

  const shelfSortDropdownOptions = $derived(
    shelfSortOptions.map((o) => ({ value: o.key, label: t(o.label as MessageKey) })),
  );

  const shelfWarnings = $derived(shelfQueryState.invalidTokens.map((tok) => tok.raw));

  const shelfSortToken = $derived.by(() => {
    for (let i = shelfQueryState.smartTokens.length - 1; i >= 0; i -= 1) {
      const tok = shelfQueryState.smartTokens[i];
      if (tok.field === 'sort') return tok.value;
    }
    return null;
  });

  $effect(() => {
    if (selectedShelfBook) {
      showShelfModal = true;
    }
  });

  $effect(() => {
    if (!showShelfModal && selectedShelfBook) {
      onCloseDetails();
    }
  });
</script>

{#snippet shelfBookCard(book: ReaderBook)}
  <BookCard
    {book}
    variant="shelf"
    compact={true}
    selected={false}
    onSelect={() => {
      onOpenDetails(book);
    }}
    onRead={() => {
      void onStartReading(book);
    }}
    {t}
  >
    {#snippet actions()}
      <ShelfActionMenu
        bookId={book.id}
        isFavorite={Boolean(book.collectionIds?.includes(FAVORITES_COLLECTION_ID))}
        readLabel={t('app.read' as MessageKey)}
        editLabel={t('library.editMetadata.title' as MessageKey)}
        removeLabel={t('library.removeFromShelf' as MessageKey)}
        favoriteAddLabel={t('library.favoriteAdd' as MessageKey)}
        favoriteRemoveLabel={t('library.favoriteRemove' as MessageKey)}
        triggerLabel={t('library.optionsFor' as MessageKey, { title: book.title })}
        onViewDetails={() => onOpenDetails(book)}
        viewDetailsLabel={t('shelf.viewDetails' as MessageKey)}
        onEdit={() => {
          onEditBook(book);
        }}
        onRemove={() => {
          onRemoveBook(book);
        }}
        onToggleFavorite={() => {
          void onToggleFavorite(book);
        }}
      />
    {/snippet}
  </BookCard>
{/snippet}

<section class="space-y-3">
  <header class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
    <div class="flex flex-wrap items-center gap-2" data-testid="shelf-tabs">
      {#each shelfTabOptions as tabOption}
        <button
          type="button"
          data-testid={`shelf-tab-${tabOption.key}`}
          class={`rounded-full border px-3 py-1.5 text-xs font-medium transition-colors ${shelfQueryState.tab === tabOption.key ? 'border-(--color-accent) bg-(--color-background) text-(--color-primary)' : 'border-(--color-border) bg-(--color-background) text-(--color-text-muted) hover:bg-(--color-surface-hover)'}`}
          onclick={() => {
            onSetTab(tabOption.key);
          }}
        >
          {t(tabOption.label as MessageKey)}
        </button>
      {/each}
    </div>

    <div class="flex flex-wrap items-center gap-2">
      <span class="sr-only">{t('home.shelfSortLabel' as MessageKey)}</span>
      <div data-testid="shelf-sort">
        <Dropdown
          options={shelfSortDropdownOptions}
          value={shelfQueryState.sortKey}
          onchange={({ value }) => {
            onSetSort(value as string);
          }}
        />
      </div>

      <fieldset
        class="inline-flex rounded-full border border-(--color-border) bg-(--color-background) p-0.5"
        data-testid="shelf-view-toggle"
      >
        <legend class="sr-only">{t('shelf.viewToggleAria' as MessageKey)}</legend>
        <button
          type="button"
          class={`flex h-7 w-8 items-center justify-center rounded-full px-0 py-1 text-xs font-medium transition-colors ${shelfQueryState.viewMode === 'grid' ? 'bg-(--color-accent-soft) text-(--color-accent)' : 'text-(--color-text-muted) hover:text-(--color-primary)'}`}
          onclick={() => {
            onSetViewMode('grid');
          }}
          aria-label={t('shelf.viewGrid' as MessageKey)}
        >
          <List size={14} strokeWidth={1.8} />
        </button>
        <button
          type="button"
          class={`flex h-7 w-8 items-center justify-center rounded-full px-0 py-1 text-xs font-medium transition-colors ${shelfQueryState.viewMode === 'list' ? 'bg-(--color-accent-soft) text-(--color-accent)' : 'text-(--color-text-muted) hover:text-(--color-primary)'}`}
          onclick={() => {
            onSetViewMode('list');
          }}
          aria-label={t('shelf.viewList' as MessageKey)}
        >
          <LayoutGrid size={14} strokeWidth={1.8} />
        </button>
      </fieldset>

      <div class="relative min-w-[220px] flex-1 lg:min-w-[280px]">
        <input
          type="text"
          data-testid="shelf-search"
          class="w-full rounded-full border border-(--color-border) bg-(--color-background) px-3.5 py-1.5 pr-8 text-sm text-(--color-primary) placeholder-(--color-text-muted) focus:border-(--color-accent) focus:outline-none focus:ring-2 focus:ring-(--color-accent-soft)"
          placeholder={t('home.shelfSearchPlaceholder' as MessageKey)}
          value={shelfQueryState.rawQuery}
          oninput={onShelfQueryInput}
        />
        {#if shelfQueryState.rawQuery.length > 0}
          <button
            type="button"
            class="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-(--color-text-muted)"
            aria-label={t('home.shelfClearSearch' as MessageKey)}
            onclick={onClearShelfQuery}
          >
            x
          </button>
        {/if}
      </div>
    </div>
  </header>

  {#if shelfSortToken}
    <p class="text-xs text-(--color-text-muted)">
      {t('home.shelfSortFromQuery' as MessageKey, { value: shelfSortToken })}
    </p>
  {/if}

  {#if shelfWarnings.length > 0}
    <div
      class="rounded-lg border border-(--color-border) bg-(--color-surface) px-3 py-2 text-xs text-(--color-primary) shadow-(--shadow-soft)"
      data-testid="shelf-warnings"
    >
      <p class="font-medium">{t('home.shelfWarningsLabel' as MessageKey)}</p>
      <p class="mt-1 text-(--color-text-muted)">
        {t('home.shelfSearchInvalid' as MessageKey, { value: shelfWarnings.join(', ') })}
      </p>
    </div>
  {/if}

  <p class="text-xs text-(--color-text-muted)">
    {t('home.shelfResults' as MessageKey, {
      count: shelfBooks.length,
      total: myShelfBooks.length,
    })}
  </p>
</section>

{#if myShelfBooks.length === 0}
  <p class="text-sm text-(--color-text-muted)">{t('home.myShelfPlaceholder' as MessageKey)}</p>
{:else if shelfBooks.length === 0}
  <p class="text-sm text-(--color-text-muted)">{t('home.shelfNoResults' as MessageKey)}</p>
{:else}
  {#if shelfQueryState.viewMode === 'grid'}
    {#if shelfBooks.length === 1}
      {@const book = shelfBooks[0]!}
      {@render shelfBookCard(book)}
    {:else}
      <ul class="grid grid-cols-1 gap-2 md:grid-cols-2">
        {#each shelfBooks as book}
          <li>
            {@render shelfBookCard(book)}
          </li>
        {/each}
      </ul>
    {/if}
  {:else}
    <ul class="space-y-2">
      {#each shelfBooks as book}
        <li>
          {@render shelfBookCard(book)}
        </li>
      {/each}
    </ul>
  {/if}
{/if}

<ShelfDetailModal
  bind:open={showShelfModal}
  book={selectedShelfBook}
  {collections}
  {t}
  onClose={onCloseDetails}
  onStartReading={(b) => onStartReading(b as unknown as ReaderBook)}
  onDeleteCover={(b) => onDeleteCover(b as unknown as ReaderBook)}
  onStatusChange={(b, s) => onStatusChange(b as unknown as ReaderBook, s)}
  onToggleFavorite={(b) => onToggleFavorite(b as unknown as ReaderBook)}
  {onSaveEdit}
  {onCoverUpdated}
/>
