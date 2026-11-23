<script lang="ts">
  import Skeleton from '$lib/shared/ui/feedback/Skeleton.svelte';
  import BookOpen from 'lucide-svelte/icons/book-open';
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

  // Covers lead: the columns size to a book, not to the window.
  const gridClass =
    'm-0 grid list-none grid-cols-[repeat(auto-fill,minmax(10.5rem,1fr))] gap-x-6 gap-y-9 p-0';

  const focusRing =
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) focus-visible:ring-offset-2 focus-visible:ring-offset-(--color-background)';
</script>

{#if isLoading}
  <ul class={gridClass} aria-hidden="true">
    {#each Array(10) as _, index (index)}
      <li class="content-auto [contain-intrinsic-size:auto_360px]">
        <Skeleton variant="rounded" width="100%" class="aspect-[2/3] h-auto" />
        <Skeleton variant="text" class="mt-3 w-4/5" />
        <Skeleton variant="text" class="mt-1.5 w-1/2" />
        <Skeleton variant="rounded" height="36px" class="mt-3 rounded-full" />
      </li>
    {/each}
  </ul>
{:else}
  <ul class={gridClass}>
    {#each books as book}
      {@const progress = getSafeProgressPercentage(book)}
      <li class="flex flex-col content-auto [contain-intrinsic-size:auto_360px]">
        <article class="group/book flex flex-1 flex-col">
          <div
            class="relative aspect-[2/3] w-full overflow-hidden rounded-(--radius-md) border border-(--color-edge) bg-(--color-surface-subtle) shadow-[0_16px_32px_-20px_rgba(0,0,0,0.75)] transition-[transform,box-shadow] duration-(--duration-normal) ease-(--ease-smooth) group-hover/book:-translate-y-1 group-hover/book:shadow-[0_22px_40px_-20px_rgba(0,0,0,0.8)]"
          >
            <button
              type="button"
              class="block h-full w-full rounded-(--radius-md) {focusRing}"
              aria-label={`${t('shelf.openBook')}: ${book.title}`}
              onclick={() => onOpenBook?.(book)}
            >
              <SafeCover
                path={book.coverPath ?? ''}
                alt={`Portada de ${book.title}`}
                className="h-full w-full object-cover"
              >
                {#snippet fallback()}
                  <div
                    class="flex h-full w-full flex-col items-center justify-center gap-3 bg-linear-to-b from-(--color-surface-subtle) to-(--color-horizon-fade) px-5 text-center"
                  >
                    <BookOpen
                      size={28}
                      strokeWidth={1.5}
                      class="text-(--color-text-muted)"
                      aria-hidden="true"
                    />
                    <span class="text-micro uppercase tracking-[0.16em] text-(--color-text-muted)">
                      {t('shelf.noCover')}
                    </span>
                  </div>
                {/snippet}
              </SafeCover>
            </button>

            <span
              class="pointer-events-none absolute top-2 left-2 rounded-full border border-(--color-edge) bg-(--color-horizon-fade)/85 px-2 py-0.5 text-micro font-semibold uppercase tracking-[0.12em] text-(--color-secondary) backdrop-blur-sm"
            >
              {getStateLabel(book)}
            </span>

            <div class="absolute top-2 right-2">
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

            {#if progress > 0}
              <div
                class="pointer-events-none absolute inset-x-0 bottom-0 h-1 bg-(--color-horizon-fade)/70"
                role="progressbar"
                aria-label={t('shelf.progressAria', { title: book.title })}
                aria-valuenow={progress}
                aria-valuemin="0"
                aria-valuemax="100"
              >
                <div
                  class="h-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand)"
                  style={`width: ${formatPercent(book)};`}
                ></div>
              </div>
            {/if}
          </div>

          <div class="mt-3 mb-3 min-w-0">
            <h2
              class="line-clamp-2 min-h-[2.75em] text-sm leading-snug font-semibold text-(--color-primary)"
            >
              {book.title}
            </h2>
            <p class="mt-0.5 line-clamp-1 text-xs text-(--color-text-muted)">
              {book.author || t('shelf.unknownAuthor')}
            </p>
            <p class="mt-1.5 text-xs text-(--color-text-muted) tabular-nums">
              {t('shelf.percentRead', { percent: formatPercent(book) })}
              <span aria-hidden="true">·</span>
              {book.minutesRead}
              {t('library.min')}
            </p>
          </div>

          <button
            type="button"
            class="mt-auto inline-flex h-9 w-full items-center justify-center gap-2 rounded-full text-sm font-semibold whitespace-nowrap transition-[filter,background-color,border-color,color] duration-(--duration-fast) ease-(--ease-smooth) active:scale-96 {focusRing} {progress >
            0
              ? 'bg-linear-to-r from-(--color-brand-start) to-(--color-brand) text-(--color-brand-on) hover:brightness-110'
              : 'border border-(--color-edge) bg-(--color-home-card) text-(--color-primary) hover:border-(--color-brand)/60 hover:text-(--color-brand-text)'}"
            onclick={() => onContinueReading?.(book)}
          >
            <Play size={13} strokeWidth={2} fill="currentColor" aria-hidden="true" />
            {progress > 0 ? t('app.continue') : t('shelf.start')}
          </button>
        </article>
      </li>
    {/each}
  </ul>
{/if}
