<script lang="ts">
  import { createSettingsRouter, type SettingsTab } from '../useSettingsRouter.svelte';
  import { createSettingsLocale } from '../useSettingsLocale.svelte';
  import { createSettingsData } from '../useSettingsData.svelte';
  import { createTranslationSettings } from '../useTranslationSettings.svelte';
  import { createSettingsProfile } from '../useSettingsProfile.svelte';
  import SettingsTabs from './SettingsTabs.svelte';
  import SettingsCuentaTab from './SettingsCuentaTab.svelte';
  import SettingsDataTab from './SettingsDataTab.svelte';
  import SettingsSelectionMenuTab from './SettingsSelectionMenuTab.svelte';
  import SettingsStorageTab from './SettingsStorageTab.svelte';
  import SettingsSyncTab from './SettingsSyncTab.svelte';
  import SettingsShortcutsTab from './SettingsShortcutsTab.svelte';
  import SettingsAboutTab from './SettingsAboutTab.svelte';
  import type { LibraryBookDto, CollectionDto, UiLocale } from '$lib/shared/types';
  import type { MessageKey } from '$lib/shared/i18n';
  import { onDestroy } from 'svelte';
  import { authState } from '$lib/shared/stores/AuthState.svelte';
  import { settingsState } from '$lib/shared/stores/SettingsDomainState.svelte';
  import { driveState } from '$lib/shared/stores/driveState.svelte';
  import { beginDriveConnect } from '$lib/shared/services/DriveConnectService';
  import { pushToast } from '$lib/shared/stores/ToastQueue.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';

  let {
    isOpen = false,
    onRequestClose,
    locale,
    onLocaleChange,
    t,
    books = [],
    collections = [],
    initialTab,
  } = $props<{
    isOpen: boolean;
    onRequestClose?: () => void;
    locale: UiLocale;
    onLocaleChange?: (locale: UiLocale) => void;
    books?: LibraryBookDto[];
    collections?: CollectionDto[];
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    initialTab?: SettingsTab;
  }>();

  // svelte-ignore state_referenced_locally
  const router = createSettingsRouter({ initialTab });
  $effect(() => {
    if (initialTab !== undefined) router.activeTab = initialTab;
  });

  // `locale` is a read-only prop: the parent owns the value (`settingsState.locale`)
  // and this panel only forwards changes upward, never writes the prop back.
  const localeState = createSettingsLocale({
    onLocaleChange: (next) => onLocaleChange?.(next),
  });
  // svelte-ignore state_referenced_locally
  const profile = createSettingsProfile({ t });
  // svelte-ignore state_referenced_locally
  const data = createSettingsData({ t });
  const translation = createTranslationSettings();
  // The pair and the history are account-scoped and only read when the tab is
  // shown, so loading them costs nothing until the reader opens the tab.
  $effect(() => {
    if (isOpen && router.activeTab === 'seleccion') void translation.load();
  });

  // Keep the local locale hook in sync if the parent changes locale externally
  $effect(() => {
    void locale;
    if (localeState.locale !== locale) localeState.locale = locale;
  });
  $effect(() => {
    void settingsState.dailyGoalMinutes;
    profile.syncFromStore();
  });

  // Auto-load devices when signed in
  $effect(() => {
    if (authState.isAuthenticated && authState.userId) {
      void profile.loadDevices(authState.userId);
    }
  });

  function closePanel(): void {
    onRequestClose?.();
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

  $effect(() => {
    if (isOpen) {
      void localeState.loadLocale();
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

{#if isOpen}
  <aside class="flex h-full min-h-0 w-full flex-1 flex-col overflow-hidden">
    <div class="shrink-0 px-8 pt-6 pb-3">
      <div class="flex w-full items-center gap-3">
        <Button
          variant="secondary"
          size="sm"
          class="size-10 shrink-0"
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
        </Button>
        <SettingsTabs
          activeTab={router.activeTab}
          onTabChange={handleTabChange}
          onKeydown={handleTabKeydown}
          {t}
        />
      </div>
    </div>

    <form novalidate onsubmit={(e) => e.preventDefault()} class="flex-1 flex flex-col min-h-0">
      {#if router.activeTab === 'cuenta'}
        <SettingsCuentaTab {t} profileState={profile} {localeState} />
      {:else if router.activeTab === 'datos'}
        <div
          role="tabpanel"
          id="tabpanel-datos"
          aria-labelledby="tab-datos"
          class="relative flex flex-1 flex-col overflow-y-auto px-8 pt-4 pb-12"
        >
          <div class="flex w-full max-w-5xl flex-col gap-6 [&>*]:shrink-0">
            <SettingsDataTab
              {t}
              {books}
              {collections}
              selectedExportBook={data.selectedExportBook}
              selectedExportFormat={data.selectedExportFormat}
              annotationsOnlyWithNote={data.annotationsOnlyWithNote}
              isExportingLibrary={data.isExportingLibrary}
              isExportingHighlights={data.isExportingHighlights}
              isExportingCollections={data.isExportingCollections}
              isExportingBook={data.isExportingBook}
              isExportingEverything={data.isExportingEverything}
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
              onExportLibrary={() => void data.handleExportLibrary(books)}
              onExportHighlights={() => void data.handleExportHighlights(books)}
              onExportCollections={() => void data.handleExportCollections(collections)}
              onExportBook={(bookId: string) => void data.handleExportBook(bookId, books)}
              onExportEverything={() => void data.handleExportEverything(books, collections)}
              onExportColdBackup={() => void data.handleExportColdBackup()}
              onImportColdBackup={() => void data.handleImportColdBackup()}
              onExportDictionary={(format) => void data.handleExportDictionary(format)}
              onImportDictionary={(file) => void data.handleImportDictionary(file)}
              onSelectedExportBookChange={(v: string) => data.handleSelectedExportBookChange(v)}
              onSelectedExportFormatChange={(v: 'json' | 'markdown') =>
                data.handleSelectedExportFormatChange(v)}
              onAnnotationsOnlyWithNoteChange={(v: boolean) =>
                data.handleAnnotationsOnlyWithNoteChange(v)}
            />
          </div>
        </div>
      {:else if router.activeTab === 'seleccion'}
        <SettingsSelectionMenuTab
          {t}
          {locale}
          sourceLanguage={translation.sourceLanguage}
          targetLanguage={translation.targetLanguage}
          definitionLanguage={translation.definitionLanguage}
          history={translation.history}
          isLoading={translation.isLoading}
          isSaving={translation.isSaving}
          isClearingHistory={translation.isClearingHistory}
          error={translation.error}
          onSourceChange={(v) => (translation.sourceLanguage = v)}
          onTargetChange={(v) => (translation.targetLanguage = v)}
          onDefinitionLanguageChange={(v) => void translation.saveDefinitionLanguage(v)}
          onSave={(source, target) => void translation.save(source, target)}
          onClearHistory={() => void translation.clearHistory()}
        />
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
          class="relative flex flex-1 flex-col overflow-y-auto px-8 pt-4 pb-12"
        >
          <SettingsAboutTab {t} {locale} />
        </div>
      {/if}
    </form>
  </aside>
{/if}
