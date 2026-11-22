<script lang="ts">
  import SyncView from '$lib/features/sync/components/SyncView.svelte';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();
</script>

<div
  role="tabpanel"
  id="tabpanel-sincronizacion"
  aria-labelledby="tab-sincronizacion"
  class="relative flex flex-1 flex-col overflow-y-auto px-8 pt-4 pb-12"
>
  <SyncView {t} />
</div>
