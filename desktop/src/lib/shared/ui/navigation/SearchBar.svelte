<script lang="ts">
  type Props = {
    /** Bindable current query. */
    value?: string;
    /** Placeholder text. */
    placeholder?: string;
    /** Accessible name for the inner input. */
    ariaLabel?: string;
    /** Optional id, so a consumer can point an external `<label for>` at it. */
    id?: string;
    /** Render the leading magnifier (default true). */
    icon?: boolean;
    /** Extra classes for the container (call-site sizing/layout). */
    class?: string;
    /** Optional shortcut hint; renders a keycap button when provided. */
    shortcutLabel?: string;
    /** Accessible name for the shortcut keycap. */
    shortcutAriaLabel?: string;
    /** Invoked when the shortcut keycap is activated. */
    onShortcut?: () => void;
    /**
     * Opt-in trigger key (e.g. `/`). When set, the field renders the keycap and
     * focuses itself from anywhere on a matching keydown when the event target
     * is not an editable field.
     */
    shortcutKey?: string;
    /** Accessible name for the clear affordance. */
    clearLabel?: string;
    /** Fired on Enter — Descubrir submits its query from here. */
    onsubmit?: (value: string) => void;
    /** Passthrough test id, placed on the inner input. */
    'data-testid'?: string;
  };

  let {
    value = $bindable(''),
    placeholder = '',
    ariaLabel,
    id,
    icon = true,
    class: className = '',
    shortcutLabel,
    shortcutAriaLabel,
    onShortcut,
    shortcutKey,
    clearLabel = 'Clear search',
    onsubmit,
    'data-testid': dataTestId,
  }: Props = $props();

  let input = $state<HTMLInputElement | null>(null);

  // The keycap renders from either the label or the trigger key, so a screen can
  // opt in with just `shortcutKey` and still get an accessible name.
  const keycapLabel = $derived(shortcutLabel ?? shortcutKey ?? '');
  const keycapAriaLabel = $derived(shortcutAriaLabel ?? keycapLabel);

  // Public so a screen can still drive focus programmatically.
  export function focus(): void {
    input?.focus();
    input?.select();
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Enter' || !onsubmit) return;
    event.preventDefault();
    onsubmit(value);
  }

  function handleShortcut(): void {
    if (onShortcut) onShortcut();
    else focus();
  }

  function isEditableTarget(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false;
    const tag = target.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
  }

  // One window listener per mounted field. `defaultPrevented` makes the first
  // field that handles the key win, so a second mounted field cannot double-handle
  // the same keydown. Editable targets are skipped so the key never steals focus
  // from another field the user is typing in.
  function handleShortcutKey(event: KeyboardEvent): void {
    if (!shortcutKey) return;
    if (event.defaultPrevented) return;
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.key !== shortcutKey) return;
    if (isEditableTarget(event.target)) return;
    event.preventDefault();
    focus();
  }

  function clear(): void {
    value = '';
    focus();
  }
</script>

<svelte:window onkeydown={handleShortcutKey} />

<div
  role="search"
  class={`search-field group flex h-11 w-full min-w-0 items-center gap-2 rounded-2xl border border-(--color-border) bg-(--color-background) px-3.5 transition-colors focus-within:border-(--color-accent) focus-within:ring-2 focus-within:ring-(--color-accent-soft) ${className}`}
>
  {#if icon}
    <!--
      The magnifier is a flow sibling, not an absolutely positioned overlay:
      the text is laid out after it and can never sit under it, even when the
      unlayered global `input[type='text']` rule wins on padding. `type="search"`
      additionally keeps this input out of that rule without touching the
      padding of every other text input.
    -->
    <svg
      class="h-4 w-4 shrink-0 text-(--color-text-muted)"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      aria-hidden="true"
    >
      <circle cx="11" cy="11" r="7"></circle>
      <path d="M20 20L17 17"></path>
    </svg>
  {/if}

  <input
    {id}
    type="search"
    data-testid={dataTestId}
    bind:this={input}
    bind:value
    {placeholder}
    aria-label={ariaLabel}
    class="h-full min-w-0 flex-1 appearance-none border-0 bg-transparent p-0 text-sm text-(--color-primary) outline-none placeholder:text-(--color-text-muted)"
    onkeydown={handleKeydown}
  />

  {#if value}
    <button
      type="button"
      class="shrink-0 rounded-md text-(--color-text-muted) transition-colors hover:text-(--color-primary)"
      aria-label={clearLabel}
      onclick={clear}
    >
      <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M6 18L18 6M6 6l12 12"
        />
      </svg>
    </button>
  {/if}

  {#if keycapLabel}
    <button
      type="button"
      class="shrink-0 rounded-md border border-(--color-border) px-1.5 py-0.5 text-micro text-(--color-text-muted) transition-colors hover:border-(--color-accent) hover:text-(--color-primary)"
      aria-label={keycapAriaLabel}
      onclick={handleShortcut}
    >
      {keycapLabel}
    </button>
  {/if}
</div>
