<script lang="ts">
  import { Select } from 'bits-ui';
  import type { Snippet } from 'svelte';

  export type Option = {
    value: string;
    label: string;
  };

  type Props = {
    options?: Option[];
    value?: string | null;
    placeholder?: string;
    disabled?: boolean;
    onchange?: (detail: { value: string }) => void;
    class?: string;
    /**
     * Extra classes for the PORTALLED panel only — never the trigger.
     *
     * The panel is portalled to `document.body`, so a caller's own scope cannot
     * reach it with a plain class. This is the seam for a caller that has to
     * repaint the panel (a dark surface, for instance). Inert when unset: the
     * merged class list is byte-identical to before.
     */
    contentClass?: string;
    /**
     * Marks the portalled panel as a floating reader menu.
     *
     * OPT-IN and OFF by default, deliberately. `ReaderWorkspace` dismisses every
     * floating menu on a capture-phase `pointerdown` unless the press landed
     * inside one, recognised by the `[data-floating-menu]` marker. This panel is
     * portalled OUT of the menu, so a press on one of its options reads as a
     * press outside and closes the menu the option belongs to — the change never
     * lands.
     *
     * The attributes forwarded here reach the INNER `<div role="listbox">`,
     * which is the element the guard's `closest()` is called from (bits-ui
     * merges `Select.Content`'s rest props onto it). Defaulting this on would
     * make every dropdown in the app claim an exemption it never needs, so only
     * the call sites that live inside a floating menu opt in.
     */
    markAsFloatingMenu?: boolean;
    trigger?: Snippet;
    option?: Snippet<[Option]>;
  };

  let {
    options = [],
    value = $bindable(null),
    placeholder = 'Select...',
    disabled = false,
    onchange,
    class: className = '',
    contentClass = '',
    markAsFloatingMenu = false,
    trigger,
    option,
  }: Props = $props();

  /**
   * The panel's own class list plus `contentClass`, and the floating-menu marker
   * only when the caller asked for it. Kept as one derived object because
   * `Select.Content` spreads its props onto the inner listbox div, so these are
   * the attributes the guard actually sees.
   */
  const contentProps = $derived({
    class:
      'z-(--layer-popover) w-(--bits-select-anchor-width) min-w-[160px] rounded-md ' +
      `bg-(--color-elevated) py-1 shadow-lg ring-1 ring-(--color-border) ${contentClass}`,
    ...(markAsFloatingMenu ? { 'data-floating-menu': 'true' } : {}),
  });

  // Measured: `Select.Value`'s own label resolution treats `""` as "nothing
  // selected" and prints the placeholder, but `HighlightsView` ships real "All"
  // options whose value is `""`. The label is derived from the caller's list so
  // those options still read back into the trigger, exactly as this component
  // did before the swap.
  const selectedLabel = $derived(options.find((o) => o.value === value)?.label ?? placeholder);

  // `onValueChange` is the single writer of the bound value: measured to fire
  // once per change, where `bind:value` plus the callback would deliver the same
  // change twice. `onchange` therefore fires exactly once per real change.
  function handleValueChange(next: string): void {
    value = next;
    onchange?.({ value: next });
  }
</script>

<Select.Root
  type="single"
  value={value ?? ''}
  onValueChange={handleValueChange}
  {disabled}
  items={options}
>
  <!-- The positioning wrapper is kept on purpose. The popup is portalled now, so
       `relative` no longer anchors it, but the wrapper is what makes a caller's
       `w-full` resolve against its own shrink-to-fit box instead of the parent's
       width. Removing it would silently change the width of every `w-full`
       call site. -->
  <div class="relative inline-block">
    <Select.Trigger
      class="inline-flex items-center justify-between rounded-lg border border-(--color-border) bg-(--color-surface) px-3 py-2 text-sm text-(--color-primary) hover:bg-(--color-surface-hover) disabled:cursor-not-allowed disabled:opacity-50 {className}"
    >
      {#if trigger}
        {@render trigger()}
      {:else}
        <Select.Value {placeholder}>{selectedLabel}</Select.Value>
        <svg class="ml-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M19 9l-7 7-7-7"
          />
        </svg>
      {/if}
    </Select.Trigger>
  </div>

  <Select.Portal>
    <!-- `mt-1` moved into `sideOffset={4}` because the portalled layer is
         positioned by floating-ui, not by the old `relative` parent. `w-full`
         became the anchor width for the same reason: inside the floating wrapper
         `100%` would resolve against the wrapper's own `max-content` box. -->
    <Select.Content {...contentProps} side="bottom" align="start" sideOffset={4}>
      {#each options as opt}
        <!-- Item highlight reads bits-ui's `data-highlighted` state so the
             keyboard-active item is visible; `hover:` is kept so pointer hover
             still matches the pre-swap look. -->
        <Select.Item
          value={opt.value}
          label={opt.label}
          class="w-full px-3 py-2 text-left text-sm text-(--color-primary) hover:bg-(--color-surface-hover) data-[highlighted]:bg-(--color-surface-hover)"
        >
          {#if option}
            {@render option(opt)}
          {:else}
            {opt.label}
          {/if}
        </Select.Item>
      {/each}
    </Select.Content>
  </Select.Portal>
</Select.Root>
