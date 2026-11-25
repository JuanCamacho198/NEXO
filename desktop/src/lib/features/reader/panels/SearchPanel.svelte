<script lang="ts">
  import type {
    SearchBookTextResponse,
    SearchNavigationTarget,
    SearchResult,
  } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  import { createFocusTrap } from '$lib/shared/utils/focusTrap';
  import { fly } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import { tick } from 'svelte';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import LoaderCircle from 'lucide-svelte/icons/loader-circle';
  import Search from 'lucide-svelte/icons/search';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import ReaderDrawerHeader from '../chrome/ReaderDrawerHeader.svelte';
  import { READER_DRAWER, READER_DRAWER_FLY_X, READER_DRAWER_SCRIM } from '../chrome/readerDrawer';

  type Props = {
    open: boolean;
    onClose: () => void;
    bookId?: string | null;
    disabledReason?: string | null;
    isSearching?: boolean;
    response: SearchBookTextResponse | null;
    onSearch?: (query: string, page: number) => void;
    onJump?: (target: SearchNavigationTarget) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let {
    open,
    onClose,
    bookId = null,
    disabledReason = null,
    isSearching = false,
    response,
    onSearch,
    onJump,
    t,
  }: Props = $props();

  let query = $state('');
  /** The query the shown results answer, for marking it in the snippets. */
  let searchedQuery = $state('');
  let panelEl: HTMLElement | undefined = $state();
  let inputEl: HTMLInputElement | undefined = $state();

  const canSubmit = $derived(Boolean(bookId) && !isSearching && query.trim().length > 0);

  // Svelte JS transitions ignore the global `prefers-reduced-motion` CSS block,
  // so the panel reads the query itself and zeroes its own duration.
  let reducedMotion = $state(false);
  $effect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const apply = (): void => {
      reducedMotion = mq.matches;
    };
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  });

  const resultCount = (): number => response?.items.length ?? 0;
  const hasResults = (): boolean => resultCount() > 0;
  const isNoMatch = (): boolean => Boolean(response) && resultCount() === 0;
  const currentPage = (): number => response?.page ?? 1;
  const pageSize = (): number => response?.pageSize ?? 20;
  const total = (): number => response?.total ?? 0;
  const hasMore = (): boolean => currentPage() * pageSize() < total();

  const runSearch = (page = 1): void => {
    if (!bookId || !query.trim()) {
      return;
    }

    searchedQuery = query.trim();
    onSearch?.(query.trim(), page);
  };

  /** Splits a snippet around each case-insensitive occurrence of the query. */
  function markMatches(snippet: string): { text: string; hit: boolean }[] {
    const needle = searchedQuery.toLowerCase();
    if (!needle) return [{ text: snippet, hit: false }];
    const parts: { text: string; hit: boolean }[] = [];
    const haystack = snippet.toLowerCase();
    let from = 0;
    for (let at = haystack.indexOf(needle); at !== -1; at = haystack.indexOf(needle, from)) {
      if (at > from) parts.push({ text: snippet.slice(from, at), hit: false });
      parts.push({ text: snippet.slice(at, at + needle.length), hit: true });
      from = at + needle.length;
    }
    if (from < snippet.length) parts.push({ text: snippet.slice(from), hit: false });
    return parts;
  }

  // Opening search means typing next: the field takes the focus.
  $effect(() => {
    if (!open || disabledReason) return;
    void tick().then(() => inputEl?.focus());
  });

  const jumpTo = (item: SearchResult, index: number): void => {
    onJump?.({
      resultId: `${item.chunkId}:${index}`,
      locator: item.locator,
      snippet: item.snippet,
    });
  };

  // The guard is evaluated on the overlay root: a click on the dimmed backdrop
  // targets the backdrop div while `currentTarget` stays the root, so the
  // comparison fires. The backdrop div carries no handler of its own.
  function handleBackdropClick(e: MouseEvent): void {
    if (e.target === e.currentTarget) onClose();
  }

  // Focus trap: keep Tab focus inside the drawer when open
  $effect(() => {
    if (open && panelEl) {
      const trap = createFocusTrap(panelEl);
      trap.activate();
      return () => trap.deactivate();
    }
  });
</script>

{#if open}
  <div
    class="fixed inset-0 z-40"
    onclick={handleBackdropClick}
    onkeydown={(e) => e.key === 'Escape' && onClose()}
    role="presentation"
  >
    <!-- Backdrop -->
    <div class={READER_DRAWER_SCRIM}></div>

    <!-- Drawer -->
    <div
      bind:this={panelEl}
      class={READER_DRAWER}
      transition:fly={{
        x: reducedMotion ? 0 : READER_DRAWER_FLY_X,
        opacity: 0,
        duration: reducedMotion ? 0 : 250,
        easing: cubicOut,
      }}
      onkeydown={(e) => e.key === 'Escape' && onClose()}
      role="dialog"
      aria-label={t('search.title')}
      tabindex="0"
    >
      <ReaderDrawerHeader title={t('search.title')} closeLabel={t('settings.close')} {onClose} />

      <div class="flex flex-col p-4">
        {#if disabledReason}
          <p class="m-0 flex items-start gap-2 text-sm leading-relaxed text-(--color-secondary)">
            <span
              class="mt-1.5 size-2 shrink-0 rounded-full bg-(--color-warning)"
              aria-hidden="true"
            ></span>
            {disabledReason}
          </p>
        {:else}
          <form
            class="mb-4 flex flex-col gap-2"
            onsubmit={(event) => {
              event.preventDefault();
              runSearch(1);
            }}
          >
            <!-- One field, one ring: `search-field` drops the global focus
              outline so only the border lights up. The submit lives inside
              the pill, so an empty query no longer shows a dead button. -->
            <div
              class="search-field flex items-center gap-2 rounded-full border border-(--color-edge) bg-(--color-home-card) py-1 pr-1 pl-3.5 transition-colors focus-within:border-(--color-brand)"
            >
              <Search
                size={16}
                strokeWidth={2}
                class="shrink-0 text-(--color-text-muted)"
                aria-hidden="true"
              />
              <input
                type="text"
                bind:this={inputEl}
                class="min-w-0 flex-1 rounded-none! border-0! bg-transparent! px-0 py-1.5 text-sm text-(--color-primary) placeholder:text-(--color-text-muted) focus:outline-none"
                bind:value={query}
                placeholder={t('search.placeholder')}
                aria-label={t('search.placeholder')}
                disabled={isSearching || !bookId}
              />
              <button
                type="submit"
                class="flex size-8 shrink-0 cursor-pointer items-center justify-center rounded-full transition-[background-color,color,opacity] disabled:cursor-not-allowed {canSubmit ||
                isSearching
                  ? 'bg-linear-to-r from-(--color-brand-start) to-(--color-brand) text-(--color-brand-on)'
                  : 'bg-(--color-surface-hover) text-(--color-text-muted)'}"
                aria-label={isSearching ? t('search.searching') : t('search.search')}
                title={isSearching ? t('search.searching') : t('search.search')}
                disabled={!canSubmit}
              >
                {#if isSearching}
                  <LoaderCircle
                    size={16}
                    strokeWidth={2.25}
                    class="animate-spin"
                    aria-hidden="true"
                  />
                {:else}
                  <ArrowRight size={16} strokeWidth={2.25} aria-hidden="true" />
                {/if}
              </button>
            </div>
          </form>

          {#if hasResults()}
            <ul class="-mx-2 flex flex-col gap-0.5">
              {#each response?.items ?? [] as item, index}
                <li>
                  <button
                    type="button"
                    class="w-full cursor-pointer rounded-(--radius-md) px-3 py-2.5 text-left transition-colors hover:bg-(--color-surface-hover)"
                    onclick={() => jumpTo(item, index)}
                  >
                    <p class="text-sm leading-relaxed text-(--color-secondary)">
                      {#each markMatches(item.snippet) as part, partIndex (partIndex)}
                        {#if part.hit}<mark
                            class="rounded-sm bg-(--color-brand-soft) px-0.5 font-semibold text-(--color-brand-text)"
                            >{part.text}</mark
                          >{:else}{part.text}{/if}
                      {/each}
                    </p>
                    <p class="mt-1 text-xs font-medium text-(--color-brand-text)">
                      {t('search.locator')}: {item.locator}
                    </p>
                  </button>
                </li>
              {/each}
            </ul>

            <nav
              class="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-(--color-edge) pt-3 text-xs text-(--color-text-muted)"
              aria-label="Search pagination"
            >
              <span
                >{t('search.page')}
                {currentPage()} · {Math.min(total(), pageSize() * currentPage())} / {total()}
                {t('search.matches')}</span
              >
              <div class="flex gap-1">
                <Button
                  variant="secondary"
                  size="sm"
                  onclick={() => runSearch(currentPage() - 1)}
                  disabled={currentPage() <= 1 || isSearching}
                >
                  {t('search.prev')}
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  onclick={() => runSearch(currentPage() + 1)}
                  disabled={!hasMore() || isSearching}
                >
                  {t('search.next')}
                </Button>
              </div>
            </nav>
          {:else if isNoMatch()}
            <p class="text-sm text-(--color-text-muted)">{t('search.noMatches')}</p>
          {/if}
        {/if}
      </div>
    </div>
  </div>
{/if}
