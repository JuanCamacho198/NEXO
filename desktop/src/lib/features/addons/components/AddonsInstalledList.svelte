<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { InstalledAddonRow } from '$lib/shared/services/addons/AddonRegistry';
  import type { InstallOutcome } from '$lib/features/settings/useSettingsAddons.svelte';
  import Skeleton from '$lib/shared/ui/feedback/Skeleton.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Puzzle from 'lucide-svelte/icons/puzzle';
  import { panelClass } from '$lib/shared/ui/emberStyles';
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
    /**
     * `false` drops the card frame so the list sits open inside a Settings
     * section; the Addons screen keeps the framed list (default).
     */
    framed?: boolean;
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
    framed = true,
    consentById = {},
    onUrlChange,
    onInstall,
    onToggle,
    onUninstall,
    consentControl,
    capabilityBadges,
  }: Props = $props();

  const frame = $derived(framed ? `${panelClass} px-5` : '');

  // ADD-01 B: uninstall is destructive, so the first click only arms the
  // confirmation for that row; the mutation leaves on the explicit confirm.
  let confirmingId = $state<string | null>(null);
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
    <ul class="flex max-w-3xl flex-col divide-y divide-(--color-edge) {frame}" aria-hidden="true">
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
    <div class="flex max-w-3xl items-center gap-4 {frame} {framed ? 'py-5' : 'py-1'}">
      <span
        class="flex size-11 shrink-0 items-center justify-center rounded-full border border-(--color-edge) bg-(--color-brand-soft) text-(--color-brand-text)"
        aria-hidden="true"
      >
        <Puzzle size={20} strokeWidth={1.8} />
      </span>
      <p class="m-0 text-sm text-(--color-secondary)">{t('settings.addons.empty')}</p>
    </div>
  {:else}
    <ul class="flex max-w-3xl flex-col divide-y divide-(--color-edge) {frame}">
      {#each installed as addon (addon.id)}
        <li class="flex flex-col gap-3 py-4">
          <div class="flex items-start justify-between gap-3">
            <div class="flex min-w-0 items-center gap-3">
              <span
                class="flex size-10 shrink-0 items-center justify-center rounded-(--radius-md) border border-(--color-edge) bg-(--color-surface-subtle) {addon.enabled
                  ? 'text-(--color-brand-text)'
                  : 'text-(--color-text-muted)'}"
                aria-hidden="true"
              >
                <Puzzle size={18} strokeWidth={1.8} />
              </span>
              <div class="min-w-0">
                <p class="m-0 truncate text-base font-semibold text-(--color-primary)">
                  {addon.manifest.name}
                </p>
                <p class="m-0 truncate text-xs text-(--color-text-muted)">{addon.url}</p>
              </div>
            </div>
            <div class="flex shrink-0 items-center gap-2">
              <Button
                variant="secondary"
                size="sm"
                disabled={isBusy}
                onclick={() => onToggle(addon.id, !addon.enabled)}
              >
                {addon.enabled ? t('settings.addons.disable') : t('settings.addons.enable')}
              </Button>
              <Button
                variant="danger-outline"
                size="sm"
                disabled={isBusy}
                onclick={() => (confirmingId = addon.id)}
              >
                {t('settings.addons.uninstall')}
              </Button>
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
              class="flex flex-col gap-3 rounded-(--radius-md) border border-(--color-danger)/40 bg-(--color-error-soft) px-4 py-3"
            >
              <p class="m-0 text-sm text-(--color-primary)">
                {t('addons.uninstall.confirm', { name: addon.manifest.name })}
              </p>
              <div class="flex items-center gap-2">
                <Button
                  variant="danger"
                  size="sm"
                  disabled={isBusy}
                  onclick={() => {
                    confirmingId = null;
                    onUninstall(addon.id);
                  }}
                >
                  {t('addons.uninstall.confirmAction')}
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={isBusy}
                  onclick={() => (confirmingId = null)}
                >
                  {t('addons.uninstall.cancel')}
                </Button>
              </div>
            </div>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}

  {#if showInstallForm}
    <AddonInstallForm {t} {url} {isBusy} {installOutcome} {framed} {onUrlChange} {onInstall} />
  {/if}
</div>
