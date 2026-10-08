<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import {
    COMMAND_GROUPS,
    COMMAND_REGISTRY,
    defaultCommandPaletteActions,
    runCommand,
    type CommandEntry,
    type CommandPaletteActions,
  } from './commands';
  import { rankMatches } from './commandSearch';
  import { commandPaletteState } from './commandPaletteState.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    /** Injectable so tests can assert every row runs a real action. */
    actions?: CommandPaletteActions;
  };

  let { t, actions = defaultCommandPaletteActions }: Props = $props();

  const listboxId = 'command-palette-listbox';

  let query = $state('');
  let activeIndex = $state(0);
  let inputEl = $state<HTMLInputElement | null>(null);
  // Non-reactive on purpose: only read back when the palette closes.
  let previouslyFocused: HTMLElement | null = null;

  // Ranked, group-major view. Within each command group the rows whose own
  // label matches the query come first, followed by the rows that only matched
  // the group's descriptive name; ties keep the registry order. The listbox
  // renders this exact order, so the flat list the keyboard walks (`filtered`)
  // and the grouped DOM never disagree.
  const rankedGroups = $derived.by(() => {
    const needle = query.trim().toLowerCase();
    return COMMAND_GROUPS.map((group) => {
      const groupLabel = t(group.labelKey);
      const candidates = COMMAND_REGISTRY.filter((entry) => entry.group === group.id);
      const entries =
        needle.length === 0
          ? candidates
          : rankMatches(
              candidates,
              needle,
              (entry) => t(entry.descriptionKey),
              () => groupLabel,
            );
      return { ...group, entries };
    }).filter((group) => group.entries.length > 0);
  });

  const filtered = $derived(rankedGroups.flatMap((group) => group.entries));

  const activeEntry = $derived(filtered[activeIndex] as CommandEntry | undefined);

  const groups = $derived(rankedGroups);

  function optionId(entry: CommandEntry): string {
    return `command-option-${entry.id}`;
  }

  function move(delta: number): void {
    const count = filtered.length;
    if (count === 0) return;
    activeIndex = (activeIndex + delta + count) % count;
  }

  function execute(entry: CommandEntry): void {
    runCommand(entry, actions);
    commandPaletteState.hide();
  }

  function handleInputKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        move(1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        move(-1);
        break;
      case 'Home':
        event.preventDefault();
        activeIndex = 0;
        break;
      case 'End':
        event.preventDefault();
        if (filtered.length > 0) activeIndex = filtered.length - 1;
        break;
      case 'Enter':
        event.preventDefault();
        if (activeEntry) execute(activeEntry);
        break;
      default:
        break;
    }
  }

  // A fresh palette on every open: empty query and cursor on the first row.
  $effect(() => {
    if (commandPaletteState.open) {
      query = '';
      activeIndex = 0;
    }
  });

  // Put the caret in the input so the user can type straight away. This cannot
  // ride the open flag: bits-ui portals the content, so when the effect first
  // runs `inputEl` is still null. The deterministic fix is `onOpenAutoFocus`
  // below; this only covers the case where the dialog is already mounted.
  $effect(() => {
    if (!commandPaletteState.open || !inputEl) return;
    inputEl.focus();
  });

  // Keep the cursor inside the window the query leaves behind.
  $effect(() => {
    if (filtered.length === 0) {
      activeIndex = 0;
    } else if (activeIndex >= filtered.length) {
      activeIndex = filtered.length - 1;
    }
  });

  // Follow the highlighted row so the selection is always visible.
  $effect(() => {
    const entry = activeEntry;
    if (!entry || typeof document === 'undefined') return;
    const el = document.getElementById(optionId(entry));
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' });
  });

  // Take the dialog's initial focus instead of letting bits-ui land it on the
  // first focusable element — the modal's close button — which left the palette
  // open but untypeable.
  function handleOpenAutoFocus(event: Event): void {
    event.preventDefault();
    // Remember where focus was before the palette opened. Ctrl+K can be pressed
    // with nothing focused (`document.activeElement === document.body`), in
    // which case bits-ui has no opener to return to and closing would dump the
    // user at the top of the page.
    previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    inputEl?.focus();
  }

  function isRestorable(el: HTMLElement | null): el is HTMLElement {
    if (!el || el === document.body || !el.isConnected) return false;
    if (el.hasAttribute('disabled')) return false;
    return el.tabIndex >= 0 || el.isContentEditable;
  }

  // Return focus to the remembered element, or to the main content region when
  // it has been removed or is no longer focusable.
  function restoreFocus(): void {
    if (isRestorable(previouslyFocused)) {
      previouslyFocused.focus();
      return;
    }
    const fallback = document.getElementById('main-content');
    if (fallback instanceof HTMLElement) fallback.focus();
  }

  // Runs after bits-ui's own close-focus restore (which no-ops when the opener
  // was removed), so the fallback actually lands.
  let wasOpen = false;
  $effect(() => {
    const isOpen = commandPaletteState.open;
    if (wasOpen && !isOpen) restoreFocus();
    wasOpen = isOpen;
  });
</script>

<!--
  The real Ctrl+K command palette. It is a bits-ui dialog (via the app's Modal
  facade), so focus is trapped inside while open and returned to the previously
  focused element on close. The input drives the listbox with the WAI-ARIA
  combobox pattern; the verbatim result count is announced through a live
  region, and the empty state uses its own message.
-->
<Modal
  bind:open={commandPaletteState.open}
  title={t('settings.shortcuts.commandPalette')}
  size="lg"
  onOpenAutoFocus={handleOpenAutoFocus}
>
  <div class="flex flex-col gap-3">
    <input
      type="text"
      role="combobox"
      aria-expanded={filtered.length > 0}
      aria-controls={filtered.length > 0 ? listboxId : undefined}
      aria-activedescendant={activeEntry ? optionId(activeEntry) : undefined}
      aria-autocomplete="list"
      aria-label={t('commandPalette.searchLabel')}
      placeholder={t('commandPalette.searchPlaceholder')}
      autocomplete="off"
      spellcheck="false"
      bind:this={inputEl}
      bind:value={query}
      onkeydown={handleInputKeydown}
      class="w-full rounded-lg border border-(--color-border) bg-(--color-background) px-3 py-2.5 text-sm text-(--color-primary) transition-colors placeholder:text-(--color-text-muted) focus-visible:border-(--color-accent)"
    />

    <p class="sr-only" role="status" aria-live="polite">
      {t('commandPalette.resultsCount', { count: filtered.length })}
    </p>

    {#if filtered.length === 0}
      <p
        class="rounded-lg border border-(--color-border) bg-(--color-surface) px-3 py-8 text-center text-sm text-(--color-text-muted)"
      >
        {t('commandPalette.empty')}
      </p>
    {:else}
      <ul
        id={listboxId}
        role="listbox"
        aria-label={t('commandPalette.listLabel')}
        class="max-h-80 list-none overflow-y-auto rounded-lg border border-(--color-border) bg-(--color-surface) p-1"
      >
        {#each groups as group (group.id)}
          <li
            role="presentation"
            class="px-3 pt-2 pb-1 text-xs font-semibold tracking-wide text-(--color-text-muted) uppercase"
          >
            {t(group.labelKey)}
          </li>
          {#each group.entries as entry (entry.id)}
            {@const isActive = activeEntry?.id === entry.id}
            <!--
              Keyboard interaction belongs to the combobox input above (the
              WAI-ARIA listbox pattern routes arrows/Enter through it), so the
              option itself only needs pointer activation.
            -->
            <!-- svelte-ignore a11y_click_events_have_key_events -->
            <li
              id={optionId(entry)}
              role="option"
              aria-selected={isActive}
              class="flex cursor-pointer items-center rounded-md px-3 py-2 text-sm transition-colors {isActive
                ? 'bg-(--color-accent-soft) text-(--color-primary)'
                : 'text-(--color-primary) hover:bg-(--color-surface-subtle)'}"
              onmouseenter={() => {
                activeIndex = filtered.indexOf(entry);
              }}
              onclick={() => execute(entry)}
            >
              {t(entry.descriptionKey)}
            </li>
          {/each}
        {/each}
      </ul>
    {/if}
  </div>
</Modal>
