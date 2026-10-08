<script lang="ts">
  import { BookCard, ShelfActionMenu } from '$lib/features/library';
  import { FAVORITES_COLLECTION_ID } from '$lib/features/library/utils';
  import ShelfDetailModal from './ShelfDetailModal.svelte';
  import type { LibraryBookDto, CollectionDto } from '$lib/shared/types';
  import type { ReaderBook } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';

  // Home shows a short recent-books strip, never the full catalogue: search,
  // filter, sort and view controls live only in Estantería (one toolbar app-wide).
  const RECENT_SHELF_LIMIT = 6;

  export type ShelfSectionProps = {
    myShelfBooks: ReaderBook[];
    collections: CollectionDto[];
    previewBookId: string | null;
    selectedShelfBook: LibraryBookDto | null;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
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
    myShelfBooks,
    collections,
    previewBookId,
    selectedShelfBook,
    t,
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

  const recentShelfBooks = $derived(myShelfBooks.slice(0, RECENT_SHELF_LIMIT));

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

{#if myShelfBooks.length === 0}
  <p class="text-sm text-(--color-text-muted)">{t('home.myShelfPlaceholder' as MessageKey)}</p>
{:else}
  {#if recentShelfBooks.length === 1}
    {@const book = recentShelfBooks[0]!}
    {@render shelfBookCard(book)}
  {:else}
    <ul class="grid grid-cols-1 gap-2 md:grid-cols-2">
      {#each recentShelfBooks as book}
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
