<script lang="ts">
  import { onMount } from 'svelte';
  import Info from 'lucide-svelte/icons/info';
  import Star from 'lucide-svelte/icons/star';
  import { openUrl } from '@tauri-apps/plugin-opener';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { UiLocale } from '$lib/shared/types';
  import {
    checkForUpdates,
    defaultPluginUpdatePorts,
    defaultUpdateCheckDeps,
    getInstalledAppVersion,
    performPluginUpdateNow,
    resolveUpdateFeedUrl,
    type PluginUpdatePorts,
    type UpdateCheckDeps,
    type UpdateCheckState,
    type UpdateErrorKind,
  } from '$lib/features/settings/update/updateChecker';
  import {
    defaultSuppressionStorage,
    recordRemindLater,
  } from '$lib/features/settings/update/updateSuppression';
  import {
    READING_QUOTES,
    quoteDisplayText,
    selectDailyQuote,
  } from '$lib/features/settings/about/readingQuotes';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    locale: UiLocale;
    updateDeps?: Partial<UpdateCheckDeps>;
    pluginPorts?: PluginUpdatePorts;
  };

  let { t, locale, updateDeps, pluginPorts }: Props = $props();

  type DialogModel = {
    installedVersion: string;
    feedVersion: string;
    notes: string;
  };

  const REPOSITORY_URL = 'https://github.com/JuanCamacho198/NEXO';
  const ISSUES_URL = 'https://github.com/JuanCamacho198/NEXO/issues';
  const LICENSE_URL = `${REPOSITORY_URL}/blob/main/LICENSE`;

  let installedVersion = $state<string | null>(null);
  let versionFailed = $state(false);
  let feedEnabled = $state(false);
  let checking = $state(false);
  let manualOutcome = $state<UpdateCheckState | null>(null);
  let dialog = $state<DialogModel | null>(null);
  let installPhase = $state<'idle' | 'downloading' | 'installed' | 'failed'>('idle');
  let downloadProgress = $state<string | null>(null);
  let versionCopied = $state(false);
  let copyResetTimer: ReturnType<typeof setTimeout> | null = null;

  // Rotation is deterministic: same day of year, same quote. Never localized
  // away — a quote is a factual attribution.
  const dailyQuote = $derived(selectDailyQuote(READING_QUOTES, new Date()));

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

  const copyVersion = async (): Promise<void> => {
    if (!installedVersion) return;
    try {
      await navigator.clipboard.writeText(installedVersion);
    } catch {
      return;
    }
    versionCopied = true;
    if (copyResetTimer) clearTimeout(copyResetTimer);
    copyResetTimer = setTimeout(() => {
      versionCopied = false;
      copyResetTimer = null;
    }, 2000);
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

    // The automatic startup check now runs once at app launch (see App.svelte),
    // so this tab owns only the manual check. The feed gate stays: a feed that
    // cannot answer is never promised.
    return () => {
      cancelled = true;
      if (copyResetTimer) clearTimeout(copyResetTimer);
    };
  });
</script>

<section class="space-y-5 w-full max-w-none">
  <header class="flex flex-col gap-1">
    <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">
      {t('settings.tab.about')}
    </h1>
    <p class="text-sm text-(--color-text-muted)">{t('settings.about.subtitle')}</p>
  </header>

  <!-- Identity: icon, name, real version (copyable), channel. The Panel
       carries no title so the app name keeps its existing span (not a
       heading) and the page h1 stays the sole top heading. -->
  <Panel>
    <div class="flex items-center gap-3">
      <Info size={32} strokeWidth={2} class="text-(--color-primary)" aria-hidden="true" />
      <div class="flex min-w-0 flex-col">
        <span class="text-xl font-semibold text-(--color-primary)"
          >{t('settings.about.appName')}</span
        >
        {#if installedVersion}
          <Button
            variant="ghost"
            size="sm"
            onclick={() => void copyVersion()}
            aria-label={t('settings.about.copyVersion')}
          >
            <span>{t('settings.about.version', { version: installedVersion })}</span>
            <span aria-live="polite">
              {versionCopied ? t('settings.about.versionCopied') : t('settings.about.copyVersion')}
            </span>
          </Button>
        {:else if versionFailed}
          <span class="text-xs text-(--color-text-muted)">{t('settings.about.versionUnknown')}</span
          >
        {:else}
          <span class="text-xs text-(--color-text-muted)">{t('settings.about.versionLoading')}</span
          >
        {/if}
        <span class="text-xs text-(--color-text-muted)">
          {t('settings.about.channel', { channel: t('settings.about.channelStable') })}
        </span>
      </div>
    </div>
    <p class="mt-3 text-sm text-(--color-text-muted)">{t('settings.about.tagline')}</p>
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
          <p class="text-xs text-(--color-text-muted)" role="status">
            {t('update.upToDate')}
          </p>
        {:else if manualOutcome?.status === 'error'}
          <p class="text-xs text-(--color-text-muted)" role="status">
            {t(errorKeyFor(manualOutcome.kind))}
          </p>
        {/if}
      </div>
    {/if}
  </Panel>

  <!-- Update notice: a status region, not a modal. It needs neither
       interruption nor protected focus, and the Settings overlay already owns
       Escape and focus, so a nested dialog would be a false aria-modal. -->
  {#if dialog}
    <!-- No Panel title: the h3 below must stay an h3 (heading-order test). -->
    <Panel aria-labelledby="update-available-title">
      <h3
        id="update-available-title"
        class="mt-0 mb-2 text-sm font-semibold text-(--color-primary)"
      >
        {t('update.availableTitle')}
      </h3>
      <p class="text-sm text-(--color-text-muted)" role="status">
        {t('update.availableBody', {
          version: dialog.feedVersion,
          current: dialog.installedVersion,
        })}
      </p>
      <h4 class="mt-3 mb-1 text-xs font-semibold text-(--color-primary)">{t('update.notes')}</h4>
      <p class="text-sm text-(--color-text-muted)">{dialog.notes}</p>
      {#if installPhase === 'installed'}
        <p class="mt-3 text-sm text-(--color-text-muted)" role="status">
          {t('update.relaunchConfirm')}
        </p>
        <div class="mt-2 flex gap-2">
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
        <div class="mt-3 flex gap-2">
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
        {#if installPhase === 'downloading'}
          <p class="sr-only" role="status">
            {t('update.downloading')}{downloadProgress ? ` ${downloadProgress}` : ''}
          </p>
        {/if}
        {#if installPhase === 'failed' && manualOutcome?.status === 'error'}
          <p class="mt-2 text-xs text-(--color-text-muted)" role="status">
            {t(errorKeyFor(manualOutcome.kind))}
          </p>
        {/if}
      {/if}
    </Panel>
  {/if}

  <!-- Daily quote: discrete, factual, verifiable. No protagonist heading, and
       it never displaces version, update or support. The recessed Panel
       variant replaces the hand-rolled surface-dim figure; the caption uses
       a div because figcaption requires a figure parent. -->
  {#if dailyQuote}
    {@const quote = dailyQuote}
    {@const display = quoteDisplayText(quote, locale)}
    <Panel variant="surface">
      <blockquote class="m-0">
        <p class="whitespace-pre-line text-sm italic text-(--color-primary)">{display.text}</p>
      </blockquote>
      <div
        class="mt-2 flex flex-wrap items-baseline gap-x-2 gap-y-1 text-xs text-(--color-text-muted)"
      >
        <span class="font-medium text-(--color-primary)">{quote.author}</span>
        <span aria-hidden="true">·</span>
        <span>{quote.work}</span>
        <span aria-hidden="true">·</span>
        <span>{quote.year}</span>
        {#if display.isTranslation}
          <span class="rounded-full border border-(--color-border) px-1.5 py-0.5">
            {t('settings.about.quoteTranslated')}
          </span>
        {/if}
        <!-- Ghost keeps the link quiet; underline styling does not clash
             with the atom's own chrome so it travels in `class`. -->
        <Button
          variant="ghost"
          size="sm"
          class="underline decoration-dotted underline-offset-2"
          onclick={() => void openUrl(quote.sourceUrl)}
        >
          {t('settings.about.quoteSource')}
        </Button>
        {#if display.isTranslation}
          <details class="w-full text-xs text-(--color-text-muted)">
            <summary class="cursor-pointer">
              {t('settings.about.quoteOriginal', { lang: quote.lang })}
            </summary>
            <p lang={quote.lang} class="mt-1 whitespace-pre-line italic text-(--color-primary)">
              {quote.original}
            </p>
          </details>
        {/if}
      </div>
    </Panel>
  {/if}

  <!-- Product license: NEXO's own license, kept as a discrete, small block.
       Third-party library notices no longer live in the UI; their obligation
       is preserved in THIRD-PARTY-NOTICES.md at the repository root. No
       Panel title: the license line is a span, not a heading. -->
  <Panel>
    <div class="flex items-center justify-between gap-3">
      <span class="text-xs text-(--color-text-muted)">
        {t('settings.about.license')}:
        <span class="font-medium text-(--color-primary)">{t('settings.about.licenseName')}</span>
      </span>
      <Button variant="ghost" size="sm" onclick={() => void openUrl(LICENSE_URL)}>
        {t('settings.about.viewLicense')}
      </Button>
    </div>
  </Panel>

  <!-- Support: real repository URLs, opened through the Tauri opener.
       No Panel title: the h3 below must stay an h3 (heading-order test). -->
  <Panel>
    <h3 class="mt-0 mb-2 text-sm font-semibold text-(--color-primary)">
      {t('settings.about.links')}
    </h3>
    <p class="mt-2 text-xs text-(--color-primary)">{t('settings.about.starBody')}</p>
    <Button class="mt-2" onclick={() => void openUrl(REPOSITORY_URL)} variant="accent" size="sm">
      <Star size={14} strokeWidth={1.8} aria-hidden="true" />
      <span class="ml-1.5">{t('settings.about.starCta')}</span>
    </Button>
    <div class="mt-2 flex gap-2">
      <Button onclick={() => void openUrl(REPOSITORY_URL)} variant="ghost" size="sm">
        {t('settings.about.github')}
      </Button>
      <Button onclick={() => void openUrl(ISSUES_URL)} variant="ghost" size="sm">
        {t('settings.about.reportIssue')}
      </Button>
    </div>
  </Panel>
</section>
