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
  import Button from '$lib/shared/ui/forms/Button.svelte';

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
  let panelEl: HTMLElement | undefined = $state();

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

    onSearch?.(query.trim(), page);
  };

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
    <div class="absolute inset-0 bg-(--color-surface)/70"></div>

    <!-- Drawer -->
    <div
      bind:this={panelEl}
      class="absolute right-0 top-0 flex h-full w-65 flex-col overflow-y-auto border-l border-(--color-border-deep) bg-(--color-surface)/70 pt-15 text-(--color-text-muted) backdrop-blur-sm"
      transition:fly={{
        x: reducedMotion ? 0 : 260,
        opacity: 0,
        duration: reducedMotion ? 0 : 250,
        easing: cubicOut,
      }}
      onkeydown={(e) => e.key === 'Escape' && onClose()}
      role="dialog"
      aria-label={t('search.title')}
      tabindex="0"
    >
      <!-- Heading -->
      <header
        class="flex items-center justify-between border-b border-(--color-border)/5 px-5 py-4"
      >
        <h2 class="text-base font-bold text-(--color-primary)">{t('search.title')}</h2>
        <Button
          variant="ghost"
          size="sm"
          onclick={onClose}
          class="shrink-0 px-2 text-(--color-text-muted) hover:text-(--color-text-inverse)"
          aria-label={t('settings.close')}
          title={t('settings.close')}
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
            ><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"
            ></line></svg
          >
        </Button>
      </header>

      <div class="flex flex-col p-4">
        {#if disabledReason}
          <div
            class="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900"
          >
            {disabledReason}
          </div>
        {:else}
          <form
            class="mb-3 flex gap-2"
            onsubmit={(event) => {
              event.preventDefault();
              runSearch(1);
            }}
          >
            <input
              type="text"
              class="min-w-0 flex-1 rounded-md border border-(--color-border) bg-(--color-surface) px-3 py-2 text-sm text-(--color-primary)"
              bind:value={query}
              placeholder={t('search.placeholder')}
              disabled={isSearching || !bookId}
            />
            <button
              type="submit"
              class="rounded-md bg-(--color-primary) px-3 py-2 text-sm font-medium text-(--color-background) hover:opacity-90 disabled:opacity-60"
              disabled={isSearching || !bookId || !query.trim()}
            >
              {isSearching ? t('search.searching') : t('search.search')}
            </button>
          </form>

          {#if hasResults()}
            <ul class="space-y-2">
              {#each response?.items ?? [] as item, index}
                <li>
                  <button
                    type="button"
                    class="w-full rounded-lg border border-(--color-border) bg-(--color-background) px-3 py-2 text-left hover:bg-(--color-surface-hover)"
                    onclick={() => jumpTo(item, index)}
                  >
                    <p class="text-sm text-(--color-primary)">{item.snippet}</p>
                    <p class="mt-1 text-xs text-(--color-text-muted)">
                      {t('search.locator')}: {item.locator}
                    </p>
                  </button>
                </li>
              {/each}
            </ul>

            <nav
              class="mt-3 flex items-center justify-between text-xs text-(--color-text-muted)"
              aria-label="Search pagination"
            >
              <span
                >{t('search.page')}
                {currentPage()} · {Math.min(total(), pageSize() * currentPage())} / {total()}
                {t('search.matches')}</span
              >
              <div class="flex gap-1">
                <button
                  type="button"
                  class="rounded border border-(--color-border) px-2 py-1 text-(--color-primary) hover:bg-(--color-surface-hover) disabled:opacity-50"
                  onclick={() => runSearch(currentPage() - 1)}
                  disabled={currentPage() <= 1 || isSearching}
                >
                  {t('search.prev')}
                </button>
                <button
                  type="button"
                  class="rounded border border-(--color-border) px-2 py-1 text-(--color-primary) hover:bg-(--color-surface-hover) disabled:opacity-50"
                  onclick={() => runSearch(currentPage() + 1)}
                  disabled={!hasMore() || isSearching}
                >
                  {t('search.next')}
                </button>
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
