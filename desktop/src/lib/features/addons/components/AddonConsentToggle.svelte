<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    addonId: string;
    addonName: string;
    granted: boolean;
    disabled?: boolean;
    onToggle: (id: string, granted: boolean) => void;
  };

  let { t, addonId, addonName, granted, disabled = false, onToggle }: Props = $props();

  // Granting opens an inline disclosure (informed consent): the user sees
  // what the grant allows before Allow persists it. Deny closes the
  // disclosure without granting. Withdrawing an existing grant is immediate.
  let confirming = $state(false);

  function onCheck(e: Event): void {
    const checked = (e.currentTarget as HTMLInputElement).checked;
    if (checked && !granted) {
      confirming = true;
      return;
    }
    confirming = false;
    onToggle(addonId, checked);
  }
</script>

<!--
  AddonConsentToggle — per-addon network-consent control (slice 8):
  a checkbox bound to the persisted grant plus an inline disclosure shown
  while granting. Purely presentational: every value arrives via props and
  the grant leaves through onToggle. Fills the `consentControl` slot of
  AddonsInstalledList from the Addons screen.
-->
<div class="flex shrink-0 flex-col gap-1">
  <label class="flex cursor-pointer items-center gap-1 text-sm">
    <input
      type="checkbox"
      checked={granted}
      {disabled}
      aria-label={t('addons.consent.label', { name: addonName })}
      onchange={onCheck}
    />
    <span>{granted ? t('addons.consent.granted') : t('addons.consent.label')}</span>
  </label>
  {#if confirming && !granted}
    <div role="dialog" aria-label={t('addons.consent.title')} class="flex flex-col gap-1 text-sm">
      <p class="m-0 font-medium">{t('addons.consent.title')}</p>
      <p class="m-0 text-(--color-text-muted)">{t('addons.consent.body', { name: addonName })}</p>
      <div class="flex gap-2">
        <button
          type="button"
          class="text-sm"
          {disabled}
          onclick={() => {
            confirming = false;
            onToggle(addonId, true);
          }}
        >
          {t('addons.consent.allow')}
        </button>
        <button
          type="button"
          class="text-sm"
          onclick={() => {
            confirming = false;
          }}
        >
          {t('addons.consent.deny')}
        </button>
      </div>
    </div>
  {/if}
</div>
