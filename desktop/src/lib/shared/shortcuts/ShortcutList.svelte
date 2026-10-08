<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import {
    SHORTCUT_CONTEXT_LABELS,
    SHORTCUT_GROUPS,
    SHORTCUT_REGISTRY,
    bindingToCaps,
    resolveBinding,
  } from './registry';
  import { getShortcutPlatform } from './platform';
  import ShortcutKey from './ShortcutKey.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();

  const platform = getShortcutPlatform();

  const groups = $derived(
    SHORTCUT_GROUPS.map((group) => ({
      ...group,
      entries: SHORTCUT_REGISTRY.filter((entry) => entry.group === group.id),
    })).filter((group) => group.entries.length > 0),
  );
</script>

<div class="flex flex-col gap-4">
  {#each groups as group (group.id)}
    <div class="flex flex-col gap-2">
      <h2 class="m-0 text-xs font-semibold uppercase tracking-wide text-(--color-text-muted)">
        {t(group.labelKey)}
      </h2>
      <ul class="m-0 grid list-none gap-2 p-0">
        {#each group.entries as entry (entry.id)}
          <li class="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span class="inline-flex min-w-21.5 items-center gap-1">
              {#each bindingToCaps(resolveBinding(entry, platform), platform) as cap, i (i)}
                <ShortcutKey label={cap} />
              {/each}
            </span>
            <span class="ml-auto flex flex-wrap items-baseline justify-end gap-x-2 text-right">
              <span class="text-sm text-(--color-primary)">{t(entry.descriptionKey)}</span>
              <span class="text-xs text-(--color-text-muted)"
                >· {t(SHORTCUT_CONTEXT_LABELS[entry.context])}</span
              >
            </span>
          </li>
        {/each}
      </ul>
    </div>
  {/each}
</div>
