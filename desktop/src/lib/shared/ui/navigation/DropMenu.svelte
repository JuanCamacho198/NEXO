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
</script>

<DropdownMenu.Root>
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
      <div class="py-1" role="none" use:menuItemSemantics>{@render children?.()}</div>
    </DropdownMenu.Content>
  </DropdownMenu.Portal>
</DropdownMenu.Root>
