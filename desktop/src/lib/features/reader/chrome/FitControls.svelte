<script lang="ts">
  import Frame from 'lucide-svelte/icons/frame';
  import UnfoldHorizontal from 'lucide-svelte/icons/unfold-horizontal';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { FitMode } from './fitZoom';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onFit: (mode: FitMode) => void;
  };

  let { t, onFit }: Props = $props();

  const buttonClass =
    'inline-flex items-center justify-center px-2.5 py-1.5 border border-(--color-border) rounded-full bg-(--color-surface) text-(--color-primary) cursor-pointer text-xs min-w-11 min-h-11 hover:bg-[color-mix(in_srgb,var(--color-primary)_8%,var(--color-surface))]';
</script>

<div class="inline-flex items-center gap-1" data-testid="fit-controls">
  <button
    type="button"
    onclick={() => onFit('width')}
    class={buttonClass}
    aria-label={t('reader.fitWidth')}
    data-testid="fit-width"
  >
    <UnfoldHorizontal size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
  </button>
  <button
    type="button"
    onclick={() => onFit('page')}
    class={buttonClass}
    aria-label={t('reader.fitPage')}
    data-testid="fit-page"
  >
    <Frame size={14} strokeWidth={1.8} class="h-3.5 w-3.5" aria-hidden="true" />
  </button>
</div>
