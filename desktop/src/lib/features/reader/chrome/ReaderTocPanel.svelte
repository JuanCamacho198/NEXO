<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import { createFocusTrap } from '$lib/shared/utils/focusTrap';
  import { fly } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import X from 'lucide-svelte/icons/x';
  import Button from '$lib/shared/ui/forms/Button.svelte';

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
    <div class="absolute inset-0 bg-(--color-surface)/70"></div>

    <!-- Sidebar -->
    <div
      bind:this={sidebarEl}
      class="absolute right-0 top-0 flex h-full w-65 flex-col overflow-y-auto border-l border-(--color-border-deep) bg-(--color-surface)/70 pt-15 text-(--color-text-muted) backdrop-blur-sm"
      transition:fly={{
        x: reducedMotion ? 0 : 260,
        opacity: 0,
        duration: reducedMotion ? 0 : 250,
        easing: cubicOut,
      }}
      onkeydown={(e) => e.key === 'Escape' && onClose()}
      role="dialog"
      aria-label={t('reader.tabla_contenidos')}
      tabindex="0"
    >
      <!-- Heading. The close control lives here, in the panel, next to the
        title: the top bar's X sits outside the panel and the reader has to
        look away from the panel to find it. Matches ReaderTextSettings. -->
      <header
        class="flex items-center justify-between border-b border-(--color-border)/5 px-5 py-4"
      >
        <h2 class="text-base font-bold text-(--color-primary)">{t('reader.tabla_contenidos')}</h2>
        <Button
          variant="ghost"
          size="sm"
          onclick={onClose}
          class="shrink-0 px-2 text-(--color-text-muted) hover:text-(--color-primary)"
          aria-label={t('settings.close')}
          title={t('settings.close')}
        >
          <X size={16} strokeWidth={2} aria-hidden="true" />
        </Button>
      </header>

      <!-- Chapter List -->
      {#if entries.length === 0}
        <div class="flex flex-1 items-center justify-center px-5">
          <p class="text-sm italic text-(--color-text-muted)/60">{t('reader.toc_empty')}</p>
        </div>
      {:else}
        <nav class="flex-1 overflow-y-auto py-2">
          {#each entries as entry, i (i)}
            {@const depth = Math.max(0, Math.min(entry.depth, 3))}
            <button
              type="button"
              class="flex w-full cursor-pointer items-start py-2 pr-5 text-left transition-colors hover:bg-(--color-surface-hover)"
              class:mt-3={depth === 0 && i > 0}
              class:border-l-2={entry.id === activeId}
              class:border-(--color-accent-blue)={entry.id === activeId}
              style="background-color: {entry.id === activeId
                ? 'rgba(73, 212, 255, 0.05)'
                : 'transparent'}; padding-left: {20 + depth * 16}px;"
              onclick={() => onNavigate(entry)}
            >
              <span
                class="leading-relaxed"
                class:text-sm={depth === 0}
                class:text-xs={depth >= 1}
                class:font-medium={depth === 0 || entry.id === activeId}
                class:tracking-wide={depth === 0}
                class:text-(--color-primary)={entry.id === activeId}
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
