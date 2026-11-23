<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import { panelClass } from '$lib/shared/ui/emberStyles';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import { RAIL_SOURCE_LABEL_KEYS, type DiscoverRailSource } from './railPlan';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  /**
   * ONE grouped failure notice: every rail served by `group` failed, so the
   * reader is told which source is down instead of watching the page silently
   * get shorter. It renders BELOW the rails that worked, and a source that
   * failed on a single rail keeps that rail's own inline error instead of
   * gaining this box — the notice only ever speaks for a whole dead source.
   *
   * `role="status"` gives the text a polite live region, so the failure is
   * announced rather than left for the reader to notice.
   */
  let {
    group,
    t,
    onRetry,
    onDismiss,
  }: {
    group: DiscoverRailSource;
    t: Translate;
    onRetry: () => void;
    onDismiss: () => void;
  } = $props();

  const message = $derived(
    t('discover.rail.groupFailed', { source: t(RAIL_SOURCE_LABEL_KEYS[group]) }),
  );
</script>

<div
  role="status"
  aria-live="polite"
  class="flex flex-wrap items-center gap-2 {panelClass} px-4 py-2.5"
>
  <p class="m-0 flex-1 text-sm text-(--color-text-muted)">{message}</p>
  <Button variant="secondary" size="sm" onclick={onRetry}>
    {t('discover.retry')}
  </Button>
  <Button variant="link" size="sm" onclick={onDismiss}>
    {t('discover.dismiss')}
  </Button>
</div>
