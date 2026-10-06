<script lang="ts">
  import { onMount } from 'svelte';
  import { scale } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import Star from 'lucide-svelte/icons/star';
  import BookOpen from 'lucide-svelte/icons/book-open';
  import SquarePen from 'lucide-svelte/icons/square-pen';
  import Eye from 'lucide-svelte/icons/eye';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import { getShelfMenuId } from '../utils';

  const {
    bookId,
    isFavorite,
    readLabel,
    editLabel,
    removeLabel,
    favoriteAddLabel,
    favoriteRemoveLabel,
    triggerLabel,
    onRead,
    onEdit,
    onRemove,
    onToggleFavorite,
    onViewDetails,
    viewDetailsLabel = '',
  }: {
    bookId: string;
    isFavorite: boolean;
    readLabel: string;
    editLabel: string;
    removeLabel: string;
    favoriteAddLabel: string;
    favoriteRemoveLabel: string;
    triggerLabel: string;
    onRead?: () => void;
    onEdit: () => void;
    onRemove: () => void;
    onToggleFavorite: () => void;
    onViewDetails?: () => void;
    viewDetailsLabel?: string;
  } = $props();

  let isOpen = $state(false);
  let containerEl = $state<HTMLDivElement | null>(null);
  let triggerEl = $state<HTMLButtonElement | null>(null);
  let menuEl = $state<HTMLDivElement | null>(null);
  let menuPos = $state<{ top: number; left: number } | null>(null);

  // Svelte JS transitions ignore the global `prefers-reduced-motion` CSS block,
  // so the menu reads the query itself and zeroes its own duration.
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

  function updateMenuPosition(): void {
    if (!triggerEl) return;
    const rect = triggerEl.getBoundingClientRect();
    const menuWidth = 224; // w-56
    const gap = 8;
    const vw = typeof window !== 'undefined' ? window.innerWidth : 0;
    const top = rect.bottom + gap;
    const left = Math.min(rect.right - menuWidth, vw - menuWidth - 8);
    menuPos = { top, left: Math.max(8, left) };
  }

  const getMenuButtons = (): HTMLButtonElement[] => {
    if (!menuEl) {
      return [] as HTMLButtonElement[];
    }

    return Array.from(menuEl.querySelectorAll<HTMLButtonElement>("[data-menu-item='true']"));
  };

  const focusItemAt = (index: number): void => {
    const items = getMenuButtons();
    if (items.length === 0) {
      return;
    }

    const nextIndex = (index + items.length) % items.length;
    items[nextIndex]?.focus();
  };

  const closeMenu = (returnFocus: boolean): void => {
    if (!isOpen) {
      return;
    }

    isOpen = false;
    if (returnFocus) {
      triggerEl?.focus();
    }
  };

  const openMenu = (focusFirstItem: boolean): void => {
    if (isOpen) {
      return;
    }

    isOpen = true;
    updateMenuPosition();
    if (focusFirstItem) {
      queueMicrotask(() => {
        updateMenuPosition();
        focusItemAt(0);
      });
    }
  };

  const toggleMenu = (): void => {
    if (isOpen) {
      closeMenu(true);
      return;
    }

    openMenu(true);
  };

  const handleAction = (action: () => void): void => {
    action();
    closeMenu(true);
  };

  const handleTriggerKeyDown = (event: KeyboardEvent): void => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      openMenu(true);
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      openMenu(false);
      queueMicrotask(() => {
        const items = getMenuButtons();
        if (items.length === 0) {
          return;
        }
        items[items.length - 1]?.focus();
      });
      return;
    }

    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      toggleMenu();
    }
  };

  const handleMenuKeyDown = (event: KeyboardEvent): void => {
    const items = getMenuButtons();
    if (items.length === 0) {
      return;
    }

    const activeIndex = items.findIndex((item) => item === document.activeElement);

    if (event.key === 'Escape') {
      event.preventDefault();
      closeMenu(true);
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      focusItemAt(activeIndex + 1);
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      focusItemAt(activeIndex <= 0 ? items.length - 1 : activeIndex - 1);
      return;
    }

    if (event.key === 'Home') {
      event.preventDefault();
      focusItemAt(0);
      return;
    }

    if (event.key === 'End') {
      event.preventDefault();
      focusItemAt(items.length - 1);
      return;
    }

    if (event.key === 'Tab') {
      closeMenu(false);
    }
  };

  onMount(() => {
    const handleDocumentPointerDown = (event: PointerEvent): void => {
      if (!isOpen) {
        return;
      }

      if (containerEl && !containerEl.contains(event.target as Node)) {
        closeMenu(false);
      }
    };

    const handleDocumentKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        closeMenu(true);
      }
    };

    // The dialog layer now paints above this menu, so a menu left open under a
    // dialog is already non-interactable; dismissing it keeps it from coming
    // back into view if the dialog closes. The facade announces every open as
    // `np:dialog-open` (see shared/ui/layout/Modal.svelte); a menu action
    // already closes on its own, this covers programmatic opens.
    const handleDialogOpen = (): void => closeMenu(false);

    document.addEventListener('pointerdown', handleDocumentPointerDown, true);
    document.addEventListener('keydown', handleDocumentKeyDown, true);
    window.addEventListener('np:dialog-open', handleDialogOpen);
    return () => {
      document.removeEventListener('pointerdown', handleDocumentPointerDown, true);
      document.removeEventListener('keydown', handleDocumentKeyDown, true);
      window.removeEventListener('np:dialog-open', handleDialogOpen);
    };
  });

  $effect(() => {
    if (!isOpen) return;
    const handleScroll = (): void => closeMenu(false);
    const handleResize = (): void => updateMenuPosition();
    window.addEventListener('scroll', handleScroll, true);
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('scroll', handleScroll, true);
      window.removeEventListener('resize', handleResize);
    };
  });
</script>

<div bind:this={containerEl} role="group" aria-label={triggerLabel} class="relative inline-block">
  <button
    bind:this={triggerEl}
    type="button"
    class="rounded-md border border-(--color-border) bg-(--color-surface) px-2 py-1 text-xs text-(--color-text-muted)"
    aria-label={triggerLabel}
    aria-haspopup="menu"
    aria-expanded={isOpen}
    aria-controls={getShelfMenuId(bookId)}
    data-testid={`shelf-actions-trigger-${bookId}`}
    onclick={toggleMenu}
    onkeydown={handleTriggerKeyDown}
  >
    ...
  </button>

  {#if isOpen}
    <div
      bind:this={menuEl}
      id={getShelfMenuId(bookId)}
      role="menu"
      tabindex="-1"
      aria-label={triggerLabel}
      class="fixed z-[999] w-56 origin-top-right rounded-md bg-(--color-elevated) shadow-xl ring-1 ring-(--color-border)"
      style={menuPos ? `top:${menuPos.top}px; left:${menuPos.left}px` : ''}
      transition:scale={{
        start: 0.96,
        opacity: 0,
        duration: reducedMotion ? 0 : 150,
        easing: cubicOut,
      }}
      onkeydown={handleMenuKeyDown}
    >
      <div class="py-1">
        <button
          type="button"
          role="menuitem"
          tabindex="0"
          data-menu-item="true"
          class="inline-flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover)"
          onclick={() => {
            handleAction(onToggleFavorite);
          }}
        >
          <Star size={16} strokeWidth={1.8} aria-hidden="true" />
          <span>{isFavorite ? favoriteRemoveLabel : favoriteAddLabel}</span>
        </button>

        {#if onRead}
          <button
            type="button"
            role="menuitem"
            tabindex="0"
            data-menu-item="true"
            class="inline-flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover)"
            onclick={() => {
              handleAction(onRead);
            }}
          >
            <BookOpen size={16} strokeWidth={1.8} aria-hidden="true" />
            <span>{readLabel}</span>
          </button>
        {/if}

        <button
          type="button"
          role="menuitem"
          tabindex="0"
          data-menu-item="true"
          class="inline-flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover)"
          onclick={() => {
            handleAction(onEdit);
          }}
        >
          <SquarePen size={16} strokeWidth={1.8} aria-hidden="true" />
          <span>{editLabel}</span>
        </button>

        {#if onViewDetails}
          <button
            type="button"
            role="menuitem"
            tabindex="0"
            data-menu-item="true"
            class="inline-flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover)"
            onclick={() => {
              handleAction(onViewDetails);
            }}
          >
            <Eye size={16} strokeWidth={1.8} aria-hidden="true" />
            <span>{viewDetailsLabel}</span>
          </button>
        {/if}

        <div role="separator" class="my-1 h-px bg-(--color-border)"></div>

        <button
          type="button"
          role="menuitem"
          tabindex="0"
          data-menu-item="true"
          class="inline-flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-(--color-error) hover:bg-(--color-error-soft)"
          onclick={() => {
            handleAction(onRemove);
          }}
        >
          <Trash2 size={16} strokeWidth={1.8} aria-hidden="true" />
          <span>{removeLabel}</span>
        </button>
      </div>
    </div>
  {/if}
</div>
