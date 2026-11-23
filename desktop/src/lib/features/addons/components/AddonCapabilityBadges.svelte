<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import AddonCapabilityDetail, { capabilityLabel } from './AddonCapabilityDetail.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    capabilities: readonly string[];
  };

  let { t, capabilities }: Props = $props();

  // Opening the detail is local toggle state (precedent: AddonConsentToggle's
  // `confirming`): the dialog lists the same declared capabilities, so no
  // state leaves this component.
  let detailOpen = $state(false);
</script>

<!--
  AddonCapabilityBadges — one distinct badge per declared capability
  (slice 9); none when the list is empty (renders nothing). The known id
  (`resolve`) is localized, unknown ids render raw. Activating a badge opens
  the capability detail dialog for the same list.
-->
{#if capabilities.length > 0}
  <div class="flex shrink-0 flex-wrap items-center gap-1">
    {#each capabilities as capability (capability)}
      <button
        type="button"
        class="shrink-0 rounded-full border border-(--color-edge) bg-(--color-surface-subtle) px-2.5 py-0.5 text-xs font-medium text-(--color-secondary) transition-colors hover:border-(--color-brand)/60 hover:text-(--color-primary) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-brand)"
        title={t('addons.capabilities.title')}
        onclick={() => (detailOpen = true)}
      >
        {capabilityLabel(capability, t)}
      </button>
    {/each}
  </div>
  {#if detailOpen}
    <AddonCapabilityDetail {capabilities} {t} onClose={() => (detailOpen = false)} />
  {/if}
{/if}
