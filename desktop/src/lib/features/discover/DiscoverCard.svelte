<script lang="ts">
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
  class="group flex flex-col gap-2 rounded-lg border border-(--color-border) bg-(--color-surface-subtle) p-2 text-left transition-colors hover:border-(--color-primary)/40 focus:outline-none focus-visible:ring-2 focus-visible:ring-(--color-primary)/50"
  onclick={() => onOpen(book)}
  onpointerenter={() => onPrefetch?.(book)}
  onfocus={() => onPrefetch?.(book)}
>
  <div
    class="relative aspect-2/3 w-full overflow-hidden rounded-md bg-gradient-to-br from-(--color-primary)/8 to-(--color-primary)/3"
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
      <div
        class="flex h-full w-full flex-col justify-between gap-2 border border-(--color-border)/60 bg-(--color-surface) p-3"
        data-testid="discover-text-card"
      >
        <p class="m-0 line-clamp-4 text-sm font-semibold text-(--color-primary)">
          {book.title}
        </p>
        <div class="flex min-w-0 flex-col gap-1">
          {#if authors}
            <p class="m-0 line-clamp-2 text-xs text-(--color-text-muted)">{authors}</p>
          {/if}
          <span class="h-0.5 w-8 shrink-0 rounded-full bg-(--color-primary)/40" aria-hidden="true"
          ></span>
        </div>
      </div>
    {/if}
    <span
      class="absolute top-2 left-2 rounded-full border border-(--color-border) bg-(--color-background)/95 px-2 py-0.5 text-micro font-medium uppercase tracking-wider text-(--color-secondary)"
    >
      {providerLabel(book.provider)}
    </span>
  </div>
  <div class="min-w-0">
    <p class="truncate text-sm font-medium text-(--color-primary)">{book.title}</p>
    <p class="truncate text-xs text-(--color-text-muted)">
      {authors}
    </p>
  </div>
</button>
