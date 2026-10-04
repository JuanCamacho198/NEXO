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
  class="flex-1 overflow-y-auto p-4 flex flex-col gap-4 [&>*]:shrink-0"
>
  <SyncView {t} />
</div>
