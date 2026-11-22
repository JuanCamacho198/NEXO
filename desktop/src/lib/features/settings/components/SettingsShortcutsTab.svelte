<script lang="ts">
  import { headingMarker } from '$lib/shared/ui/emberStyles';
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
  class="relative flex flex-1 flex-col overflow-y-auto px-8 pt-4 pb-12"
>
  <section class="w-full max-w-5xl space-y-6">
    <header class="flex flex-col gap-1">
      <div class="flex items-center gap-3">
        <div class="h-7 {headingMarker}" aria-hidden="true"></div>
        <h1 class="m-0 text-[1.75rem] font-bold tracking-[-0.02em] text-(--color-primary)">
          {t('settings.shortcuts.title')}
        </h1>
      </div>
      <p class="text-sm text-(--color-text-muted)">{t('settings.shortcuts.subtitle')}</p>
    </header>

    <div class="border-t border-(--color-edge)">
      <ShortcutList {t} />
    </div>
  </section>
</div>
