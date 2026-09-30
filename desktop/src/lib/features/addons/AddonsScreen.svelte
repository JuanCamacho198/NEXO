<script lang="ts">
  import { addonsState, bindAddonsNotifier } from './addonsStore.svelte';
  import { createAddonConsentState } from './useAddonConsentState.svelte';
  import {
    createAddonReadSheet,
    type AddonReadDownloadOutcome,
    type AddonReadDownloadTask,
  } from './useAddonReadSheet.svelte';
  import AddonsInstalledList from './components/AddonsInstalledList.svelte';
  import AddonsFirstPartySection from './components/AddonsFirstPartySection.svelte';
  import AddonConsentToggle from './components/AddonConsentToggle.svelte';
  import AddonCapabilityBadges from './components/AddonCapabilityBadges.svelte';
  import AddonReadSheet from './components/AddonReadSheet.svelte';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import type { CatalogBook } from '$lib/shared/services/catalog/CatalogProvider';
  import { addonSourceIdOf } from '$lib/shared/services/catalog/CatalogProvider';
  import { liveCatalogProvider } from '$lib/shared/services/catalog/liveComposite';
  import { EMPTY_ADDON_ACCESS } from '$lib/shared/services/addons/AddonCatalogProvider';
  import { addonConsent } from '$lib/shared/services/addons/AddonConsent';
  import { discoverState } from '$lib/features/discover/DiscoverDomainState.svelte';
  import { declaredCapabilities } from '@nexo/manifest-validator';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  let { t }: { t: Translate } = $props();

  // Same shared state as the Settings panel: managing an addon from either
  // surface updates the other. The deep-link confirm dialog stays reachable
  // here because it is shell-global (AppModals), not route-gated.
  const consentState = createAddonConsentState();

  $effect(() => {
    bindAddonsNotifier({ t });
    void addonsState.refresh();
  });

  // The consent snapshot follows the visible rows: whenever the installed
  // set changes, re-read the persisted grants so every toggle reflects the
  // durable state (grants/withdrawals survive a restart).
  $effect(() => {
    const ids = addonsState.installed.map((row) => row.id);
    void consentState.refresh(ids);
  });

  // Addon read sheet (slice 9, addon-source books only): resolve runs through
  // the live composite (already consent-gated per addon); non-addon books
  // short-circuit to the empty resolution with zero I/O. The in-app download
  // consumes the SINGLE Discover download machine's backend progress (no
  // second pipeline): bytes are polled from its progress fields and the
  // terminal machine state maps to the sheet outcome.
  const readSheet = createAddonReadSheet({
    consent: {
      grant: (id: string) => addonConsent.grant(id),
      revoke: (id: string) => addonConsent.revoke(id),
    },
    addonNameOf: (id: string) =>
      addonsState.installed.find((row) => row.id === id)?.manifest.name ?? id,
    resolve: (book: CatalogBook) =>
      addonSourceIdOf(book.provider) === null
        ? Promise.resolve({ ...EMPTY_ADDON_ACCESS, options: [] })
        : (liveCatalogProvider.resolveAddonAccess?.(book) ??
          Promise.resolve({ ...EMPTY_ADDON_ACCESS, options: [] })),
    download: (
      book: CatalogBook,
      url: string,
      onProgress: (downloaded: number, total: number | null) => void,
    ): AddonReadDownloadTask => {
      const promise = (async (): Promise<AddonReadDownloadOutcome> => {
        onProgress(0, null);
        const timer = setInterval(() => {
          onProgress(discoverState.progressBytes, discoverState.progressTotal);
        }, 100);
        try {
          await discoverState.startDownloadUrl(book, url);
        } finally {
          clearInterval(timer);
        }
        if (discoverState.downloadState === 'imported') return { kind: 'imported' };
        if (discoverState.downloadState === 'cancelled') return { kind: 'cancelled' };
        return { kind: 'failed', code: 'UPSTREAM_ERROR' };
      })();
      return { promise, cancel: () => discoverState.cancelDownload() };
    },
  });
</script>

<!--
  Addons screen content (slices 7–9): the shared installed list, the read-only
  first-party section, and the inline install error/offline block (rendered by
  AddonsInstalledList without unmounting either list). The consent toggle
  (slice 8) fills the list's consentControl slot and the capability badges
  (slice 9) fill its capabilityBadges slot; the read sheet (slice 9) is hosted
  at screen level with the production ports above (Hidden by default, so it
  composes nothing until an addon-source book opens it).
-->
<section aria-labelledby="addons-heading" class="flex h-auto flex-col gap-6">
  <div class="flex max-w-2xl flex-col gap-1">
    <h2 id="addons-heading" class="m-0 text-lg font-semibold text-(--color-primary)">
      {t('addons.title')}
    </h2>
    <p class="m-0 text-sm opacity-70">{t('addons.subtitle')}</p>
  </div>

  <AddonsInstalledList
    {t}
    url={addonsState.url}
    installed={addonsState.installed}
    isBusy={addonsState.isBusy}
    installOutcome={addonsState.installOutcome}
    consentById={consentState.consentById}
    onUrlChange={(v: string) => (addonsState.url = v)}
    onInstall={() => void addonsState.handleInstall()}
    onToggle={(id: string, enabled: boolean) => void addonsState.handleToggle(id, enabled)}
    onUninstall={(id: string) => void addonsState.handleUninstall(id)}
  >
    {#snippet consentControl({ addon, granted })}
      <AddonConsentToggle
        {t}
        addonId={addon.id}
        addonName={addon.manifest.name}
        {granted}
        disabled={addonsState.isBusy}
        onToggle={(id: string, next: boolean) => void consentState.handleConsentToggle(id, next)}
      />
    {/snippet}
    {#snippet capabilityBadges(addon)}
      <AddonCapabilityBadges capabilities={declaredCapabilities(addon.manifest)} {t} />
    {/snippet}
  </AddonsInstalledList>

  <AddonsFirstPartySection {t} />

  <AddonReadSheet
    state={readSheet.state}
    {t}
    onClose={() => readSheet.close()}
    onRetry={() => void readSheet.retry()}
    onAllow={() => void readSheet.allow()}
    onDeny={() => void readSheet.deny()}
    onCancel={() => readSheet.cancelDownload()}
  />
</section>
