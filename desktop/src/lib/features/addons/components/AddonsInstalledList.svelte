<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { InstalledAddonRow } from '$lib/shared/services/addons/AddonRegistry';
  import type { InstallOutcome } from '$lib/features/settings/useSettingsAddons.svelte';
  import Skeleton from '$lib/shared/ui/feedback/Skeleton.svelte';
  import AddonInstallForm from './AddonInstallForm.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    url: string;
    installed: InstalledAddonRow[];
    isBusy: boolean;
    installOutcome: InstallOutcome;
    /**
     * ADD-02 G: the Addons screen passes `true` while the initial registry
     * refresh is in flight so an empty registry never flashes "no addons
     * installed". Defaults to `false`: Settings and the presentational tests
     * mount the list without an initial-refresh signal and keep the previous
     * behaviour.
     */
    isLoading?: boolean;
    /**
     * ADD-01 A: the Addons screen moves the install-by-URL form to the end of
     * the screen (secondary affordance) and passes `false`. Settings keeps the
     * in-list form through the default `true`.
     */
    showInstallForm?: boolean;
    /** Slice 8 fills: per-addon consent control. Receives the row + current grant. */
    consentById?: Record<string, boolean>;
    onUrlChange: (value: string) => void;
    onInstall: () => void;
    onToggle: (id: string, enabled: boolean) => void;
    onUninstall: (id: string) => void;
    /** Slice 8 fill point: per-addon consent control. */
    consentControl?: Snippet<[{ addon: InstalledAddonRow; granted: boolean }]>;
    /** Slice 9 fill point: capability badges. */
    capabilityBadges?: Snippet<[InstalledAddonRow]>;
  };

  let {
    t,
    url,
    installed,
    isBusy,
    installOutcome,
    isLoading = false,
    showInstallForm = true,
    consentById = {},
    onUrlChange,
    onInstall,
    onToggle,
    onUninstall,
    consentControl,
    capabilityBadges,
  }: Props = $props();

  // ADD-01 B: uninstall is destructive, so the first click only arms the
  // confirmation for that row; the mutation leaves on the explicit confirm.
  let confirmingId = $state<string | null>(null);

  const actionButton =
    'inline-flex min-h-6 items-center justify-center rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2';
  const neutralButton = `${actionButton} border-(--color-border) bg-(--color-surface) text-(--color-primary) hover:bg-(--color-surface-hover) focus-visible:ring-(--color-accent)`;
  const dangerButton = `${actionButton} border-(--color-danger)/40 bg-transparent text-(--color-danger) hover:bg-(--color-danger)/10 focus-visible:ring-(--color-danger)`;
  const confirmButton = `${actionButton} border-transparent bg-(--color-error) text-(--color-background) hover:opacity-90 focus-visible:ring-(--color-error)`;
</script>

<!--
  AddonsInstalledList — presentational addon management (slice 7). The installed
  rows lead with their state and permissions (ADD-01); the install-by-URL form
  is a secondary block, rendered here for Settings and moved to the end of the
  Addons screen via `showInstallForm={false}`. Loading shows in-shape skeletons
  and the empty copy appears only after the initial refresh resolves (ADD-02 G).
  Owns no state: every value arrives via props and every mutation leaves through
  an on* callback. The inline install error/offline block never unmounts the
  list. consentControl / capabilityBadges are render slots that slices 8–9 fill.
-->
<div class="flex flex-col gap-4" aria-busy={isLoading}>
  {#if isLoading && installed.length === 0}
    <ul class="flex max-w-2xl flex-col divide-y divide-(--color-border)" aria-hidden="true">
      {#each [0, 1, 2] as row (row)}
        <li class="flex items-center justify-between gap-3 py-3">
          <div class="flex min-w-0 flex-1 flex-col gap-2">
            <Skeleton variant="text" width="9rem" height="0.875rem" />
            <Skeleton variant="text" width="14rem" height="0.75rem" />
          </div>
          <Skeleton variant="rounded" width="5.5rem" height="2rem" />
        </li>
      {/each}
    </ul>
  {:else if installed.length === 0}
    <p class="m-0 max-w-2xl text-sm text-(--color-text-muted)">{t('settings.addons.empty')}</p>
  {:else}
    <ul class="flex max-w-2xl flex-col divide-y divide-(--color-border)">
      {#each installed as addon (addon.id)}
        <li class="flex flex-col gap-3 py-3">
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <p class="m-0 truncate text-sm font-medium text-(--color-primary)">
                {addon.manifest.name}
              </p>
              <p class="m-0 truncate text-xs text-(--color-text-muted)">{addon.url}</p>
            </div>
            <div class="flex shrink-0 items-center gap-2">
              <button
                type="button"
                class={neutralButton}
                disabled={isBusy}
                onclick={() => onToggle(addon.id, !addon.enabled)}
              >
                {addon.enabled ? t('settings.addons.disable') : t('settings.addons.enable')}
              </button>
              <button
                type="button"
                class={dangerButton}
                disabled={isBusy}
                onclick={() => (confirmingId = addon.id)}
              >
                {t('settings.addons.uninstall')}
              </button>
            </div>
          </div>

          {#if consentControl || capabilityBadges}
            <div class="flex flex-wrap items-center gap-3">
              {@render consentControl?.({ addon, granted: consentById[addon.id] ?? false })}
              {@render capabilityBadges?.(addon)}
            </div>
          {/if}

          {#if confirmingId === addon.id}
            <div
              role="group"
              aria-label={t('addons.uninstall.confirm', { name: addon.manifest.name })}
              class="flex flex-col gap-2 rounded-lg border border-(--color-danger)/40 bg-(--color-error-soft) px-3 py-2"
            >
              <p class="m-0 text-sm text-(--color-primary)">
                {t('addons.uninstall.confirm', { name: addon.manifest.name })}
              </p>
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  class={confirmButton}
                  disabled={isBusy}
                  onclick={() => {
                    confirmingId = null;
                    onUninstall(addon.id);
                  }}
                >
                  {t('addons.uninstall.confirmAction')}
                </button>
                <button
                  type="button"
                  class={neutralButton}
                  disabled={isBusy}
                  onclick={() => (confirmingId = null)}
                >
                  {t('addons.uninstall.cancel')}
                </button>
              </div>
            </div>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}

  {#if showInstallForm}
    <AddonInstallForm {t} {url} {isBusy} {installOutcome} {onUrlChange} {onInstall} />
  {/if}
</div>
