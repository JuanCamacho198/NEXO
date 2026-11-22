<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { LibraryBookDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  import { getSafeProgressPercentage } from '$lib/shared/stores/HomeState';
  import SafeCover from './SafeCover.svelte';
  import FileText from 'lucide-svelte/icons/file-text';
  import Play from 'lucide-svelte/icons/play';

  type Variant = 'shelf' | 'continue-reading';

  type Props = {
    book: LibraryBookDto;
    variant: Variant;
    selected?: boolean;
    compact?: boolean;
    showReadButton?: boolean;
    onSelect?: () => void;
    onRead?: () => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    actions?: Snippet;
  };

  let {
    book,
    variant,
    selected = false,
    compact = false,
    showReadButton = true,
    onSelect,
    onRead,
    t,
    actions,
  }: Props = $props();

  const progress = $derived(Math.round(getSafeProgressPercentage(book)));
  const isContinue = $derived(variant === 'continue-reading');
  const showProgress = $derived(isContinue || book.readingStatus === 'completed');
  // The Continue Reading variant consumes the dedicated carousel key
  // (identical copy, AD-5); the shelf keeps its own label.
  const progressLabel = $derived(
    isContinue ? t('home.continue.progress') : t('home.shelfSort.progress'),
  );
  // The read button reads "Continue" in the Continue Reading context
  // (resuming an in-progress book) and "Read" elsewhere (starting fresh).
  const readLabel = $derived(isContinue ? t('app.continue') : t('app.read'));

  const coverSize = $derived(
    isContinue ? (compact ? 'h-44 w-30' : 'h-52 w-36') : compact ? 'h-24 w-17' : 'h-28 w-20',
  );

  const containerClass = $derived.by(() => {
    if (isContinue) {
      // Borderless and unfilled: the hero is the one visible plane, and a fill
      // here would cut a box through its backdrop.
      return '';
    }
    const edge = selected
      ? 'border-(--color-brand)/60 bg-(--color-home-card)'
      : 'border-(--color-edge) bg-(--color-home-card) hover:border-(--color-edge-strong)';
    return `group/card relative isolate h-full overflow-hidden rounded-(--radius-lg) border ${
      compact ? 'p-3 pr-3.5' : 'p-4'
    } ${edge}`;
  });

  const focusRing =
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) focus-visible:ring-offset-2 focus-visible:ring-offset-(--color-background)';
</script>

<article
  class={`${containerClass} transition-[border-color,background-color] duration-(--duration-fast) ease-(--ease-smooth)`}
  aria-label={`${book.title}, ${book.author || t('app.unknownAuthor')}, ${progress}%`}
>
  {#if !isContinue}
    <!-- Warm light that rises from the card's corner on hover. -->
    <span
      class="pointer-events-none absolute inset-y-0 right-0 -z-10 w-2/3 bg-radial-[at_100%_100%] from-(--color-brand-soft) to-transparent to-70% opacity-0 transition-opacity duration-(--duration-slow) ease-(--ease-smooth) group-hover/card:opacity-100"
      class:opacity-100={selected}
      aria-hidden="true"
    ></span>
  {/if}

  <div class={isContinue ? 'flex flex-col gap-5' : 'flex items-center justify-between gap-3'}>
    <button
      type="button"
      class={`min-w-0 flex-1 rounded-(--radius-md) text-left ${focusRing}`}
      onclick={onSelect}
    >
      <div class={`flex ${isContinue ? 'items-start gap-6' : 'items-center gap-4'}`}>
        <SafeCover
          path={book.coverPath ?? ''}
          alt={`Cover for ${book.title}`}
          className={`${coverSize} shrink-0 object-cover ${
            isContinue
              ? 'rounded-(--radius-md) shadow-[0_18px_36px_-14px_rgba(0,0,0,0.7)] ring-1 ring-(--color-edge)'
              : 'rounded-(--radius-sm) shadow-[0_6px_16px_-8px_rgba(0,0,0,0.6)]'
          }`}
        >
          {#snippet fallback()}
            <div
              class={`${coverSize} flex shrink-0 items-center justify-center ${
                isContinue ? 'rounded-(--radius-md)' : 'rounded-(--radius-sm)'
              } border border-(--color-edge) bg-(--color-surface-subtle) text-micro uppercase tracking-widest text-(--color-text-muted)`}
            >
              {t('library.cover')}
            </div>
          {/snippet}
        </SafeCover>

        <div class={`min-w-0 flex-1 ${isContinue ? 'pt-1' : ''}`}>
          <p
            class={`font-semibold text-(--color-primary) ${
              isContinue
                ? `${compact ? 'text-xl' : 'text-[1.75rem]'} pr-24 leading-tight tracking-[-0.02em] text-balance line-clamp-2`
                : `${compact ? 'text-[15px]' : 'text-base'} leading-snug line-clamp-1`
            }`}
          >
            {book.title}
          </p>
          <p
            class={`mt-1 line-clamp-1 ${
              isContinue
                ? 'text-base text-(--color-secondary)'
                : 'text-sm text-(--color-text-muted)'
            }`}
          >
            {book.author || t('app.unknownAuthor')}
          </p>
          <p
            class={`flex items-center gap-1.5 text-(--color-text-muted) ${
              isContinue ? 'mt-2 text-sm' : 'mt-1.5 text-xs font-medium tracking-wide'
            }`}
          >
            {#if isContinue}
              <FileText size={15} strokeWidth={1.8} aria-hidden="true" />
            {/if}
            {book.format.toUpperCase()}
          </p>

          {#if showProgress}
            <div class={isContinue ? 'mt-7' : 'mt-2.5'}>
              <div
                class={`mb-2 flex items-center gap-1.5 ${
                  isContinue
                    ? 'text-sm text-(--color-secondary)'
                    : 'text-micro text-(--color-text-muted)'
                }`}
              >
                <span>{progressLabel} <span class="tabular-nums">{progress}%</span></span>
                {#if isContinue && book.totalPages > 0 && book.currentPage > 0}
                  <span aria-hidden="true">·</span>
                  <span class="tabular-nums text-(--color-text-muted)">
                    {t('home.continue.position', {
                      current: book.currentPage,
                      total: book.totalPages,
                    })}
                  </span>
                {/if}
              </div>
              <div
                class={`w-full overflow-hidden rounded-full bg-(--color-edge) ${isContinue ? 'h-2' : 'h-1'}`}
              >
                <div
                  class="h-full rounded-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand)"
                  style={`width:${progress}%`}
                  role="progressbar"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  aria-valuenow={progress}
                  aria-label={progressLabel}
                ></div>
              </div>
            </div>
          {/if}
        </div>
      </div>
    </button>

    <div
      class={isContinue ? 'flex items-center gap-3' : 'flex shrink-0 items-center gap-2 self-start'}
    >
      {#if showReadButton}
        {#if isContinue}
          <button
            type="button"
            class={`inline-flex h-11 shrink-0 items-center gap-2 whitespace-nowrap rounded-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand) px-6 text-sm font-semibold text-(--color-brand-on) shadow-[0_10px_24px_-10px_var(--color-brand-glow)] transition-[filter,transform,box-shadow] duration-(--duration-fast) ease-(--ease-smooth) hover:brightness-110 hover:shadow-[0_12px_28px_-8px_var(--color-brand-glow)] active:scale-96 ${focusRing}`}
            onclick={onRead}
          >
            <Play size={15} strokeWidth={2} fill="currentColor" aria-hidden="true" />
            {readLabel}
          </button>
        {:else}
          <button
            type="button"
            class={`inline-flex h-9 shrink-0 items-center whitespace-nowrap rounded-full border border-(--color-edge) bg-(--color-surface-subtle) px-4 text-sm font-medium text-(--color-primary) transition-colors duration-(--duration-fast) ease-(--ease-smooth) hover:border-(--color-brand)/60 hover:text-(--color-brand-text) ${focusRing}`}
            onclick={onRead}
          >
            {readLabel}
          </button>
        {/if}
      {/if}
      {@render actions?.()}
    </div>
  </div>
</article>
