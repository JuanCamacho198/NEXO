<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import { createFocusTrap } from '$lib/shared/utils/focusTrap';
  import { fly } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import ReaderDrawerHeader from './ReaderDrawerHeader.svelte';
  import { READER_DRAWER, READER_DRAWER_FLY_X, READER_DRAWER_SCRIM } from './readerDrawer';

  export interface TocEntry {
    id: string;
    title: string;
    depth: number;
    /**
     * Spine index this entry points at, as the Rust extractor resolved it
     * (`epub_extractor.rs` `build_toc`). Several TOC entries can share one spine
     * index — a "Part" heading and its first chapter point at the same file —
     * which is why `id` is a monotonic counter instead of this value.
     *
     * Absent on PDF entries, whose outline carries no page until a destination
     * resolves asynchronously.
     */
    index?: number;
    active?: boolean;
    pageNumber?: number;
  }

  type Props = {
    open: boolean;
    entries: TocEntry[];
    activeId?: string;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onNavigate: (entry: TocEntry) => void;
    onClose: () => void;
  };

  let { open, entries, activeId, t, onNavigate, onClose }: Props = $props();

  let sidebarEl: HTMLElement | undefined = $state();

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

  function handleBackdropClick(e: MouseEvent): void {
    if (e.target === e.currentTarget) onClose();
  }

  // Focus trap: keep Tab focus inside the sidebar when open
  $effect(() => {
    if (open && sidebarEl) {
      const trap = createFocusTrap(sidebarEl);
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

    <!-- Sidebar -->
    <div
      bind:this={sidebarEl}
      class={READER_DRAWER}
      transition:fly={{
        x: reducedMotion ? 0 : READER_DRAWER_FLY_X,
        opacity: 0,
        duration: reducedMotion ? 0 : 250,
        easing: cubicOut,
      }}
      onkeydown={(e) => e.key === 'Escape' && onClose()}
      role="dialog"
      aria-label={t('reader.tabla_contenidos')}
      tabindex="0"
    >
      <ReaderDrawerHeader
        title={t('reader.tabla_contenidos')}
        closeLabel={t('settings.close')}
        {onClose}
      />

      <!-- Chapter List -->
      {#if entries.length === 0}
        <div class="flex flex-1 items-center justify-center px-5">
          <p class="text-sm italic text-(--color-text-muted)/60">{t('reader.toc_empty')}</p>
        </div>
      {:else}
        <nav class="flex flex-1 flex-col gap-0.5 p-2">
          {#each entries as entry, i (i)}
            {@const depth = Math.max(0, Math.min(entry.depth, 3))}
            {@const isActive = entry.id === activeId}
            <button
              type="button"
              class="flex w-full cursor-pointer items-start rounded-(--radius-md) py-2 pr-3 text-left transition-colors {isActive
                ? 'bg-(--color-brand-soft)'
                : 'hover:bg-(--color-surface-hover)'}"
              class:mt-2={depth === 0 && i > 0}
              aria-current={isActive ? 'location' : undefined}
              style="padding-left: {12 + depth * 16}px;"
              onclick={() => onNavigate(entry)}
            >
              <span
                class="leading-relaxed"
                class:text-sm={depth === 0}
                class:text-xs={depth >= 1}
                class:font-medium={depth === 0 && !isActive}
                class:font-semibold={isActive}
                class:text-(--color-brand-text)={isActive}
                style={entry.id === activeId
                  ? undefined
                  : `color: var(--color-${depth === 0 ? 'secondary' : 'text-muted'}); opacity: ${
                      depth === 0 ? 1 : depth === 1 ? 0.8 : 0.6
                    };`}
              >
                {entry.title}
              </span>
            </button>
          {/each}
        </nav>
      {/if}
    </div>
  </div>
{/if}
