<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
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
  <Button variant="secondary" size="sm" onclick={onRetry}>
    {t('discover.retry')}
  </Button>
</div>
