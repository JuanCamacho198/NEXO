<script lang="ts">
  import { discoverState, filterBooksByChip } from './DiscoverDomainState.svelte';
  import { discoverErrorKey } from './discoverErrorCopy';
  import { DISCOVER_RAIL_LIMIT, RAIL_SCOPE_LIMIT } from './railPlan';
  import type { DiscoverRailState } from './DiscoverRailsDomainState.svelte';
  import type { CatalogBook, CatalogErrorCode } from '$lib/shared/services/catalog';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import type { ReaderBook } from '$lib/shared/types';
  import { openableLibraryBook } from './discoverLibraryMatch';
  import DiscoverCard from './DiscoverCard.svelte';
  import DiscoverDetail from './DiscoverDetail.svelte';
  import DiscoverHero from './DiscoverHero.svelte';
  import DiscoverOfflineState from './DiscoverOfflineState.svelte';
  import DiscoverRailSection from './DiscoverRailSection.svelte';
  import DiscoverSkeletonCard from './DiscoverSkeletonCard.svelte';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  /** Read model of the rail-scoped browse view (`DiscoverRailState` re-exported). */
  type ScopeView = {
    titleKey: MessageKey;
    kind: 'term' | 'featured';
    books: CatalogBook[];
    error: CatalogErrorCode | null;
  };

  let { t, onOpenBook }: { t: Translate; onOpenBook?: (book: ReaderBook) => void } = $props();

  /** Live source count for the hero pill (`En línea · N fuentes`). */
  let sourceCount = $state(0);
  /** Selected trending chip; filters loaded rails client-side only (no catalog call). */
  let selectedChip = $state<string | null>(null);

  /**
   * Library row matching the open catalog detail, if any. Discover imports
   * reuse the catalog id as the library id, so the match is exact. A row with
   * no usable file path (the file vanished) does not count, so the Download CTA
   * stays available instead of offering "Open book" on a missing file. Reacts to
   * both the open detail and `libraryState.books`, so the CTA flips to the
   * in-library state as soon as a post-import refresh lands.
   */
  const libraryBook = $derived(
    discoverState.detail
      ? openableLibraryBook(libraryState.getBookById(discoverState.detail.id))
      : null,
  );

  function openLibraryBook(): void {
    if (libraryBook) onOpenBook?.(libraryBook);
  }

  async function loadRails(): Promise<void> {
    await discoverState.ensureRailsLoaded();
    sourceCount = discoverState.refreshSources().length;
  }

  // Browse-first load: every rail resolves through an explicit featured ordering
  // or a non-empty term search, and each rail fails without affecting the others.
  $effect(() => {
    void loadRails();
  });

  // Search pagination and term-scope paging follow the main-content scroller
  // (this screen owns no internal scroll container — `#main-content` is the
  // single scroller).
  $effect(() => {
    const scroller = document.getElementById('main-content');
    if (!scroller) return;
    const onScroll = (): void => {
      if (scroller.scrollTop + scroller.clientHeight < scroller.scrollHeight - 240) return;
      const scope = discoverState.railsState.scope;
      if (scope && scope.kind === 'term') {
        void discoverState.loadRailScopeNextPage();
        return;
      }
      if (discoverState.query.trim() === '') return;
      void discoverState.loadNextPage();
    };
    scroller.addEventListener('scroll', onScroll);
    return () => scroller.removeEventListener('scroll', onScroll);
  });

  /** Search branch is active once a query produced non-idle search state. */
  const searchActive = $derived(
    discoverState.query.trim() !== '' && discoverState.status !== 'idle',
  );

  const showSearchGrid = $derived(
    searchActive && (discoverState.status === 'loaded' || discoverState.status === 'loadingMore'),
  );

  /** i18n titles parallel to the index-stable rail plan (en + es resolve here). */
  const railTitles = $derived(discoverState.railsState.specs.map((spec) => t(spec.titleKey)));

  /**
   * Per-rail render is never gated on an all-rails-settled condition: each index
   * hands its own state to `DiscoverRailSection`, so a fast rail renders while a
   * slow one is still `Loading` and rail order never changes. Chip filtering is
   * pure `$derived` work over already-loaded books — zero catalog calls.
   */
  function railBooks(rail: DiscoverRailState): CatalogBook[] {
    return rail.kind === 'Loaded' ? filterBooksByChip(rail.books, selectedChip) : [];
  }

  /** "Ver todo": scope the browse view to this rail's term or featured ordering. */
  function openRailScope(index: number): void {
    const spec = discoverState.railsState.specs[index];
    if (!spec) return;
    void discoverState.openRailScope(
      spec.kind === 'featured'
        ? { kind: 'featured', sort: spec.sort, titleKey: spec.titleKey }
        : { kind: 'term', term: spec.term, titleKey: spec.titleKey },
    );
  }

  const scopeView = $derived.by<ScopeView | null>(() => {
    const scope = discoverState.railsState.scope;
    if (!scope) return null;
    return {
      titleKey: scope.titleKey,
      kind: scope.kind,
      books: discoverState.railsState.scopeBooks,
      error: discoverState.railsState.scopeError,
    };
  });

  const showOffline = $derived(
    !discoverState.isOnline && discoverState.rails.every((rail) => rail.kind !== 'Loaded'),
  );

  function submitSearch(query: string): void {
    // A search supersedes the scoped view; hero/search/chips behavior is unchanged.
    discoverState.closeRailScope();
    discoverState.setQuery(query);
    void discoverState.searchFirstPage();
  }

  function retryRails(): void {
    void discoverState.refreshRails();
  }
</script>

<!--
  Flow layout (`h-auto`, no `overflow-y-auto`): the AppRouter `#main-content`
  is the single scroller for the rail page; titlebar/sidebar stay fixed.
-->
<section aria-labelledby="discover-heading" class="flex h-auto flex-col gap-6">
  <DiscoverHero
    {t}
    {sourceCount}
    isOnline={discoverState.isOnline}
    {selectedChip}
    onSelectChip={(chip) => (selectedChip = chip)}
    onSearchSubmit={submitSearch}
  />

  {#if discoverState.detailStatus !== 'closed'}
    <DiscoverDetail
      detail={discoverState.detail}
      detailStatus={discoverState.detailStatus}
      downloadState={discoverState.downloadState}
      downloadError={discoverState.downloadError}
      progressBytes={discoverState.progressBytes}
      progressTotal={discoverState.progressTotal}
      {t}
      onDismiss={() => discoverState.dismissDetail()}
      onDownload={() => void discoverState.startDownload()}
      onCancelDownload={() => discoverState.cancelDownload()}
      onRetryDownload={() => void discoverState.retryDownload()}
      onRetryDetail={() => void discoverState.retryDetail()}
      inLibrary={libraryBook !== null}
      onOpenBook={openLibraryBook}
    />
  {/if}

  {#if searchActive}
    <div class="flex flex-col gap-3">
      <h2 class="m-0 text-lg font-semibold text-(--color-primary)">
        {t('discover.searchResults')}
      </h2>
      {#if discoverState.status === 'loading'}
        <div
          class="grid gap-3"
          style="grid-template-columns: repeat(auto-fill, minmax(160px, 1fr))"
        >
          {#each Array.from({ length: DISCOVER_RAIL_LIMIT }, (_, i) => i) as i (i)}
            <DiscoverSkeletonCard />
          {/each}
        </div>
      {:else if discoverState.status === 'empty'}
        <div class="flex max-w-2xl flex-col items-start gap-2">
          <p class="m-0 text-sm text-(--color-text-muted)">{t('discover.empty')}</p>
          <button
            type="button"
            class="rounded-md border border-(--color-primary)/25 bg-(--color-primary)/8 px-3 py-1.5 text-sm font-medium text-(--color-primary) transition-colors hover:bg-(--color-primary)/15"
            onclick={() => navigationState.navigateToAddons()}
          >
            {t('discover.manageAddons')}
          </button>
        </div>
      {:else if discoverState.status === 'offline' || discoverState.status === 'error'}
        <div class="flex max-w-2xl flex-col items-start gap-2">
          <p class="text-sm text-(--color-text-muted)">
            {#if discoverState.errorCode === 'INVALID_PAGE'}{t('discover.errorInvalidPage')}
            {:else if discoverState.errorCode === 'NOT_FOUND'}{t('discover.errorNotFound')}
            {:else}{t(discoverErrorKey(discoverState.errorCode))}{/if}
          </p>
          <button
            type="button"
            class="rounded-md border border-(--color-primary)/25 bg-(--color-primary)/8 px-3 py-1.5 text-sm font-medium text-(--color-primary) transition-colors hover:bg-(--color-primary)/15"
            onclick={() => void discoverState.retry()}
          >
            {t('discover.retry')}
          </button>
        </div>
      {:else if showSearchGrid}
        <div
          class="grid gap-3"
          style="grid-template-columns: repeat(auto-fill, minmax(160px, 1fr))"
        >
          {#each discoverState.books as book (book.id)}
            <DiscoverCard
              {book}
              onOpen={(book) => void discoverState.openDetail(book)}
              onPrefetch={(book) => discoverState.prefetchDetail(book)}
            />
          {/each}
        </div>
        {#if discoverState.status === 'loadingMore'}
          <p class="mt-3 max-w-2xl text-xs text-(--color-text-muted)">
            {t('discover.loadingMore')}
          </p>
        {:else if discoverState.nextPage === null}
          <p class="mt-3 max-w-2xl text-xs text-(--color-text-muted)">
            {t('discover.endOfResults')}
          </p>
        {/if}
      {/if}
    </div>
  {:else if scopeView}
    <div class="flex flex-col gap-3">
      <div class="flex items-baseline justify-between gap-3">
        <h2 class="m-0 text-lg font-semibold text-(--color-primary)">{t(scopeView.titleKey)}</h2>
        <button
          type="button"
          class="rounded-md px-2 py-1 text-sm font-medium text-(--color-primary) transition-colors hover:bg-(--color-primary)/10"
          onclick={() => discoverState.closeRailScope()}
        >
          {t('discover.railScope.back')}
        </button>
      </div>
      {#if scopeView.error}
        <div class="flex max-w-2xl flex-col items-start gap-2">
          <p class="m-0 text-sm text-(--color-text-muted)">
            {t(discoverErrorKey(scopeView.error))}
          </p>
          <button
            type="button"
            class="rounded-md border border-(--color-primary)/25 bg-(--color-primary)/8 px-3 py-1.5 text-sm font-medium text-(--color-primary) transition-colors hover:bg-(--color-primary)/15"
            onclick={() => void discoverState.loadRailScopeNextPage()}
          >
            {t('discover.retry')}
          </button>
        </div>
      {:else if scopeView.books.length === 0}
        <p class="max-w-2xl text-sm text-(--color-text-muted)">{t('discover.empty')}</p>
      {:else}
        <div
          class="grid gap-3"
          style="grid-template-columns: repeat(auto-fill, minmax(160px, 1fr))"
        >
          {#each scopeView.books as book (book.id)}
            <DiscoverCard
              {book}
              onOpen={(book) => void discoverState.openDetail(book)}
              onPrefetch={(book) => discoverState.prefetchDetail(book)}
            />
          {/each}
        </div>
        {#if scopeView.kind === 'featured'}
          <p class="mt-3 text-xs text-(--color-text-muted)">
            {t('discover.railScope.singlePage', { count: RAIL_SCOPE_LIMIT })}
          </p>
        {/if}
      {/if}
    </div>
  {:else if showOffline}
    <DiscoverOfflineState {t} onRetry={retryRails} />
  {:else}
    {#each discoverState.rails as rail, index (index)}
      <DiscoverRailSection
        title={railTitles[index] ?? ''}
        state={rail}
        books={railBooks(rail)}
        {t}
        onOpen={(book) => void discoverState.openDetail(book)}
        onPrefetch={(book) => discoverState.prefetchDetail(book)}
        onRetry={() => void discoverState.retryRail(index)}
        onViewAll={() => openRailScope(index)}
      />
    {/each}
  {/if}
</section>
