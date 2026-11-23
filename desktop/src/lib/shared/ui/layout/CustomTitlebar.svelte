<script lang="ts">
  import { titlebarState } from '$lib/stores/titlebarState.svelte';
  import Copy from 'lucide-svelte/icons/copy';
  import Minus from 'lucide-svelte/icons/minus';
  import Square from 'lucide-svelte/icons/square';
  import X from 'lucide-svelte/icons/x';
  import { onMount } from 'svelte';

  let { hidden = false, floating = false }: { hidden?: boolean; floating?: boolean } = $props();

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

  const controlClass =
    'pointer-events-auto flex h-9 w-12 cursor-pointer items-center justify-center border-none text-(--color-text-muted) transition-colors duration-(--duration-fast) ease-(--ease-smooth) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-(--color-accent-nav-bar)';
</script>

<!--
  The window is borderless on every platform (`decorations: false`), so this bar
  is the only chrome. Layout follows the platform: Windows and Linux put the
  controls on the right, macOS puts them on the left.

  `floating`: on the sidebar shell the bar leaves the layout and floats over the
  window's top band, transparent, so the rail runs to the top edge and the
  window controls sit over the content corner. The bar ignores the pointer and
  only its drag region and controls take it back, so nothing under the band is
  blocked except by those. The reader keeps the in-flow bar, because its own
  chrome sits at the top of the content.

  macOS inset: `TrafficLightPosition` in `tauri.conf.json` places the native
  lights at y=20, i.e. their centres sit on the bar's vertical centre. The bar
  reserves a wide empty block on the left for them, and the drag region starts
  only after that block, so a drag can never begin on top of a traffic light.

  Accessibility: `role="banner"` + `aria-label` gives a screen reader one named
  landmark for the window frame instead of loose text above the document. The
  drag regions are `aria-hidden` and `role="presentation"` — they are pointer
  affordances, not content, and announcing an empty div is noise. Branding is
  `aria-hidden` for the same reason: the landmark already carries the name.
-->
<div
  class="flex h-9 shrink-0 items-center select-none {floating
    ? 'pointer-events-none fixed inset-x-0 top-0 z-40'
    : ''}"
  class:pl-4={!isMac && !floating}
  class:hidden
  data-platform={isMac ? 'macos' : 'other'}
  data-floating={floating ? 'true' : undefined}
  role="banner"
  aria-label="Window"
>
  {#if isMac}
    <!-- Reserved for the native traffic lights; never draggable, never occluded. -->
    <div class="h-full w-[78px] shrink-0" aria-hidden="true"></div>
    {#if !floating}
      <span
        class="text-sm leading-none font-bold tracking-[0.2em] text-(--color-brand-text)"
        aria-hidden="true">NEXO</span
      >
    {/if}
    <div
      class="pointer-events-auto h-full flex-1"
      data-tauri-drag-region
      role="presentation"
      aria-hidden="true"
    ></div>
  {:else}
    {#if !floating}
      <!-- Left: branding (the sidebar carries the logo when it is shown) -->
      <span
        class="text-sm leading-none font-bold tracking-[0.2em] text-(--color-brand-text)"
        aria-hidden="true">NEXO</span
      >
    {/if}

    <!-- Center: drag region -->
    <div
      class="pointer-events-auto h-full flex-1"
      data-tauri-drag-region
      role="presentation"
      aria-hidden="true"
    ></div>

    <!-- Right: window controls -->
    <button
      onclick={titlebarState.handleMinimize}
      class="{controlClass} hover:bg-(--color-surface-hover) hover:text-(--color-primary)"
      aria-label="Minimize"
      title="Minimize"
    >
      <Minus size={16} strokeWidth={1.6} aria-hidden="true" />
    </button>
    <button
      onclick={titlebarState.handleMaximize}
      class="{controlClass} hover:bg-(--color-surface-hover) hover:text-(--color-primary)"
      aria-label={titlebarState.isMaximized ? 'Restore' : 'Maximize'}
      title={titlebarState.isMaximized ? 'Restore' : 'Maximize'}
    >
      {#if titlebarState.isMaximized}
        <Copy size={14} strokeWidth={1.6} aria-hidden="true" />
      {:else}
        <Square size={13} strokeWidth={1.6} aria-hidden="true" />
      {/if}
    </button>
    <button
      onclick={titlebarState.handleClose}
      class="{controlClass} hover:bg-(--color-error) hover:text-white"
      aria-label="Close"
      title="Close"
    >
      <X size={16} strokeWidth={1.6} aria-hidden="true" />
    </button>
  {/if}
</div>
