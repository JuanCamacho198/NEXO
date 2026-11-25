<script lang="ts">
  import Skeleton from '$lib/shared/ui/feedback/Skeleton.svelte';
  import Play from 'lucide-svelte/icons/play';
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
    onEdit?: (book: ShelfBook) => void;
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
    onEdit,
    onRemoveBook,
  }: Props = $props();

  const focusRing =
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) focus-visible:ring-offset-2 focus-visible:ring-offset-(--color-background)';
</script>

{#if isLoading}
  <ul class="m-0 list-none space-y-3 p-0" aria-hidden="true">
    {#each Array(5) as _, index (index)}
      <li class="content-auto [contain-intrinsic-size:auto_128px]">
        <div
          class="flex items-center gap-5 rounded-(--radius-lg) border border-(--color-edge) bg-(--color-home-card) p-3"
        >
          <Skeleton variant="rounded" width="64px" height="96px" class="shrink-0" />
          <div class="min-w-0 flex-1 space-y-2">
            <Skeleton variant="text" class="w-2/3" />
            <Skeleton variant="text" class="w-1/3" />
            <Skeleton variant="rounded" height="6px" class="mt-4 w-full max-w-xl" />
          </div>
        </div>
      </li>
    {/each}
  </ul>
{:else}
  <ul class="m-0 list-none space-y-3 p-0">
    {#each books as book}
      {@const progress = getSafeProgressPercentage(book)}
      <li class="content-auto [contain-intrinsic-size:auto_128px]">
        <article
          class="group/row flex flex-col gap-4 rounded-(--radius-lg) border border-(--color-edge) bg-(--color-home-card) p-3 pr-4 transition-colors duration-(--duration-fast) ease-(--ease-smooth) hover:border-(--color-edge-strong) md:flex-row md:items-center"
        >
          <div class="flex items-center gap-5 md:min-w-0 md:flex-1">
            <button
              type="button"
              class="h-24 w-16 shrink-0 overflow-hidden rounded-(--radius-sm) border border-(--color-edge) bg-(--color-surface-subtle) shadow-[0_8px_18px_-10px_rgba(0,0,0,0.7)] {focusRing}"
              aria-label={`${t('shelf.openBook')}: ${book.title}`}
              onclick={() => onOpenBook?.(book)}
            >
              <SafeCover
                path={book.coverPath ?? ''}
                alt={`${t('library.cover')} ${book.title}`}
                className="h-full w-full object-cover"
              >
                {#snippet fallback()}
                  <div
                    class="flex h-full w-full items-center justify-center bg-linear-to-b from-(--color-surface-subtle) to-(--color-horizon-fade) px-1.5 text-center text-micro uppercase tracking-[0.12em] text-(--color-text-muted)"
                  >
                    {t('shelf.noCover')}
                  </div>
                {/snippet}
              </SafeCover>
            </button>

            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2.5">
                <h2 class="line-clamp-1 text-base font-semibold text-(--color-primary)">
                  {book.title}
                </h2>
                <span
                  class="rounded-full border border-(--color-edge) px-2 py-0.5 text-micro font-semibold uppercase tracking-[0.12em] text-(--color-text-muted)"
                >
                  {getStateLabel(book)}
                </span>
              </div>
              <p class="mt-0.5 text-sm text-(--color-text-muted)">
                {book.author || t('shelf.unknownAuthor')}
              </p>

              <div
                class="mt-3 max-w-xl space-y-1.5"
                role="progressbar"
                aria-label={t('shelf.progressAria', { title: book.title })}
                aria-valuenow={progress}
                aria-valuemin="0"
                aria-valuemax="100"
              >
                <div class="h-1.5 w-full overflow-hidden rounded-full bg-(--color-edge)">
                  <div
                    class="h-full rounded-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand)"
                    style={`width: ${formatPercent(book)};`}
                  ></div>
                </div>
                <div
                  class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-(--color-text-muted) tabular-nums"
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

          <div class="flex items-center gap-2 md:justify-end">
            <button
              type="button"
              class="inline-flex h-10 items-center gap-2 rounded-full px-5 text-sm font-semibold whitespace-nowrap transition-[filter,background-color,border-color,color] duration-(--duration-fast) ease-(--ease-smooth) active:scale-96 {focusRing} {progress >
              0
                ? 'bg-linear-to-r from-(--color-brand-start) to-(--color-brand) text-(--color-brand-on) hover:brightness-110'
                : 'border border-(--color-edge) bg-(--color-surface-subtle) text-(--color-primary) hover:border-(--color-brand)/60 hover:text-(--color-brand-text)'}"
              onclick={() => onContinueReading?.(book)}
            >
              <Play size={13} strokeWidth={2} fill="currentColor" aria-hidden="true" />
              {progress > 0 ? t('shelf.continueReading') : t('shelf.startReading')}
            </button>
            <ShelfBookActions
              {book}
              {t}
              {onOpenBook}
              {onToggleFavorite}
              {onStatusChange}
              {onViewDetails}
              {onEdit}
              {onRemoveBook}
              variant="list"
            />
          </div>
        </article>
      </li>
    {/each}
  </ul>
{/if}
