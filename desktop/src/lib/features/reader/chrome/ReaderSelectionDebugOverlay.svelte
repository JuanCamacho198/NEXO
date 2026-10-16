<script lang="ts">
  type SelectionBounds = { left: number; top: number; right: number; bottom: number };
  type SelectionContainer = { left: number; top: number; width: number; height: number };

  type Props = {
    enabled: boolean;
    computedToolbarX: number | null;
    computedToolbarY: number | null;
    selectionBounds: SelectionBounds | null;
    selectionContainer: SelectionContainer | null;
  };

  let { enabled, computedToolbarX, computedToolbarY, selectionBounds, selectionContainer }: Props =
    $props();
</script>

{#if enabled && computedToolbarX !== null && computedToolbarY !== null}
  <div
    aria-hidden="true"
    class="pointer-events-none fixed z-9998"
    style="left: {computedToolbarX}px; top: {computedToolbarY}px;"
  >
    <div class="relative">
      <div class="absolute -left-1 -top-1 h-3 w-3 rounded-full bg-cyan-400 ring-2 ring-white"></div>
      <div
        class="absolute left-3 top-0 whitespace-nowrap rounded bg-cyan-500 px-1.5 py-0.5 text-micro font-mono text-white shadow"
      >
        toolbar target ({Math.round(computedToolbarX)},{Math.round(computedToolbarY)})
      </div>
    </div>
  </div>
{/if}
{#if enabled && selectionBounds && selectionContainer}
  {@const selParentLeft = selectionContainer.left + selectionBounds.left}
  {@const selParentTop = selectionContainer.top + selectionBounds.top}
  {@const selParentWidth = selectionBounds.right - selectionBounds.left}
  {@const selParentHeight = selectionBounds.bottom - selectionBounds.top}
  <div
    aria-hidden="true"
    class="pointer-events-none fixed z-9997 border-2 border-dashed border-red-500"
    style="left: {selParentLeft}px; top: {selParentTop}px; width: {selParentWidth}px; height: {selParentHeight}px;"
  >
    <span
      class="absolute -top-5 left-0 whitespace-nowrap rounded bg-red-500 px-1.5 py-0.5 text-micro font-mono text-white shadow"
      >selection bounds ({Math.round(selParentLeft)},{Math.round(selParentTop)})</span
    >
  </div>
{/if}
