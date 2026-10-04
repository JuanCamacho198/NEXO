<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import Modal from '$lib/shared/ui/layout/Modal.svelte';
  import ShortcutList from './ShortcutList.svelte';
  import { helpState } from './helpState.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();
</script>

<!--
  The `?` help surface. It renders the same registry as Ajustes → Atajos, so
  the app has one list, not two. Mounted app-wide in App.svelte; `helpState` is
  toggled by the global dispatcher. Escape closes it through bits-ui.
-->
<Modal bind:open={helpState.open} title={t('settings.shortcuts.helpTitle')} size="lg">
  <ShortcutList {t} />
</Modal>
