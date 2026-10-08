<script lang="ts">
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { InstallOutcome } from '$lib/features/settings/useSettingsAddons.svelte';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    url: string;
    isBusy: boolean;
    installOutcome: InstallOutcome;
    onUrlChange: (value: string) => void;
    onInstall: () => void;
  };

  let { t, url, isBusy, installOutcome, onUrlChange, onInstall }: Props = $props();

  const hasInlineProblem = $derived(
    installOutcome.kind === 'error' || installOutcome.kind === 'offline',
  );
</script>

<!--
  AddonInstallForm — the secondary install-by-URL affordance (ADD-01 A).
  Extracted from AddonsInstalledList so the Addons screen can present it as the
  last block, after the installed list and the first-party catalogue. Settings
  keeps rendering it inside AddonsInstalledList through `showInstallForm`.
  Owns no state: url/isBusy/installOutcome arrive via props and every mutation
  leaves through onInstall. The inline role="alert" keeps retry reachable
  without unmounting either list (ADD-01 I).
-->
<div
  class="flex max-w-2xl flex-col gap-3 rounded-(--radius-xl) border border-(--color-border) bg-(--color-surface-subtle) p-4"
>
  <div class="flex flex-col gap-1">
    <p class="m-0 text-sm text-(--color-text-muted)">{t('addons.install.description')}</p>
    <label class="text-sm font-medium text-(--color-secondary)" for="addon-install-url">
      {t('settings.addons.urlLabel')}
    </label>
  </div>

  <div class="flex flex-col gap-2 sm:flex-row">
    <input
      id="addon-install-url"
      type="url"
      class="min-w-0 flex-1 rounded-lg border border-(--color-border) bg-(--color-background) px-3 py-2 text-sm text-(--color-primary) transition-colors placeholder:text-(--color-text-muted) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-accent)"
      placeholder={t('settings.addons.urlPlaceholder')}
      value={url}
      oninput={(e) => onUrlChange((e.currentTarget as HTMLInputElement).value)}
    />
    <Button variant="secondary" disabled={isBusy} onclick={onInstall}>
      {isBusy ? t('settings.addons.installing') : t('settings.addons.install')}
    </Button>
  </div>

  {#if hasInlineProblem}
    <div
      role="alert"
      class="flex flex-col gap-2 rounded-lg border border-(--color-error)/40 bg-(--color-error-soft) px-3 py-2 text-sm text-(--color-primary) sm:flex-row sm:items-center sm:justify-between"
    >
      <p class="m-0">
        {installOutcome.kind === 'offline'
          ? t('addons.install.offline')
          : t('addons.install.errorInline')}
      </p>
      <Button variant="secondary" size="sm" disabled={isBusy} onclick={onInstall}>
        {t('addons.install.retry')}
      </Button>
    </div>
  {/if}
</div>
