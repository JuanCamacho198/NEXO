<script lang="ts">
  import { scale } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { TagDto } from '$lib/shared/types/book';

  type Props = {
    highlightId: string;
    position: { x: number; y: number };
    assignedTags: TagDto[];
    onCustomColor: () => void;
    onCopy: () => void;
    onTag: () => void;
    onNote: () => void;
    onDelete: () => void;
    onClose: () => void;
    setColorPickerAnchor?: (el: HTMLElement | null) => void;
    setTagPopoverAnchor?: (el: HTMLElement | null) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let {
    position,
    assignedTags,
    onCustomColor,
    onCopy,
    onTag,
    onNote,
    onDelete,
    onClose,
    setColorPickerAnchor,
    setTagPopoverAnchor,
    t,
  }: Props = $props();

  let paletteBtn = $state<HTMLButtonElement | null>(null);
  let tagBtn = $state<HTMLButtonElement | null>(null);

  // Compact horizontal pill. MENU_HEIGHT_ESTIMATE only drives the above/below
  // flip; the actual height is dictated by tag-chip wrap.
  const MENU_WIDTH = 224;
  const MENU_HEIGHT_ESTIMATE = 56;
  const PADDING = 8;

  const menuX = $derived(
    Math.max(PADDING, Math.min(position.x, window.innerWidth - MENU_WIDTH - PADDING)),
  );
  const menuY = $derived(
    position.y + MENU_HEIGHT_ESTIMATE > window.innerHeight - PADDING
      ? position.y - MENU_HEIGHT_ESTIMATE - PADDING
      : position.y + PADDING,
  );

  // Terminal actions close the menu; opening popovers / modals (color picker,
  // tag picker, note editor) do NOT close — they overlay on top while the
  // user picks/types. The user dismisses the underlying menu explicitly with
  // Escape or by clicking outside.
  function handleCopyClick(): void {
    onCopy();
    onClose();
  }

  function handleDeleteClick(): void {
    onDelete();
    onClose();
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    }
  }

  $effect(() => {
    setColorPickerAnchor?.(paletteBtn);
  });

  $effect(() => {
    setTagPopoverAnchor?.(tagBtn);
  });

  const ICON_BTN =
    'flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-text-inverse) transition-colors hover:bg-white/10 hover:text-(--color-accent-sky) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent-sky)';
  const ICON_BTN_DANGER =
    'flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-(--color-error) transition-colors hover:bg-red-500/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-error)';
  const TOOLTIP =
    'pointer-events-none absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-black/85 px-2 py-1 text-xs font-medium text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100';
</script>

<div
  class="fixed z-[100] rounded-xl border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) p-2 shadow-xl"
  style="left: {menuX}px; top: {menuY}px; width: {MENU_WIDTH}px;"
  role="menu"
  aria-label={t('highlight.contextMenuAriaLabel')}
  in:scale={{ duration: 140, start: 0.94, easing: cubicOut }}
  out:scale={{ duration: 100, start: 0.97, easing: cubicOut }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={handleKeydown}
  tabindex="-1"
>
  <!-- Assigned tags chips (metadata, wraps to multiple lines if many) -->
  {#if assignedTags.length > 0}
    <div
      class="mb-1.5 flex flex-wrap gap-1 px-1 pb-1.5 border-b border-(--color-highlight-menu-border)"
    >
      {#each assignedTags as tag (tag.id)}
        <span
          class="max-w-full truncate rounded-full px-2 py-0.5 text-micro font-medium text-(--color-text-inverse)"
          style={tag.color
            ? `background-color: ${tag.color}33; border: 1px solid ${tag.color}66;`
            : 'background-color: var(--color-highlight-menu-border);'}
        >
          {tag.name}
        </span>
      {/each}
    </div>
  {/if}

  <!-- Actions row (horizontal pill) -->
  <div class="flex items-center gap-2 px-1 py-1" role="group">
    <!-- Change color (opens ColorPickerPopover via onCustomColor).
         Designed as a multicolor gradient trigger with a white ring + "+" icon
         to distinguish it from the action buttons. Reference: Android
         nextPage-movil.pen → "Selection Menu → Color Picker Trigger" -->
    <div class="group relative">
      <button
        type="button"
        bind:this={paletteBtn}
        class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full ring-2 ring-(--color-text-inverse) transition-transform hover:scale-110 focus-visible:outline-none focus-visible:ring-(--color-accent-sky)"
        style="background-image: linear-gradient(135deg, var(--color-error) 0%, var(--color-success) 50%, var(--color-accent-start) 100%);"
        onclick={onCustomColor}
        aria-label={t('highlight.changeColor')}
        title={t('highlight.changeColor')}
      >
        <svg
          class="h-3 w-3 text-white drop-shadow-[0_1px_1px_rgba(0,0,0,0.35)]"
          viewBox="0 0 7 7"
          fill="currentColor"
          aria-hidden="true"
        >
          <path d="M3 4l-3 0 0-1 3 0 0-3 1 0 0 3 3 0 0 1-3 0 0 3-1 0 0-3 0 0" />
        </svg>
      </button>
      <span class={TOOLTIP} role="tooltip">
        {t('highlight.changeColor')}
      </span>
    </div>

    <!-- Separator -->
    <span class="text-base font-normal text-(--color-text-auxiliary)">|</span>

    <!-- Copy -->
    <div class="group relative">
      <button
        type="button"
        class={ICON_BTN}
        onclick={handleCopyClick}
        aria-label={t('reader.copiar')}
        title={t('reader.copiar')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
        </svg>
      </button>
      <span class={TOOLTIP} role="tooltip">{t('reader.copiar')}</span>
    </div>

    <!-- Tag (opens TagPopover) -->
    <div class="group relative">
      <button
        type="button"
        bind:this={tagBtn}
        class={ICON_BTN}
        onclick={onTag}
        aria-label={t('highlight.tag')}
        title={t('highlight.tag')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path
            d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"
          />
          <line x1="7" y1="7" x2="7.01" y2="7" />
        </svg>
      </button>
      <span class={TOOLTIP} role="tooltip">{t('highlight.tag')}</span>
    </div>

    <!-- Note (opens NoteEditorModal) -->
    <div class="group relative">
      <button
        type="button"
        class={ICON_BTN}
        onclick={onNote}
        aria-label={t('highlight.note')}
        title={t('highlight.note')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
          <line x1="9" y1="13" x2="15" y2="13" />
          <line x1="9" y1="17" x2="15" y2="17" />
        </svg>
      </button>
      <span class={TOOLTIP} role="tooltip">{t('highlight.note')}</span>
    </div>

    <!-- Separator -->
    <span class="text-base font-normal text-(--color-text-auxiliary)">|</span>

    <!-- Delete (red) -->
    <div class="group relative">
      <button
        type="button"
        class={ICON_BTN_DANGER}
        onclick={handleDeleteClick}
        aria-label={t('reader.eliminar_destacado')}
        title={t('reader.eliminar_destacado')}
      >
        <svg
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <polyline points="3 6 5 6 21 6" />
          <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
          <path d="M10 11v6" />
          <path d="M14 11v6" />
          <path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2" />
        </svg>
      </button>
      <span class={TOOLTIP} role="tooltip">
        {t('reader.eliminar_destacado')}
      </span>
    </div>
  </div>
</div>

<svelte:window onkeydown={handleKeydown} />
