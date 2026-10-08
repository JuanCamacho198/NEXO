<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    formatBytes: (bytes: number) => string;
    tempBytes: number;
    booksBytes: number;
  };

  let { t, formatBytes, tempBytes, booksBytes }: Props = $props();
</script>

<!--
  Two plain-language lines, one coherent magnitude each. No fractions of the
  device: the old bars mixed local bytes with remote Drive bytes and a 0% Hot
  figure, so they compared things that were never parts of one whole.
-->
<dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm" data-testid="storage-breakdown">
  <dt class="text-(--color-text-muted)">{t('storage.breakdown.temporary')}</dt>
  <dd class="m-0 tabular-nums text-(--color-primary)">{formatBytes(tempBytes)}</dd>
  <dt class="text-(--color-text-muted)">{t('storage.breakdown.bookFiles')}</dt>
  <dd class="m-0 tabular-nums text-(--color-primary)">{formatBytes(booksBytes)}</dd>
</dl>
