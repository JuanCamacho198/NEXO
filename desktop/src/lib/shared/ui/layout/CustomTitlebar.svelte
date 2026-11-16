<script lang="ts">
  import { titlebarState } from '$lib/stores/titlebarState.svelte';
  import Copy from 'lucide-svelte/icons/copy';
  import Minus from 'lucide-svelte/icons/minus';
  import Square from 'lucide-svelte/icons/square';
  import X from 'lucide-svelte/icons/x';
  import { onMount } from 'svelte';

  let { hidden = false }: { hidden?: boolean } = $props();

  const isMac = titlebarState.isMacLayout;

  onMount(() => {
    // A failed init only costs the maximize/restore icon and the resize
    // listener; it must never take the window controls down with it, so the
    // rejection is contained here rather than surfacing as an unhandled one.
    titlebarState.init().catch((err) => {
      console.warn('[custom-titlebar] init() failed:', err);
    });
    return () => titlebarState.destroy();
  });
</script>

<!--
  The window is borderless on every platform (`decorations: false`), so this bar
  is the only chrome. Layout follows the platform: Windows and Linux put the
  controls on the right, macOS puts them on the left.

  macOS inset: `TrafficLightPosition` in `tauri.conf.json` places the native
  lights at y=20, i.e. their centres sit on the bar's vertical centre. The bar
  reserves a wide empty block on the left for them, and the drag region starts
  only after that block, so a drag can never begin on top of a traffic light.
-->
<div
  class="flex h-9 shrink-0 items-center select-none"
  class:pl-4={!isMac}
  class:hidden
  data-platform={isMac ? 'macos' : 'other'}
>
  {#if isMac}
    <!-- Reserved for the native traffic lights; never draggable, never occluded. -->
    <div class="h-full w-[78px] shrink-0" aria-hidden="true"></div>
    <span class="font-bold text-sm text-(--color-accent-blue) leading-none">NEXO</span>
    <div class="flex-1 h-full" data-tauri-drag-region></div>
  {:else}
    <!-- Left: branding -->
    <span class="font-bold text-sm text-(--color-accent-blue) leading-none">NEXO</span>

    <!-- Center: drag region -->
    <div class="flex-1 h-full" data-tauri-drag-region></div>

    <!-- Right: window controls -->
    <button
      onclick={titlebarState.handleMinimize}
      class="w-11 h-9 flex items-center justify-center border-none text-(--color-text-secondary) hover:bg-(--color-surface-hover) cursor-pointer transition-colors"
      aria-label="Minimize"
      title="Minimize"
    >
      <Minus size={16} class="h-4 w-4" aria-hidden="true" />
    </button>
    <button
      onclick={titlebarState.handleMaximize}
      class="w-11 h-9 flex items-center justify-center border-none text-(--color-text-secondary) hover:bg-(--color-surface-hover) cursor-pointer transition-colors"
      aria-label="Maximize"
      title="Maximize"
    >
      {#if titlebarState.isMaximized}
        <Copy size={16} class="h-4 w-4" aria-hidden="true" />
      {:else}
        <Square size={16} class="h-4 w-4" aria-hidden="true" />
      {/if}
    </button>
    <button
      onclick={titlebarState.handleClose}
      class="w-11 h-9 flex items-center justify-center border-none text-(--color-text-secondary) hover:bg-(--color-error) hover:text-white cursor-pointer transition-colors"
      aria-label="Close"
      title="Close"
    >
      <X size={16} class="h-4 w-4" aria-hidden="true" />
    </button>
  {/if}
</div>
