<script lang="ts">
  type PersistedHighlight = {
    id: string;
    color: string;
    pageNumber: number;
    rects: Array<{ left: number; top: number; width: number; height: number }>;
  };

  import type { MessageKey } from '$lib/shared/i18n';
  // Stored highlight colors may hold legacy hexes; resolve each through the
  // shared nearest-RGB resolver so they render as their pinned canonical
  // target (spec HPU-2).
  import { nearestHighlightHex } from './pdfSelection';

  type Props = {
    persistedHighlights: PersistedHighlight[];
    currentPage: number;
    scale: number;
    activeHighlightId: string | null;
    onHighlightClick: (hl: PersistedHighlight, event: MouseEvent) => void;
    t?: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let {
    persistedHighlights,
    currentPage,
    scale,
    activeHighlightId,
    onHighlightClick,
    t: _t,
  }: Props = $props();
</script>

<div class="absolute inset-0 z-1 pointer-events-none" role="presentation">
  {#each persistedHighlights.filter((h) => h.pageNumber === currentPage) as hl (hl.id)}
    {#each hl.rects as rect, index (`${hl.id}-${index}`)}
      {@const resolvedColor = nearestHighlightHex(hl.color)}
      <div
        class="absolute rounded pointer-events-auto cursor-pointer"
        class:z-3={activeHighlightId === hl.id}
        style="left: {rect.left * scale}px; top: {rect.top * scale}px; width: {rect.width *
          scale}px; height: {rect.height *
          scale}px; --highlight-color: {resolvedColor}; background: color-mix(in srgb, var(--highlight-color, var(--color-warning)) 48%, transparent); box-shadow: 0 0 0 1px color-mix(in srgb, var(--highlight-color, var(--color-warning)) 25%, transparent);"
        onmouseenter={(e) => {
          (e.currentTarget as HTMLElement).style.background =
            `color-mix(in srgb, var(--highlight-color, var(--color-warning)) 60%, transparent)`;
        }}
        onmouseleave={(e) => {
          const isActive = activeHighlightId === hl.id;
          (e.currentTarget as HTMLElement).style.background = isActive
            ? `color-mix(in srgb, var(--highlight-color, var(--color-warning)) 72%, transparent)`
            : `color-mix(in srgb, var(--highlight-color, var(--color-warning)) 48%, transparent)`;
        }}
        role="button"
        tabindex="0"
        aria-label={_t ? _t('pdf.highlightAria') : 'Highlight'}
        onclick={(e) => onHighlightClick(hl, e)}
        onkeydown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onHighlightClick(hl, e as unknown as MouseEvent);
          }
        }}
      ></div>
    {/each}
  {/each}
</div>
