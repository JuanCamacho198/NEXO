<script lang="ts">
  import SettingsResetModal from './SettingsResetModal.svelte';
  import { createSettingsRouter, type SettingsTab } from '../useSettingsRouter.svelte';
  import { createSettingsAppearance } from '../useSettingsAppearance.svelte';
  import { createSettingsReader } from '../useSettingsReader.svelte';
  import { createSettingsData } from '../useSettingsData.svelte';
  import { addonsState, bindAddonsNotifier } from '$lib/features/addons/addonsStore.svelte';
  import SettingsAddonsSection from './SettingsAddonsSection.svelte';
  import { createSettingsProfile } from '../useSettingsProfile.svelte';
  import SettingsTabs from './SettingsTabs.svelte';
  import SettingsCuentaTab from './SettingsCuentaTab.svelte';
  import SettingsAppearanceTab from './SettingsAppearanceTab.svelte';
  import SettingsReaderTab from './SettingsReaderTab.svelte';
  import SettingsDataTab from './SettingsDataTab.svelte';
  import SettingsStorageTab from './SettingsStorageTab.svelte';
  import SettingsSyncTab from './SettingsSyncTab.svelte';
  import SettingsShortcutsTab from './SettingsShortcutsTab.svelte';
  import SettingsAboutTab from './SettingsAboutTab.svelte';
  import type { UiLocale, ReaderSettings } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  import { onDestroy } from 'svelte';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
  import { driveState } from '$lib/shared/stores/driveState.svelte';
  import { beginDriveConnect } from '$lib/shared/services/DriveConnectService';
  import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';

  let {
    isOpen = $bindable(false),
    mode = 'overlay',
    onRequestClose,
    locale,
    onLocaleChange,
    onReaderSettingsChange,
    t,
    books = [],
    initialTab,
  } = $props<{
    isOpen: boolean;
    mode?: 'overlay' | 'page';
    onRequestClose?: () => void;
    locale: UiLocale;
    onLocaleChange?: (locale: UiLocale) => void;
    onReaderSettingsChange?: (settings: ReaderSettings) => void;
    books?: { id: string; title: string }[];
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    initialTab?: SettingsTab;
  }>();

  // svelte-ignore state_referenced_locally
  const router = createSettingsRouter({ initialTab });
  $effect(() => {
    if (initialTab !== undefined) router.activeTab = initialTab;
  });

  const appearance = createSettingsAppearance({
    onLocaleChange: (next) => {
      locale = next as UiLocale;
      onLocaleChange?.(next as UiLocale);
    },
  });
  const reader = createSettingsReader({
    onReaderSettingsChange: (next) => onReaderSettingsChange?.(next),
  });
  // svelte-ignore state_referenced_locally
  const profile = createSettingsProfile({ t });
  // svelte-ignore state_referenced_locally
  const data = createSettingsData({ t });
  // Single shared addon state (slice 7): this surface and the Addons screen
  // mutate the same instance, so either surface updates the other.
  $effect(() => {
    bindAddonsNotifier({ t });
    void addonsState.refresh();
  });

  // Keep appearance locale in sync if parent changes locale externally
  $effect(() => {
    void locale;
    if (appearance.locale !== locale) appearance.locale = locale;
  });
  $effect(() => {
    void settingsState.dailyGoalMinutes;
    profile.syncFromStore();
  });

  let showResetModal = $state(false);
  let pendingResetTab = $state<'cuenta' | 'apariencia' | 'reader' | null>(null);

  // Auto-load devices when signed in
  $effect(() => {
    if (authState.isAuthenticated && authState.userId) {
      void profile.loadDevices(authState.userId);
    }
  });

  function closePanel(): void {
    if (mode === 'page') {
      onRequestClose?.();
      return;
    }
    isOpen = false;
  }

  async function handleConnectDrive(): Promise<void> {
    const result = await beginDriveConnect();
    if (result.kind === 'failure') {
      pushToast('error', result.message || t('settings.sync.drive.connectFailed'));
    } else if (result.kind === 'success') {
      pushToast('success', t('settings.sync.drive.connected'));
    }
  }

  async function handleTabChange(tab: SettingsTab): Promise<void> {
    router.activeTab = tab;
    if (tab === 'cuenta') {
      await profile.loadProfileData();
      if (authState.isAuthenticated && authState.userId)
        await profile.loadDevices(authState.userId);
    } else {
      profile.stopHeartbeat();
    }
    if (tab === 'apariencia' || tab === 'reader') {
      await Promise.all([appearance.loadAppearance(), reader.loadReader()]);
    }
  }

  function handleTabKeydown(e: KeyboardEvent): void {
    const tabs: SettingsTab[] = router.tabs;
    const idx = tabs.indexOf(router.activeTab);
    let next: number | null = null;
    if (e.key === 'ArrowRight') {
      e.preventDefault();
      next = (idx + 1) % tabs.length;
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      next = (idx - 1 + tabs.length) % tabs.length;
    } else if (e.key === 'Home') {
      e.preventDefault();
      next = 0;
    } else if (e.key === 'End') {
      e.preventDefault();
      next = tabs.length - 1;
    }
    if (next !== null) {
      void handleTabChange(tabs[next]);
      document.getElementById(`tab-${tabs[next]}`)?.focus();
    }
  }

  function openResetModal(tab: 'cuenta' | 'apariencia' | 'reader'): void {
    pendingResetTab = tab;
    showResetModal = true;
  }
  function closeResetModal(): void {
    showResetModal = false;
    pendingResetTab = null;
  }
  async function confirmReset(): Promise<void> {
    if (pendingResetTab === 'cuenta') appearance.resetToDefaults();
    else if (pendingResetTab === 'reader') reader.resetToDefaults();
    else if (pendingResetTab === 'apariencia') appearance.resetToDefaults();
    closeResetModal();
    await Promise.all([appearance.saveAppearance(), reader.saveReader()]);
  }
  async function handleSaveSettings(): Promise<void> {
    await Promise.all([appearance.saveAppearance(), reader.saveReader()]);
  }

  $effect(() => {
    if (isOpen) {
      void appearance.loadAppearance();
      void reader.loadReader();
      void profile.loadProfileData();
    }
  });

  $effect(() => {
    const handleBeforeUnload = (): void => profile.stopHeartbeat();
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  });

  onDestroy(() => profile.destroy());
</script>

{#if mode === 'page' || isOpen}
  {#if mode === 'overlay'}
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div class="fixed inset-0 w-screen h-screen bg-black/40 z-[999]" onclick={closePanel}></div>
  {/if}
  <aside
    class={mode === 'overlay'
      ? 'fixed top-0 right-0 w-[350px] h-screen bg-(--color-surface) border-l border-(--color-border) shadow-xl z-[1000] flex flex-col animate-[slide-in_0.3s_ease-out]'
      : 'w-full h-full flex-1 flex flex-col bg-(--color-background) overflow-hidden min-h-0'}
  >
    <div class="flex items-center p-3 border-b border-(--color-border)">
      <button
        class="inline-flex items-center justify-center size-8 rounded-lg bg-(--color-surface) border border-(--color-border) text-(--color-text-muted) cursor-pointer hover:text-(--color-primary) hover:border-(--color-primary) transition-all duration-200"
        onclick={closePanel}
        aria-label={t('app.backToHome')}
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M19 12H5m7-7l-7 7 7 7" />
        </svg>
      </button>
    </div>

    <SettingsTabs
      activeTab={router.activeTab}
      onTabChange={handleTabChange}
      onKeydown={handleTabKeydown}
      {t}
    />

    <form novalidate onsubmit={(e) => e.preventDefault()} class="flex-1 flex flex-col min-h-0">
      {#if router.activeTab === 'cuenta'}
        <SettingsCuentaTab {t} profileState={profile} appearanceState={appearance} />
      {:else if router.activeTab === 'apariencia'}
        <div
          role="tabpanel"
          id="tabpanel-apariencia"
          aria-labelledby="tab-apariencia"
          class="flex-1 overflow-y-auto p-4 flex flex-col gap-4"
        >
          <SettingsAppearanceTab
            {t}
            preferredTheme={appearance.preferredTheme}
            preferredFontScale={appearance.preferredFontScale}
            readerThemeMode={reader.readerThemeMode}
            readerBrightness={reader.readerBrightness}
            readerContrast={reader.readerContrast}
            readerEpubFontSize={reader.readerEpubFontSize}
            readerEpubFontFamily={reader.readerEpubFontFamily}
            isSavingSettings={appearance.isSavingSettings || reader.isSavingSettings}
            onSaveSettings={() => void handleSaveSettings()}
            onOpenResetModal={() => openResetModal('apariencia')}
            onPreferredThemeChange={(v: string) => appearance.handlePreferredThemeChange(v)}
            onPreferredFontScaleChange={(v: number) => appearance.handlePreferredFontScaleChange(v)}
            onReaderThemeModeChange={(v) => reader.handleReaderThemeModeChange(v)}
            onReaderBrightnessChange={(v) => reader.handleReaderBrightnessChange(v)}
            onReaderContrastChange={(v) => reader.handleReaderContrastChange(v)}
            onReaderEpubFontSizeChange={(v) => reader.handleReaderEpubFontSizeChange(v)}
            onReaderEpubFontFamilyChange={(v) => reader.handleReaderEpubFontFamilyChange(v)}
          />
        </div>
      {:else if router.activeTab === 'reader'}
        <SettingsReaderTab {t} {reader} onOpenResetModal={() => openResetModal('reader')} />
      {:else if router.activeTab === 'datos'}
        <div
          role="tabpanel"
          id="tabpanel-datos"
          aria-labelledby="tab-datos"
          class="flex-1 overflow-y-auto p-4 flex flex-col gap-4"
        >
          <SettingsDataTab
            {t}
            {books}
            isClearingCache={data.isClearingCache}
            cacheCleared={data.cacheCleared}
            selectedExportBook={data.selectedExportBook}
            selectedExportFormat={data.selectedExportFormat}
            isExportingHighlights={data.isExportingHighlights}
            isExportingColdBackup={data.isExportingColdBackup}
            isImportingColdBackup={data.isImportingColdBackup}
            isExportingDictionary={data.isExportingDictionary}
            isImportingDictionary={data.isImportingDictionary}
            dictionaryExportError={data.dictionaryExportError}
            dictionaryImportResult={data.dictionaryImportResult}
            dictionaryImportError={data.dictionaryImportError}
            isDriveConnected={driveState.isAuthorized}
            isConnectingDrive={driveState.isConnecting}
            onConnectDrive={() => void handleConnectDrive()}
            onClearCache={() => void data.handleClearCache()}
            onExportLibrary={() => {}}
            onExportHighlights={() => void data.handleExportHighlights()}
            onExportColdBackup={() => void data.handleExportColdBackup()}
            onImportColdBackup={() => void data.handleImportColdBackup()}
            onExportDictionary={(format) => void data.handleExportDictionary(format)}
            onImportDictionary={(file) => void data.handleImportDictionary(file)}
            onSelectedExportBookChange={(v: string) => data.handleSelectedExportBookChange(v)}
            onSelectedExportFormatChange={(v: 'json' | 'markdown') =>
              data.handleSelectedExportFormatChange(v)}
          />
          <SettingsAddonsSection
            {t}
            url={addonsState.url}
            installed={addonsState.installed}
            isBusy={addonsState.isBusy}
            installOutcome={addonsState.installOutcome}
            onUrlChange={(v: string) => (addonsState.url = v)}
            onInstall={() => void addonsState.handleInstall()}
            onToggle={(id: string, enabled: boolean) => void addonsState.handleToggle(id, enabled)}
            onUninstall={(id: string) => void addonsState.handleUninstall(id)}
          />
        </div>
      {:else if router.activeTab === 'almacenamiento'}
        <SettingsStorageTab {t} />
      {:else if router.activeTab === 'sincronizacion'}
        <SettingsSyncTab {t} />
      {:else if router.activeTab === 'atajos'}
        <SettingsShortcutsTab {t} />
      {:else if router.activeTab === 'acerca'}
        <div
          role="tabpanel"
          id="tabpanel-acerca"
          aria-labelledby="tab-acerca"
          class="flex-1 overflow-y-auto p-4 flex flex-col gap-4"
        >
          <SettingsAboutTab {t} />
        </div>
      {/if}
    </form>

    <SettingsResetModal
      show={showResetModal}
      {t}
      onClose={closeResetModal}
      onConfirm={confirmReset}
    />
  </aside>
{/if}
