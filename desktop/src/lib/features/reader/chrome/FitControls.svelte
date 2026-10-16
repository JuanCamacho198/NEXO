<script lang="ts">
  import Frame from 'lucide-svelte/icons/frame';
  import UnfoldHorizontal from 'lucide-svelte/icons/unfold-horizontal';
  import { chromeIconButton } from './chromeControl';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { FitMode } from './fitZoom';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    onFit: (mode: FitMode) => void;
  };

  let { t, onFit }: Props = $props();

  const buttonClass = chromeIconButton('action');
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
