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
</script>

{#if isLoading}
  <ul class="space-y-3 list-none p-0 m-0" aria-hidden="true">
    {#each Array(5) as _, index (index)}
      <li class="content-auto [contain-intrinsic-size:auto_160px]">
        <div
          class="flex flex-col gap-4 rounded-(--radius-xl) border border-(--color-border) bg-(--color-surface) p-4 md:flex-row md:items-center"
        >
          <Skeleton variant="rounded" width="80px" height="112px" class="shrink-0" />
          <div class="min-w-0 flex-1 space-y-2">
            <Skeleton variant="text" class="w-2/3" />
            <Skeleton variant="text" class="w-1/3" />
            <Skeleton variant="rounded" height="8px" class="mt-4 w-full max-w-xl" />
          </div>
        </div>
      </li>
    {/each}
  </ul>
{:else}
  <ul class="space-y-3 list-none p-0 m-0">
    {#each books as book}
      <li class="content-auto [contain-intrinsic-size:auto_160px]">
        <article
          class="flex flex-col gap-4 rounded-(--radius-xl) border border-(--color-border) bg-(--color-surface) p-4 shadow-(--shadow-panel) md:flex-row md:items-center"
        >
          <div class="flex items-start gap-4 md:min-w-0 md:flex-1">
            <div
              class="h-28 w-20 shrink-0 overflow-hidden rounded-(--radius-lg) bg-(--color-surface-subtle)"
            >
              <SafeCover
                path={book.coverPath ?? ''}
                alt={`${t('library.cover')} ${book.title}`}
                className="h-full w-full object-cover"
              >
                {#snippet fallback()}
                  <div
                    class="flex h-full w-full items-center justify-center bg-(--color-surface-subtle) px-2 text-center text-micro uppercase tracking-[0.16em] text-(--color-text-muted)"
                  >
                    {t('shelf.noCover')}
                  </div>
                {/snippet}
              </SafeCover>
            </div>

            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <h2 class="line-clamp-1 text-base font-semibold text-(--color-primary)">
                  {book.title}
                </h2>
                <span
                  class="rounded-full border border-(--color-border) px-2 py-1 text-micro uppercase tracking-[0.12em] text-(--color-text-muted)"
                >
                  {getStateLabel(book)}
                </span>
              </div>
              <p class="mt-1 text-sm text-(--color-text-muted)">
                {book.author || t('shelf.unknownAuthor')}
              </p>

              <div
                class="mt-4 max-w-xl space-y-2"
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
                <div
                  class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-(--color-text-muted)"
                >
                  <span>{t('shelf.percentRead', { percent: formatPercent(book) })}</span>
                  <span>{t('shelf.minutesLogged', { minutes: book.minutesRead })}</span>
                  <span
                    >{t('shelf.pageProgress', {
                      current: book.currentPage,
                      total: book.totalPages || '-',
                    })}</span
                  >
                </div>
              </div>
            </div>
          </div>

          <div class="flex flex-wrap items-center gap-2 md:justify-end">
            <Button
              variant="secondary"
              size="sm"
              class="rounded-xl whitespace-nowrap"
              onclick={() => onOpenBook?.(book)}>{t('shelf.read')}</Button
            >
            <Button
              variant="accent"
              size="sm"
              class="rounded-xl whitespace-nowrap"
              onclick={() => onContinueReading?.(book)}
            >
              {getSafeProgressPercentage(book) > 0
                ? t('shelf.continueReading')
                : t('shelf.startReading')}
            </Button>
            <ShelfBookActions
              {book}
              {t}
              {onOpenBook}
              {onToggleFavorite}
              {onStatusChange}
              {onViewDetails}
              {onRemoveBook}
              variant="list"
            />
          </div>
        </article>
      </li>
    {/each}
  </ul>
{/if}
