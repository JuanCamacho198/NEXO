<script lang="ts">
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
  class="flex flex-wrap items-center gap-2 rounded-md border border-(--color-border) bg-(--color-surface) px-3 py-2"
>
  <p class="m-0 flex-1 text-sm text-(--color-text-muted)">{message}</p>
  <button
    type="button"
    class="rounded-md border border-(--color-primary)/25 bg-(--color-primary)/8 px-3 py-1.5 text-sm font-medium text-(--color-primary) transition-colors hover:bg-(--color-primary)/15"
    onclick={onRetry}
  >
    {t('discover.retry')}
  </button>
  <button
    type="button"
    class="rounded-md px-2 py-1 text-sm font-medium text-(--color-text-muted) transition-colors hover:bg-(--color-primary)/10"
    onclick={onDismiss}
  >
    {t('discover.dismiss')}
  </button>
</div>
