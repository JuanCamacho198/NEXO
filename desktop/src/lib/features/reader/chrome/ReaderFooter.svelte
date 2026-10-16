<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    title: string;
    bookProgress: number;
    currentPdfPage: number;
    totalPdfPages: number;
    viewerKind: 'pdf' | 'epub' | null;
    isFullscreen: boolean;
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { title, bookProgress, currentPdfPage, totalPdfPages, viewerKind, isFullscreen, t }: Props =
    $props();
</script>

<footer
  class="flex h-12 shrink-0 items-center justify-between border-t border-(--color-border) bg-(--color-reader-chrome) px-8"
  class:hidden={isFullscreen}
>
  <span class="font-inter text-xs font-normal text-(--color-text-muted)">
    {title}
  </span>
  {#if viewerKind === 'pdf' && totalPdfPages > 0}
    <div class="flex items-center gap-3">
      <span class="font-inter text-xs font-normal text-(--color-text-muted)"
        >{totalPdfPages - currentPdfPage} {t('pdf.pagesLeft')}</span
      >
      <div class="h-2 w-50 rounded-full bg-(--color-surface-strong)">
        <div
          class="h-full rounded-full bg-(--color-accent-sky) transition-all duration-300"
          style="width: {Math.round((currentPdfPage / totalPdfPages) * 100)}%"
        ></div>
      </div>
      <span class="font-inter text-xs font-normal text-(--color-text-muted)"
        >{Math.round((currentPdfPage / totalPdfPages) * 100)}%</span
      >
    </div>
  {:else}
    <div class="flex items-center gap-3">
      <div class="h-2 w-50 rounded-full bg-(--color-surface-strong)">
        <div
          data-testid="footer-progress-bar"
          class="h-full rounded-full bg-(--color-accent-sky) transition-all duration-300"
          style="width: {bookProgress}%"
        ></div>
      </div>
      <span class="font-inter text-xs font-normal text-(--color-text-muted)">{bookProgress}%</span>
    </div>
  {/if}
</footer>
