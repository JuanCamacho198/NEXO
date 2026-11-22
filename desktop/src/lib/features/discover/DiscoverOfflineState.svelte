<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import { panelClass } from '$lib/shared/ui/emberStyles';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  let {
    t,
    onRetry,
  }: {
    t: Translate;
    onRetry: () => void;
  } = $props();
</script>

<div role="alert" class="flex max-w-2xl flex-col items-start gap-3 {panelClass} p-5">
  <p class="m-0 text-sm text-(--color-text-muted)">{t('discover.offline')}</p>
  <Button variant="secondary" size="sm" onclick={onRetry}>
    {t('discover.retry')}
  </Button>
</div>
