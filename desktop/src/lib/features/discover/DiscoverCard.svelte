<script lang="ts">
  import { focusRing } from '$lib/shared/ui/emberStyles';
  import type { CatalogBook } from '$lib/shared/services/catalog';
  import { providerLabel } from './discoverProviderLabel';

  let {
    book,
    onOpen,
    onPrefetch,
  }: {
    book: CatalogBook;
    onOpen: (book: CatalogBook) => void;
    /** Optional cache warm on pointer/keyboard intent; never runs on render. */
    onPrefetch?: (book: CatalogBook) => void;
  } = $props();

  let coverFailed = $state(false);
  /**
   * A coverless book (or one whose image failed) is not dropped and not left as
   * an empty frame: the cover slot becomes a TEXT CARD — the title and author
   * set on the theme's subtle surface, so the tile reads as a deliberately
   * typographic card rather than a broken image.
   */
  const showCover = $derived(book.coverUrl !== null && book.coverUrl !== '' && !coverFailed);
  const authors = $derived(book.authors.join(', '));
</script>

<button
  type="button"
  class="group/book flex flex-col rounded-(--radius-md) text-left {focusRing}"
  onclick={() => onOpen(book)}
  onpointerenter={() => onPrefetch?.(book)}
  onfocus={() => onPrefetch?.(book)}
>
  <div
    class="relative aspect-2/3 w-full overflow-hidden rounded-(--radius-md) border border-(--color-edge) bg-(--color-surface-subtle) shadow-[0_16px_32px_-20px_rgba(0,0,0,0.75)] transition-[transform,box-shadow] duration-(--duration-normal) ease-(--ease-smooth) group-hover/book:-translate-y-1 group-hover/book:shadow-[0_22px_40px_-20px_rgba(0,0,0,0.8)]"
  >
    {#if showCover}
      <img
        src={book.coverUrl}
        alt={book.title}
        loading="lazy"
        decoding="async"
        class="h-full w-full object-cover"
        onerror={() => (coverFailed = true)}
      />
    {:else}
      <!-- Top padding clears the provider badge so it never covers the title. -->
      <div
        class="flex h-full w-full flex-col justify-between gap-2 bg-linear-to-b from-(--color-surface-subtle) to-(--color-horizon-fade) px-3 pt-10 pb-3"
        data-testid="discover-text-card"
      >
        <p class="m-0 line-clamp-5 text-sm leading-snug font-semibold text-(--color-primary)">
          {book.title}
        </p>
        <div class="flex min-w-0 flex-col gap-1.5">
          {#if authors}
            <p class="m-0 line-clamp-2 text-xs text-(--color-text-muted)">{authors}</p>
          {/if}
          <span
            class="h-0.5 w-8 shrink-0 rounded-full bg-linear-to-r from-(--color-brand-start) to-(--color-brand)"
            aria-hidden="true"
          ></span>
        </div>
      </div>
    {/if}
    <span
      class="absolute top-2 left-2 rounded-full border border-(--color-edge) bg-(--color-horizon-fade)/85 px-2 py-0.5 text-micro font-semibold uppercase tracking-[0.12em] text-(--color-secondary) backdrop-blur-sm"
    >
      {providerLabel(book.provider)}
    </span>
  </div>
  <div class="mt-3 min-w-0">
    <p class="line-clamp-2 text-sm leading-snug font-semibold text-(--color-primary)">
      {book.title}
    </p>
    <p class="mt-0.5 truncate text-xs text-(--color-text-muted)">
      {authors}
    </p>
  </div>
</button>
