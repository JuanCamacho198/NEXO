<script lang="ts">
  import { titlebarState } from '$lib/stores/titlebarState.svelte';
  import Copy from 'lucide-svelte/icons/copy';
  import Minus from 'lucide-svelte/icons/minus';
  import Square from 'lucide-svelte/icons/square';
  import X from 'lucide-svelte/icons/x';
  import { onMount } from 'svelte';

  let { hidden = false }: { hidden?: boolean } = $props();

  onMount(() => {
    titlebarState.init();
    return () => titlebarState.destroy();
  });
</script>

<div class="flex h-9 items-center pl-4 select-none" class:hidden>
  <!-- Left: branding -->
  <span class="font-bold text-sm text-(--color-accent-blue) leading-none">NP</span>
  <span class="ml-2 text-sm font-medium text-(--color-primary) leading-none tracking-tight"
    >Nexo</span
  >

  <!-- Center: drag region -->
  <div class="flex-1 h-full" data-tauri-drag-region></div>

  <!-- Right: window controls -->
  <button
    onclick={titlebarState.handleMinimize}
    class="w-11 h-9 flex items-center justify-center border-none text-(--color-text-secondary) hover:bg-(--color-surface-hover) cursor-pointer transition-colors"
    aria-label="Minimize"
  >
    <Minus size={16} class="h-4 w-4" aria-hidden="true" />
  </button>
  <button
    onclick={titlebarState.handleMaximize}
    class="w-11 h-9 flex items-center justify-center border-none text-(--color-text-secondary) hover:bg-(--color-surface-hover) cursor-pointer transition-colors"
    aria-label="Maximize"
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
  >
    <X size={16} class="h-4 w-4" aria-hidden="true" />
  </button>
</div>
