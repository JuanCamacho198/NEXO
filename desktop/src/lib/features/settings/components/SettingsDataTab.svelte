<script lang="ts">
  import SettingsPrivacySection from './SettingsPrivacySection.svelte';
  import SettingsNotificationsSection from './SettingsNotificationsSection.svelte';
  import Dropdown from '$lib/shared/ui/navigation/Dropdown.svelte';
  import Button from '$lib/shared/ui/forms/Button.svelte';
  import Panel from '$lib/shared/ui/layout/Panel.svelte';
  import type { MessageKey } from '$lib/shared/i18n';
  import type { CollectionDto, LibraryBookDto } from '$lib/shared/types';

  type Props = {
    t: (key: MessageKey, params?: Record<string, string | number>) => string;
    books: LibraryBookDto[];
    collections?: CollectionDto[];
    isClearingCache: boolean;
    cacheCleared: boolean;
    selectedExportBook: string;
    selectedExportFormat: 'json' | 'markdown';
    annotationsOnlyWithNote?: boolean;
    isExportingLibrary?: boolean;
    isExportingHighlights: boolean;
    isExportingCollections?: boolean;
    isExportingBook?: boolean;
    isExportingEverything?: boolean;
    isExportingColdBackup?: boolean;
    isImportingColdBackup?: boolean;
    isExportingDictionary?: boolean;
    isImportingDictionary?: boolean;
    dictionaryExportError?: string | null;
    dictionaryImportResult?: string | null;
    dictionaryImportError?: string | null;
    isDriveConnected?: boolean;
    isConnectingDrive?: boolean;
    onClearCache: () => void;
    onExportLibrary: () => void;
    onExportHighlights: () => void;
    onExportCollections?: () => void;
    onExportBook?: (bookId: string) => void;
    onExportEverything?: () => void;
    onExportColdBackup?: () => void;
    onImportColdBackup?: () => void;
    onExportDictionary?: (format: 'json' | 'csv') => void;
    onImportDictionary?: (file: File) => void;
    onConnectDrive?: () => void;
    onNavigateToStorage?: () => void;
    onSelectedExportBookChange: (value: string) => void;
    onSelectedExportFormatChange: (value: 'json' | 'markdown') => void;
    onAnnotationsOnlyWithNoteChange?: (value: boolean) => void;
  };

  let {
    t,
    books,
    collections = [],
    isClearingCache,
    cacheCleared,
    selectedExportBook,
    selectedExportFormat,
    annotationsOnlyWithNote = false,
    isExportingLibrary = false,
    isExportingHighlights,
    isExportingCollections = false,
    isExportingBook = false,
    isExportingEverything = false,
    isExportingColdBackup = false,
    isImportingColdBackup = false,
    isExportingDictionary = false,
    isImportingDictionary = false,
    dictionaryExportError = null,
    dictionaryImportResult = null,
    dictionaryImportError = null,
    isDriveConnected = true,
    isConnectingDrive = false,
    onClearCache,
    onExportLibrary,
    onExportHighlights,
    onExportCollections = () => {},
    onExportBook = () => {},
    onExportEverything = () => {},
    onExportColdBackup = () => {},
    onImportColdBackup = () => {},
    onExportDictionary = () => {},
    onImportDictionary = () => {},
    onConnectDrive = () => {},
    onNavigateToStorage,
    onSelectedExportBookChange,
    onSelectedExportFormatChange,
    onAnnotationsOnlyWithNoteChange = () => {},
  }: Props = $props();

  // Clear cache is destructive, so the first click only arms this inline
  // confirmation (the same role="group" + Cancel pattern as the addon
  // uninstall row). No modal: this needs a guard, not protected focus.
  let showClearConfirm = $state(false);

  // The per-book scope owns its own selection so it cannot collide with the
  // highlights module's book filter above it.
  let selectedBookForExport = $state('');

  const exportBookOptions = $derived([
    { value: 'all', label: t('settings.data.allBooks') },
    ...(books ?? []).map((b) => ({ value: b.id, label: b.title })),
  ]);

  const bookScopeOptions = $derived((books ?? []).map((b) => ({ value: b.id, label: b.title })));

  const exportFormatOptions = $derived([
    { value: 'json', label: 'JSON' },
    { value: 'markdown', label: t('settings.data.markdown') },
  ]);

  async function handleDictionaryFile(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    try {
      await onImportDictionary(file);
    } finally {
      input.value = '';
    }
  }
</script>

{#snippet groupHeading(testId: string, label: string)}
  <h3 class="text-sm font-semibold text-(--color-primary)" data-testid={testId}>
    {label}
  </h3>
{/snippet}

<section class="space-y-5 w-full max-w-none">
  <header class="flex flex-col gap-1">
    <h1 class="text-3xl font-semibold tracking-tight text-(--color-primary)">
      {t('settings.tab.data')}
    </h1>
    <p class="text-sm text-(--color-text-muted)">{t('settings.data.subtitle')}</p>
  </header>

  {@render groupHeading('settings-group-backup', t('settings.data.group.backup'))}
  <Panel title={t('settings.data.coldBackup')} subtitle={t('settings.data.coldBackupDescription')}>
    {#if !isDriveConnected}
      <div class="mb-3 flex flex-col gap-2 rounded-lg bg-(--color-background) p-3">
        <p class="text-xs text-(--color-text-muted)">{t('settings.data.driveNotConnected')}</p>
        <Button
          variant="primary"
          size="md"
          onclick={onConnectDrive}
          disabled={isConnectingDrive || isExportingColdBackup || isImportingColdBackup}
        >
          {isConnectingDrive
            ? t('settings.sync.drive.connecting')
            : t('settings.data.connectDrive')}
        </Button>
      </div>
    {/if}
    <div class="flex gap-2">
      <Button
        variant="primary"
        size="md"
        class="flex-1"
        onclick={onExportColdBackup}
        disabled={isExportingColdBackup || isImportingColdBackup}
      >
        {isExportingColdBackup ? t('settings.data.exporting') : t('settings.data.coldExport')}
      </Button>
      <Button
        variant="secondary"
        size="md"
        class="flex-1"
        onclick={onImportColdBackup}
        disabled={isExportingColdBackup || isImportingColdBackup}
      >
        {isImportingColdBackup ? t('settings.data.importing') : t('settings.data.coldImport')}
      </Button>
    </div>
  </Panel>

  {@render groupHeading('settings-group-export', t('settings.data.group.export'))}
  <p
    class="px-1 -mt-1 text-xs leading-relaxed text-(--color-text-muted)"
    data-testid="export-files-boundary"
  >
    {t('settings.data.exportFilesNotIncluded')}
  </p>

  <Panel
    title={t('settings.data.exportEverything')}
    subtitle={t('settings.data.exportEverythingDescription')}
  >
    <Button
      variant="primary"
      size="md"
      onclick={onExportEverything}
      disabled={isExportingEverything}
      data-testid="export-everything-button"
    >
      {#snippet leadingIcon()}
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          ><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline
            points="7 10 12 15 17 10"
          /><line x1="12" y1="15" x2="12" y2="3" /></svg
        >
      {/snippet}
      {isExportingEverything
        ? t('settings.data.exporting')
        : t('settings.data.exportEverythingButton')}
    </Button>
  </Panel>

  <Panel
    title={t('settings.data.exportLibraryTitle')}
    subtitle={t('settings.data.exportLibraryDescription')}
  >
    <Button
      variant="secondary"
      size="md"
      onclick={onExportLibrary}
      disabled={isExportingLibrary}
      data-testid="export-library-button"
    >
      {#snippet leadingIcon()}
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          ><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path
            d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"
          /></svg
        >
      {/snippet}
      {isExportingLibrary ? t('settings.data.exporting') : t('settings.data.exportLibraryButton')}
    </Button>
  </Panel>

  <Panel
    title={t('settings.data.exportHighlights')}
    subtitle={t('settings.data.exportHighlightsDescription')}
  >
    <section class="flex flex-col gap-2 rounded-lg bg-(--color-background) p-3">
      <div class="flex gap-2">
        <Dropdown
          options={exportBookOptions}
          value={selectedExportBook}
          class="flex-1"
          onchange={({ value }) => onSelectedExportBookChange(value)}
        />
        <Dropdown
          options={exportFormatOptions}
          value={selectedExportFormat}
          class="w-[90px] shrink-0"
          onchange={({ value }) => onSelectedExportFormatChange(value as 'json' | 'markdown')}
        />
        <Button
          variant="primary"
          size="md"
          onclick={onExportHighlights}
          disabled={isExportingHighlights}
          data-testid="export-highlights-button"
        >
          {#snippet leadingIcon()}
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              ><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" /><polyline
                points="15 3 21 3 21 9"
              /><line x1="10" y1="14" x2="21" y2="3" /></svg
            >
          {/snippet}
          {isExportingHighlights ? t('settings.data.exporting') : t('settings.data.download')}
        </Button>
      </div>
      <label
        class="flex cursor-pointer items-center gap-2 text-xs text-(--color-secondary) select-none"
      >
        <input
          type="checkbox"
          class="size-3.5 cursor-pointer accent-(--color-primary)"
          checked={annotationsOnlyWithNote}
          onchange={(e) => onAnnotationsOnlyWithNoteChange(e.currentTarget.checked)}
          data-testid="annotations-only-with-note"
        />
        <span>{t('settings.data.annotationsOnlyWithNote')}</span>
      </label>
    </section>
  </Panel>

  <Panel
    title={t('settings.data.exportCollections')}
    subtitle={t('settings.data.exportCollectionsDescription')}
  >
    <Button
      variant="secondary"
      size="md"
      onclick={onExportCollections}
      disabled={isExportingCollections || collections.length === 0}
      data-testid="export-collections-button"
    >
      {#snippet leadingIcon()}
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          ><path
            d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"
          /></svg
        >
      {/snippet}
      {isExportingCollections
        ? t('settings.data.exporting')
        : t('settings.data.exportCollectionsButton')}
    </Button>
  </Panel>

  <Panel
    title={t('settings.data.exportOneBook')}
    subtitle={t('settings.data.exportOneBookDescription')}
  >
    <section class="flex gap-2" data-testid="export-book-scope">
      <Dropdown
        options={bookScopeOptions}
        value={selectedBookForExport}
        placeholder={t('settings.data.selectBook')}
        class="flex-1"
        onchange={({ value }) => (selectedBookForExport = value)}
      />
      <Button
        variant="primary"
        size="md"
        class="shrink-0"
        onclick={() => onExportBook(selectedBookForExport)}
        disabled={isExportingBook || !selectedBookForExport}
        data-testid="export-book-button"
      >
        {#snippet leadingIcon()}
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            ><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path
              d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"
            /></svg
          >
        {/snippet}
        {isExportingBook ? t('settings.data.exporting') : t('settings.data.exportOneBookButton')}
      </Button>
    </section>
  </Panel>

  <Panel
    title={t('settings.data.dictionary.title')}
    subtitle={t('settings.data.dictionary.description')}
  >
    <div class="flex flex-wrap items-center gap-2" data-testid="dictionary-transfer-actions">
      <Button
        variant="primary"
        size="md"
        class="border border-transparent"
        onclick={() => onExportDictionary('json')}
        disabled={isExportingDictionary || isImportingDictionary}
      >
        {t('settings.data.dictionary.exportJson')}
      </Button>
      <Button
        variant="secondary"
        size="md"
        onclick={() => onExportDictionary('csv')}
        disabled={isExportingDictionary || isImportingDictionary}
      >
        {t('settings.data.dictionary.exportCsv')}
      </Button>
      <Button
        as="label"
        variant="secondary"
        size="md"
        disabled={isExportingDictionary || isImportingDictionary}
      >
        <input
          type="file"
          accept=".json,.csv"
          class="hidden"
          data-testid="dictionary-import-input"
          onchange={handleDictionaryFile}
          disabled={isExportingDictionary || isImportingDictionary}
        />
        <span>{t('settings.data.dictionary.import')}</span>
      </Button>
    </div>
    {#if dictionaryExportError}
      <p
        class="mt-2 text-xs text-(--color-error)"
        data-testid="dictionary-export-error"
        role="alert"
      >
        {dictionaryExportError}
      </p>
    {/if}
    {#if dictionaryImportResult}
      <p
        class="mt-2 text-xs text-(--color-success)"
        data-testid="dictionary-import-result"
        role="status"
        aria-live="polite"
      >
        {dictionaryImportResult}
      </p>
    {/if}
    {#if dictionaryImportError}
      <p
        class="mt-2 text-xs text-(--color-warning)"
        data-testid="dictionary-import-error"
        role="alert"
      >
        {dictionaryImportError}
      </p>
    {/if}
  </Panel>

  {@render groupHeading('settings-group-maintenance', t('settings.data.group.maintenance'))}
  <Panel title={t('settings.data.clearCache')} subtitle={t('settings.data.clearCacheDescription')}>
    <div class="flex flex-col gap-2">
      <div class="flex flex-wrap items-center gap-2">
        <Button
          variant="danger"
          size="md"
          onclick={() => (showClearConfirm = true)}
          disabled={isClearingCache}
          data-testid="clear-cache-button"
        >
          {#snippet leadingIcon()}
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              ><polyline points="3 6 5 6 21 6" /><path
                d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
              /></svg
            >
          {/snippet}
          {t('settings.data.clearCache')}
        </Button>
        {#if onNavigateToStorage}
          <Button
            variant="ghost"
            size="sm"
            class="underline-offset-2 hover:underline"
            onclick={onNavigateToStorage}
            data-testid="open-storage-tab"
          >
            {t('settings.data.storageOptions')}
          </Button>
        {/if}
      </div>

      {#if isClearingCache || cacheCleared}
        <p
          class="text-xs text-(--color-text-muted)"
          role="status"
          aria-live="polite"
          data-testid="clear-cache-status"
        >
          {isClearingCache ? t('settings.data.clearing') : t('settings.data.cleared')}
        </p>
      {/if}

      {#if showClearConfirm}
        <div
          role="group"
          aria-label={t('settings.data.clearCacheConfirm')}
          data-testid="clear-cache-confirm"
          class="flex flex-col gap-2 rounded-lg border border-(--color-danger)/40 bg-(--color-error-soft) px-3 py-2"
        >
          <p class="m-0 text-xs text-(--color-primary)">{t('settings.data.clearCacheConfirm')}</p>
          <div class="flex items-center gap-2">
            <Button
              variant="danger"
              size="sm"
              onclick={() => {
                showClearConfirm = false;
                onClearCache();
              }}
              disabled={isClearingCache}
              data-testid="clear-cache-confirm-button"
            >
              {t('settings.data.clearCache')}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onclick={() => (showClearConfirm = false)}
              data-testid="clear-cache-cancel-button"
            >
              {t('settings.data.cancel')}
            </Button>
          </div>
        </div>
      {/if}
    </div>
  </Panel>

  {@render groupHeading('settings-group-privacy-addons', t('settings.data.group.privacyAddons'))}
  <SettingsPrivacySection {t} />
  {@render groupHeading('settings-group-notifications', t('settings.data.group.notifications'))}
  <SettingsNotificationsSection {t} />
</section>
