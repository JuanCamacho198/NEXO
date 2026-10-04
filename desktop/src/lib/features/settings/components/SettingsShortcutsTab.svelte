<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import ShortcutList from '$lib/shared/shortcuts/ShortcutList.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();
</script>

<div
  role="tabpanel"
  id="tabpanel-atajos"
  aria-labelledby="tab-atajos"
  class="flex-1 overflow-y-auto p-4 flex flex-col gap-4"
>
  <section class="rounded-xl border border-(--color-border) bg-(--color-surface) p-4">
    <p class="mt-0 mb-3 text-sm text-(--color-text-muted)">
      {t('settings.shortcuts.description')}
    </p>
    <ShortcutList {t} />
  </section>
</div>
