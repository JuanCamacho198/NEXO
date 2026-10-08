<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import type { CatalogErrorCode } from '$lib/shared/services/catalog';
  import { discoverErrorKey } from './discoverErrorCopy';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  /**
   * Inline per-rail failure: a stable message plus a retry that re-resolves only
   * its own rail. Copy is the shared split (offline / slow / rate-limited /
   * upstream), so a timeout or a 429 never reads as "sin conexión".
   */
  let {
    code,
    t,
    onRetry,
  }: {
    code: CatalogErrorCode;
    t: Translate;
    onRetry: () => void;
  } = $props();
</script>

<div class="flex flex-col items-start gap-2">
  <p class="m-0 text-sm text-(--color-text-muted)">
    {t(discoverErrorKey(code))}
  </p>
  <button
    type="button"
    class="rounded-md border border-(--color-primary)/25 bg-(--color-primary)/8 px-3 py-1.5 text-sm font-medium text-(--color-primary) transition-colors hover:bg-(--color-primary)/15"
    onclick={onRetry}
  >
    {t('discover.retry')}
  </button>
</div>
