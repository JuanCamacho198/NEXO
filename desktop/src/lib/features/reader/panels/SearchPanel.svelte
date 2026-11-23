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
            <input
              type="text"
              class="min-w-0 rounded-full! border-(--color-edge)! bg-(--color-home-card)! px-4 py-2.5 text-sm text-(--color-primary) transition-colors placeholder:text-(--color-text-muted) focus:border-(--color-brand)! focus:outline-none"
              bind:value={query}
              placeholder={t('search.placeholder')}
              disabled={isSearching || !bookId}
            />
            <Button
              type="submit"
              variant="accent"
              fullWidth
              disabled={isSearching || !bookId || !query.trim()}
            >
              {isSearching ? t('search.searching') : t('search.search')}
            </Button>
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
                    <p class="text-sm leading-relaxed text-(--color-primary)">{item.snippet}</p>
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
