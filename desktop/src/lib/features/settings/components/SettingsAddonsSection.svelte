<script lang="ts">
  import { sectionClass } from '$lib/shared/ui/emberStyles';
  import AddonsInstalledList from '$lib/features/addons/components/AddonsInstalledList.svelte';
  import type { InstallOutcome } from '../useSettingsAddons.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { InstalledAddonRow } from '$lib/shared/services/addons/AddonRegistry';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    url: string;
    installed: InstalledAddonRow[];
    isBusy: boolean;
    installOutcome: InstallOutcome;
    consentById?: Record<string, boolean>;
    onUrlChange: (value: string) => void;
    onInstall: () => void;
    onToggle: (id: string, enabled: boolean) => void;
    onUninstall: (id: string) => void;
  };

  let {
    t,
    url,
    installed,
    isBusy,
    installOutcome,
    consentById = {},
    onUrlChange,
    onInstall,
    onToggle,
    onUninstall,
  }: Props = $props();
</script>

<section class="{sectionClass} flex flex-col gap-4">
  <header>
    <h2 class="text-base font-semibold text-(--color-primary)">{t('settings.addons.title')}</h2>
    <p class="mt-1 text-sm leading-relaxed text-(--color-text-muted)">
      {t('settings.addons.description')}
    </p>
  </header>
  <AddonsInstalledList
    framed={false}
    {t}
    {url}
    {installed}
    {isBusy}
    {installOutcome}
    {consentById}
    {onUrlChange}
    {onInstall}
    {onToggle}
    {onUninstall}
  />
</section>
