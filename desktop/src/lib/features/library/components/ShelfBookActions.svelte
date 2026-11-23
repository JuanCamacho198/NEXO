<script lang="ts">
  import DropMenu from '$lib/shared/ui/navigation/DropMenu.svelte';
  import Ellipsis from 'lucide-svelte/icons/ellipsis';
  import { FAVORITES_COLLECTION_ID, type ShelfBook } from '$lib/features/library/utils';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    book: ShelfBook;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onOpenBook?: (book: ShelfBook) => void;
    onToggleFavorite?: (book: ShelfBook) => void;
    onStatusChange?: (book: ShelfBook, status: string) => void;
    onViewDetails?: (book: ShelfBook) => void;
    onRemoveBook?: (book: ShelfBook) => void;
    variant?: 'grid' | 'list';
  };

  let {
    book,
    t,
    onOpenBook,
    onToggleFavorite,
    onStatusChange,
    onViewDetails,
    onRemoveBook,
    variant = 'grid',
  }: Props = $props();

  const isFavorite = $derived(Boolean(book.collectionIds?.includes(FAVORITES_COLLECTION_ID)));

  const focusRing =
    'transition-colors duration-(--duration-fast) ease-(--ease-smooth) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)';

  const triggerClass = $derived(
    variant === 'list'
      ? `flex size-10 items-center justify-center rounded-full border border-(--color-edge) bg-(--color-surface-subtle) text-(--color-text-muted) hover:border-(--color-edge-strong) hover:text-(--color-primary) ${focusRing}`
      : `flex size-8 items-center justify-center rounded-full border border-(--color-edge) bg-(--color-horizon-fade)/85 text-(--color-secondary) backdrop-blur-sm hover:text-(--color-primary) ${focusRing}`,
  );
</script>

<DropMenu position="bottom-right">
  {#snippet trigger()}
    <button
      type="button"
      class={triggerClass}
      aria-label={t('shelf.bookOptions', { title: book.title })}
    >
      <Ellipsis size={16} strokeWidth={2} aria-hidden="true" />
    </button>
  {/snippet}
  <button
    class="w-full px-4 py-2.5 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover)"
    onclick={() => onOpenBook?.(book)}>{t('shelf.openBook')}</button
  >
  <button
    class="w-full px-4 py-2.5 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover)"
    onclick={() => onToggleFavorite?.(book)}
  >
    {isFavorite ? t('shelf.removeFavorite') : t('shelf.markFavorite')}
  </button>
  <button
    class="w-full px-4 py-2.5 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover)"
    onclick={() => onStatusChange?.(book, 'completed')}>{t('shelf.markCompleted')}</button
  >
  <button
    class="w-full px-4 py-2.5 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover)"
    onclick={() => onViewDetails?.(book)}>{t('shelf.viewDetails')}</button
  >
  <button
    class="w-full px-4 py-2.5 text-left text-sm text-(--color-danger) hover:bg-(--color-surface-hover)"
    onclick={() => onRemoveBook?.(book)}>{t('shelf.removeLibrary')}</button
  >
</DropMenu>
