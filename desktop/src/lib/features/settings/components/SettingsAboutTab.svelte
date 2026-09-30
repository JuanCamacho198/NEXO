<script lang="ts">
  import { onMount } from 'svelte';
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import {
    checkForUpdates,
    defaultPluginUpdatePorts,
    defaultUpdateCheckDeps,
    getInstalledAppVersion,
    performPluginUpdateNow,
    resolveUpdateFeedUrl,
    runStartupUpdateCheck,
    type PluginUpdatePorts,
    type UpdateCheckDeps,
    type UpdateCheckState,
    type UpdateErrorKind,
  } from '$lib/features/settings/update/updateChecker';
  import {
    defaultSuppressionStorage,
    recordRemindLater,
  } from '$lib/features/settings/update/updateSuppression';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    updateDeps?: Partial<UpdateCheckDeps>;
    pluginPorts?: PluginUpdatePorts;
  };

  let { t, updateDeps, pluginPorts }: Props = $props();

  type DialogModel = {
    installedVersion: string;
    feedVersion: string;
    notes: string;
  };

  let installedVersion = $state<string | null>(null);
  let versionFailed = $state(false);
  let feedEnabled = $state(false);
  let checking = $state(false);
  let manualOutcome = $state<UpdateCheckState | null>(null);
  let dialog = $state<DialogModel | null>(null);
  let installPhase = $state<'idle' | 'downloading' | 'installed' | 'failed'>('idle');
  let downloadProgress = $state<string | null>(null);

  const buildDeps = (): UpdateCheckDeps => ({
    ...defaultUpdateCheckDeps(resolveUpdateFeedUrl()),
    ...updateDeps,
  });

  const ports = (): PluginUpdatePorts => pluginPorts ?? defaultPluginUpdatePorts();

  const errorKeyFor = (kind: UpdateErrorKind): MessageKey => {
    if (kind === 'OFFLINE') return 'update.errorOffline';
    if (kind === 'MALFORMED') return 'update.errorMalformed';
    return 'update.errorUnreachable';
  };

  const openDialogForAvailable = (
    state: Extract<UpdateCheckState, { status: 'available' }>,
  ): void => {
    installPhase = 'idle';
    downloadProgress = null;
    dialog = {
      installedVersion: state.installedVersion,
      feedVersion: state.feedVersion,
      notes: state.notes,
    };
  };

  const runManualCheck = async (): Promise<void> => {
    if (checking) return;
    checking = true;
    manualOutcome = { status: 'checking' };
    try {
      const state = await checkForUpdates(buildDeps(), { manual: true });
      manualOutcome = state;
      if (state?.status === 'available') openDialogForAvailable(state);
    } finally {
      checking = false;
    }
  };

  const handleRemindLater = (): void => {
    if (!dialog) return;
    recordRemindLater(defaultSuppressionStorage(), dialog.feedVersion, Date.now());
    dialog = null;
  };

  const handleUpdateNow = async (): Promise<void> => {
    if (!dialog || installPhase === 'downloading') return;
    installPhase = 'downloading';
    downloadProgress = null;
    try {
      const outcome = await performPluginUpdateNow(ports(), (downloaded, total) => {
        downloadProgress = total !== null ? `${downloaded} / ${total}` : `${downloaded}`;
      });
      if (outcome === 'noUpdateAnymore') {
        dialog = null;
        manualOutcome = installedVersion
          ? { status: 'upToDate', installedVersion }
          : { status: 'error', kind: 'UNREACHABLE' };
        installPhase = 'idle';
        return;
      }
      installPhase = 'installed';
    } catch {
      // The plugin path (signature-verified download/install) is unavailable
      // while the updater keypair stays blocked-until-decided; surface the
      // feed-unreachable copy rather than a false up-to-date claim.
      installPhase = 'failed';
      manualOutcome = { status: 'error', kind: 'UNREACHABLE' };
    }
  };

  const handleRelaunch = async (): Promise<void> => {
    await ports().relaunchApp();
  };

  onMount(() => {
    let cancelled = false;
    void (async () => {
      const version = updateDeps?.getInstalledVersion
        ? await updateDeps.getInstalledVersion()
        : await getInstalledAppVersion();
      if (cancelled) return;
      if (version) {
        installedVersion = version;
      } else {
        versionFailed = true;
      }
      const feedUrl = buildDeps().feedUrl;
      feedEnabled = feedUrl.trim().length > 0;
    })();

    // Startup check, deferred until the launch settles. At most one automatic
    // check runs per launch (module guard); the dialog opens only for a
    // verified newer version with no active remind-later suppression.
    const settleTimer = setTimeout(() => {
      void (async () => {
        if (cancelled) return;
        const state = await runStartupUpdateCheck(buildDeps());
        if (cancelled) return;
        if (state?.status === 'available') openDialogForAvailable(state);
      })();
    }, 1500);

    return () => {
      cancelled = true;
      clearTimeout(settleTimer);
    };
  });
</script>

<Panel title={t('settings.about')}>
  <section class="rounded-lg border border-(--color-border) bg-(--color-surface) p-4">
    <div class="flex items-center gap-3">
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="32"
        height="32"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        class="text-(--color-primary)"
        ><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path
          d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"
        /></svg
      >
      <div class="flex flex-col">
        <span class="text-lg font-semibold text-(--color-primary)">Nexo</span>
        {#if installedVersion}
          <span class="text-xs text-(--color-text-muted)">Version {installedVersion}</span>
        {:else if versionFailed}
          <span class="text-xs text-(--color-text-muted)">Version —</span>
        {:else}
          <span class="text-xs text-(--color-text-muted)">Version …</span>
        {/if}
      </div>
    </div>
    <p class="text-sm text-(--color-text-muted) mt-3">
      A modern e-reader application for enjoying your EPUB collection with a clean, customizable
      reading experience.
    </p>
    {#if feedEnabled}
      <div class="mt-3 flex flex-col gap-2">
        <Button
          onclick={() => void runManualCheck()}
          variant="secondary"
          size="sm"
          disabled={checking}
        >
          {checking ? t('update.checking') : t('update.check')}
        </Button>
        {#if manualOutcome?.status === 'upToDate'}
          <p class="text-xs text-(--color-text-muted)">{t('update.upToDate')}</p>
        {:else if manualOutcome?.status === 'error'}
          <p class="text-xs text-(--color-text-muted)">{t(errorKeyFor(manualOutcome.kind))}</p>
        {/if}
      </div>
    {/if}
  </section>

  {#if dialog}
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('update.availableTitle')}
      class="rounded-lg border border-(--color-border) bg-(--color-surface) p-4 mt-4"
    >
      <h4 class="mt-0 mb-2 text-sm font-semibold text-(--color-primary)">
        {t('update.availableTitle')}
      </h4>
      <p class="text-sm text-(--color-text-muted)">
        {t('update.availableBody', {
          version: dialog.feedVersion,
          current: dialog.installedVersion,
        })}
      </p>
      <h5 class="mt-3 mb-1 text-xs font-semibold text-(--color-primary)">{t('update.notes')}</h5>
      <p class="text-sm text-(--color-text-muted)">{dialog.notes}</p>
      {#if installPhase === 'installed'}
        <p class="text-sm text-(--color-text-muted) mt-3">{t('update.relaunchConfirm')}</p>
        <div class="flex gap-2 mt-2">
          <Button onclick={() => void handleRelaunch()} variant="primary" size="sm">
            {t('update.now')}
          </Button>
          <Button
            onclick={() => {
              dialog = null;
            }}
            variant="ghost"
            size="sm"
          >
            {t('update.later')}
          </Button>
        </div>
      {:else}
        <div class="flex gap-2 mt-3">
          <Button
            onclick={() => void handleUpdateNow()}
            variant="primary"
            size="sm"
            disabled={installPhase === 'downloading'}
          >
            {installPhase === 'downloading'
              ? (downloadProgress ?? t('update.checking'))
              : t('update.now')}
          </Button>
          <Button onclick={handleRemindLater} variant="ghost" size="sm">
            {t('update.later')}
          </Button>
        </div>
        {#if installPhase === 'failed' && manualOutcome?.status === 'error'}
          <p class="text-xs text-(--color-text-muted) mt-2">
            {t(errorKeyFor(manualOutcome.kind))}
          </p>
        {/if}
      {/if}
    </div>
  {/if}

  <section class="rounded-lg border border-(--color-border) bg-(--color-surface) p-4 mt-4">
    <h4 class="mt-0 mb-2 text-sm font-semibold text-(--color-primary)">Credits</h4>
    <ul class="m-0 p-0 list-none">
      <li class="flex justify-between py-1 border-b border-(--color-border) last:border-b-0">
        <span class="text-xs text-(--color-text-muted)">Core Team</span>
        <span class="text-xs text-(--color-primary) font-medium">Nexo Contributors</span>
      </li>
      <li class="flex justify-between py-1 border-b border-(--color-border) last:border-b-0">
        <span class="text-xs text-(--color-text-muted)">EPUB Parsing</span>
        <span class="text-xs text-(--color-primary) font-medium">epub.js</span>
      </li>
      <li class="flex justify-between py-1 border-b border-(--color-border) last:border-b-0">
        <span class="text-xs text-(--color-text-muted)">Framework</span>
        <span class="text-xs text-(--color-primary) font-medium">Svelte / Tauri</span>
      </li>
    </ul>
  </section>

  <section class="rounded-lg border border-(--color-border) bg-(--color-surface) p-4 mt-4">
    <h4 class="mt-0 mb-2 text-sm font-semibold text-(--color-primary)">Links</h4>
    <div class="flex gap-2">
      <Button
        onclick={() => window.open('https://github.com/JuanCamacho198/NEXO', '_blank')}
        variant="ghost"
        size="sm"
      >
        GitHub
      </Button>
      <Button
        onclick={() => window.open('https://github.com/JuanCamacho198/NEXO/issues', '_blank')}
        variant="ghost"
        size="sm"
      >
        Report Issue
      </Button>
    </div>
  </section>
</Panel>
