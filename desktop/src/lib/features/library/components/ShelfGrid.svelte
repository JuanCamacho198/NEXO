<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Skeleton from '$lib/shared/ui/feedback/Skeleton.svelte';
  import SafeCover from './SafeCover.svelte';
  import ShelfBookActions from './ShelfBookActions.svelte';
  import {
    formatPercent,
    getSafeProgressPercentage,
    getStateLabel,
    type ShelfBook,
  } from '$lib/features/library/utils';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    books: ShelfBook[];
    isLoading?: boolean;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onOpenBook?: (book: ShelfBook) => void;
    onContinueReading?: (book: ShelfBook) => void;
    onToggleFavorite?: (book: ShelfBook) => void;
    onStatusChange?: (book: ShelfBook, status: string) => void;
    onViewDetails?: (book: ShelfBook) => void;
    onRemoveBook?: (book: ShelfBook) => void;
  };

  let {
    books,
    isLoading = false,
    t,
    onOpenBook,
    onContinueReading,
    onToggleFavorite,
    onStatusChange,
    onViewDetails,
    onRemoveBook,
  }: Props = $props();

  const gridClass =
    'grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4 2xl:grid-cols-5 list-none p-0 m-0';
</script>

{#if isLoading}
  <ul class={gridClass} aria-hidden="true">
    {#each Array(8) as _, index (index)}
      <li class="content-auto [contain-intrinsic-size:auto_280px]">
        <div
          class="flex flex-col rounded-(--radius-xl) border border-(--color-border) bg-(--color-surface) p-3"
        >
          <Skeleton variant="rounded" width="100%" height="176px" class="mb-2.5" />
          <Skeleton variant="text" class="w-3/4" />
          <Skeleton variant="text" class="mt-2 w-1/2" />
          <Skeleton variant="rounded" height="8px" class="mt-2.5" />
          <div class="mt-2.5 grid grid-cols-2 gap-2">
            <Skeleton variant="rounded" height="32px" />
            <Skeleton variant="rounded" height="32px" />
          </div>
        </div>
      </li>
    {/each}
  </ul>
{:else}
  <ul class={gridClass}>
    {#each books as book}
      <li class="content-auto [contain-intrinsic-size:auto_280px]">
        <article
          class="group flex h-full flex-col rounded-(--radius-xl) border border-(--color-border) bg-(--color-surface) p-3 shadow-(--shadow-panel)"
        >
          <div
            class="relative mb-2.5 h-44 w-full overflow-hidden rounded-(--radius-lg) bg-(--color-surface-subtle)"
          >
            <SafeCover
              path={book.coverPath ?? ''}
              alt={`Portada de ${book.title}`}
              className="h-full w-full object-cover"
            >
              {#snippet fallback()}
                <div
                  class="flex h-full w-full items-center justify-center bg-(--color-surface-subtle) px-6 text-center text-xs uppercase tracking-[0.18em] text-(--color-text-muted)"
                >
                  {t('shelf.noCover')}
                </div>
              {/snippet}
            </SafeCover>

            <span
              class="absolute left-2 top-2 rounded-full border border-(--color-border) bg-(--color-surface) px-2 py-0.5 text-xs font-medium uppercase tracking-wider text-(--color-text-muted)"
            >
              {getStateLabel(book)}
            </span>

            <div class="absolute right-2 top-2">
              <ShelfBookActions
                {book}
                {t}
                {onOpenBook}
                {onToggleFavorite}
                {onStatusChange}
                {onViewDetails}
                {onRemoveBook}
                variant="grid"
              />
            </div>
          </div>

          <div class="space-y-1">
            <h2 class="line-clamp-2 text-sm font-semibold text-(--color-primary)">{book.title}</h2>
            <p class="line-clamp-1 text-xs text-(--color-text-muted)">
              {book.author || t('shelf.unknownAuthor')}
            </p>
          </div>

          <div
            class="mt-auto space-y-1 pt-2.5"
            role="progressbar"
            aria-label={t('shelf.progressAria', { title: book.title })}
            aria-valuenow={getSafeProgressPercentage(book)}
            aria-valuemin="0"
            aria-valuemax="100"
          >
            <div class="h-2 w-full overflow-hidden rounded-full bg-(--color-border)">
              <div
                class="h-full rounded-full bg-(--color-accent)"
                style={`width: ${formatPercent(book)};`}
              ></div>
            </div>
            <div class="flex items-center justify-between text-xs text-(--color-text-muted)">
              <span>{t('shelf.percentRead', { percent: formatPercent(book) })}</span>
              <span>{book.minutesRead} {t('library.min')}</span>
            </div>
          </div>

          <div class="grid grid-cols-2 gap-2 pt-2.5">
            <Button
              variant="secondary"
              size="sm"
              class="rounded-xl whitespace-nowrap"
              onclick={() => onOpenBook?.(book)}
            >
              {t('shelf.openBook')}
            </Button>
            <Button
              variant="accent"
              size="sm"
              class="rounded-xl whitespace-nowrap"
              onclick={() => onContinueReading?.(book)}
            >
              {getSafeProgressPercentage(book) > 0 ? t('app.continue') : t('shelf.start')}
            </Button>
          </div>
        </article>
      </li>
    {/each}
  </ul>
{/if}
