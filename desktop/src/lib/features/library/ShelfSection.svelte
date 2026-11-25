<script lang="ts">
  import { BookCard, ShelfActionMenu } from '$lib/features/library';
  import { FAVORITES_COLLECTION_ID } from '$lib/features/library/utils';
  import type { ReaderBook } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';

  // Home shows a short recent-books strip, never the full catalogue: search,
  // filter, sort and view controls live only in Estantería (one toolbar app-wide).
  const RECENT_SHELF_LIMIT = 6;

  export type ShelfSectionProps = {
    myShelfBooks: ReaderBook[];
    previewBookId: string | null;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onOpenDetails: (book: ReaderBook) => void;
    onStartReading: (book: ReaderBook) => void;
    onEditBook: (book: ReaderBook) => void;
    onRemoveBook: (book: ReaderBook) => void;
    onToggleFavorite: (book: ReaderBook) => Promise<void>;
  };

  let {
    myShelfBooks,
    previewBookId,
    t,
    onOpenDetails,
    onStartReading,
    onEditBook,
    onRemoveBook,
    onToggleFavorite,
  }: ShelfSectionProps = $props();

  // previewBookId is part of the public contract (PR3) but unused in presentation-only restyle
  // svelte-ignore state_referenced_locally
  void previewBookId;

  const recentShelfBooks = $derived(myShelfBooks.slice(0, RECENT_SHELF_LIMIT));
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
    <ul class="grid grid-cols-1 gap-3 md:grid-cols-2">
      {#each recentShelfBooks as book}
        <li>
          {@render shelfBookCard(book)}
        </li>
      {/each}
    </ul>
  {/if}
{/if}
