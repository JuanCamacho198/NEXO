<script lang="ts">
  import { scale } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import Copy from 'lucide-svelte/icons/copy';
  import NotebookPen from 'lucide-svelte/icons/notebook-pen';
  import Tag from 'lucide-svelte/icons/tag';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { TagDto } from '$lib/shared/types/book';
  import { HIGHLIGHT_COLORS, nearestHighlightHex } from './highlightColors';
  import {
    placeFloatingMenu,
    type FloatingMenuAnchor,
  } from '$lib/shared/services/placement/floatingMenuPlacement';
  import {
    MENU_DIVIDER,
    MENU_ICON_BUTTON,
    MENU_ICON_BUTTON_DANGER,
    MENU_SWATCH,
    MENU_SWATCH_DOT,
    MENU_SWATCH_DOT_ACTIVE,
    MENU_TOOLTIP,
  } from './floatingMenuStyles';

  type Props = {
    highlightId: string;
    /** The click point: where the menu goes when there is no anchor box. */
    position: { x: number; y: number };
    /**
     * The highlight's on-screen box (EPUB). When present the menu sits centred
     * above the text, like the selection toolbar, instead of at the click.
     */
    anchor?: FloatingMenuAnchor | null;
    /** The highlight's colour, shown as the palette trigger. */
    currentColor?: string;
    assignedTags: TagDto[];
    /** Picks a colour from the inline palette. */
    onSelectColor?: (hex: string) => void;
    onCopy: () => void;
    onTag: () => void;
    onNote: () => void;
    onDelete: () => void;
    onClose: () => void;
    setTagPopoverAnchor?: (el: HTMLElement | null) => void;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let {
    position,
    anchor = null,
    currentColor = HIGHLIGHT_COLORS[0].hex,
    assignedTags,
    onSelectColor,
    onCopy,
    onTag,
    onNote,
    onDelete,
    onClose,
    setTagPopoverAnchor,
    t,
  }: Props = $props();

  let tagBtn = $state<HTMLButtonElement | null>(null);
  let paletteOpen = $state(false);

  // Fixed, predicted widths (the placement module's rule: place from a known
  // size, never measure-then-move). Closed: one swatch + four actions.
  // Open: the swatch becomes the five-colour row.
  const MENU_WIDTH = 224;
  const MENU_WIDTH_PALETTE = 332;
  const MENU_HEIGHT_ESTIMATE = 56;
  const TAG_ROW_HEIGHT = 30;
  const PADDING = 8;
  const ANCHOR_OFFSET = 12;

  const menuWidth = $derived(paletteOpen ? MENU_WIDTH_PALETTE : MENU_WIDTH);
  const menuHeight = $derived(
    MENU_HEIGHT_ESTIMATE + (assignedTags.length > 0 ? TAG_ROW_HEIGHT : 0),
  );
  const activeHex = $derived(nearestHighlightHex(currentColor));

  const anchored = $derived(
    anchor
      ? placeFloatingMenu(
          anchor,
          { width: menuWidth, height: menuHeight },
          { width: window.innerWidth, height: window.innerHeight },
          { edgePadding: PADDING, offset: ANCHOR_OFFSET },
        )
      : null,
  );

  // Without an anchor (PDF), the menu keeps its click-point placement.
  const menuX = $derived(
    anchored
      ? anchored.x
      : Math.max(PADDING, Math.min(position.x, window.innerWidth - menuWidth - PADDING)),
  );
  const menuY = $derived(
    anchored
      ? anchored.y
      : position.y + MENU_HEIGHT_ESTIMATE > window.innerHeight - PADDING
        ? position.y - MENU_HEIGHT_ESTIMATE - PADDING
        : position.y + PADDING,
  );

  /** The caret's x inside the menu, pointing at the highlight's centre. */
  const caretX = $derived(
    anchor && anchored
      ? Math.max(18, Math.min((anchor.left + anchor.right) / 2 - anchored.x, menuWidth - 18))
      : null,
  );

  // Terminal actions close the menu; opening popovers / modals (tag picker,
  // note editor) do NOT close — they overlay on top while the user picks or
  // types. The reader dismisses the menu with Escape or by clicking outside.
  function handleCopyClick(): void {
    onCopy();
    onClose();
  }

  function handleDeleteClick(): void {
    onDelete();
    onClose();
  }

  function pickColor(hex: string): void {
    paletteOpen = false;
    onSelectColor?.(hex);
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (paletteOpen) paletteOpen = false;
      else onClose();
    }
  }

  $effect(() => {
    setTagPopoverAnchor?.(tagBtn);
  });
</script>

<div
  class="fixed z-[100] rounded-2xl border border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) px-2 py-1.5 shadow-[0_16px_36px_-14px_rgba(0,0,0,0.6)] transition-[width] duration-150"
  data-floating-menu="true"
  data-placement={anchored?.placement ?? 'point'}
  style="left: {menuX}px; top: {menuY}px; width: {menuWidth}px;"
  role="menu"
  data-testid="highlight-context-menu"
  aria-label={t('highlight.contextMenuAriaLabel')}
  in:scale={{ duration: 140, start: 0.94, easing: cubicOut }}
  out:scale={{ duration: 100, start: 0.97, easing: cubicOut }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={handleKeydown}
  tabindex="-1"
>
  {#if caretX !== null && anchored}
    <!-- The caret ties the menu to the text it acts on. -->
    <span
      class="pointer-events-none absolute size-2.5 rotate-45 border-(--color-highlight-menu-border) bg-(--color-highlight-menu-bg) {anchored.placement ===
      'above'
        ? '-bottom-[5px] border-r border-b'
        : '-top-[5px] border-t border-l'}"
      style="left: {caretX - 5}px;"
      aria-hidden="true"
    ></span>
  {/if}

  <!-- Assigned tags (metadata; wraps if there are many) -->
  {#if assignedTags.length > 0}
    <div
      class="mb-1 flex flex-wrap gap-1 border-b border-(--color-highlight-menu-border) px-1 pt-0.5 pb-1.5"
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

  <!-- The box has a fixed, predicted width (no measure-then-move); the
       actions spread across it so it never ends in an empty strip. -->
  <div class="flex items-center justify-between" role="group">
    <!-- Colour: the highlight's own colour is the trigger, and it opens the
         same five swatches the selection toolbar shows, in place — one click
         to see the choice, one to make it. -->
    {#if paletteOpen}
      <div class="flex items-center gap-0.5" role="group" aria-label={t('highlight.colors')}>
        {#each HIGHLIGHT_COLORS as color (color.hex)}
          <button
            type="button"
            class={MENU_SWATCH}
            onclick={() => pickColor(color.hex)}
            aria-label={t('highlight.selectColor', { color: t(color.i18nKey) })}
            aria-pressed={activeHex === color.hex}
          >
            <span
              class="{MENU_SWATCH_DOT} {activeHex === color.hex ? MENU_SWATCH_DOT_ACTIVE : ''}"
              style="background-color: {color.hex};"
            ></span>
          </button>
        {/each}
      </div>
    {:else}
      <div class="group relative">
        <button
          type="button"
          class={MENU_SWATCH}
          onclick={() => (paletteOpen = true)}
          aria-label={t('highlight.changeColor')}
          aria-expanded={paletteOpen}
          title={t('highlight.changeColor')}
        >
          <span
            class="{MENU_SWATCH_DOT} {MENU_SWATCH_DOT_ACTIVE}"
            style="background-color: {currentColor};"
          ></span>
        </button>
        <span class={MENU_TOOLTIP} role="tooltip">{t('highlight.changeColor')}</span>
      </div>
    {/if}

    <span class={MENU_DIVIDER} aria-hidden="true"></span>

    <div class="group relative">
      <button
        type="button"
        class={MENU_ICON_BUTTON}
        onclick={onNote}
        aria-label={t('highlight.note')}
        title={t('highlight.note')}
      >
        <NotebookPen size={16} strokeWidth={2} aria-hidden="true" />
      </button>
      <span class={MENU_TOOLTIP} role="tooltip">{t('highlight.note')}</span>
    </div>

    <div class="group relative">
      <button
        type="button"
        bind:this={tagBtn}
        class={MENU_ICON_BUTTON}
        onclick={onTag}
        aria-label={t('highlight.tag')}
        title={t('highlight.tag')}
      >
        <Tag size={16} strokeWidth={2} aria-hidden="true" />
      </button>
      <span class={MENU_TOOLTIP} role="tooltip">{t('highlight.tag')}</span>
    </div>

    <div class="group relative">
      <button
        type="button"
        class={MENU_ICON_BUTTON}
        onclick={handleCopyClick}
        aria-label={t('reader.copiar')}
        title={t('reader.copiar')}
      >
        <Copy size={16} strokeWidth={2} aria-hidden="true" />
      </button>
      <span class={MENU_TOOLTIP} role="tooltip">{t('reader.copiar')}</span>
    </div>

    <span class={MENU_DIVIDER} aria-hidden="true"></span>

    <div class="group relative">
      <button
        type="button"
        class={MENU_ICON_BUTTON_DANGER}
        onclick={handleDeleteClick}
        aria-label={t('reader.eliminar_destacado')}
        title={t('reader.eliminar_destacado')}
      >
        <Trash2 size={16} strokeWidth={2} aria-hidden="true" />
      </button>
      <span class={MENU_TOOLTIP} role="tooltip">{t('reader.eliminar_destacado')}</span>
    </div>
  </div>
</div>

<svelte:window onkeydown={handleKeydown} />
