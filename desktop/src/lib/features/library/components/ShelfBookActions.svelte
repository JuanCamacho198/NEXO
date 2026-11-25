<script lang="ts">
  import DropMenu from '$lib/shared/ui/navigation/DropMenu.svelte';
  import BookOpen from 'lucide-svelte/icons/book-open';
  import CircleCheck from 'lucide-svelte/icons/circle-check';
  import Ellipsis from 'lucide-svelte/icons/ellipsis';
  import Info from 'lucide-svelte/icons/info';
  import SquarePen from 'lucide-svelte/icons/square-pen';
  import Star from 'lucide-svelte/icons/star';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import { FAVORITES_COLLECTION_ID, type ShelfBook } from '$lib/features/library/utils';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    book: ShelfBook;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onOpenBook?: (book: ShelfBook) => void;
    onToggleFavorite?: (book: ShelfBook) => void;
    onStatusChange?: (book: ShelfBook, status: string) => void;
    onEdit?: (book: ShelfBook) => void;
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
    onEdit,
    onViewDetails,
    onRemoveBook,
    variant = 'grid',
  }: Props = $props();

  const isFavorite = $derived(Boolean(book.collectionIds?.includes(FAVORITES_COLLECTION_ID)));
  // "Mark as completed" on a finished book is a no-op the menu should not offer.
  const isCompleted = $derived(book.readingStatus === 'completed');

  const focusRing =
    'transition-colors duration-(--duration-fast) ease-(--ease-smooth) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)';

  const triggerClass = $derived(
    variant === 'list'
      ? `flex size-10 items-center justify-center rounded-full border border-(--color-edge) bg-(--color-surface-subtle) text-(--color-text-muted) hover:border-(--color-edge-strong) hover:text-(--color-primary) ${focusRing}`
      : `flex size-8 items-center justify-center rounded-full border border-(--color-edge) bg-(--color-horizon-fade)/85 text-(--color-secondary) backdrop-blur-sm hover:text-(--color-primary) ${focusRing}`,
  );

  // One recipe for every row: an icon column, then the label. The icon is
  // muted and lights up with the row, so the list reads by words first.
  const ITEM =
    'group/item mx-1.5 flex w-[calc(100%-0.75rem)] cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm text-(--color-primary) transition-colors hover:bg-(--color-surface-hover) focus-visible:bg-(--color-surface-hover) focus-visible:outline-none';
  const ICON = 'shrink-0 text-(--color-text-muted) group-hover/item:text-(--color-primary)';
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
  <button class={ITEM} onclick={() => onOpenBook?.(book)}>
    <BookOpen size={16} strokeWidth={1.8} class={ICON} aria-hidden="true" />
    {t('shelf.openBook')}
  </button>
  <button class={ITEM} onclick={() => onToggleFavorite?.(book)}>
    <Star
      size={16}
      strokeWidth={1.8}
      class={isFavorite ? 'shrink-0 text-(--color-brand)' : ICON}
      fill={isFavorite ? 'currentColor' : 'none'}
      aria-hidden="true"
    />
    {isFavorite ? t('shelf.removeFavorite') : t('shelf.markFavorite')}
  </button>
  {#if !isCompleted}
    <button class={ITEM} onclick={() => onStatusChange?.(book, 'completed')}>
      <CircleCheck size={16} strokeWidth={1.8} class={ICON} aria-hidden="true" />
      {t('shelf.markCompleted')}
    </button>
  {/if}
  {#if onEdit}
    <button class={ITEM} onclick={() => onEdit?.(book)}>
      <SquarePen size={16} strokeWidth={1.8} class={ICON} aria-hidden="true" />
      {t('shelf.editMetadata')}
    </button>
  {/if}
  <button class={ITEM} onclick={() => onViewDetails?.(book)}>
    <Info size={16} strokeWidth={1.8} class={ICON} aria-hidden="true" />
    {t('shelf.viewDetails')}
  </button>
  <div role="separator" class="mx-3 my-1 h-px bg-(--color-edge)"></div>
  <button
    class="{ITEM} text-(--color-danger)! hover:bg-(--color-danger)/10"
    onclick={() => onRemoveBook?.(book)}
  >
    <Trash2 size={16} strokeWidth={1.8} class="shrink-0" aria-hidden="true" />
    {t('shelf.removeLibrary')}
  </button>
</DropMenu>
