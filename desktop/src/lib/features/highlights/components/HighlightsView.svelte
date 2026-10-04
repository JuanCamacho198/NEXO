<script lang="ts">
  import { onMount } from 'svelte';
  import type { HighlightDto } from '$lib/shared/types';
  import type { ViewerPort } from '$lib/shared/ports';
  import { TauriViewerAdapter } from '$lib/shared/ports';
  import { createHighlightsViewDeps, type HighlightsViewDeps } from '../highlightsViewDeps';
  import { createHighlightsFilters } from '../useHighlightsFilters.svelte';
  import { createHighlightsSync } from '../useHighlightsSync.svelte';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import { searchState } from '$lib/shared/stores/SearchDomainState.svelte';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
  import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
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
  const viewerPort: ViewerPort = viewerPortProp ?? new TauriViewerAdapter();
  // svelte-ignore state_referenced_locally
  const deps: HighlightsViewDeps = depsProp ?? createHighlightsViewDeps(viewerPort);
  let highlights = $state<HighlightDto[]>([]);
  let isLoading = $state(true);
  const totalHighlights = $derived(highlights.length);
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
    const ids = new Set(highlights.map((h) => h.bookId));
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
    void statsState.loadStats(book.id);
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
        <h1 class="text-[1.875rem] font-bold text-(--color-primary) m-0 mb-1">
          {t('home.highlightsTitle')}
        </h1>
        <p class="text-[0.875rem] text-(--color-text-muted) m-0">{t('home.highlightsSubtitle')}</p>
      </div>
      <button
        type="button"
        class="shrink-0 w-10 h-10 rounded-full flex items-center justify-center border border-(--color-border) bg-(--color-surface) text-(--color-text-muted) transition-all hover:border-(--color-border-strong) hover:text-(--color-primary) hover:bg-(--color-surface-hover,rgba(25,41,62,0.96)) disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
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
          class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full border border-(--color-border) bg-(--color-surface) text-[0.7rem] font-medium text-(--color-text-muted) shadow-sm"
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
    {#each typeTabs as tab}<button
        type="button"
        class={`px-3 py-1.5 rounded-full text-xs font-medium border transition-colors cursor-pointer ${filters.selectedType === tab.value ? 'bg-(--color-accent-soft) text-(--color-primary) border-(--color-accent)' : 'bg-(--color-surface) text-(--color-text-muted) border-(--color-border) hover:border-(--color-border-strong) hover:text-(--color-primary)'}`}
        onclick={() => (filters.selectedType = tab.value)}>{t(tab.labelKey)}</button
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
      class="w-full h-12 pl-14 pr-20 rounded-2xl border border-(--color-border) bg-(--color-surface) text-(--color-primary) text-[0.875rem] font-sans transition-colors focus:outline-none focus:border-(--color-accent-blue,#49d4ff) focus:shadow-[0_0_0_3px_rgba(73,212,255,0.15)] placeholder:text-(--color-text-muted)"
      placeholder={t('home.highlightsSearchPlaceholder')}
      bind:value={filters.searchQuery}
    /><kbd
      class="absolute right-4 inline-flex items-center gap-1 px-2 py-1 rounded-md border border-(--color-border) bg-(--color-background) text-(--color-text-muted) text-[0.7rem] font-sans pointer-events-none"
      aria-hidden="true">{t('library.searchShortcut')}</kbd
    >
  </div>
  <div class="mb-4 rounded-2xl border border-(--color-border) bg-(--color-surface) px-4 py-3">
    <div class="mb-3 flex items-center justify-between gap-3">
      <h2
        class="m-0 text-[0.75rem] font-semibold text-(--color-text-muted) uppercase tracking-wider"
      >
        {t('home.highlightsFiltersHeading')}
      </h2>
      <Button size="sm" variant="ghost" onclick={filters.clearFilters}
        >{t('home.highlightsClearFilters')}</Button
      >
    </div>
    <div class="flex flex-wrap items-center gap-x-5 gap-y-3">
      <div class="flex items-center gap-2">
        <span class="text-[0.75rem] font-semibold text-(--color-primary) uppercase tracking-wider"
          >{t('home.highlightsFilterColor')}</span
        >
        <div class="flex items-center gap-1">
          {#each HIGHLIGHT_COLORS as color}<button
              type="button"
              class="w-6 h-6 rounded-full border-2 border-transparent cursor-pointer transition-all hover:scale-[1.15] {filters.selectedColors.has(
                color.key,
              )
                ? 'border-(--color-primary) shadow-[0_0_0_3px_rgba(73,212,255,0.25)] scale-110'
                : ''}"
              style="background: {color.hex};"
              aria-label={t('highlight.selectColor', {
                color: t(`settings.color.${color.key}` as import('$lib/shared/i18n').MessageKey),
              })}
              onclick={() => filters.toggleColor(color.key)}
            ></button>{/each}<button
            type="button"
            class="px-2 py-1 rounded-md border border-(--color-border) bg-transparent text-(--color-text-muted) text-[0.75rem] cursor-pointer transition-all font-sans hover:bg-(--color-surface-hover,rgba(25,41,62,0.96)) {filters
              .selectedColors.size === 0
              ? 'border-(--color-accent-blue,#49d4ff) text-(--color-primary) bg-(--color-panel-accent)'
              : ''}"
            onclick={() => {
              filters.selectedColors = new Set();
            }}>{t('home.shelfTab.all')}</button
          >
        </div>
      </div>
      <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div class="flex items-center gap-2">
          <span class="text-[0.75rem] font-semibold text-(--color-primary) uppercase tracking-wider"
            >{t('home.highlightsFilterBook')}</span
          ><Dropdown
            options={bookFilterOptions}
            bind:value={filters.selectedBookId}
            class="min-w-[150px]"
          />
        </div>
        <div class="flex items-center gap-2">
          <span class="text-[0.75rem] font-semibold text-(--color-primary) uppercase tracking-wider"
            >{t('home.highlightsFilterTag')}</span
          ><Dropdown
            options={tagFilterOptions}
            bind:value={filters.selectedTagId}
            class="min-w-[130px]"
          />
        </div>
        <div class="flex items-center gap-2">
          <span class="text-[0.75rem] font-semibold text-(--color-primary) uppercase tracking-wider"
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
          class="flex items-stretch gap-4 p-5 rounded-2xl border border-(--color-border) bg-(--color-surface) transition-all cursor-default hover:border-(--color-border-strong) hover:shadow-(--shadow-soft) hover:bg-(--color-surface-hover,rgba(25,41,62,0.96))"
          style="--bar-color: {resolveHighlightHex(highlight.color)}"
        >
          <div class="w-1 min-h-full rounded bg-(--bar-color) shrink-0"></div>
          <div class="flex-1 min-w-0 flex flex-col justify-center gap-1">
            <p class="text-[0.875rem] font-medium text-(--color-primary) m-0 line-height-[1.5]">
              {highlight.text}
            </p>
            {#if highlight.note}<p class="text-[0.75rem] text-(--color-text-muted) m-0 italic">
                <SquarePen size={14} strokeWidth={1.8} />{highlight.note}
              </p>{/if}
            <p class="text-[0.75rem] text-(--color-text-muted) m-0">
              {t('home.highlightsPageLabel')}
              {highlight.pageNumber}{book ? ` · ${book.title}` : ''}
            </p>
          </div>
          {#if book}<div class="flex flex-col items-center gap-1 shrink-0 w-20 text-center">
              <div
                class="w-12 h-16 rounded-md overflow-hidden border border-(--color-border) bg-(--color-background) flex items-center justify-center"
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
                  class="w-8 h-8 flex items-center justify-center rounded-md border border-transparent bg-transparent text-(--color-text-muted) text-[1.1rem] cursor-pointer transition-all font-sans hover:bg-(--color-panel-accent) hover:border-(--color-border) hover:text-(--color-primary)"
                  aria-label="Opciones"><EllipsisVertical size={14} strokeWidth={1.8} /></button
                >{/snippet}
              <div class="flex flex-col">
                <button
                  class="flex items-center gap-2 w-full p-2 border-none bg-transparent text-(--color-primary) text-[0.875rem] font-sans cursor-pointer text-left transition-colors hover:bg-(--color-panel-accent)"
                  onclick={() => handleCopy(highlight.text)}
                  ><Copy size={14} strokeWidth={1.8} />{t('home.highlightsCopy')}</button
                ><button
                  class="flex items-center gap-2 w-full p-2 border-none bg-transparent text-(--color-primary) text-[0.875rem] font-sans cursor-pointer text-left transition-colors hover:bg-(--color-panel-accent)"
                  onclick={() => handleViewInBook(highlight)}
                  ><Book size={14} strokeWidth={1.8} />{t('home.highlightsViewInBook')}</button
                >{#if highlight.note}<button
                    class="flex items-center gap-2 w-full p-2 border-none bg-transparent text-(--color-primary) text-[0.875rem] font-sans cursor-pointer text-left transition-colors hover:bg-(--color-panel-accent)"
                    ><SquarePen size={14} strokeWidth={1.8} />{t('home.highlightsEditNote')}</button
                  >{/if}<button
                  class="flex items-center gap-2 w-full p-2 border-none bg-transparent text-(--color-error) text-[0.875rem] font-sans cursor-pointer text-left transition-colors hover:bg-(--color-error-bg,rgba(255,123,131,0.14))"
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
