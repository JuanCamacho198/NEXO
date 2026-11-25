<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import { createFocusTrap } from '$lib/shared/utils/focusTrap';
  import { fly } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import ReaderDrawerHeader from './ReaderDrawerHeader.svelte';
  import { READER_DRAWER, READER_DRAWER_FLY_X, READER_DRAWER_SCRIM } from './readerDrawer';
  import { splitTocTitle } from './tocTitle';

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
    /** The entry the reader is in now: marked, and scrolled to on open. */
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

  // A long TOC opens on the chapter being read, not on "Cover".
  $effect(() => {
    if (!open || !sidebarEl || !activeId) return;
    const current = sidebarEl.querySelector<HTMLElement>('[aria-current="location"]');
    current?.scrollIntoView?.({ block: 'center' });
  });

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
            {@const parts = splitTocTitle(entry.title)}
            <button
              type="button"
              class="relative flex w-full cursor-pointer items-start gap-2.5 rounded-(--radius-md) py-2 pr-3 text-left transition-colors {isActive
                ? 'bg-(--color-brand-soft)'
                : 'hover:bg-(--color-surface-hover)'}"
              class:mt-2={depth === 0 && i > 0}
              aria-current={isActive ? 'location' : undefined}
              style="padding-left: {12 + depth * 16}px;"
              onclick={() => onNavigate(entry)}
            >
              {#if depth > 0}
                <!-- A guide line ties sub-entries to their parent. -->
                <span
                  class="absolute inset-y-0 w-px bg-(--color-edge)"
                  style="left: {depth * 16}px;"
                  aria-hidden="true"
                ></span>
              {/if}
              {#if isActive}
                <span
                  class="absolute inset-y-2 left-1 w-[3px] rounded-full bg-(--color-brand)"
                  aria-hidden="true"
                ></span>
              {/if}
              {#if parts.number}
                <span
                  class="min-w-5 shrink-0 pt-px text-right text-xs font-semibold tabular-nums {isActive
                    ? 'text-(--color-brand-text)'
                    : 'text-(--color-text-muted)'}">{parts.number}</span
                >
              {/if}
              <span class="flex min-w-0 flex-1 flex-col">
                <!-- The eyebrow carries the book's own label and, on the chapter
                  being read, "you are here": on its own line it never squeezes
                  the title. -->
                {#if parts.label || isActive}
                  <span
                    class="flex items-center gap-1.5 text-[0.6875rem] font-semibold tracking-[0.08em] uppercase {isActive
                      ? 'text-(--color-brand-text)'
                      : 'text-(--color-text-muted)'}"
                  >
                    {#if parts.label}<span>{parts.label}</span>{/if}
                    {#if parts.label && isActive}<span aria-hidden="true">·</span>{/if}
                    {#if isActive}<span>{t('reader.tocYouAreHere')}</span>{/if}
                  </span>
                {/if}
                <!-- Capitals a book shouts in are drawn as small caps rather
                  than rewritten: lowercasing would lose the proper nouns. The
                  larger size gives the synthesised small caps the height of
                  the neighbouring titles' capitals. -->
                <span
                  class="leading-snug {isActive
                    ? 'font-semibold text-(--color-brand-text)'
                    : depth === 0
                      ? 'font-medium text-(--color-primary)'
                      : 'text-(--color-secondary)'} {parts.shouting
                    ? 'text-[1.0625rem] leading-tight! tracking-[0.04em] [font-variant-caps:all-small-caps]'
                    : 'text-sm'}"
                  data-toc-depth={depth}>{parts.title}</span
                >
              </span>
            </button>
          {/each}
        </nav>
      {/if}
    </div>
  </div>
{/if}
