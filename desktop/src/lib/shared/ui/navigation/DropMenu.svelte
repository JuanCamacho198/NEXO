<script lang="ts">
  import { DropdownMenu } from 'bits-ui';
  import type { Snippet } from 'svelte';
  import { menuItemSemantics, menuTriggerHost } from './menuItemSemantics';

  type Props = {
    trigger: Snippet;
    children?: Snippet;
    position?: 'bottom-left' | 'bottom-right';
  };

  let { trigger, children, position = 'bottom-right' }: Props = $props();

  const align = $derived(position === 'bottom-right' ? 'end' : 'start');

  let open = $state(false);

  // The items are the caller's plain buttons, not bits-ui items, so picking one
  // never closed the menu: it stayed up over the dialog the item opened, and
  // again after that dialog closed (measured on the library "⋯" menu). A click
  // that lands on an item is a choice, so the menu closes on it.
  function closeOnPick(event: MouseEvent): void {
    const target = event.target as Element | null;
    if (target?.closest('[role="menuitem"]')) open = false;
  }
</script>

<DropdownMenu.Root bind:open>
  <!-- The caller's `trigger` snippets are zero-argument and render their own
       `<button>`, so bits-ui's trigger props are routed onto that button by
       `menuTriggerHost` instead of onto the host the `child` snippet returns.
       The host is `display: contents`: it generates no box and carries no role,
       which is what removes the nested-interactive wrapper this component used
       to render. -->
  <DropdownMenu.Trigger>
    {#snippet child({ props })}
      <span class="contents" use:menuTriggerHost={props}>{@render trigger()}</span>
    {/snippet}
  </DropdownMenu.Trigger>

  <DropdownMenu.Portal>
    <DropdownMenu.Content
      class="z-(--layer-popover) w-56 rounded-md bg-(--color-elevated) shadow-lg ring-1 ring-(--color-border) focus:outline-none"
      side="bottom"
      {align}
      sideOffset={8}
    >
      <!-- `children` render verbatim; `menuItemSemantics` supplies the item
           registration bits-ui cannot, because the five items are caller-owned
           plain buttons. `role="none"` keeps the `role="menu"` ownership
           relationship intact across this layout wrapper. -->
      <!-- svelte-ignore a11y_click_events_have_key_events -->
      <div class="py-1" role="none" use:menuItemSemantics onclick={closeOnPick}>
        {@render children?.()}
      </div>
    </DropdownMenu.Content>
  </DropdownMenu.Portal>
</DropdownMenu.Root>
