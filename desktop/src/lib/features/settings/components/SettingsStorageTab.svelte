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
  class="relative flex flex-1 flex-col overflow-y-auto px-8 pt-4 pb-12"
>
  <StorageView {t} books={libraryState.books} />
</div>
