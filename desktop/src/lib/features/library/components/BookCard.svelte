<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { LibraryBookDto } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  import { getSafeProgressPercentage } from '$lib/shared/stores/HomeState';
  import SafeCover from './SafeCover.svelte';
  import { Button } from '$lib/shared/ui';

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
  const showProgress = $derived(
    variant === 'continue-reading' || book.readingStatus === 'completed',
  );
  // The Continue Reading variant consumes the dedicated carousel key
  // (identical copy, AD-5); the shelf keeps its own label.
  const progressLabel = $derived(
    variant === 'continue-reading' ? t('home.continue.progress') : t('home.shelfSort.progress'),
  );
  // The read button reads "Continue" in the Continue Reading context
  // (resuming an in-progress book) and "Read" elsewhere (starting fresh).
  const readLabel = $derived(variant === 'continue-reading' ? t('app.continue') : t('app.read'));

  const containerClass = $derived.by(() => {
    if (variant === 'continue-reading') {
      // Borderless inside the outer Home card — single-box look, no double border
      const selectedClass = selected
        ? 'bg-[color:color-mix(in_srgb,var(--color-primary)_6%,transparent)] rounded-xl'
        : 'bg-transparent';
      return `border-0 bg-transparent p-0 shadow-none ${selectedClass}`;
    }
    const selectedClass = selected
      ? 'border-(--color-primary) bg-[color:color-mix(in_srgb,var(--color-primary)_10%,var(--color-surface))]'
      : 'border-(--color-border) bg-(--color-background)';
    const base = compact ? 'rounded-lg border p-3' : 'rounded-xl border p-4';
    return `${base} ${selectedClass}`;
  });
</script>

<article
  class={`${containerClass} transition-all duration-200`}
  aria-label={`${book.title}, ${book.author || t('app.unknownAuthor')}, ${progress}%`}
>
  <div
    class={variant === 'continue-reading'
      ? 'flex flex-col gap-3'
      : 'flex items-start justify-between gap-3'}
  >
    <button type="button" class="min-w-0 flex-1 text-left" onclick={onSelect}>
      <div class="flex items-start gap-4">
        <SafeCover
          path={book.coverPath ?? ''}
          alt={`Cover for ${book.title}`}
          className={variant === 'continue-reading'
            ? compact
              ? 'h-32 w-[108px] rounded-lg object-cover shadow-md'
              : 'h-36 w-[120px] rounded-lg object-cover shadow-md'
            : compact
              ? 'h-14 w-10 rounded object-cover shadow-sm'
              : 'h-16 w-12 rounded object-cover shadow-sm'}
        >
          {#snippet fallback()}
            <div
              class={`${
                variant === 'continue-reading'
                  ? compact
                    ? 'h-32 w-[108px]'
                    : 'h-36 w-[120px]'
                  : compact
                    ? 'h-14 w-10'
                    : 'h-16 w-12'
              } flex items-center justify-center rounded-lg bg-(--color-surface) text-micro uppercase tracking-widest text-(--color-text-muted)`}
            >
              {t('library.cover')}
            </div>
          {/snippet}
        </SafeCover>

        <div class="min-w-0 flex-1 space-y-1">
          <p
            class={`${
              variant === 'continue-reading'
                ? compact
                  ? 'text-[15px]'
                  : 'text-[18px]'
                : compact
                  ? 'text-sm'
                  : 'text-base'
            } font-semibold leading-tight text-(--color-primary) line-clamp-2`}
          >
            {book.title}
          </p>
          <p class="text-xs leading-snug text-(--color-text-muted)">
            {book.author || t('app.unknownAuthor')}
          </p>
          <p class="text-[10px] font-medium uppercase tracking-widest text-(--color-text-muted)">
            {book.format.toUpperCase()}
          </p>
          {#if showProgress}
            <div class="mt-2">
              <div class="mb-1 flex items-center gap-1 text-2xs text-(--color-text-muted)">
                <span>{progressLabel} {progress}%</span>
              </div>
              <div class="h-1.5 w-full overflow-hidden rounded bg-(--color-border)">
                <div
                  class="h-full rounded bg-(--color-accent)"
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
      class={variant === 'continue-reading'
        ? 'flex items-center gap-2 pt-1'
        : 'flex shrink-0 items-start gap-2'}
    >
      {#if showReadButton}
        <Button
          size="sm"
          variant={variant === 'continue-reading' ? 'accent' : 'primary'}
          class="shrink-0 whitespace-nowrap"
          onclick={onRead}
        >
          {readLabel}
        </Button>
      {/if}
      {@render actions?.()}
    </div>
  </div>
</article>
