<script lang="ts">
  import FilterChip from '$lib/shared/ui/forms/FilterChip.svelte';
  import { headingMarker, panelClass } from '$lib/shared/ui/emberStyles';
  import { onMount } from 'svelte';
  import type { HighlightDto } from '$lib/shared/types';
  import type { ViewerPort } from '$lib/shared/ports';
  import { TauriViewerAdapter } from '$lib/shared/ports';
  import { createHighlightsViewDeps, type HighlightsViewDeps } from '../highlightsViewDeps';
  import { createHighlightsFilters } from '../useHighlightsFilters.svelte';
  import { createHighlightsSync } from '../useHighlightsSync.svelte';
  import { isRealBook } from '$lib/features/library/utils';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import { searchState } from '$lib/shared/stores/SearchDomainState.svelte';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
  import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import SafeCover from '$lib/features/library/components/SafeCover.svelte';
  import Pagination from '$lib/shared/ui/navigation/Pagination.svelte';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import DropMenu from '$lib/shared/ui/navigation/DropMenu.svelte';
  import Book from 'lucide-svelte/icons/book';
  import CircleCheck from 'lucide-svelte/icons/circle-check';
  import Copy from 'lucide-svelte/icons/copy';
  import EllipsisVertical from 'lucide-svelte/icons/ellipsis-vertical';
  import LoaderCircle from 'lucide-svelte/icons/loader-circle';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import Search from 'lucide-svelte/icons/search';
  import SquarePen from 'lucide-svelte/icons/square-pen';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import EmptyState from '$lib/shared/ui/feedback/EmptyState.svelte';
  import Skeleton from '$lib/shared/ui/feedback/Skeleton.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import { HIGHLIGHT_COLORS, formatDate, resolveHighlightHex, type Props } from '../state.svelte';
  import { hasEditableContext } from '$lib/features/reader/viewer-epub/keyboardNav';
  let { books, t, viewerPort: viewerPortProp, deps: depsProp }: Props = $props();
  const viewerPort: ViewerPort = $derived(viewerPortProp ?? new TauriViewerAdapter());
  // svelte-ignore state_referenced_locally
  const deps: HighlightsViewDeps = depsProp ?? createHighlightsViewDeps(viewerPort);
  let highlights = $state<HighlightDto[]>([]);
  let isLoading = $state(true);
  // A highlight is only reachable when its book is present and real. Stub books
  // (empty file_path) have no file to open, so their highlights never render and
  // must not drive the empty states or the book filter.
  const reachableHighlights = $derived(
    highlights.filter((h) => isRealBook(books.find((b) => b.id === h.bookId))),
  );
  const totalHighlights = $derived(reachableHighlights.length);
  const filters = createHighlightsFilters({
    getHighlights: () => highlights,
    getBooks: () => books,
    deps,
  });
  const sync = createHighlightsSync({
    deps,
    getHighlights: () => highlights,
    setHighlights: (v) => (highlights = v),
  });
  const bookMap = $derived(new Map(books.map((b) => [b.id, b])));
  const uniqueBooks = $derived.by(() => {
    const ids = new Set(reachableHighlights.map((h) => h.bookId));
    return books.filter((b) => ids.has(b.id));
  });
  const bookFilterOptions = $derived([
    { value: '', label: t('home.highlightsAllBooks') },
    ...uniqueBooks.map((b) => ({ value: b.id, label: b.title })),
  ]);
  const dateFilterOptions: Array<{ value: string; label: string }> = $derived([
    { value: '', label: t('home.highlightsAllDates') },
    { value: '7d', label: t('home.highlightsLastWeek') },
    { value: '30d', label: t('home.highlightsLastMonth') },
    { value: '90d', label: t('home.highlightsLast3Months') },
  ]);
  const typeTabs: Array<{
    value: typeof filters.selectedType;
    labelKey: import('$lib/shared/i18n').MessageKey;
  }> = [
    { value: 'all', labelKey: 'home.highlightsTypeAll' },
    { value: 'quotes', labelKey: 'home.highlightsTypeQuotes' },
    { value: 'ideas', labelKey: 'home.highlightsTypeIdeas' },
    { value: 'passages', labelKey: 'home.highlightsTypePassages' },
  ];
  const tagFilterOptions = $derived([
    { value: '', label: t('home.highlightsAllTags') },
    ...filters.allTags.map((tag) => ({ value: tag.id, label: tag.name })),
  ]);
  function sortByUpdatedAtDesc(list: HighlightDto[]): HighlightDto[] {
    return [...list].sort(
      (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
    );
  }
  async function loadHighlights(): Promise<void> {
    isLoading = true;
    try {
      const raw = await deps.listHighlights();
      highlights = sortByUpdatedAtDesc(raw);
    } catch {
      highlights = [];
    } finally {
      isLoading = false;
    }
  }
  async function handleViewInBook(highlight: HighlightDto): Promise<void> {
    const book = libraryState.getBookById(highlight.bookId);
    if (!book) return;
    libraryState.promoteBookForReading(book.id);
    readerState.activeReadingBookId = book.id;
    navigationState.shelfDetailsBookId = null;
    navigationState.route = 'reader';
    searchState.resetSearch();
    libraryState.recordReaderOpenMetric(book.format);
    await readerState.startReading(book);
    void statsState.loadStats(book.id, authState.userId ?? '');
    searchState.searchTargetLocator = highlight.cfi
      ? highlight.cfi
      : book.format.toLowerCase() === 'pdf'
        ? `page:${highlight.pageNumber}`
        : null;
  }
  function handleCopy(text: string): void {
    navigator.clipboard.writeText(text);
  }
  const handleKeydown = (e: KeyboardEvent): void => {
    if (e.key !== '/') return;
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (hasEditableContext(e.target as Element | null)) return;
    e.preventDefault();
    document.getElementById('highlights-search')?.focus();
  };
  onMount(() => {
    void (async () => {
      await loadHighlights();
      await filters.loadTagsAndMap();
    })();
    void sync.syncHighlightsInBackground();
    window.addEventListener('keydown', handleKeydown);
    const onHighlightsChanged = (): void => {
      void (async () => {
        try {
          const fresh = await deps.listHighlights();
          highlights = sortByUpdatedAtDesc(fresh);
          await filters.loadTagsAndMap();
        } catch {}
      })();
    };
    window.addEventListener('highlights:changed', onHighlightsChanged as EventListener);
    return () => {
      window.removeEventListener('keydown', handleKeydown);
      window.removeEventListener('highlights:changed', onHighlightsChanged as EventListener);
      sync.cleanup();
    };
  });
</script>

<section class="max-w-full">
  <header class="mb-6">
    <div class="flex items-start justify-between gap-4">
      <div class="flex-1 min-w-0">
        <div class="flex items-center gap-3">
          <div class="h-8 {headingMarker}" aria-hidden="true"></div>
          <h1 class="m-0 text-[1.75rem] font-bold tracking-[-0.02em] text-(--color-primary)">
            {t('home.highlightsTitle')}
          </h1>
        </div>
        <p class="m-0 mt-1 text-sm text-(--color-text-muted)">{t('home.highlightsSubtitle')}</p>
      </div>
      <button
        type="button"
        class="flex size-10 shrink-0 cursor-pointer items-center justify-center rounded-full border border-(--color-edge) bg-(--color-home-card) text-(--color-text-muted) transition-colors hover:border-(--color-brand)/60 hover:text-(--color-brand-text) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) disabled:cursor-not-allowed disabled:opacity-50"
        aria-label={t('home.highlightsRefresh')}
        title={t('home.highlightsRefresh')}
        disabled={sync.syncState === 'syncing'}
        onclick={() => void sync.syncHighlightsInBackground(true)}
      >
        {#if sync.syncState === 'syncing'}
          <LoaderCircle size={20} strokeWidth={1.8} class="animate-spin" aria-hidden="true" />
        {:else}
          <RefreshCw size={20} strokeWidth={1.8} aria-hidden="true" />
        {/if}
      </button>
    </div>
    {#if sync.syncState === 'syncing' || sync.syncState === 'synced'}<div
        class="mt-3 flex justify-center"
      >
        <span
          class="inline-flex items-center gap-1.5 rounded-full border border-(--color-edge) bg-(--color-home-card) px-3 py-1 text-xs font-medium text-(--color-text-muted)"
          >{#if sync.syncState === 'syncing'}<LoaderCircle
              size={14}
              strokeWidth={1.8}
              class="animate-spin"
              aria-hidden="true"
            />{t('home.highlightsSyncing')}{:else}<CircleCheck
              size={14}
              strokeWidth={1.8}
              class="text-(--color-success)"
              aria-hidden="true"
            />{t('home.highlightsSynced')}{/if}</span
        >
        >
      </div>{/if}
  </header>
  <div class="flex flex-wrap items-center gap-2 mb-4">
    {#each typeTabs as tab}<FilterChip
        selected={filters.selectedType === tab.value}
        onclick={() => (filters.selectedType = tab.value)}>{t(tab.labelKey)}</FilterChip
      >{/each}
  </div>
  <div class="relative flex items-center mb-5">
    <Search
      size={20}
      strokeWidth={1.8}
      class="pointer-events-none absolute left-4 z-0 text-(--color-text-muted)"
      aria-hidden="true"
    /><input
      id="highlights-search"
      type="text"
      class="h-11 w-full rounded-full border border-(--color-edge) bg-(--color-home-card) pr-20 pl-12 font-sans text-sm text-(--color-primary) transition-colors placeholder:text-(--color-text-muted) hover:border-(--color-edge-strong) focus:border-(--color-brand)/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)/40"
      placeholder={t('home.highlightsSearchPlaceholder')}
      bind:value={filters.searchQuery}
    /><kbd
      class="pointer-events-none absolute right-3 inline-flex items-center gap-1 rounded-full border border-(--color-edge) bg-(--color-surface-subtle) px-2 py-0.5 font-sans text-[0.7rem] text-(--color-text-muted)"
      aria-hidden="true">{t('library.searchShortcut')}</kbd
    >
  </div>
  <div class="mb-4 {panelClass} px-5 py-4">
    <div class="mb-3 flex items-center justify-between gap-3">
      <h2 class="m-0 text-sm font-semibold text-(--color-primary)">
        {t('home.highlightsFiltersHeading')}
      </h2>
      <Button size="sm" variant="link" onclick={filters.clearFilters}
        >{t('home.highlightsClearFilters')}</Button
      >
    </div>
    <div class="flex flex-wrap items-center gap-x-5 gap-y-3">
      <div class="flex items-center gap-2">
        <span class="text-xs font-medium text-(--color-text-muted)"
          >{t('home.highlightsFilterColor')}</span
        >
        <div class="flex items-center gap-1">
          {#each HIGHLIGHT_COLORS as color}<button
              type="button"
              class="size-6 cursor-pointer rounded-full border-2 border-(--color-home-card) transition-transform hover:scale-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand) {filters.selectedColors.has(
                color.key,
              )
                ? 'scale-110 ring-2 ring-(--color-brand)'
                : ''}"
              style="background: {color.hex};"
              aria-label={t('highlight.selectColor', {
                color: t(`settings.color.${color.key}` as import('$lib/shared/i18n').MessageKey),
              })}
              onclick={() => filters.toggleColor(color.key)}
            ></button>{/each}<FilterChip
            class="ml-1 h-7 px-3 text-xs"
            selected={filters.selectedColors.size === 0}
            onclick={() => {
              filters.selectedColors = new Set();
            }}>{t('home.shelfTab.all')}</FilterChip
          >
        </div>
      </div>
      <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div class="flex items-center gap-2">
          <span class="text-xs font-medium text-(--color-text-muted)"
            >{t('home.highlightsFilterBook')}</span
          ><Dropdown
            options={bookFilterOptions}
            bind:value={filters.selectedBookId}
            class="min-w-[150px]"
          />
        </div>
        <div class="flex items-center gap-2">
          <span class="text-xs font-medium text-(--color-text-muted)"
            >{t('home.highlightsFilterTag')}</span
          ><Dropdown
            options={tagFilterOptions}
            bind:value={filters.selectedTagId}
            class="min-w-[130px]"
          />
        </div>
        <div class="flex items-center gap-2">
          <span class="text-xs font-medium text-(--color-text-muted)"
            >{t('home.highlightsFilterDate')}</span
          ><Dropdown
            options={dateFilterOptions}
            bind:value={filters.selectedDateRange}
            class="min-w-[140px]"
          />
        </div>
      </div>
    </div>
  </div>
  <h2 class="sr-only">{t('home.highlightsResultsHeading')}</h2>
  {#if !isLoading && totalHighlights > 0}
    <p class="text-[0.75rem] text-(--color-text-muted) m-0 mb-3">
      {t('home.highlightsShowingCount', { count: filters.filteredHighlights.length })}
    </p>
  {/if}
  {#if isLoading}<ul class="list-none p-0 m-0 flex flex-col gap-2">
      {#each Array(3) as _}<Skeleton variant="book" height="100px" />{/each}
    </ul>
  {:else if totalHighlights === 0}<div class="flex min-h-[55vh] items-center justify-center">
      <EmptyState
        icon="book"
        title={t('home.highlightsEmptyNoDataTitle')}
        description={t('home.highlightsEmptyNoDataDescription')}
      />
    </div>
  {:else if filters.filteredHighlights.length === 0}<div
      class="flex min-h-[55vh] items-center justify-center"
    >
      <EmptyState
        icon="search"
        title={t('home.highlightsEmptyTitle')}
        description={t('home.highlightsEmptyDescription')}
      >
        {#snippet action()}
          <Button size="sm" variant="ghost" onclick={filters.clearFilters}
            >{t('home.highlightsClearFilters')}</Button
          >
        {/snippet}
      </EmptyState>
    </div>
  {:else}<ul class="list-none p-0 m-0 flex flex-col gap-2">
      {#each filters.paginatedHighlights as highlight (highlight.id)}{@const book = bookMap.get(
          highlight.bookId,
        )}
        <li
          class="flex cursor-default items-stretch gap-4 rounded-(--radius-lg) border border-(--color-edge) bg-(--color-home-card) p-5 transition-colors hover:border-(--color-edge-strong)"
          style="--bar-color: {resolveHighlightHex(highlight.color)}"
        >
          <div
            class="min-h-full w-1 shrink-0 rounded-full bg-(--bar-color)"
            aria-hidden="true"
          ></div>
          <div class="flex-1 min-w-0 flex flex-col justify-center gap-1">
            <p
              class="m-0 text-[1.0625rem] leading-relaxed text-(--color-primary)"
              style="font-family: var(--font-serif)"
            >
              {highlight.text}
            </p>
            {#if highlight.note}<p
                class="m-0 flex items-start gap-1.5 text-xs text-(--color-secondary) italic"
              >
                <SquarePen size={14} strokeWidth={1.8} />{highlight.note}
              </p>{/if}
            <p class="text-[0.75rem] text-(--color-text-muted) m-0">
              {t('home.highlightsPageLabel')}
              {highlight.pageNumber}{book ? ` · ${book.title}` : ''}
            </p>
          </div>
          {#if book}<div class="flex flex-col items-center gap-1 shrink-0 w-20 text-center">
              <div
                class="flex h-16 w-12 items-center justify-center overflow-hidden rounded-(--radius-sm) border border-(--color-edge) bg-(--color-surface-subtle) shadow-[0_8px_18px_-10px_rgba(0,0,0,0.7)]"
              >
                {#if book.coverPath}<SafeCover
                    path={book.coverPath}
                    alt={book.title}
                    className="w-full h-full object-cover"
                  />{:else}<span class="text-[1.25rem] opacity-50"
                    ><Book size={20} strokeWidth={1.8} /></span
                  >{/if}
              </div>
              <p
                class="text-[0.65rem] font-semibold text-(--color-primary) m-0 max-w-20 overflow-hidden text-ellipsis whitespace-nowrap"
              >
                {book.title}
              </p>
              <p
                class="text-[0.6rem] text-(--color-text-muted) m-0 max-w-20 overflow-hidden text-ellipsis whitespace-nowrap"
              >
                {book.author || t('app.unknownAuthor')}
              </p>
            </div>{/if}
          <div class="flex flex-col items-end justify-between shrink-0 min-w-30">
            <span class="text-[0.75rem] text-(--color-text-muted) whitespace-nowrap"
              >{formatDate(highlight.createdAt)}</span
            ><DropMenu position="bottom-right"
              >{#snippet trigger()}<button
                  class="flex size-8 cursor-pointer items-center justify-center rounded-full border border-(--color-edge) bg-(--color-surface-subtle) text-(--color-text-muted) transition-colors hover:border-(--color-edge-strong) hover:text-(--color-primary) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
                  aria-label="Opciones"><EllipsisVertical size={14} strokeWidth={1.8} /></button
                >{/snippet}
              <div class="flex flex-col">
                <button
                  class="flex items-center gap-2 w-full p-2 border-none bg-transparent text-(--color-primary) text-[0.875rem] font-sans cursor-pointer text-left transition-colors hover:bg-(--color-surface-hover)"
                  onclick={() => handleCopy(highlight.text)}
                  ><Copy size={14} strokeWidth={1.8} />{t('home.highlightsCopy')}</button
                ><button
                  class="flex items-center gap-2 w-full p-2 border-none bg-transparent text-(--color-primary) text-[0.875rem] font-sans cursor-pointer text-left transition-colors hover:bg-(--color-surface-hover)"
                  onclick={() => handleViewInBook(highlight)}
                  ><Book size={14} strokeWidth={1.8} />{t('home.highlightsViewInBook')}</button
                >{#if highlight.note}<button
                    class="flex items-center gap-2 w-full p-2 border-none bg-transparent text-(--color-primary) text-[0.875rem] font-sans cursor-pointer text-left transition-colors hover:bg-(--color-surface-hover)"
                    ><SquarePen size={14} strokeWidth={1.8} />{t('home.highlightsEditNote')}</button
                  >{/if}<button
                  class="flex items-center gap-2 w-full p-2 border-none bg-transparent text-(--color-error) text-[0.875rem] font-sans cursor-pointer text-left transition-colors hover:bg-(--color-error)/12"
                  onclick={() => sync.handleDelete(highlight, highlights, (v) => (highlights = v))}
                  ><Trash2 size={14} strokeWidth={1.8} />{t('home.highlightsDelete')}</button
                >
              </div></DropMenu
            >
          </div>
        </li>{/each}
    </ul>
    {#if filters.totalPages > 1}<div class="flex justify-center mt-6 pb-4">
        <Pagination bind:current={filters.currentPage} total={filters.totalPages} />
      </div>{/if}{/if}
</section>
