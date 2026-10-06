<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import {
    dictionaryState,
    type DictionaryStateApi,
  } from '$lib/shared/stores/DictionaryState.svelte';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';
  import { readerState } from '$lib/shared/stores/ReaderDomainState.svelte';
  import { navigationState } from '$lib/shared/stores/NavigationDomainState.svelte';
  import { searchState } from '$lib/shared/stores/SearchDomainState.svelte';
  import { statsState } from '$lib/shared/stores/StatsDomainState.svelte';
  import EmptyState from '$lib/shared/ui/feedback/EmptyState.svelte';
  import SearchBar from '$lib/shared/ui/navigation/SearchBar.svelte';
  import Plus from 'lucide-svelte/icons/plus';
  import X from 'lucide-svelte/icons/x';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import DictionaryKpiRow from './DictionaryKpiRow.svelte';
  import DictionaryRow from './DictionaryRow.svelte';
  import DictionaryDetailPanel from './DictionaryDetailPanel.svelte';
  import { deriveDictionaryKpis } from '../dictionaryKpis';
  import {
    EMPTY_USER_FIELD_DRAFT,
    userFieldDraft,
    userFieldPatchFrom,
    type UserFieldDraft,
  } from '../dictionaryEntry';
  import {
    openDictionaryBook,
    type DictionaryBookNavigationDeps,
    type DictionaryBookTarget,
  } from '../dictionaryBookNavigation';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    dictionary?: DictionaryStateApi;
    /**
     * Overrides the reader-navigation surface behind `Ver libro`. Production
     * uses `storeBookNavigation` below, the Highlights pattern bound to the
     * domain stores; tests inject a fake to observe the sequence.
     */
    bookNavigation?: DictionaryBookNavigationDeps;
    /** Notified when an edit session for the four user-authored fields opens. */
    onEdit?: (id: string) => void;
  };

  let { t, dictionary = dictionaryState, bookNavigation, onEdit }: Props = $props();

  const storeBookNavigation: DictionaryBookNavigationDeps = {
    getBookById: (bookId) => libraryState.getBookById(bookId),
    promoteBookForReading: (bookId) => libraryState.promoteBookForReading(bookId),
    setActiveReadingBookId: (bookId) => {
      readerState.activeReadingBookId = bookId;
    },
    clearShelfDetails: () => {
      navigationState.shelfDetailsBookId = null;
    },
    openReader: () => {
      navigationState.route = 'reader';
    },
    resetSearch: () => searchState.resetSearch(),
    recordReaderOpenMetric: (format) => libraryState.recordReaderOpenMetric(format),
    startReading: (book) => readerState.startReading(book),
    loadStats: (bookId) => {
      void statsState.loadStats(bookId);
    },
    setSearchTargetLocator: (locator) => {
      searchState.searchTargetLocator = locator;
    },
  };

  function handleViewBook(target: DictionaryBookTarget): void {
    void openDictionaryBook(target, bookNavigation ?? storeBookNavigation);
  }

  type Tab = 'all' | 'recent' | 'az';

  let searchQuery = $state('');
  let debouncedQuery = $state('');
  let activeTab = $state<Tab>('all');
  let selectedId = $state<string | null>(null);
  /** The entry whose user-authored fields are being edited, or null. */
  let editingId = $state<string | null>(null);
  let editDraft = $state<UserFieldDraft>(EMPTY_USER_FIELD_DRAFT);
  let showAddForm = $state(false);
  let newWord = $state('');
  let newTags = $state('');
  let isAdding = $state(false);
  let errorMsg = $state<string | null>(null);
  let duplicateWord = $state<string | null>(null);
  /** Polite live-region text announced after add/save/delete succeed. */
  let announcement = $state('');
  /** Error from a failed save/delete, surfaced in a visible alert. */
  let actionError = $state<string | null>(null);
  let debounceTimer: ReturnType<typeof setTimeout> | null = null;

  const tabs: { id: Tab; label: MessageKey }[] = [
    { id: 'all', label: 'dictionary.tabAll' },
    { id: 'recent', label: 'dictionary.tabRecent' },
    { id: 'az', label: 'dictionary.tabAz' },
  ];

  const kpis = $derived(deriveDictionaryKpis(dictionary.words));

  const filteredWords = $derived.by(() => {
    const query = debouncedQuery.trim();
    const base = query ? dictionary.search(query, 50) : [...dictionary.words];
    if (activeTab === 'recent') {
      return [...base].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
    }
    if (activeTab === 'az') {
      return [...base].sort((a, b) => (a.word ?? '').localeCompare(b.word ?? ''));
    }
    return base;
  });

  const selectedWord = $derived(dictionary.words.find((w) => w.id === selectedId) ?? null);

  /**
   * The edit session is open only for the entry the panel is showing, so
   * selecting another row closes it without a second state to keep in sync.
   */
  const isEditing = $derived(editingId != null && editingId === selectedWord?.id);

  $effect(() => {
    const q = searchQuery;
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      debouncedQuery = q;
    }, 250);
  });

  onMount(() => {
    void dictionary.load();
  });

  onDestroy(() => {
    if (debounceTimer) clearTimeout(debounceTimer);
  });

  async function handleAdd(): Promise<void> {
    const trimmed = newWord.trim();
    if (!trimmed) return;
    const tags = newTags
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    isAdding = true;
    errorMsg = null;
    duplicateWord = null;
    actionError = null;
    try {
      const created = await dictionary.add(trimmed, { tags });
      newWord = '';
      newTags = '';
      showAddForm = false;
      selectedId = created.id;
      announcement = t('dictionary.added', { word: created.word ?? trimmed });
    } catch (e) {
      const msg = e instanceof Error ? e.message : t('errors.commandFailure');
      if (msg.includes('dictionary.duplicate') || msg.includes('duplicate')) {
        duplicateWord = trimmed;
        errorMsg = t('dictionary.duplicate', { word: trimmed });
      } else {
        errorMsg = msg;
      }
    } finally {
      isAdding = false;
    }
  }

  async function handleDelete(id: string): Promise<void> {
    const entry = dictionary.words.find((w) => w.id === id);
    if (!confirm(t('dictionary.deleteConfirm', { word: entry?.word ?? '' }))) return;
    actionError = null;
    try {
      await dictionary.remove(id);
      if (selectedId === id) selectedId = null;
      if (editingId === id) closeEdit();
      announcement = t('dictionary.deleted', { word: entry?.word ?? '' });
    } catch (e) {
      actionError = e instanceof Error ? e.message : t('errors.commandFailure');
    }
  }

  /**
   * Opens or closes the add form and clears any stale validation or action
   * error, so reopening never shows a previous failed attempt.
   */
  function toggleAddForm(): void {
    showAddForm = !showAddForm;
    if (showAddForm) {
      errorMsg = null;
      duplicateWord = null;
      actionError = null;
    }
  }

  function handleSelect(id: string): void {
    selectedId = id;
    closeEdit();
  }

  function closeEdit(): void {
    editingId = null;
    editDraft = EMPTY_USER_FIELD_DRAFT;
  }

  /**
   * Opens the edit session with a draft seeded from the stored entry, so the
   * four inputs start from what is saved rather than from a stale draft.
   */
  function handleEdit(id: string): void {
    if (editingId === id) return;
    const entry = dictionary.words.find((w) => w.id === id);
    if (!entry) return;
    editingId = id;
    editDraft = userFieldDraft(entry);
    onEdit?.(id);
  }

  /**
   * REQ-DRE-002 / REQ-DRE-007: the only write an edit session performs. The
   * payload is built from the four user-authored fields, so no evidence value
   * can travel through it — a re-capture stays the reader flow.
   */
  async function handleSaveEdit(): Promise<void> {
    const id = editingId;
    if (!id) return;
    actionError = null;
    try {
      await dictionary.update(id, userFieldPatchFrom(editDraft));
      closeEdit();
      announcement = t('dictionary.saved');
    } catch (e) {
      actionError = e instanceof Error ? e.message : t('errors.commandFailure');
    }
  }
</script>

<section class="flex w-full flex-col gap-6 p-6">
  <header class="flex h-20 items-center justify-between gap-4">
    <div class="flex min-w-0 flex-col gap-1.5">
      <h1 class="truncate text-2xl font-extrabold text-(--color-primary)">
        {t('dictionary.title')}
      </h1>
      <p class="truncate text-2sm text-(--color-text-tertiary)">{t('dictionary.subtitle')}</p>
    </div>
    <button
      type="button"
      class="flex shrink-0 cursor-pointer items-center gap-2 rounded-[10px] bg-(--color-accent-blue) px-4 py-2.5 text-2sm font-bold text-(--color-accent-on) transition-opacity hover:opacity-90"
      aria-expanded={showAddForm}
      aria-controls="dictionary-add-form"
      onclick={toggleAddForm}
    >
      {#if showAddForm}
        <X size={16} strokeWidth={1.8} class="h-4 w-4" aria-hidden="true" />
      {:else}
        <Plus size={16} strokeWidth={1.8} class="h-4 w-4" aria-hidden="true" />
      {/if}
      <span>{t('dictionary.newWord')}</span>
    </button>
  </header>

  <p class="sr-only" role="status" aria-live="polite">{announcement}</p>

  {#if actionError}
    <p
      class="rounded-md bg-(--color-error-soft) px-3 py-2 text-xs text-(--color-error)"
      role="alert"
      data-testid="dictionary-action-error"
    >
      {actionError}
    </p>
  {/if}

  {#if dictionary.words.length > 0}
    <DictionaryKpiRow {t} {kpis} />
  {/if}

  <div class="flex gap-3">
    <div
      class="flex w-110 shrink-0 flex-col gap-3.5 rounded-lg border border-(--color-panel-border) bg-(--color-panel) p-4"
      data-testid="dictionary-words-panel"
    >
      <label for="dictionary-search" class="sr-only">{t('dictionary.searchLabel')}</label>
      <SearchBar
        id="dictionary-search"
        bind:value={searchQuery}
        placeholder={t('dictionary.searchPlaceholder')}
        shortcutKey="/"
        shortcutLabel={t('dictionary.searchShortcut')}
        shortcutAriaLabel={t('dictionary.searchShortcutAria')}
      />

      <div class="flex items-center gap-2" data-testid="dictionary-tabs">
        {#each tabs as tab (tab.id)}
          <button
            type="button"
            class={`cursor-pointer rounded-full px-3 py-1.5 text-xs font-semibold transition-colors ${
              activeTab === tab.id
                ? 'bg-(--color-accent-fill) text-(--color-accent-blue)'
                : 'bg-(--color-panel-input) text-(--color-secondary)'
            }`}
            aria-pressed={activeTab === tab.id}
            onclick={() => (activeTab = tab.id)}
          >
            {t(tab.label)}
          </button>
        {/each}
      </div>

      {#if showAddForm}
        <form
          id="dictionary-add-form"
          class="flex flex-col gap-2 rounded-md border border-(--color-panel-border) bg-(--color-panel-input) p-3"
          onsubmit={(event) => {
            event.preventDefault();
            void handleAdd();
          }}
        >
          <div class="flex gap-2">
            <input
              type="text"
              class="h-10 min-w-0 flex-1 rounded-md border border-(--color-panel-border) bg-(--color-panel) px-3 text-2sm text-(--color-primary) placeholder:text-(--color-text-tertiary)"
              placeholder={t('dictionary.wordPlaceholder')}
              bind:value={newWord}
              disabled={isAdding}
            />
            <input
              type="text"
              class="h-10 w-32 rounded-md border border-(--color-panel-border) bg-(--color-panel) px-2 text-xs text-(--color-primary) placeholder:text-(--color-text-tertiary)"
              placeholder={t('dictionary.tagsPlaceholder')}
              bind:value={newTags}
              disabled={isAdding}
            />
            <Button size="sm" type="submit" disabled={!newWord.trim() || isAdding}>
              {#if isAdding}
                {t('settings.saving')}
              {:else}
                {t('dictionary.addWord')}
              {/if}
            </Button>
          </div>
          {#if errorMsg}
            <p class="text-xs {duplicateWord ? 'text-(--color-warning)' : 'text-(--color-error)'}">
              {errorMsg}
            </p>
          {/if}
        </form>
      {/if}

      {#if dictionary.isLoading}
        <div
          class="rounded-md border border-(--color-panel-border) bg-(--color-panel-input) p-8 text-center text-sm text-(--color-text-tertiary)"
        >
          {t('stats.loading')}
        </div>
      {:else if dictionary.error}
        <div
          class="flex min-h-[24vh] items-center justify-center"
          data-testid="dictionary-load-error"
        >
          <EmptyState
            icon="error"
            title={t('dictionary.loadErrorTitle')}
            description={t('dictionary.loadErrorDescription')}
          >
            {#snippet action()}
              <Button type="button" size="sm" onclick={() => void dictionary.load()}>
                {t('dictionary.retry')}
              </Button>
            {/snippet}
          </EmptyState>
        </div>
      {:else if filteredWords.length === 0}
        <div class="flex min-h-[24vh] items-center justify-center">
          <EmptyState
            icon="search"
            title={dictionary.words.length === 0
              ? t('dictionary.emptyTitle')
              : t('dictionary.noResultsTitle')}
            description={dictionary.words.length === 0
              ? t('dictionary.emptyDescription')
              : t('dictionary.noResultsDescription')}
          />
        </div>
      {:else}
        <ul class="m-0 flex list-none flex-col gap-1.5 p-0" data-testid="dictionary-list">
          {#each filteredWords as w, index (w.id)}
            <DictionaryRow
              word={w}
              {index}
              selected={selectedId === w.id}
              {t}
              onselect={(id) => handleSelect(id)}
            />
          {/each}
        </ul>
      {/if}
    </div>

    <DictionaryDetailPanel
      word={selectedWord}
      {t}
      editing={isEditing}
      draft={editDraft}
      onChangeDraft={(next) => (editDraft = next)}
      onViewBook={handleViewBook}
      onEdit={handleEdit}
      onDelete={handleDelete}
      onSave={handleSaveEdit}
      onCancelEdit={closeEdit}
    />
  </div>
</section>
