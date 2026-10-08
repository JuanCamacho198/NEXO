<script lang="ts">
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import { FIRST_PARTY_BUILTINS } from '../firstPartySources';
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
  };

  let { t }: Props = $props();
</script>

<!--
  AddonsFirstPartySection — read-only, visually distinct first-party list
  (slice 7). No install/uninstall affordance, no registry access: it renders
  the static first-party model only. Consent (slice 8) and capability badges
  (slice 9) do not belong here. Each row carries the same trailing kind badge
  so the list stays aligned (ADD-01 J), and the whole block sits on the
  recessed `surface` Panel so it never competes with the installed addons.
-->
<Panel
  variant="surface"
  title={t('addons.firstParty.title')}
  subtitle={t('addons.firstParty.readOnly')}
>
  <div class="flex flex-col gap-6">
    <div class="flex flex-col gap-2">
      <h3 class="m-0 text-sm font-semibold text-(--color-secondary)">
        {t('addons.firstParty.builtinTitle')}
      </h3>
      <ul class="m-0 flex list-none flex-col gap-1 p-0">
        {#each FIRST_PARTY_BUILTINS as source (source.sourceId)}
          <li class="flex items-center justify-between gap-3 py-1">
            <p class="m-0 truncate text-sm text-(--color-secondary)">{source.name}</p>
            <span
              class="shrink-0 rounded border border-(--color-border) px-1.5 py-0.5 text-xs text-(--color-text-muted)"
            >
              {t('addons.firstParty.builtinBadge')}
            </span>
          </li>
        {/each}
      </ul>
    </div>
  </div>
</Panel>
