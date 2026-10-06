<script lang="ts">
  import StorageView from '$lib/features/storage/components/StorageView.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import { libraryState } from '$lib/shared/stores/LibraryDomainState.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();
</script>

<div
  role="tabpanel"
  id="tabpanel-almacenamiento"
  aria-labelledby="tab-almacenamiento"
  class="relative flex-1 overflow-y-auto p-4 flex flex-col gap-4"
>
  <StorageView {t} books={libraryState.books} />
</div>
