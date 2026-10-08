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
  class="relative flex-1 overflow-y-auto p-4 flex flex-col gap-4"
>
  <section class="space-y-5 w-full max-w-none">
    <header class="flex flex-col gap-1">
      <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">
        {t('settings.shortcuts.title')}
      </h1>
      <p class="text-sm text-(--color-text-muted)">{t('settings.shortcuts.subtitle')}</p>
    </header>

    <div class="rounded-xl border border-(--color-border) bg-(--color-surface) p-5">
      <ShortcutList {t} />
    </div>
  </section>
</div>
