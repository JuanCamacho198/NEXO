<script lang="ts">
  import { scale } from 'svelte/transition';
  import { cubicOut } from 'svelte/easing';
  import BookOpenText from 'lucide-svelte/icons/book-open-text';
  import Copy from 'lucide-svelte/icons/copy';
  import Languages from 'lucide-svelte/icons/languages';
  import NotebookPen from 'lucide-svelte/icons/notebook-pen';
  import Tag from 'lucide-svelte/icons/tag';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { TagDto } from '$lib/shared/types/book';
  import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';
  import type { DefinitionResult } from '$lib/shared/ports/DictionaryLookupPort';
  import { isDefinableSelection } from '$lib/shared/services/definition/definitionSelectionGuard';
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
  import LookupPanel from './LookupPanel.svelte';
  import { createLookup, type LanguagePair } from './useLookup.svelte';

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
    /** The highlighted text: what Translate and Define look up. */
    text?: string;
    assignedTags: TagDto[];
    /** Picks a colour from the inline palette. */
    onSelectColor?: (hex: string) => void;
    /**
     * The same lookups the selection toolbar offers, for the highlight's text.
     * Without them the two buttons are not shown.
     */
    onTranslate?: () => Promise<TranslationOutcome>;
    onDefine?: () => Promise<DefinitionResult>;
    onChangeLanguages?: (next: LanguagePair & { changed: 'source' | 'target' }) => Promise<void>;
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
    text = '',
    assignedTags,
    onSelectColor,
    onTranslate,
    onDefine,
    onChangeLanguages,
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

  const hasLookup = $derived(Boolean(onTranslate && onDefine));
  const canDefine = $derived(isDefinableSelection(text));

  const lookup = createLookup({
    translate: () => onTranslate!(),
    define: () => onDefine!(),
    changeLanguages: (next) => onChangeLanguages?.(next) ?? Promise.resolve(),
    canTranslate: () => Boolean(onTranslate) && text.trim().length > 0,
    canDefine: () => Boolean(onDefine) && canDefine,
  });

  // Fixed, predicted widths (the placement module's rule: place from a known
  // size, never measure-then-move). Every piece of the row has a fixed size:
  // 28px swatches, 32px actions, 2px gaps, 9px dividers, 8px padding, a 1px
  // border. The row is centred in the box, so a few pixels of slack never
  // shows as an empty strip at one end.
  const ACTIONS = 3; // note, tag, copy
  const LOOKUP_ACTIONS = 2; // translate, define
  const rowWidth = (swatches: number): number => {
    const lookupWidth = hasLookup ? LOOKUP_ACTIONS * 32 + 9 : 0;
    const items = swatches + (hasLookup ? LOOKUP_ACTIONS + 1 : 0) + 1 + ACTIONS + 1 + 1;
    return swatches * 28 + 9 + lookupWidth + ACTIONS * 32 + 9 + 32 + (items - 1) * 2;
  };
  const LOOKUP_PANEL_WIDTH = 340;
  const LOOKUP_PANEL_BUDGET = 260;
  const MENU_HEIGHT_ESTIMATE = 56;
  const TAG_ROW_HEIGHT = 30;
  const PADDING = 8;
  const ANCHOR_OFFSET = 12;

  const menuWidth = $derived(
    Math.max(
      rowWidth(paletteOpen ? HIGHLIGHT_COLORS.length : 1) + 2 * 8 + 2,
      lookup.open ? LOOKUP_PANEL_WIDTH : 0,
    ),
  );
  const menuHeight = $derived(
    MENU_HEIGHT_ESTIMATE +
      (assignedTags.length > 0 ? TAG_ROW_HEIGHT : 0) +
      (lookup.open ? LOOKUP_PANEL_BUDGET : 0),
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
      : position.y + menuHeight > window.innerHeight - PADDING
        ? position.y - menuHeight - PADDING
        : position.y + PADDING,
  );

  /** The caret's x inside the menu, pointing at the highlight's centre. */
  const caretX = $derived(
    anchor && anchored
      ? Math.max(18, Math.min((anchor.left + anchor.right) / 2 - anchored.x, menuWidth - 18))
      : null,
  );

  /** The lookup panel's ceiling: its budget, or less when the window is short. */
  const panelMaxHeight = $derived(
    Math.max(
      120,
      Math.min(LOOKUP_PANEL_BUDGET - 8, window.innerHeight - menuY - MENU_HEIGHT_ESTIMATE - 16),
    ),
  );

  // Terminal actions close the menu; opening popovers / modals (tag picker,
  // note editor) and the lookup panel do NOT close it. The reader dismisses
  // the menu with Escape or by clicking outside.
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

  function openPalette(): void {
    lookup.close();
    paletteOpen = true;
  }

  function toggleLookup(tab: 'translate' | 'define'): void {
    paletteOpen = false;
    lookup.toggle(tab);
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (paletteOpen) paletteOpen = false;
      else if (lookup.open) lookup.close();
      else onClose();
    }
  }

  $effect(() => {
    setTagPopoverAnchor?.(tagBtn);
  });
</script>

{#snippet action(
  label: string,
  Icon: typeof Copy,
  onclick: () => void,
  opts: { active?: boolean; disabled?: boolean; danger?: boolean; bind?: boolean } = {},
)}
  <div class="group relative">
    {#if opts.bind}
      <button
        type="button"
        bind:this={tagBtn}
        class={MENU_ICON_BUTTON}
        {onclick}
        aria-label={label}
        title={label}
      >
        <Icon size={16} strokeWidth={2} aria-hidden="true" />
      </button>
    {:else}
      <button
        type="button"
        class="{opts.danger ? MENU_ICON_BUTTON_DANGER : MENU_ICON_BUTTON} {opts.active
          ? 'bg-white/10 text-(--color-brand-text)'
          : ''}"
        {onclick}
        disabled={opts.disabled}
        aria-label={label}
        aria-pressed={opts.active === undefined ? undefined : opts.active}
        title={label}
      >
        <Icon size={16} strokeWidth={2} aria-hidden="true" />
      </button>
    {/if}
    {#if !lookup.open}
      <span class={MENU_TOOLTIP} role="tooltip">{label}</span>
    {/if}
  </div>
{/snippet}

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
  onwheel={(e) => e.stopPropagation()}
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
          #{tag.name}
        </span>
      {/each}
    </div>
  {/if}

  <!-- Colour | look it up | annotate | copy | delete. The row keeps its
       natural width and is centred in the box. -->
  <div class="mx-auto flex w-max items-center gap-0.5" role="group">
    <!-- Colour: the highlight's own colour is the trigger, and it opens the
         same five swatches the selection toolbar shows, in place. -->
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
          onclick={openPalette}
          aria-label={t('highlight.changeColor')}
          aria-expanded={paletteOpen}
          title={t('highlight.changeColor')}
        >
          <span
            class="{MENU_SWATCH_DOT} {MENU_SWATCH_DOT_ACTIVE}"
            style="background-color: {currentColor};"
          ></span>
        </button>
        {#if !lookup.open}
          <span class={MENU_TOOLTIP} role="tooltip">{t('highlight.changeColor')}</span>
        {/if}
      </div>
    {/if}

    <span class={MENU_DIVIDER} aria-hidden="true"></span>

    {#if hasLookup}
      {@render action(t('reader.translate'), Languages, () => toggleLookup('translate'), {
        active: lookup.tab === 'translate',
      })}
      {@render action(t('reader.define'), BookOpenText, () => toggleLookup('define'), {
        active: lookup.tab === 'define',
        disabled: !canDefine,
      })}
      <span class={MENU_DIVIDER} aria-hidden="true"></span>
    {/if}

    {@render action(t('highlight.note'), NotebookPen, onNote)}
    {@render action(t('highlight.tag'), Tag, onTag, { bind: true })}
    {@render action(t('reader.copiar'), Copy, handleCopyClick)}

    <span class={MENU_DIVIDER} aria-hidden="true"></span>

    {@render action(t('reader.eliminar_destacado'), Trash2, handleDeleteClick, { danger: true })}
  </div>

  {#if lookup.open}
    <LookupPanel {lookup} {t} width={menuWidth - 16} maxHeight={panelMaxHeight} />
  {/if}
</div>

<svelte:window onkeydown={handleKeydown} />
