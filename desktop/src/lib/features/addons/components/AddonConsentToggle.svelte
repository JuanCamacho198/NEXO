<script lang="ts">
  import type { MessageKey } from '$lib/shared/i18n';
  import type { AddonConsentScope } from '$lib/shared/services/addons/AddonConsent';

  /**
   * The two scopes read differently on purpose. `library_meta` discloses the
   * reader's OWN library, so its disclosure names the books it would reveal;
   * the network scope names the addon's server instead.
   */
  const SCOPE_KEYS: Record<
    AddonConsentScope,
    { label: MessageKey; granted: MessageKey; title: MessageKey; body: MessageKey }
  > = {
    stream: {
      label: 'addons.consent.label',
      granted: 'addons.consent.granted',
      title: 'addons.consent.title',
      body: 'addons.consent.body',
    },
    library_meta: {
      label: 'addons.libraryMeta.label',
      granted: 'addons.libraryMeta.granted',
      title: 'addons.libraryMeta.title',
      body: 'addons.libraryMeta.body',
    },
  };

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    addonId: string;
    addonName: string;
    granted: boolean;
    /** Which grant this control writes. `stream` is the network-access grant. */
    scope?: AddonConsentScope;
    disabled?: boolean;
    onToggle: (id: string, granted: boolean) => void;
  };

  let {
    t,
    addonId,
    addonName,
    granted,
    scope = 'stream',
    disabled = false,
    onToggle,
  }: Props = $props();

  // Granting opens an inline disclosure (informed consent): the user sees
  // what the grant allows before Allow persists it. Deny closes the
  // disclosure without granting. Withdrawing an existing grant is immediate.
  //
  // The two scopes read differently on purpose. `library_meta` discloses the
  // reader's OWN library, so its disclosure names the books it would reveal;
  // the network scope names the addon's server instead.
  const keys = $derived(SCOPE_KEYS[scope]);

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
  AddonConsentToggle — per-addon consent control (slice 8, extended by the
  library-metadata scope): a checkbox bound to the persisted grant plus an
  inline disclosure shown while granting. Purely presentational: every value
  arrives via props and the grant leaves through onToggle. Fills the
  `consentControl` slot of AddonsInstalledList from the Addons screen.
-->
<div class="flex shrink-0 flex-col gap-1">
  <label class="flex cursor-pointer items-center gap-1 text-sm">
    <input
      type="checkbox"
      checked={granted}
      {disabled}
      aria-label={t(keys.label, { name: addonName })}
      onchange={onCheck}
    />
    <span>{granted ? t(keys.granted) : t(keys.label, { name: addonName })}</span>
  </label>
  {#if confirming && !granted}
    <div role="dialog" aria-label={t(keys.title)} class="flex max-w-md flex-col gap-1 text-sm">
      <p class="m-0 font-medium">{t(keys.title, { name: addonName })}</p>
      <p class="m-0 text-(--color-text-muted)">{t(keys.body, { name: addonName })}</p>
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
